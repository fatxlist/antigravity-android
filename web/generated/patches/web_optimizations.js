(function(){
/**
 * patches/web_optimizations/bundle.js - Targeted Android WebView optimizations (PR #34):
 * 1. Base UI fast-path (`window.BASE_UI_ANIMATIONS_DISABLED = true`).
 * 2. Passive `scroll` listeners + block desktop overlay-scrollbar listeners.
 * 3. Targeted focus guard preventing startup / sheet-dismiss soft-keyboard popups.
 * 4. Surgical lazy 48kHz AudioContext/AudioWorklet gate.
 * 5. Targeted CSS optimizations for chat viewport, bottom sheets, menus, and GPUs.
 */
'use strict';

var global = window;
if (global.__AG_WEB_OPTIMIZATIONS__) return;
global.__AG_WEB_OPTIMIZATIONS__ = true;

global.BASE_UI_ANIMATIONS_DISABLED = true;

var doc = global.document;
if (!doc) return;

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

function installEventOptimizations(win, documentRef) {
  try {
    var proto = win.EventTarget && win.EventTarget.prototype;
    if (!proto || proto.__agPassivePatched) return;
    var origAdd = proto.addEventListener;
    if (typeof origAdd !== 'function') return;

    proto.addEventListener = function (type, listener, options) {
      if (listener && typeof listener === 'function') {
        if (this === documentRef && SCROLLBAR_DOC_EVENTS[type]) {
          try {
            var fnStr = Function.prototype.toString.call(listener);
            if (type === 'scroll' && fnStr.indexOf('.md-table-scroll') !== -1) {
              return;
            }
            if (fnStr.indexOf('case "dragstart":') !== -1 && fnStr.indexOf('deltaX') !== -1) {
              return;
            }
          } catch (_) {}
        }
        if (type === 'wheel' && this && this.getAttribute &&
            this.getAttribute('data-testid') === 'overlay-scrollbar') {
          return;
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
    proto.__agPassivePatched = true;
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

function installLazyAudioGate(win) {
  try {
    var OrigAudioContext = win.AudioContext || win.webkitAudioContext;
    if (!OrigAudioContext || OrigAudioContext.__agLazyGated) return;

    var WrappedAudioContext = function (opts) {
      var ctx = opts !== undefined ? new OrigAudioContext(opts) : new OrigAudioContext();
      var isBootVoiceCtx = !!(opts && opts.sampleRate === 48000);
      try {
        if (isBootVoiceCtx && ctx.state === 'running' && typeof ctx.suspend === 'function') {
          ctx.suspend().catch(function () {});
        }
        if (isBootVoiceCtx && ctx.audioWorklet && typeof ctx.audioWorklet.addModule === 'function') {
          var origAddModule = ctx.audioWorklet.addModule.bind(ctx.audioWorklet);
          var pendingUrl = null;
          var pendingOpts = undefined;
          var loadedPromise = null;

          var ensureLoaded = function () {
            if (!loadedPromise && pendingUrl) {
              loadedPromise = origAddModule(pendingUrl, pendingOpts);
            }
            return loadedPromise || Promise.resolve();
          };

          ctx.audioWorklet.addModule = function (moduleUrl, workletOpts) {
            pendingUrl = moduleUrl;
            pendingOpts = workletOpts;
            return Promise.resolve();
          };

          if (typeof ctx.resume === 'function') {
            var origResume = ctx.resume.bind(ctx);
            ctx.resume = function () {
              return ensureLoaded().then(function () {
                return origResume();
              });
            };
          }
          if (typeof ctx.createMediaStreamSource === 'function') {
            var origCreateStream = ctx.createMediaStreamSource.bind(ctx);
            ctx.createMediaStreamSource = function (stream) {
              ensureLoaded();
              return origCreateStream(stream);
            };
          }
        }
      } catch (_) {}
      return ctx;
    };
    WrappedAudioContext.prototype = OrigAudioContext.prototype;
    WrappedAudioContext.__agLazyGated = true;
    win.AudioContext = WrappedAudioContext;
    if (win.webkitAudioContext) {
      win.webkitAudioContext = WrappedAudioContext;
    }
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
      '}'
    ].join('\n');

    (documentRef.head || documentRef.documentElement).appendChild(style);
  } catch (e) {}
}

installEventOptimizations(global, doc);
installFocusGuard(global, doc);
installLazyAudioGate(global);
installStyles(doc);

global.__agWebOptimizations = {
  installed: true,
  version: '3.2.0'
};

/**
 * patches/web_optimizations/bidi.js - Automatic BiDi/RTL text alignment (PR #22)
 * while keeping code blocks, inline code, and terminals strictly LTR-isolated.
 */
function installBidi(docRef) {
  try {
    if (!docRef.querySelector('style[data-agy="bidi-fix"]')) {
      var bidiStyle = docRef.createElement('style');
      bidiStyle.setAttribute('data-agy', 'bidi-fix');
      bidiStyle.textContent =
        '[role="listitem"] p, [role="listitem"] li, [role="article"] p, [role="article"] li, ' +
        '[data-testid="conversation-view"] p, [data-testid="conversation-view"] li, ' +
        '.message-row p, .message-row li, .markdown p, .markdown li, article p, article li {' +
        '  unicode-bidi: plaintext !important;' +
        '  text-align: start !important;' +
        '}' +
        '[role="listitem"] pre, [role="article"] pre, .message-row pre, .markdown pre, code, .xterm, .xterm * {' +
        '  unicode-bidi: isolate !important;' +
        '  direction: ltr !important;' +
        '  text-align: left !important;' +
        '}';
      (docRef.head || docRef.documentElement).appendChild(bidiStyle);
    }
  } catch (e) {}
}

installBidi(doc);

/**
 * patches/web_optimizations/viewport.js - keep --agy-vvh honest while the
 * soft keyboard opens.
 */
function installViewport(global, doc) {
  try {
    if (global.visualViewport) {
      global.visualViewport.addEventListener('resize', function () {
        var vv = global.visualViewport;
        if (vv && vv.height && doc.documentElement) {
          doc.documentElement.style.setProperty(
            '--agy-vvh', Math.round(vv.height) + 'px');
        }
      }, { passive: true });
    }
  } catch (e) {}
}

installViewport(global, doc);


})();
