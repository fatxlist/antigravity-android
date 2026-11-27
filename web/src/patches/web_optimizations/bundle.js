/**
 * patches/web_optimizations/bundle.js - Targeted Android WebView optimizations (PR #34):
 * 1. Base UI fast-path (`window.BASE_UI_ANIMATIONS_DISABLED = true`).
 * 2. Passive `scroll` listeners + block desktop overlay-scrollbar listeners.
 * 3. Targeted focus guard preventing startup / sheet-dismiss soft-keyboard popups.
 * 4. Targeted CSS optimizations for chat viewport, bottom sheets, menus, and GPUs.
 */
'use strict';

var global = window;
if (global.__AG_WEB_OPTIMIZATIONS__) return;
global.__AG_WEB_OPTIMIZATIONS__ = true;

global.BASE_UI_ANIMATIONS_DISABLED = true;

var doc = global.document;
if (!doc) return;

var imeAnimating = false;
var imeFailsafeTimer = 0;
var pendingWindowResize = false;
var pendingResizeObservers = typeof Map === 'function' ? new Map() : null;
var wrappedResizeListeners = typeof WeakMap === 'function' ? new WeakMap() : null;

var SCROLLBAR_DOC_EVENTS = {
  pointerdown: true,
  pointerup: true,
  pointercancel: true,
  contextmenu: true,
  dragstart: true,
  keydown: true,
  wheel: true,
  touchstart: true,
  touchmove: true,
  scroll: true
};

function isViewportResizeTarget(win, target) {
  return target === win || (win.visualViewport && target === win.visualViewport);
}

function flushDeferredResizes(win) {
  if (pendingWindowResize) {
    pendingWindowResize = false;
    try {
      var ev;
      if (typeof win.Event === 'function') {
        ev = new win.Event('resize');
      } else if (doc && typeof doc.createEvent === 'function') {
        ev = doc.createEvent('Event');
        ev.initEvent('resize', false, false);
      }
      if (ev && typeof win.dispatchEvent === 'function') {
        win.dispatchEvent(ev);
      }
      if (ev && win.visualViewport && typeof win.visualViewport.dispatchEvent === 'function') {
        win.visualViewport.dispatchEvent(ev);
      }
    } catch (_) {}
  }
  if (pendingResizeObservers && pendingResizeObservers.size > 0) {
    var batch = [];
    pendingResizeObservers.forEach(function (item) {
      batch.push(item);
    });
    pendingResizeObservers.clear();
    for (var i = 0; i < batch.length; i++) {
      var entry = batch[i];
      try {
        entry.cb.call(entry.observer, entry.entries, entry.observer);
      } catch (_) {}
    }
  }
}

global.__agIsImeAnimating = function () {
  return !!imeAnimating;
};

global.__agSetImeAnimating = function (active) {
  var next = !!active;
  if (imeFailsafeTimer) {
    clearTimeout(imeFailsafeTimer);
    imeFailsafeTimer = 0;
  }
  if (next) {
    imeAnimating = true;
    if (typeof setTimeout === 'function') {
      imeFailsafeTimer = setTimeout(function () {
        imeFailsafeTimer = 0;
        if (imeAnimating) {
          imeAnimating = false;
          flushDeferredResizes(global);
        }
      }, 450);
    }
    return;
  }
  imeAnimating = false;
  flushDeferredResizes(global);
};

function wrapResizeListener(origListener) {
  var wrapped = wrappedResizeListeners.get(origListener);
  if (!wrapped) {
    wrapped = function () {
      if (imeAnimating) {
        pendingWindowResize = true;
        return;
      }
      return origListener.apply(this, arguments);
    };
    wrappedResizeListeners.set(origListener, wrapped);
  }
  return wrapped;
}

