/**
 * core/resume-stream-recovery.js - Recover ConnectRPC streaming calls
 * (StreamAgentStateUpdates, JetboxSubscribeToState, etc.) after Android
 * WebViewLifecycle.pause() calls webView.pauseTimers().
 */
var activeStreamClosers = new Set();
var lastResumeTs = 0;
var lastHostResumeTs = 0;
var pendingVisibilityTimer = null;
var reconnectTimer = null;
var reconnectHideTimer = null;
var reconnectAttempt = 0;
var MAX_RECONNECT_ATTEMPTS = 12;
var INITIAL_RECONNECT_MS = 1500;
var MAX_RECONNECT_MS = 12000;

var origSetTimeout = typeof global.setTimeout === 'function' ? global.setTimeout.bind(global) : setTimeout;
var origClearTimeout = typeof global.clearTimeout === 'function' ? global.clearTimeout.bind(global) : clearTimeout;

function updateNetBanner(mode, text) {
  try {
    var d = global.document;
    var root = d && (d.body || d.documentElement);
    if (!d || !root || typeof d.getElementById !== 'function') return;
    if (reconnectHideTimer) {
      try { origClearTimeout(reconnectHideTimer); } catch (_) {}
      reconnectHideTimer = null;
    }
    var banner = d.getElementById('ag-net-banner');
    if (mode === 'hidden') {
      if (banner && banner.parentNode) banner.parentNode.removeChild(banner);
      return;
    }
    if (!banner) {
      banner = d.createElement('div');
      banner.id = 'ag-net-banner';
      banner.setAttribute('role', 'status');
      banner.style.cssText =
        'position:fixed;top:max(10px, var(--agy-safe-top, env(safe-area-inset-top, 0px)));left:50%;transform:translateX(-50%);' +
        'z-index:2147483004;padding:6px 12px;border-radius:999px;font:600 12px/1.2 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;' +
        'box-shadow:0 6px 20px rgba(0,0,0,0.35);pointer-events:none;transition:opacity 0.2s ease;';
      root.appendChild(banner);
    }
    if (mode === 'ok') {
      banner.style.background = 'rgba(22,163,74,0.92)';
      banner.style.color = '#ffffff';
      banner.style.border = '1px solid rgba(74,222,128,0.45)';
    } else {
      banner.style.background = 'rgba(217,119,6,0.94)';
      banner.style.color = '#ffffff';
      banner.style.border = '1px solid rgba(251,191,36,0.5)';
    }
    banner.textContent = text;
    if (mode === 'ok') {
      reconnectHideTimer = origSetTimeout(function () {
        reconnectHideTimer = null;
        var b = d.getElementById('ag-net-banner');
        if (b && b.parentNode) b.parentNode.removeChild(b);
      }, 2200);
    }
  } catch (_) {}
}

function scheduleReconnectAttempt() {
  if (reconnectTimer || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) return;
  reconnectAttempt += 1;
  var delay = Math.min(INITIAL_RECONNECT_MS * Math.pow(1.45, reconnectAttempt - 1), MAX_RECONNECT_MS);
  var sec = Math.max(1, Math.round(delay / 1000));
  updateNetBanner('warn', 'Connection interrupted — reconnecting (' + reconnectAttempt + '/' + MAX_RECONNECT_ATTEMPTS + ', ' + sec + 's)…');
  reconnectTimer = origSetTimeout(function () {
    reconnectTimer = null;
    triggerStreamRecovery('net-reconnect', true);
    if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
      scheduleReconnectAttempt();
    }
  }, delay);
}

function notifyNetworkFailure() {
  if (!reconnectTimer && reconnectAttempt === 0) {
    scheduleReconnectAttempt();
  }
}

function notifyNetworkSuccess() {
  var wasRecovering = reconnectAttempt > 0 || !!reconnectTimer;
  if (reconnectTimer) {
    try { origClearTimeout(reconnectTimer); } catch (_) {}
    reconnectTimer = null;
  }
  reconnectAttempt = 0;
  if (wasRecovering) {
    updateNetBanner('ok', 'Reconnected');
    triggerStreamRecovery('net-online', false);
  }
}

// Fast-forward the 1000ms reconnect backoff right after a host resume.
try {
  global.setTimeout = function (fn, delay) {
    if (delay === 1000 && (Date.now() - lastResumeTs) < 3000) {
      delay = 40;
    }
    var args = Array.prototype.slice.call(arguments, 2);
    return origSetTimeout(function () {
      if (typeof fn === 'function') {
        fn.apply(global, args);
      }
    }, delay);
  };
} catch (e) { warn('setTimeout fast-forward', e); }

function triggerStreamRecovery(reason, force) {
  try {
    var now = Date.now();
    if (!force && (now - lastResumeTs) < 300) {
      return;
    }
    lastResumeTs = now;
    if (activeStreamClosers.size > 0) {
      console.log('[AGY bridge.core] ' + reason + ': rotating ' + activeStreamClosers.size + ' active stream(s)');
      var entries = Array.from(activeStreamClosers);
      entries.forEach(function (entry) {
        if (entry && entry.openedAt <= now) {
          activeStreamClosers.delete(entry);
          try { entry.close(); } catch (e) {}
        }
      });
    }
    try { if (typeof global.dispatchEvent === 'function' && typeof global.Event === 'function') global.dispatchEvent(new global.Event('online')); } catch (e) {}
    try { if (typeof global.dispatchEvent === 'function' && typeof global.Event === 'function') global.dispatchEvent(new global.Event('focus')); } catch (e) {}
  } catch (e) { warn('triggerStreamRecovery', e); }
}

