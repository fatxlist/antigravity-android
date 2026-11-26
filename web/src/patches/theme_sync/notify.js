/**
 * patches/theme_sync/notify.js - sync DOM classes, color-scheme, surface color, and native Android.
 */
function rgbToHex(cssColor) {
  if (!cssColor || typeof cssColor !== 'string') return null;
  var s = cssColor.trim().toLowerCase();
  if (!s || s === 'transparent') return null;
  if (/^#[0-9a-f]{6}$/.test(s)) return s;
  if (/^#[0-9a-f]{3}$/.test(s)) {
    return '#' + s[1] + s[1] + s[2] + s[2] + s[3] + s[3];
  }
  var m = /^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*([0-9.]+))?\s*\)$/.exec(s);
  if (!m) return null;
  if (m[4] !== undefined && parseFloat(m[4]) < 0.9) return null;
  function toHex2(n) {
    var v = Math.max(0, Math.min(255, parseInt(n, 10) || 0));
    var h = v.toString(16);
    return h.length < 2 ? '0' + h : h;
  }
  return '#' + toHex2(m[1]) + toHex2(m[2]) + toHex2(m[3]);
}

function readSurfaceHex() {
  try {
    if (typeof window.getComputedStyle !== 'function' || !doc) return null;
    var candidates = [doc.body, doc.getElementById('root'), doc.documentElement];
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (!el) continue;
      var hex = rgbToHex(window.getComputedStyle(el).backgroundColor);
      if (hex) return hex;
    }
  } catch (e) { /* ignore */ }
  return null;
}

function syncSurfaceColor() {
  try {
    var hex = readSurfaceHex();
    if (hex && hex !== state.lastSurfaceHex
        && window.Android && typeof window.Android.setSurfaceColor === 'function'
        && window.__AG_NATIVE_BRIDGE_TOKEN__) {
      state.lastSurfaceHex = hex;
      window.Android.setSurfaceColor(window.__AG_NATIVE_BRIDGE_TOKEN__, hex);
    }
  } catch (e) { /* ignore */ }
}

function notify() {
  var bodyTheme = fromClassList();
  var theme = resolveTheme();
  var themeChanged = (theme !== state.last);
  var bodyChanged = (bodyTheme !== null && bodyTheme !== state.lastFromBody);
  if (!themeChanged && !bodyChanged) return theme;
  state.last = theme;
  if (bodyTheme !== null) state.lastFromBody = bodyTheme;
  state.notifications++;

  try {
    var isDark = (theme === 'dark');
    doc.documentElement.classList.toggle('dark', isDark);
    doc.documentElement.classList.toggle('light', !isDark);
    doc.documentElement.style.colorScheme = theme;
    updateColorSchemeMeta(theme);
  } catch (e) { /* ignore */ }

  try {
    if (window.Android) {
      state.hasAndroid = true;
      // Only persist explicit user preference from body class transitions after boot,
      // never overwrite Android's "system" mode during initial pre-React load.
      if (bodyChanged && state.notifications > 1 && !window.__agThemeExplicitSync) {
        if (typeof window.Android.setThemeByUser === 'function') {
          window.Android.setThemeByUser(bodyTheme);
        } else if (typeof window.Android.setTheme === 'function') {
          window.Android.setTheme(bodyTheme);
        }
      }
      if (typeof window.Android.onVisualThemeChanged === 'function') {
        window.Android.onVisualThemeChanged(theme);
      }
      syncSurfaceColor();
      if (state.surfaceSyncTimer) clearTimeout(state.surfaceSyncTimer);
      state.surfaceSyncTimer = setTimeout(syncSurfaceColor, 150);
    }
  } catch (e) { /* never throw into JS */ }
  return theme;
}
