# Web Boot (`web/src/boot`)

## Overview
Early-boot scripts bundled as separate IIFEs via `web/src.manifest` (`boot/custom-elements-guard.js` and `boot/theme-bootstrap.js`) that run before the main application bundle to prevent duplicate `CustomElementRegistry` registration crashes and synchronously apply the initial light/dark theme to `document.documentElement`.

## Interface
- `[custom-elements-guard.js:window.customElements.define]` Wraps `window.customElements.define(name, constructor, options)` to silently skip re-registration when `name` is already defined; exports no new globals.
- `[theme-bootstrap.js:hostTheme]` Reads URL query parameter `hostTheme` via `URLSearchParams(window.location.search)` as the highest-priority theme override (`'system'`, `'light'`, or any other truthy value normalized to `'dark'`); exports no new globals.
- `[theme-bootstrap.js:window.__AG_HOST_THEME__]` Fallback host theme preference read when query parameter `hostTheme` is absent or `URLSearchParams` throws.
- `[theme-bootstrap.js:window.Android.getTheme]` Optional native bridge method invoked when resolving `'system'` theme (accepts exact `'dark'` or `'light'`).
- `[theme-bootstrap.js:window.__AG_RESOLVED_THEME__]` Secondary `'system'` theme fallback tested against `'dark'`.
- `[theme-bootstrap.js:window.matchMedia]` Final `'system'` theme fallback querying `(prefers-color-scheme: dark)`.

## Invariants
- `[custom-elements-guard.js:window.customElements]` Guard installation executes only when `window.customElements` is truthy.
- `[custom-elements-guard.js:window.customElements.get]` First registration wins: `originalDefine.call(window.customElements, name, constructor, options)` runs only when `!window.customElements.get(name)`, preventing duplicate-definition exceptions.
- `[custom-elements-guard.js:originalDefine]` Source has no internal IIFE or `'use strict'` and relies on `web/src.manifest`'s `bundle iife` wrapper to keep `originalDefine` out of global scope.
- `[theme-bootstrap.js:hostTheme]` Theme preference precedence is strictly: URL query param `hostTheme` -> `window.__AG_HOST_THEME__` -> `'system'` (with `URLSearchParams` errors falling back to `window.__AG_HOST_THEME__ || 'system'`).
- `[theme-bootstrap.js:window.Android.getTheme]` Under `'system'`, resolution precedence is strictly: `window.Android.getTheme()` (`'dark'`/`'light'` only; exceptions swallowed) -> `window.__AG_RESOLVED_THEME__ === 'dark'` (when truthy) -> `window.matchMedia('(prefers-color-scheme: dark)').matches` (defaulting to `'light'`).
- `[theme-bootstrap.js:theme]` Non-`'system'` themes normalize via `(theme === 'light' ? 'light' : 'dark')`, guaranteeing the resolved value is always `'dark'` or `'light'`.
- `[theme-bootstrap.js:document.documentElement.classList.add]` Class application is additive-only (`'dark'` or `'light'`) and does not strip pre-existing theme classes.

## Side Effects
- `[custom-elements-guard.js:window.customElements.define]` Synchronously monkey-patches `window.customElements.define` at script load time when `window.customElements` is present.
- `[theme-bootstrap.js:window.Android.getTheme]` Synchronously invokes native `window.Android.getTheme()` during boot when resolving `'system'`.
- `[theme-bootstrap.js:document.documentElement.style.colorScheme]` Synchronously sets `document.documentElement.style.colorScheme` to `'dark'` or `'light'` at script load time.
- `[theme-bootstrap.js:document.documentElement.classList.add]` Synchronously adds `'dark'` or `'light'` to `document.documentElement.classList` at script load time.

## Verification
- Run: `bash web/build.sh && bash web/tests/selftest.sh && node web/tests/test_web_patches.mjs`
