# Web Optimizations (`web/src/patches/web_optimizations`)

## Overview
Android WebView runtime optimization patch concatenated via `web/src.manifest` into `patches/web_optimizations.js`. It coordinates IME-aware `resize`/`ResizeObserver` deferral, passive/filtered DOM event handling, contenteditable focus suppression, safe-area and BiDi/LTR CSS injection, and `requestAnimationFrame`-coalesced `--agy-vvh` visual viewport height synchronization.

## Interface
- `[bundle.js:__AG_WEB_OPTIMIZATIONS__]` Global idempotency sentinel set to `true` on `window`.
- `[bundle.js:BASE_UI_ANIMATIONS_DISABLED]` Global boolean flag set to `true` on `window` to disable Base UI animations.
- `[bundle.js:__agIsImeAnimating]` Public function `window.__agIsImeAnimating()` returning `!!imeAnimating`.
- `[bundle.js:__agSetImeAnimating]` Public function `window.__agSetImeAnimating(active)` toggling IME animation deferral, managing the 450ms failsafe timer, and flushing deferred resizes on deactivation.
- `[bundle.js:__agWebOptimizations]` Public metadata object on `window`: `{ installed: true, version: '3.2.0' }`.
- `[bundle.js:__agPassivePatched]` / `[bundle.js:__agGated]` / `[bundle.js:__agFocusGuarded]` Idempotency marker flags placed on `EventTarget.prototype`, wrapped `window.ResizeObserver`, and `HTMLElement.prototype`.
- `[bundle.js:__agTouchSynthetic]` Event marker checked by `recordTap` in `installFocusGuard` to ignore synthetic touch events.
- `[bundle.js:installStyles]` Injects `<style data-agy="web-optimizations">` defining `:root` custom properties `--agy-safe-top`, `--agy-safe-bottom`, `--agy-safe-left`, `--agy-safe-right` from `--agy-inset-*` / `env(safe-area-inset-*)`, reacting to `html.agy-extra-keys`, and hiding broken `data:video/` thumbnails while styling `.agy-video-badge`.
- `[bundle.js:installMediaErrorFallback]` Attaches a capture-phase `error` listener on `document` that marks failed video `<img>` elements (`data-agy-broken-video="true"`, `display: none`) and appends `<span class="agy-video-badge" data-agy-video-badge="true">` with the uppercase format label.
- `[bidi.js:installBidi]` Injects `<style data-agy="bidi-fix">` for automatic BiDi prose alignment and LTR code/terminal isolation.
- `[viewport.js:installViewport]` / `[viewport.js:applyViewportHeight]` Maintains `--agy-vvh` on `document.documentElement.style` using shared IIFE-local `[bundle.js:global]` and `[bundle.js:doc]`.
- `[tablet-scale.js:installTabletScale]` Scales the UI on tablets (`>= 600` CSS px) by narrowing the `<meta name="viewport">` layout width and setting `initial-scale` (`1.2x` / `1.3x` / `1.4x`) while preserving `interactive-widget=` and skipping phones and Desktop Mode (`Antigravity/`).