function installEventOptimizations(win, documentRef) {
  try {
    var proto = win.EventTarget && win.EventTarget.prototype;
    if (!proto || proto.__agPassivePatched) return;
    var origAdd = proto.addEventListener;
    var origRemove = proto.removeEventListener;
    if (typeof origAdd !== 'function') return;

    var scrollbarListenerCache = typeof WeakMap === 'function' ? new WeakMap() : null;

    function isBlockedScrollbarDocListener(type, listener) {
      var cacheKey = scrollbarListenerCache ? scrollbarListenerCache.get(listener) : undefined;
      if (cacheKey !== undefined) {
        return cacheKey === 'all' || cacheKey === type;
      }
      var classification = 'keep';
      try {
        var fnStr = Function.prototype.toString.call(listener);
        if (fnStr.indexOf('case "dragstart":') !== -1 && fnStr.indexOf('deltaX') !== -1) {
          classification = 'all';
        } else if (fnStr.indexOf('.md-table-scroll') !== -1) {
          classification = 'scroll';
        }
      } catch (_) {}
      if (scrollbarListenerCache) {
        scrollbarListenerCache.set(listener, classification);
      }
      return classification === 'all' || classification === type;
    }

    proto.addEventListener = function (type, listener, options) {
      if (listener && typeof listener === 'function') {
        if (this === documentRef && SCROLLBAR_DOC_EVENTS[type]) {
          if (isBlockedScrollbarDocListener(type, listener)) {
            return;
          }
        }
        if (type === 'wheel' && this && this.getAttribute &&
            this.getAttribute('data-testid') === 'overlay-scrollbar') {
          return;
        }
        if (type === 'resize' && wrappedResizeListeners && isViewportResizeTarget(win, this)) {
          listener = wrapResizeListener(listener);
        }
      }

      if (type === 'scroll') {
        if (options === undefined || typeof options === 'boolean') {
          options = { capture: !!options, passive: true };
        } else if (typeof options === 'object' && options !== null && options.passive === undefined) {
          options = {
            capture: !!options.capture,
            once: !!options.once,
            passive: true,
            signal: options.signal
          };
        }
      }
      return origAdd.call(this, type, listener, options);
    };

    if (typeof origRemove === 'function') {
      proto.removeEventListener = function (type, listener, options) {
        if (type === 'resize' && listener && wrappedResizeListeners && isViewportResizeTarget(win, this)) {
          var wrapped = wrappedResizeListeners.get(listener);
          if (wrapped) {
            return origRemove.call(this, type, wrapped, options);
          }
        }
        return origRemove.call(this, type, listener, options);
      };
    }

    proto.__agPassivePatched = true;
  } catch (e) {}
}

function installResizeObserverGate(win) {
  try {
    var OrigRO = win.ResizeObserver;
    if (!OrigRO || OrigRO.__agGated) return;

    var WrappedRO = function (cb) {
      var roInstance;
      var wrappedCb = function (entries, observer) {
        var obs = observer || roInstance;
        if (imeAnimating && pendingResizeObservers) {
          pendingResizeObservers.set(obs, { cb: cb, entries: entries, observer: obs });
          return;
        }
        return cb.call(this, entries, obs);
      };
      roInstance = new OrigRO(wrappedCb);
      var origDisconnect = roInstance.disconnect;
      if (typeof origDisconnect === 'function') {
        roInstance.disconnect = function () {
          if (pendingResizeObservers) {
            pendingResizeObservers.delete(roInstance);
          }
          return origDisconnect.apply(this, arguments);
        };
      }
      return roInstance;
    };
    WrappedRO.prototype = OrigRO.prototype;
    WrappedRO.__agGated = true;
    win.ResizeObserver = WrappedRO;
  } catch (e) {}
}

