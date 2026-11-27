/**
 * core/public-api.js - expose window.__agBridgeCore.
 *
 * Dead entries listed in docs/DEADCODE.md are not ported.
 */
var api = {
  version: VERSION,
  config: CONFIG,
  endpoints: ENDPOINTS,
  endpoint: endpoint,
  setEndpoint: setEndpoint,
  baseOrigin: baseOrigin,
  syncCsrf: syncCsrf,
  parseEndpointSpec: parseEndpointSpec,
  triggerStreamRecovery: typeof triggerStreamRecovery === 'function' ? triggerStreamRecovery : function () {},
  getActiveStreamCount: function () { return typeof activeStreamClosers !== 'undefined' ? activeStreamClosers.size : 0; },
  notifyNetworkFailure: typeof notifyNetworkFailure === 'function' ? notifyNetworkFailure : function () {},
  notifyNetworkSuccess: typeof notifyNetworkSuccess === 'function' ? notifyNetworkSuccess : function () {}
};

window.__agBridgeCore = api;

try { console.log('[AGY bridge.core] v' + VERSION + ' ready (loopback stream recovery + online shim active)'); } catch (e) {}
