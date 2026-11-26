/**
 * patches/web_optimizations/viewport.js - keep --agy-vvh honest initially and
 * while the soft keyboard opens.
 */
function installViewport(global, doc) {
  function syncViewportHeight() {
    try {
      if (!doc || !doc.documentElement) return;
      var vv = global.visualViewport;
      var h = (vv && vv.height) ? vv.height : global.innerHeight;
      if (h && h > 0) {
        doc.documentElement.style.setProperty('--agy-vvh', Math.round(h) + 'px');
      }
    } catch (e) {}
  }

  try {
    syncViewportHeight();
    if (global.visualViewport && typeof global.visualViewport.addEventListener === 'function') {
      global.visualViewport.addEventListener('resize', syncViewportHeight, { passive: true });
    }
    if (typeof global.addEventListener === 'function') {
      global.addEventListener('resize', syncViewportHeight, { passive: true });
    }
  } catch (e) {}
}

installViewport(global, doc);