function installFocusGuard(win, documentRef) {
  try {
    var htmlProto = win.HTMLElement && win.HTMLElement.prototype;
    if (!htmlProto || htmlProto.__agFocusGuarded) return;
    var origFocus = htmlProto.focus;
    if (typeof origFocus !== 'function') return;

    var hasAnyUserTap = false;
    var lastPopupTapTime = 0;
    var lastPopupElement = null;

    var POPUP_SELECTOR =
      '[data-testid="model-selector-trigger"],' +
      '[data-testid="model-selector-panel"],' +
      '[data-testid="model-selector-item"],' +
      '[data-testid="model-selector-effort-group"],' +
      '[data-testid="model-selector-effort-option"],' +
      '[data-testid="bottom-sheet"],' +
      '[data-testid="bottom-sheet-backdrop"],' +
      '[data-testid="sidebar-toggle"],' +
      '[data-testid="sidebar-popover-backdrop"],' +
      '[role="navigation"], [role="menu"], [role="menuitem"],' +
      '[role="menuitemradio"], [role="menuitemcheckbox"],' +
      '[role="listbox"], [role="option"], [role="dialog"]';

    var INPUT_SELECTOR = '[data-lexical-editor="true"], [contenteditable="true"], textarea, input';

    var recordTap = function (ev) {
      if (ev && ev.target && !ev.__agTouchSynthetic) {
        hasAnyUserTap = true;
        var el = ev.target.closest ? ev.target : ev.target.parentElement;
        if (el && el.closest && !el.closest(INPUT_SELECTOR)) {
          var popup = el.closest(POPUP_SELECTOR);
          if (popup) {
            lastPopupTapTime = Date.now();
            lastPopupElement = popup;
            return;
          }
        }
        lastPopupTapTime = 0;
        lastPopupElement = null;
      }
    };
    documentRef.addEventListener('pointerdown', recordTap, { capture: true, passive: true });
    documentRef.addEventListener('touchstart', recordTap, { capture: true, passive: true });

    htmlProto.focus = function (options) {
      try {
        var isEditor =
          this.isContentEditable ||
          (this.getAttribute && (
            this.getAttribute('contenteditable') === 'true' ||
            this.hasAttribute('data-lexical-editor')
          ));

        if (isEditor) {
          if (!hasAnyUserTap) {
            return;
          }
          if (lastPopupTapTime && (Date.now() - lastPopupTapTime) < 700) {
            var samePopupDialog = lastPopupElement && lastPopupElement.contains && lastPopupElement.contains(this);
            if (!samePopupDialog) {
              return;
            }
          }

          if (!options || typeof options !== 'object') {
            options = { preventScroll: true };
          } else if (options.preventScroll === undefined) {
            options = { preventScroll: true, focusVisible: options.focusVisible };
          }
        }
      } catch (_) {}
      return origFocus.call(this, options);
    };
    htmlProto.__agFocusGuarded = true;
  } catch (e) {}
}

