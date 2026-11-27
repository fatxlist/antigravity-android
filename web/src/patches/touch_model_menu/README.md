# Touch Model Menu (`web/src/patches/touch_model_menu`)

## Overview
Touch-device patch concatenated via `web/src.manifest` into `patches/touch_model_menu.js`. On touch-capable devices, it intercepts primary taps on Base UI model-selector effort-group rows in the capture phase and opens their hover-gated effort submenu using focus, synthetic pointer/mouse hover events, and an `ArrowRight` keydown fallback while lazily observing `#root` only when the model selector panel is open.

## Interface
- `[public-api.js:window.__agTouchModelMenu]` Publishes global inspection/configuration handle `{ __installed, config, probe }`.
- `[public-api.js:window.__agTouchModelMenu.__installed]` Boolean sentinel (`true`) also checked by `[config.js:window.__agTouchModelMenu.__installed]` to prevent double execution.
- `[public-api.js:window.__agTouchModelMenu.config]` Direct reference to `[config.js:CONFIG]` exposing DOM selectors (`trigger`, `panel`, `effortGroup`, `effortOption`, `modelItem`, `menuItemRole`), observer root (`root: '#root'`), and timing constants (`fallbackDelayMs: 70`, `retryDelayMs: 260`, `maxWaitMs: 600`, `reopenGuardMs: 400`).
- `[public-api.js:window.__agTouchModelMenu.probe]` Returns diagnostic snapshot `{ installed, hasTouch, rows, groups, options, submenuOpen, opens }` combining `[state.js:state]` with live DOM counts for `[config.js:CONFIG.effortGroup]` and `[config.js:CONFIG.effortOption]`.
- `[synth.js:ev.__agTouchSynthetic]` Boolean expando (`true`) stamped onto synthetic `PointerEvent`, `MouseEvent`, and `KeyboardEvent` instances dispatched by `[synth.js:dispatchPointerSequence]` and `[synth.js:arrowRight]`.
- `[register.js:row.__agTouchManaged]` Boolean expando (`true`) stamped onto managed menu-item DOM nodes by `[register.js:registerRow]`.

## Invariants
- `[config.js:window.__agTouchModelMenu.__installed]` First-fragment guard exits immediately if `window.__agTouchModelMenu.__installed` is already truthy.
- `[install.js:install]` / `[detect.js:detectTouch]` Guards re-entry via `[state.js:state.installed]` and treats non-touch devices (`!state.hasTouch`) as a complete no-op without attaching observers or listeners; touch detection checks `navigator.maxTouchPoints > 0`, `'ontouchstart' in window`, `(pointer: coarse)`, or `(any-pointer: coarse)`.
- `[register.js:registerRow]` / `[register.js:makeHandler]` Attaches capture-phase `pointerdown`/`mousedown`/`click` listeners at most once per node (`row.__agTouchManaged`), increments `[state.js:state.rows]` once per row, ignores synthetic (`ev.__agTouchSynthetic`) and non-primary mouse button (`event.button !== 0`) events, and intercepts qualifying taps before calling `[open.js:openSubmenu]`.
- `[open.js:openSubmenu]` Enforces `[config.js:CONFIG.reopenGuardMs]` (400ms) cooldown via `[state.js:state.lastOpenAt]` and invokes `[state.js:state.cancelPendingOpen]` before beginning a new open sequence.
- `[open.js:openSubmenu]` Skips attaching per-open focus listener, `MutationObserver`, and fallback timers if `[detect.js:optionsOpen]()` is already true after `[detect.js:focusQuietly]` and `[synth.js:dispatchPointerSequence]`.
- `[open.js:openSubmenu]` / `[open.js:isRowReadyForKey]` Bounds per-open wait by `[config.js:CONFIG.maxWaitMs]` (600ms), sends `[synth.js:arrowRight]` at most once across focus/mutation callbacks when the row is connected and active/highlighted/expanded/`tabindex="0"`, and aborts cleanly if `optionsOpen()` or `!row.isConnected`.
- `[scan.js:scan]` / `[scan.js:armPanelObserver]` / `[scan.js:scheduleScan]` Resolves each effort group to `closest(CONFIG.menuItemRole) || parentElement || group`, disconnects `[state.js:state.observer]` whenever `![scan.js:isPanelOpen]()`, guards root observer attachment with `!state.observingRoot`, and debounces mutation rescans to `[scan.js:SCAN_DEBOUNCE_MS]` (80ms).
- `[detect.js:detectTouch]` / `[synth.js:dispatchPointerSequence]` / `[open.js:openSubmenu]` / `[scan.js:scan]` / `[public-api.js:window.__agTouchModelMenu.probe]` Suppress DOM, `matchMedia`, focus, geometry, synthetic dispatch, and observer errors inside `try/catch`.

## Side Effects
- `[public-api.js:window.__agTouchModelMenu]` / `[public-api.js:document.readyState]` Assigns `window.__agTouchModelMenu` and runs `[install.js:install]` immediately or on `DOMContentLoaded`.
- `[install.js:install]` Logs status via `console.log` and, on touch devices, attaches persistent capture-phase passive `pointerdown` and `click` listeners on `document` that call `[scan.js:armPanelObserver]`.
- `[register.js:registerRow]` / `[register.js:makeHandler]` Sets `row.__agTouchManaged = true`, attaches persistent capture-phase `pointerdown`/`mousedown`/`click` listeners to managed rows, and calls `preventDefault()`, `stopPropagation()`, and `stopImmediatePropagation()` on real primary taps.
- `[detect.js:focusQuietly]` / `[synth.js:dispatchPointerSequence]` / `[synth.js:arrowRight]` Moves focus via `el.focus({ preventScroll: true })`, reads `el.getBoundingClientRect()`, and dispatches synthetic `pointerover`/`pointerenter`/`pointermove`, `mouseover`/`mouseenter`/`mousemove`, and `keydown` (`ArrowRight`) events tagged with `__agTouchSynthetic = true`.
- `[scan.js:armPanelObserver]` / `[open.js:openSubmenu]` / `[state.js:state]` Attaches/disconnects `MutationObserver` instances on `#root` (or `body`/`documentElement`) and per-open document observers, schedules `setTimeout` timers (16ms/80ms/200ms scan timers, 80ms debounce, and 70ms/260ms/600ms open timers), and mutates bundle-scoped `[state.js:state]` fields.

## Verification
- Run: `bash web/build.sh && bash web/tests/selftest.sh && node web/tests/test_web_patches.mjs`