try {
  var bindTarget = typeof origAddEventListener === 'function'
    ? origAddEventListener
    : (typeof global.addEventListener === 'function' ? global.addEventListener.bind(global) : null);
  if (bindTarget) {
    bindTarget('agy:host-resume', function () {
      lastHostResumeTs = Date.now();
      if (pendingVisibilityTimer) {
        try { origClearTimeout(pendingVisibilityTimer); } catch (e) {}
        pendingVisibilityTimer = null;
      }
      triggerStreamRecovery('agy:host-resume', true);
    });
    bindTarget('offline', notifyNetworkFailure);
    bindTarget('online', notifyNetworkSuccess);
  }
  if (global.document && global.document.addEventListener) {
    global.document.addEventListener('visibilitychange', function () {
      if (global.document.visibilityState === 'visible') {
        if (pendingVisibilityTimer) {
          try { origClearTimeout(pendingVisibilityTimer); } catch (e) {}
        }
        pendingVisibilityTimer = origSetTimeout(function () {
          pendingVisibilityTimer = null;
          if ((Date.now() - lastHostResumeTs) > 500) {
            triggerStreamRecovery('visibilitychange:visible', false);
          }
        }, 150);
      }
    });
  }
} catch (e) { warn('resume listeners', e); }

function isStreamingRpc(pathOrUrl) {
  if (!pathOrUrl) return false;
  var s = String(pathOrUrl);
  return (s.indexOf('/exa.') !== -1 || s.indexOf('/jetski.') !== -1 || s.indexOf('Service/') !== -1)
      && (s.indexOf('Stream') !== -1 || s.indexOf('Subscribe') !== -1);
}

function isLoopbackRpcUrl(url) {
  if (!url) return false;
  var s = String(url);
  return /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?\//i.test(s) && isStreamingRpc(s);
}

function wrapStreamingFetch(origFetch, url, opts) {
  var callerSignal = (opts && opts.signal) || (url && typeof url === 'object' && url.signal) || null;
  var streamCtrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
  var resolveResume = null;
  var resumeInterrupter = new Promise(function (resolve) {
    resolveResume = resolve;
  });
  var closedByResume = false;
  var realReader = null;
  var entry = {
    openedAt: Date.now(),
    close: closeOnResume
  };

  function resolveResumeOnce() {
    if (resolveResume) {
      var r = resolveResume;
      resolveResume = null;
      try { r({ done: true, value: undefined }); } catch (e) {}
    }
  }

  function onCallerAbort() {
    activeStreamClosers.delete(entry);
    if (streamCtrl) {
      try { streamCtrl.abort(callerSignal && callerSignal.reason); } catch (e) {}
    }
    resolveResumeOnce();
  }

  function closeOnResume() {
    closedByResume = true;
    activeStreamClosers.delete(entry);
    resolveResumeOnce();
    if (realReader) {
      try { realReader.cancel('agy:host-resume'); } catch (e) {}
    }
    if (streamCtrl) {
      try { streamCtrl.abort(); } catch (e) {}
    }
  }

  if (callerSignal) {
    if (callerSignal.aborted) {
      onCallerAbort();
    } else if (callerSignal.addEventListener) {
      callerSignal.addEventListener('abort', onCallerAbort, { once: true });
    }
  }

  activeStreamClosers.add(entry);

  var fetchOpts = {};
  if (opts) {
    for (var k in opts) {
      if (Object.prototype.hasOwnProperty.call(opts, k)) fetchOpts[k] = opts[k];
    }
  }
  if (streamCtrl) {
    fetchOpts.signal = streamCtrl.signal;
  }

  return origFetch(url, fetchOpts).then(function (response) {
    if (!response || !response.body || typeof response.body.getReader !== 'function' || typeof ReadableStream === 'undefined') {
      return response;
    }
    var realBody = response.body;
    var wrappedStream = new ReadableStream({
      start: function () {
        realReader = realBody.getReader();
      },
      pull: function (controller) {
        if (closedByResume) {
          controller.close();
          return Promise.resolve();
        }
        return Promise.race([realReader.read(), resumeInterrupter]).then(function (res) {
          if (!res || res.done || closedByResume) {
            activeStreamClosers.delete(entry);
            controller.close();
          } else {
            controller.enqueue(res.value);
          }
        }).catch(function (err) {
          activeStreamClosers.delete(entry);
          if (closedByResume) {
            controller.close();
          } else {
            controller.error(err);
          }
        });
      },
      cancel: function (reason) {
        activeStreamClosers.delete(entry);
        if (realReader) {
          try { return realReader.cancel(reason); } catch (e) {}
        }
      }
    });

    try {
      return new Response(wrappedStream, {
        status: response.status,
        statusText: response.statusText,
        headers: response.headers
      });
    } catch (e) {
      try {
        Object.defineProperty(response, 'body', {
          get: function () { return wrappedStream; },
          configurable: true
        });
      } catch (e2) {}
      return response;
    }
  }).catch(function (err) {
    activeStreamClosers.delete(entry);
    throw err;
  });
}