function installStyles(documentRef) {
  try {
    var existing = documentRef.querySelectorAll(
      'style[data-agy="web-optimizations"], style[data-agy="gpu-acceleration"]'
    );
    for (var i = 0; i < existing.length; i++) {
      if (existing[i].parentNode) existing[i].parentNode.removeChild(existing[i]);
    }

    var style = documentRef.createElement('style');
    style.setAttribute('data-agy', 'web-optimizations');
    style.textContent = [
      ':root {',
      '  --agy-safe-top: max(env(safe-area-inset-top, 0px), var(--agy-inset-top, 0px));',
      '  --agy-safe-bottom: max(env(safe-area-inset-bottom, 0px), var(--agy-inset-bottom, 0px));',
      '  --agy-safe-left: max(env(safe-area-inset-left, 0px), var(--agy-inset-left, 0px));',
      '  --agy-safe-right: max(env(safe-area-inset-right, 0px), var(--agy-inset-right, 0px));',
      '}',
      'html.agy-extra-keys { --agy-safe-bottom: 0px; }',
      '[data-testid="mobile-layout"] [style*="calc(48px + env(safe-area-inset-top))"] {',
      '  height: calc(48px + var(--agy-safe-top)) !important;',
      '  padding-top: var(--agy-safe-top) !important;',
      '}',
      '[style*="calc(100% - 48px - env(safe-area-inset-top"] {',
      '  height: calc(100% - 48px - var(--agy-safe-top)) !important;',
      '}',
      '[data-testid="mobile-layout"] {',
      '  padding-left: var(--agy-safe-left);',
      '  padding-right: var(--agy-safe-right);',
      '}',
      '[data-testid="mobile-layout"]:not(:has([data-testid="agent-input-box"])) {',
      '  padding-bottom: var(--agy-safe-bottom);',
      '}',
      '[data-testid="mobile-layout"] [data-testid="agent-input-box"],',
      '[data-testid="bottom-sheet"] {',
      '  padding-bottom: var(--agy-safe-bottom) !important;',
      '}',
      '.agy-shell {',
      '  padding-top: var(--agy-safe-top) !important;',
      '  padding-left: var(--agy-safe-left);',
      '  padding-right: var(--agy-safe-right);',
      '  padding-bottom: var(--agy-safe-bottom);',
      '}',
      '.settings-modal-container, .onboarding-shell {',
      '  padding-top: var(--agy-safe-top);',
      '  padding-bottom: var(--agy-safe-bottom);',
      '  padding-left: var(--agy-safe-left);',
      '  padding-right: var(--agy-safe-right);',
      '}',
      '*, *::before, *::after { -webkit-tap-highlight-color: transparent; }',
      'button, a, [role="button"], [role="menuitem"], [role="menuitemradio"], [role="option"], [role="tab"], input, select, textarea {',
      '  touch-action: manipulation;',
      '}',
      '[data-testid="autoscroll-viewport"] {',
      '  -webkit-overflow-scrolling: touch;',
      '  overscroll-behavior-y: contain;',
      '  touch-action: pan-y;',
      '  scroll-behavior: auto !important;',
      '}',
      '.jetski-scrollable-element {',
      '  -webkit-overflow-scrolling: touch;',
      '  touch-action: pan-x pan-y;',
      '  scroll-behavior: auto !important;',
      '}',
      '[role="listbox"], [role="menu"], [data-testid="model-selector-panel"] .overflow-y-auto {',
      '  -webkit-overflow-scrolling: touch;',
      '  touch-action: pan-y;',
      '}',
      '[data-testid="overlay-scrollbar"], [data-testid="overlay-scrollbar-thumb"] {',
      '  display: none !important;',
      '  opacity: 0 !important;',
      '  pointer-events: none !important;',
      '  animation: none !important;',
      '  transition: none !important;',
      '}',
      'html.overlay-scrollbars [data-testid="autoscroll-viewport"], html.overlay-scrollbars .jetski-scrollable-element {',
      '  scrollbar-width: thin;',
      '}',
      '[data-testid="autoscroll-viewport"] [role="article"][aria-label="User message"]::after {',
      '  display: none !important;',
      '  content: none !important;',
      '  background: none !important;',
      '}',
      '[data-testid="autoscroll-viewport"] [role="article"] {',
      '  content-visibility: auto;',
      '  contain-intrinsic-block-size: auto 180px;',
      '}',
      '[data-testid="autoscroll-viewport"] > div > .scroll-mt-4,',
      '[data-testid="autoscroll-viewport"] [role="article"][aria-label="Agent response"],',
      '.artifact-viewer, .artifact-card {',
      '  contain: style;',
      '}',
      'pre, .code-block, .md-table-scroll {',
      '  contain: style;',
      '  overscroll-behavior-x: contain;',
      '}',
      '.transition-\\[height\\], .transition-\\[width\\],',
      '[style*="transition: width 500ms"], [style*="transition: padding-left 500ms"], [style*="transition: padding-right 500ms"] {',
      '  transition: none !important;',
      '}',
      '.aux-drawer-backdrop, [data-testid="bottom-sheet-backdrop"] {',
      '  transition: none !important;',
      '  animation: none !important;',
      '  will-change: opacity;',
      '  backface-visibility: hidden;',
      '}',
      '.aux-drawer-popup, [data-testid="bottom-sheet"] {',
      '  transition: none !important;',
      '  animation: none !important;',
      '  box-shadow: 0 -2px 10px rgba(0, 0, 0, 0.22) !important;',
      '  will-change: transform;',
      '  contain: layout paint style;',
      '  backface-visibility: hidden;',
      '}',
      '.animate-slideIn, .animate-overview-panel {',
      '  transition: none !important;',
      '  transition-duration: 0ms !important;',
      '  animation: none !important;',
      '  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25) !important;',
      '}',
      '.aux-drawer-popup[data-starting-style], .aux-drawer-backdrop[data-starting-style],',
      '.animate-slideIn[data-starting-style], .animate-overview-panel[data-starting-style] {',
      '  opacity: 1 !important;',
      '  transform: none !important;',
      '}',
      '.aux-drawer-popup[data-ending-style], .aux-drawer-backdrop[data-ending-style],',
      '.animate-slideIn[data-ending-style], .animate-overview-panel[data-ending-style] {',
      '  opacity: 0 !important;',
      '  pointer-events: none !important;',
      '  transition: none !important;',
      '}',
      '[data-testid="sidebar-popover-backdrop"] {',
      '  transition: none !important;',
      '}',
      'div[role="dialog"][aria-modal="true"]:not([aria-label="Settings"]) {',
      '  transition: none !important;',
      '  box-shadow: none !important;',
      '}',
      '.animate-in.slide-in-from-left {',
      '  animation-duration: 0ms !important;',
      '}',
      '[role="navigation"] [style*="mask-image"],',
      '[role="dialog"] [style*="mask-image"],',
      '[role="menu"] [style*="mask-image"],',
      '[role="listbox"] [style*="mask-image"],',
      '[data-testid="model-selector-panel"] [style*="mask-image"],',
      '[data-testid="bottom-sheet"] [style*="mask-image"] {',
      '  -webkit-mask-image: none !important;',
      '  mask-image: none !important;',
      '}',
      '.backdrop-blur-sm, .backdrop-blur, .backdrop-blur-md, .backdrop-blur-lg, [class*="backdrop-blur"] {',
      '  -webkit-backdrop-filter: none !important;',
      '  backdrop-filter: none !important;',
      '}',
      'code[class*="language-"], pre[class*="language-"], code[class*="language-"] *, pre[class*="language-"] * {',
      '  text-shadow: none !important;',
      '}',
      '[data-testid="input-attachment"] img[alt*="video" i],',
      '[data-testid="input-attachment-open"][aria-label*="video" i] img,',
      'img[src^="data:video/"], img[data-agy-broken-video="true"] {',
      '  display: none !important;',
      '}',
      '.agy-video-badge {',
      '  display: inline-flex; align-items: center; gap: 4px;',
      '  padding: 3px 8px; border-radius: 6px;',
      '  background: rgba(56, 189, 248, 0.14); border: 1px solid rgba(56, 189, 248, 0.35);',
      '  color: #38bdf8; font-size: 11px; font-weight: 600; line-height: 1.2;',
      '  user-select: none;',
      '}'
    ].join('\n');

    (documentRef.head || documentRef.documentElement).appendChild(style);
  } catch (e) {}
}

