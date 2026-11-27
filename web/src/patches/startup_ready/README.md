# Startup Ready (`web/src/patches/startup_ready`)

## Overview
Startup readiness detector and pre-ready error forwarder concatenated via `web/src.manifest` into `patches/startup_ready.js`. It monitors `#root` DOM rendering and `[ConversationLoad]` console logs to trigger a one-shot `window.Android.onUiReady` splash dismissal while forwarding pre-ready runtime errors and unhandled promise rejections to `window.Android.onUiError`.

## Interface
- `[public-api.js:global.__agStartupReady]` Publishes `window.__agStartupReady = { __installed: true, signal: signal }`.
- `[public-api.js:__installed]` Boolean sentinel (`true`) checked by `[signal.js:window.__agStartupReady.__installed]` to prevent duplicate installation.
- `[public-api.js:signal]` / `[signal.js:signal]` Public trigger `signal(reason)` (default `'unknown'`) invoking the one-shot UI readiness notification.
- `[signal.js:global.Android.onUiReady]` Consumed native callback invoked as `global.Android.onUiReady(global.__AG_NATIVE_BRIDGE_TOKEN__)`.
- `[error-capture.js:global.Android.onUiError]` Consumed native callback invoked as `global.Android.onUiError(global.__AG_NATIVE_BRIDGE_TOKEN__, full)` for pre-ready errors.
- `[signal.js:global.__AG_NATIVE_BRIDGE_TOKEN__]` Consumed host auth token passed as the first argument to `Android.onUiReady` and `Android.onUiError`.

## Invariants
- `[signal.js:window.__agStartupReady.__installed]` Single-install guard: returns immediately if `window.__agStartupReady.__installed` is truthy without registering hooks, listeners, observers, or timers.
- `[signal.js:state.signalled]` One-shot signalling: `signal(reason)` sets `state.signalled = true` before logging or invoking `Android.onUiReady`, guaranteeing `onUiReady` executes at most once.
- `[error-capture.js:state.signalled]` Pre-ready error gating: `Android.onUiError` is called only while `!state.signalled`; post-ready errors are still appended to `[error-capture.js:startupErrors]` and logged to `console.error`.
- `[console-hook.js:origInfo]` Self-restoring `console.info` hook: restores `console.info = origInfo` on the first string message containing `'[ConversationLoad]'` while `!state.signalled` (calling `signal('conversation-load-completed')`) or on the first subsequent call after `state.signalled` is true, always forwarding arguments to `origInfo`.
- `[detect-ui.js:isUiRendered]` Strict `#root`-scoped readiness priority: returns `false` if `#root` is absent; otherwise checks `[detect-ui.js:hasMessages]` (`'messages-rendered'`), then `[detect-ui.js:hasInput]` (`'chat-input-ready'`), then `[detect-ui.js:hasContent]` (`'root-has-content'`).
- `[arm.js:arm]` Bounded readiness wait: immediately signals `'no-document'` if `global.document` is missing; otherwise guarantees termination within 5500ms by clearing `[arm.js:poller]`, re-checking `isUiRendered()`, and calling `signal('fallback-timeout')`.
- `[public-api.js:global.document.readyState]` Defers `arm()` to a one-shot `DOMContentLoaded` listener when `document.readyState === 'loading'` and runs `arm()` synchronously otherwise.
- `[signal.js:signal]` / `[error-capture.js:global.addEventListener]` / `[console-hook.js:console.info]` / `[detect-ui.js:isUiRendered]` / `[arm.js:MutationObserver]` Wraps native bridge, DOM, listener, and observer calls in `try/catch` to avoid crashing page boot.

## Side Effects
- `[public-api.js:global.__agStartupReady]` Assigns `window.__agStartupReady`.
- `[signal.js:global.Android.onUiReady]` Logs `[AGY Startup] UI Ready triggered via: <reason>` (or warns on failure) and invokes `window.Android.onUiReady(window.__AG_NATIVE_BRIDGE_TOKEN__)`.
- `[error-capture.js:global.addEventListener]` Attaches permanent `window` `'error'` and `'unhandledrejection'` listeners that append to `[error-capture.js:startupErrors]`, log via `console.error`, and invoke `window.Android.onUiError` while `!state.signalled`.
- `[console-hook.js:console.info]` Temporarily replaces `console.info` until `'[ConversationLoad]'` is logged or a post-ready `console.info` call restores it.
- `[arm.js:MutationObserver]` Attaches a `{ childList: true, subtree: true }` `MutationObserver` on `#root || document.body || document.documentElement` when initially unrendered (disconnecting in its callback once `isUiRendered()` returns `true`).
- `[arm.js:global.setInterval]` / `[arm.js:global.setTimeout]` Starts a 40ms polling interval (`[arm.js:poller]`) and a 5500ms fallback timeout.
- `[public-api.js:global.document.addEventListener]` Attaches a `{ once: true }` `DOMContentLoaded` listener when `document.readyState === 'loading'`.

## Verification
- Run: `bash web/build.sh && bash web/tests/selftest.sh && node web/tests/test_web_patches.mjs`
