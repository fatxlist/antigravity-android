# Theme Sync (`web/src/patches/theme_sync`)

## Overview
Idempotent runtime theme synchronization patch concatenated via `web/src.manifest` into `patches/theme_sync.js`. It resolves the active light/dark theme from DOM classes, native `window.Android`, host parameters, or `prefers-color-scheme`, mirrors the resolved theme onto `document.documentElement` and `<meta name="color-scheme">`, and reports visual theme and opaque surface hex color changes back to Android while exposing a global inspection API on `window.__agThemeSync`.

## Interface
- `[public-api.js:window.__agThemeSync]` Exposes global control/inspection object with `__installed: true`, `getTheme()`, `sync()`, and `probe()`.
- `[public-api.js:getTheme]` / `[resolve.js:resolveTheme]` Resolves and returns the active theme (`'light'`, `'dark'`, or `window.__AG_RESOLVED_THEME__`).
- `[public-api.js:sync]` / `[notify.js:notify]` Reconciles `documentElement` classes, `<meta name="color-scheme">`, and native Android bridge state, returning the resolved theme.
- `[public-api.js:probe]` Returns diagnostic snapshot `{installed, theme, last, notifications, hostTheme, hasAndroid, observers}`.
- `[config.js:initialHostTheme]` Consumes URL query parameter `hostTheme` and `window.__AG_HOST_THEME__`.
- `[resolve.js:resolveTheme]` / `[resolve.js:fromClassList]` Consumes `document.body` classes `theme-light` / `theme-dark`, `window.Android.getTheme()`, `window.__AG_RESOLVED_THEME__`, and `matchMedia('(prefers-color-scheme: dark)')`.
- `[notify.js:notify]` / `[notify.js:syncSurfaceColor]` Consumes `window.__agThemeExplicitSync`, `window.__AG_NATIVE_BRIDGE_TOKEN__`, and native bridge methods `window.Android.setThemeByUser(bodyTheme)`, `window.Android.setTheme(bodyTheme)`, `window.Android.onVisualThemeChanged(theme)`, and `window.Android.setSurfaceColor(token, hex)`.

## Invariants
- `[config.js:window.__agThemeSync]` / `[install.js:install]` Idempotent installation: exits early if `window.__agThemeSync.__installed` is truthy or `state.installed` is `true`.
- `[config.js:initialHostTheme]` Host theme input is strictly normalized to `'light'`, `'dark'`, or `'system'` (all other values fall back to `'system'`).
- `[resolve.js:resolveTheme]` / `[resolve.js:fromClassList]` Resolution precedence is deterministic: `doc.body.classList` (`theme-light` before `theme-dark`) -> `window.Android.getTheme()` (`'dark'`/`'light'`) -> `initialHostTheme` (`'light'`/`'dark'`) -> truthy `window.__AG_RESOLVED_THEME__` -> `matchMedia('(prefers-color-scheme: dark)').matches` -> `'light'`.
- `[notify.js:notify]` Change deduplication: skips DOM/bridge updates unless `theme !== state.last` or `(bodyTheme !== null && bodyTheme !== state.lastFromBody)`.
- `[notify.js:notify]` Boot-safe user persistence: invokes `Android.setThemeByUser` (or fallback `Android.setTheme`) only when `bodyChanged` is true, `state.notifications > 1`, and `window.__agThemeExplicitSync` is falsy.
- `[resolve.js:updateColorSchemeMeta]` Sets `<meta name="color-scheme">` content strictly to `'only light'` for `'light'` and `'dark'` otherwise.
- `[notify.js:rgbToHex]` / `[notify.js:readSurfaceHex]` / `[notify.js:syncSurfaceColor]` Rejects `'transparent'` and `rgba` alpha `< 0.9`, expands `#rgb` and clamps RGB channels to `[0, 255]`, probes surface elements in fixed order (`[data-testid="mobile-layout"]`, `.agy-shell`, `.agy-sheet`, `#root`, `doc.body`, `doc.documentElement`), and calls `Android.setSurfaceColor` only when `hex` is non-null, changed from `state.lastSurfaceHex`, and `window.__AG_NATIVE_BRIDGE_TOKEN__` is truthy.
- `[install.js:install]` / `[notify.js:notify]` Attaches the `window` `'storage'` listener at most once (`state.storageBound`) and clears any pending `state.surfaceSyncTimer` before scheduling a new timeout.
- `[config.js:initialHostTheme]` / `[resolve.js:resolveTheme]` / `[notify.js:notify]` / `[install.js:install]` All DOM, observer, computed-style, and bridge operations are wrapped in `try/catch` so exceptions never leak to host page scripts.

## Side Effects
- `[public-api.js:window.__agThemeSync]` / `[public-api.js:install]` Assigns `window.__agThemeSync` and invokes `install()` immediately on script load.
- `[notify.js:notify]` Toggles `'dark'` and `'light'` classes on `document.documentElement` and sets `document.documentElement.style.colorScheme`.
- `[resolve.js:updateColorSchemeMeta]` Creates and appends `<meta name="color-scheme">` to `document.head` if missing and mutates its `content` attribute.
- `[resolve.js:resolveTheme]` / `[notify.js:notify]` / `[notify.js:syncSurfaceColor]` Calls native `window.Android` methods (`getTheme`, `setThemeByUser` / `setTheme`, `onVisualThemeChanged`, `setSurfaceColor`).
- `[install.js:install]` / `[install.js:observeBody]` Creates a `MutationObserver` (`state.observer`) watching `class` attributes on `document.documentElement` and `document.body` (deferring body observation to `DOMContentLoaded` if absent).
- `[install.js:install]` Registers persistent `'storage'` and `matchMedia('(prefers-color-scheme: dark)')` `'change'` listeners, plus a one-shot `'DOMContentLoaded'` listener when `doc.body` is not yet mounted.
- `[notify.js:notify]` / `[install.js:install]` Schedules `setTimeout` timers (`state.surfaceSyncTimer`) to call `syncSurfaceColor` after 150ms (`notify`) and 400ms (`install` / `DOMContentLoaded`).

## Verification
- Run: `bash web/build.sh && bash web/tests/selftest.sh && node web/tests/test_web_patches.mjs`