## Invariants
- `[bundle.js:__AG_WEB_OPTIMIZATIONS__]` / `[bundle.js:doc]` The concatenated IIFE executes at most once per `window` and exits early if `window.document` is unavailable.
- `[bundle.js:__agPassivePatched]` / `[bundle.js:wrappedResizeListeners]` `EventTarget.prototype` wrapping is idempotent; `removeEventListener` resolves the `WeakMap`-cached wrapper for `window`/`visualViewport` `resize` listeners so removal continues to match.
- `[bundle.js:installEventOptimizations]` / `[bundle.js:SCROLLBAR_DOC_EVENTS]` / `[bundle.js:scrollbarListenerCache]` Caches `Function.prototype.toString` classification per document listener in a `WeakMap` (`isBlockedScrollbarDocListener`), drops `document` scrollbar listeners matching `'.md-table-scroll'` (on `scroll`) or both `'case "dragstart":'` and `'deltaX'`, drops `wheel` listeners on `[data-testid="overlay-scrollbar"]`, and defaults `scroll` listeners to `passive: true` when `options.passive` is omitted.
- `[bundle.js:__agSetImeAnimating]` / `[bundle.js:isViewportResizeTarget]` / `[bundle.js:wrapResizeListener]` / `[bundle.js:installResizeObserverGate]` While `imeAnimating` is `true`, viewport `resize` events collapse into `pendingWindowResize = true` and `ResizeObserver` callbacks coalesce per observer in `pendingResizeObservers`.
- `[bundle.js:imeFailsafeTimer]` / `[bundle.js:flushDeferredResizes]` `imeAnimating` auto-clears after 450ms if not explicitly cleared; flushing snapshots and clears `pendingResizeObservers` before invoking callbacks and dispatches synthetic `resize` events to `window` and `window.visualViewport` when `pendingWindowResize` was set.
- `[bundle.js:installResizeObserverGate]` / `[bundle.js:__agGated]` Wrapped `ResizeObserver` preserves `OrigRO.prototype` and removes disconnected observers from `pendingResizeObservers` inside wrapped `disconnect()`.
- `[bundle.js:installFocusGuard]` / `[bundle.js:POPUP_SELECTOR]` / `[bundle.js:INPUT_SELECTOR]` Blocks programmatic `.focus()` on contenteditable / `[data-lexical-editor]` elements before the first real (`!ev.__agTouchSynthetic`) user tap and for 700ms after tapping a popup element outside input selectors unless the editor is inside `lastPopupElement`; otherwise defaults `options.preventScroll = true`.
- `[bundle.js:installStyles]` Removes stale `style[data-agy="web-optimizations"]` / `style[data-agy="gpu-acceleration"]` before appending; maps `--agy-safe-*` to `max(env(safe-area-inset-*, 0px), var(--agy-inset-*, 0px))`, zeroes `--agy-safe-bottom` under `html.agy-extra-keys`, applies `content-visibility: auto; contain-intrinsic-block-size: auto 180px` on `[data-testid="autoscroll-viewport"] [role="article"]` for offscreen thread layout skipping, and exempts `[aria-label="Settings"]` from dialog transition/shadow stripping.
- `[bidi.js:installBidi]` Injects `<style data-agy="bidi-fix">` only if absent; prose `p`/`li` in conversation/article/markdown containers enforces `unicode-bidi: plaintext !important; text-align: start !important`, while `pre`, `code`, `.xterm`, and `.xterm *` enforce `unicode-bidi: isolate !important; direction: ltr !important; text-align: left !important`.
- `[viewport.js:applyViewportHeight]` / `[viewport.js:scheduleViewportSync]` Never writes `--agy-vvh` while `global.__agIsImeAnimating()` is truthy, rounds `visualViewport.height || innerHeight`, skips writes when `h <= 0` or `h === lastH`, and coalesces updates to a single `requestAnimationFrame` callback (`rafId`).

## Side Effects
- `[bundle.js:__AG_WEB_OPTIMIZATIONS__]` / `[bundle.js:__agWebOptimizations]` Mutates `window` with `__AG_WEB_OPTIMIZATIONS__`, `BASE_UI_ANIMATIONS_DISABLED`, `__agIsImeAnimating`, `__agSetImeAnimating`, and `__agWebOptimizations`.
- `[bundle.js:installEventOptimizations]` Monkey-patches `EventTarget.prototype.addEventListener` and `EventTarget.prototype.removeEventListener` and sets `EventTarget.prototype.__agPassivePatched = true`.
- `[bundle.js:installResizeObserverGate]` Replaces `window.ResizeObserver` (`__agGated = true`) and wraps `disconnect` on each created instance.
- `[bundle.js:installFocusGuard]` Monkey-patches `HTMLElement.prototype.focus` (`__agFocusGuarded = true`) and attaches passive capture-phase `pointerdown` and `touchstart` listeners on `document`.
- `[bundle.js:__agSetImeAnimating]` / `[bundle.js:flushDeferredResizes]` Manages a 450ms `setTimeout` (`imeFailsafeTimer`), dispatches synthetic `'resize'` events on `window` and `window.visualViewport`, and invokes deferred `ResizeObserver` callbacks.
- `[bundle.js:installStyles]` / `[bidi.js:installBidi]` Replaces/appends `<style data-agy="web-optimizations">` and appends `<style data-agy="bidi-fix">` to `document.head || document.documentElement`.
- `[viewport.js:installViewport]` / `[viewport.js:scheduleViewportSync]` Immediately writes `--agy-vvh` on `document.documentElement.style`, registers passive `'resize'` listeners on `window.visualViewport` and `window`, and schedules `requestAnimationFrame` callbacks.

## Verification
- Run: `bash web/build.sh && bash web/tests/selftest.sh && node web/tests/test_web_patches.mjs`
