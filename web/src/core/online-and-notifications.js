/**
 * core/online-and-notifications.js - Fix false "No internet" banner in Android
 * WebView and polyfill Web Notifications API.
 */
try {
  if (typeof Navigator !== 'undefined' && Navigator.prototype) {
    Object.defineProperty(Navigator.prototype, 'onLine', {
      get: function () { return true; },
      configurable: true
    });
  }
  var origAddEventListener = global.addEventListener ? global.addEventListener.bind(global) : null;
  if (origAddEventListener) {
    global.addEventListener = function (type, listener, options) {
      if (type === 'offline') {
        return;
      }
      return origAddEventListener(type, listener, options);
    };
  }
} catch (e) { warn('online-shim', e); }

try {
  if (typeof global.Notification === 'undefined') {
    var FakeNotification = function (title, options) {
      var opts = options && typeof options === 'object' ? options : {};
      this.title = String(title || 'Antigravity');
      this.body = opts.body ? String(opts.body) : '';
      this.tag = opts.tag ? String(opts.tag) : ('agy_' + Date.now());
      this.silent = !!opts.silent;
      this.icon = opts.icon ? String(opts.icon) : '';
      this.data = opts.data !== undefined ? opts.data : null;
      this.onclick = null;
      this.onclose = null;
      try {
        var android = global.Android;
        var tok = global.__AG_NATIVE_BRIDGE_TOKEN__;
        if (android && typeof android.showAgentNotification === 'function' && tok) {
          android.showAgentNotification(String(tok), this.title, this.body, this.tag, !!this.silent);
        }
      } catch (_) {}
    };
    FakeNotification.permission = 'granted';
    FakeNotification.requestPermission = function (cb) {
      if (typeof cb === 'function') {
        try { cb('granted'); } catch (e) {}
      }
      return Promise.resolve('granted');
    };
    FakeNotification.prototype.close = function () {
      if (typeof this.onclose === 'function') {
        try { this.onclose(); } catch (e) {}
      }
    };
    global.Notification = FakeNotification;
  }
} catch (e) { warn('notification-polyfill', e); }
