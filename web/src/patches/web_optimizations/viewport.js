/**
 * patches/web_optimizations/viewport.js - keep --agy-vvh honest initially and
 * while the soft keyboard settles, without thrashing during IME animations.
 */
function installViewport(global, doc) {
  var lastH = 0;
  var rafId = 0;

  function applyViewportHeight() {
    rafId = 0;
    try {
      if (!doc || !doc.documentElement) return;
      if (global.__agIsImeAnimating && global.__agIsImeAnimating()) return;
      var vv = global.visualViewport;
      var rawH = (vv && vv.height) ? vv.height : global.innerHeight;
      var h = rawH ? Math.round(rawH) : 0;
      if (h > 0 && h !== lastH) {
        lastH = h;
        doc.documentElement.style.setProperty('--agy-vvh', h + 'px');
      }
    } catch (e) {}
  }

  function scheduleViewportSync() {
    if (global.__agIsImeAnimating && global.__agIsImeAnimating()) return;
    if (typeof global.requestAnimationFrame === 'function') {
      if (rafId) return;
      rafId = global.requestAnimationFrame(applyViewportHeight);
    } else {
      applyViewportHeight();
    }
  }

  try {
    applyViewportHeight();
    if (global.visualViewport && typeof global.visualViewport.addEventListener === 'function') {
      global.visualViewport.addEventListener('resize', scheduleViewportSync, { passive: true });
    }
    if (typeof global.addEventListener === 'function') {
      global.addEventListener('resize', scheduleViewportSync, { passive: true });
    }
  } catch (e) {}
}

installViewport(global, doc);
