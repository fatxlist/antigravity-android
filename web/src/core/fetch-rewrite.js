/**
 * core/fetch-rewrite.js - file:// WebView -> core origin fetch rewrite.
 *
 * Combines ConnectRPC file:// origin rewriting, cold-boot /voice/health retries,
 * non-idempotent POST replay safety (PR #24), and streaming resume recovery (PR #34).
 */
var RPC_MARKERS = ['exa.language_server_pb.', 'exa.host_bridge_pb.', '/exa.', '/jetski.'];

function isVoiceHealthUrl(url) {
  if (!url) return false;
  var s = String(url);
  if (/^(blob|data|wss?|chrome-extension):/i.test(s)) return false;
  if (/^https?:/i.test(s)) {
    try {
      var parsed = new URL(s);
      if (parsed.hostname !== '127.0.0.1' && parsed.hostname !== 'localhost') return false;
      return parsed.pathname === '/voice/health';
    } catch (_) {
      return false;
    }
  }
  return /^\/?voice\/health(?:[?#]|$)/i.test(s);
}

// RPC path (no leading slash) when `url` is a file:// or relative ConnectRPC
// URL; null for anything else (http(s), blob, data, static assets).
function rpcPathFrom(url) {
  if (!url) return null;
  var s = String(url);
  if (/^(https?|blob|data|wss?|chrome-extension):/i.test(s)) return null;
  var best = -1;
  for (var i = 0; i < RPC_MARKERS.length; i++) {
    var at = s.indexOf(RPC_MARKERS[i]);
    if (at >= 0 && (best === -1 || at < best)) best = at;
  }
  if (best === -1) return null;
  return s.slice(best).replace(/^\/+/, '') || null;
}

function isRetrySafeCall(url, opts, isStream) {
  var method = String((opts && opts.method) || (url && typeof url === 'object' && url.method) || 'GET').toUpperCase();
  if (method === 'GET' || method === 'HEAD' || method === 'OPTIONS') return true;
  if (isStream) return true;
  // Read-only ConnectRPC queries (all sent over HTTP POST) that are safe to retry on cold-boot socket errors.
  var u = String((url && typeof url === 'object' && url.url) || url || '');
  return /\/(?:Get|List|Check|Heartbeat|Stream|Subscribe)[A-Za-z0-9_]*(?:[?#]|$)/.test(u);
}

function isAbortedError(err, opts) {
  if (opts && opts.signal && opts.signal.aborted) return true;
  if (!err) return false;
  return err.name === 'AbortError' || (typeof err.message === 'string' && err.message.indexOf('aborted') !== -1);
}

function installFetchRewrite() {
  if (global.__AG_FETCH_REWRITE__ || typeof global.fetch !== 'function') return;
  var origFetch = global.fetch.bind(global);
  var schedule = typeof origSetTimeout === 'function' ? origSetTimeout : setTimeout;

  function fetchWithStartupRetry(u, opts, retries, isStream) {
    var safeCall = isRetrySafeCall(u, opts, isStream);
    // Allow a brief grace period (up to 4 network retries @ 400ms) for non-idempotent
    // calls across Wi-Fi <-> cellular handoffs; never retry aborted calls or HTTP errors.
    var attempts = safeCall ? retries : Math.min(retries, 4);
    var retryDelay = safeCall ? 120 : 400;
    var doCall = (isStream && typeof wrapStreamingFetch === 'function')
      ? function () { return wrapStreamingFetch(origFetch, u, opts); }
      : function () { return origFetch(u, opts); };
    return doCall().then(function (resp) {
      if (typeof notifyNetworkSuccess === 'function') notifyNetworkSuccess();
      return resp;
    }).catch(function (err) {
      if (isAbortedError(err, opts)) {
        throw err;
      }
      if (attempts > 0) {
        return new Promise(function (resolve) {
          schedule(function () {
            resolve(fetchWithStartupRetry(u, opts, attempts - 1, isStream));
          }, retryDelay);
        });
      }
      if (typeof notifyNetworkFailure === 'function') notifyNetworkFailure();
      throw err;
    });
  }

  // pYa() in main.js does not retry /voice/health on network errors during cold boot;
  // retry transient failures so the white Live Voice Call icon appears reliably.
  function fetchVoiceHealthWithRetry(u, opts, retries) {
    return origFetch(u, opts).then(function (resp) {
      if (retries > 0 && resp && (resp.status === 502 || resp.status === 503 || resp.status === 504)) {
        return new Promise(function (resolve) {
          schedule(function () {
            resolve(fetchVoiceHealthWithRetry(u, opts, retries - 1));
          }, 300);
        });
      }
      return resp;
    }, function (err) {
      if (isAbortedError(err, opts)) {
        throw err;
      }
      if (retries > 0) {
        return new Promise(function (resolve) {
          schedule(function () {
            resolve(fetchVoiceHealthWithRetry(u, opts, retries - 1));
          }, 300);
        });
      }
      throw err;
    });
  }

  global.fetch = function (resource, init) {
    try {
      // Request object: rebuild against the core origin, keep method/body.
      if (resource && typeof resource === 'object' && typeof resource.url === 'string') {
        if (isVoiceHealthUrl(resource.url)) {
          return fetchVoiceHealthWithRetry(resource, init, 15);
        }
        var rpath = rpcPathFrom(resource.url);
        if (rpath) {
          try {
            var targetUrl = appendCsrfQuery(coreBase() + '/' + rpath);
            var reqMethod = resource.method || (init && init.method) || 'POST';
            var reqHeaders = new Headers(resource.headers || (init && init.headers) || {});
            ensureCsrf(reqHeaders);
            var reqSignal = (init && init.signal) || resource.signal;

            var bodyPromise;
            if (init && init.body !== undefined) {
              bodyPromise = Promise.resolve(init.body);
            } else {
              try {
                bodyPromise = resource.clone().arrayBuffer().then(function (buf) {
                  return buf.byteLength > 0 ? buf : undefined;
                }).catch(function () { return undefined; });
              } catch (e) {
                bodyPromise = Promise.resolve(undefined);
              }
            }

            return bodyPromise.then(function (bodyData) {
              var nextOpts = {
                method: reqMethod,
                headers: reqHeaders,
                body: bodyData,
                credentials: 'omit',
                signal: reqSignal
              };
              var streamCall = typeof isStreamingRpc === 'function' && isStreamingRpc(rpath);
              return fetchWithStartupRetry(targetUrl, nextOpts, 30, streamCall);
            });
          } catch (e) { warn('fetch rewrite (Request)', e); }
        }
        if (typeof isLoopbackRpcUrl === 'function' && typeof wrapStreamingFetch === 'function' && isLoopbackRpcUrl(resource.url)) {
          return wrapStreamingFetch(origFetch, resource, init);
        }
        return origFetch(resource, init);
      }

      // String URL: the Connect/grpc-web transport's common path.
      if (typeof resource === 'string') {
        if (isVoiceHealthUrl(resource)) {
          var vhUrl = /^https?:/i.test(resource)
            ? resource
            : (coreBase() + '/' + resource.replace(/^\/+/, ''));
          return fetchVoiceHealthWithRetry(vhUrl, init, 15);
        }
        var path = rpcPathFrom(resource);
        if (path) {
          var url = appendCsrfQuery(coreBase() + '/' + path);
          var headers = new Headers((init && init.headers) || {});
          ensureCsrf(headers);
          var next = {};
          if (init) {
            for (var k in init) {
              if (Object.prototype.hasOwnProperty.call(init, k)) next[k] = init[k];
            }
          }
          next.headers = headers;

          var strStreamCall = typeof isStreamingRpc === 'function' && isStreamingRpc(path);
          return fetchWithStartupRetry(url, next, 30, strStreamCall);
        }
        if (typeof isLoopbackRpcUrl === 'function' && typeof wrapStreamingFetch === 'function' && isLoopbackRpcUrl(resource)) {
          return wrapStreamingFetch(origFetch, resource, init);
        }
      }
    } catch (e) { warn('fetch rewrite', e); }
    return origFetch(resource, init);
  };
  global.__AG_FETCH_REWRITE__ = true;
}

installFetchRewrite();
