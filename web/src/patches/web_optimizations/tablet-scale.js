/**
 * patches/web_optimizations/tablet-scale.js - scale the whole UI up on tablets
 * by narrowing the layout viewport via <meta name="viewport"> (never CSS root zoom),
 * while leaving phones (<600 CSS px) and Desktop Mode (Antigravity/) untouched.
 */
function installTabletScale(globalObj, docRef) {
  try {
    var meta = null;
    try {
      meta = docRef && docRef.querySelector('meta[name="viewport"]');
    } catch (e) {}
    if (!meta || typeof meta.getAttribute !== 'function') return;

    var ua = '';
    try {
      ua = (globalObj.navigator && globalObj.navigator.userAgent) || '';
    } catch (e) {}
    if (ua.indexOf('Antigravity/') !== -1) return;

    var original = '';
    try {
      original = meta.getAttribute('content') || '';
    } catch (e) {}
    if (!original) return;

    var interactiveMatch = /\binteractive-widget=[^,\s]+/.exec(original);
    var interactivePart = interactiveMatch ? ', ' + interactiveMatch[0] : '';

    function currentScale() {
      try {
        var m = /initial-scale=([\d.]+)/.exec(meta.getAttribute('content') || '');
        var s = m ? parseFloat(m[1]) : 1;
        return s > 0 ? s : 1;
      } catch (e) {
        return 1;
      }
    }

    function deviceWidth() {
      try {
        return (globalObj.innerWidth || 0) * currentScale();
      } catch (e) {
        return 0;
      }
    }

    function scaleFor(w) {
      if (w >= 900) return 1.4;
      if (w >= 700) return 1.3;
      if (w >= 600) return 1.2;
      return 1;
    }

    function apply() {
      var w = deviceWidth();
      var s = scaleFor(w);
      try {
        if (s <= 1) {
          if (meta.getAttribute('content') !== original) {
            meta.setAttribute('content', original);
          }
          return;
        }
        var content =
          'width=' + Math.round(w / s) +
          ', initial-scale=' + s +
          ', viewport-fit=cover, maximum-scale=' + s +
          interactivePart;
        if (meta.getAttribute('content') !== content) {
          meta.setAttribute('content', content);
        }
      } catch (e) {}
    }

    apply();
    try {
      globalObj.addEventListener('resize', apply, { passive: true });
    } catch (e) {}
  } catch (e) {}
}

installTabletScale(global, doc);