function installMediaErrorFallback(documentRef) {
  try {
    if (!documentRef || documentRef.__agMediaFallbackInstalled || typeof documentRef.addEventListener !== 'function') {
      return;
    }
    documentRef.__agMediaFallbackInstalled = true;
    documentRef.addEventListener('error', function (ev) {
      try {
        var img = ev && ev.target;
        if (!img || String(img.tagName || '').toUpperCase() !== 'IMG') return;
        var src = (img.getAttribute && img.getAttribute('src')) || img.src || '';
        var alt = (img.getAttribute && img.getAttribute('alt')) || '';
        var parent = img.parentNode;
        var parentLabel = parent && parent.getAttribute ? (parent.getAttribute('aria-label') || '') : '';
        var combined = src + ' ' + alt + ' ' + parentLabel;
        if (!/^data:video\//i.test(src) && !/(?:\.(?:mp4|mov|webm|mkv|avi)\b|\bvideo\b)/i.test(combined)) {
          return;
        }
        if (img.setAttribute) img.setAttribute('data-agy-broken-video', 'true');
        if (img.style) img.style.display = 'none';
        if (!parent || typeof documentRef.createElement !== 'function') return;
        if (parent.querySelector && parent.querySelector('[data-agy-video-badge="true"]')) return;
        var extMatch = combined.match(/\.(mp4|mov|webm|mkv|avi)\b/i) || src.match(/^data:video\/([a-z0-9]+)/i);
        var label = extMatch ? String(extMatch[1]).toUpperCase() : 'VIDEO';
        var badge = documentRef.createElement('span');
        badge.className = 'agy-video-badge';
        badge.setAttribute('data-agy-video-badge', 'true');
        badge.textContent = '\u25b6 ' + label;
        parent.appendChild(badge);
      } catch (_) {}
    }, true);
  } catch (e) {}
}

installEventOptimizations(global, doc);
installResizeObserverGate(global);
installFocusGuard(global, doc);
installStyles(doc);
installMediaErrorFallback(doc);

global.__agWebOptimizations = {
  installed: true,
  version: '3.2.0'
};
