# com.agy.ui.splash

## Overview

Manages the native startup splash screen, API 31+ Android system splash cross-fade, pulsing logo Canvas view, lazy WebView-based transient/fatal error overlay with a main-thread JavaScript retry bridge, and redacted core diagnostic log collection and export.

## Interface

- `[SplashController.java:SplashController]` Overlay state machine with mode constants `MODE_LOADING = 0`, `MODE_TRANSIENT = 1`, `MODE_ERROR = 2`, constructor `SplashController(Context, ThemeManager, FrameLayout)`, and methods `view()`, `mode()`, `isFatalShown()`, `setRetryListener(SplashRetryListener)`, `loadInitial()`, `showLoading()`, `showTransient(String)`, `showFatalError(String)`, `clearFatal()`, `setVisible()`, `dismiss()`, `refresh()`, `destroy()`, and `installExitAnimation(Activity)`.
- `[NativeSplashDotsView.java:NativeSplashDotsView]` Custom `View` rendering a tinted, pulsing `R.drawable.splash_logo` bitmap with `setDotColor(int)`, `setDark(boolean)`, `startAnimation()`, and `stopAnimation()`.
- `[SplashExitInstaller.java:SplashExitInstaller]` API-level dispatcher `install(Activity, View)` delegating API 31+ exit animations to `[SplashAnim31.java:SplashAnim31]` (`install(Activity, View)`).
- `[SplashOverlayHtml.java:SplashOverlayHtml]` Error overlay HTML generator and loader exposing `show(WebView, String, String, String, String, boolean)` and `getOverlayHtml(String, String, String, String, boolean)`.
- `[SplashRetryBridge.java:SplashRetryBridge]` JS interface binder exposing `RETRY_BRIDGE = "SplashRetry"`, `bind(WebView, Context, SplashRetryListener)`, `bind(WebView, SplashRetryListener)`, and `@JavascriptInterface` methods `[SplashRetryBridge.java:Bridge.retry]`, `[SplashRetryBridge.java:Bridge.copyLogs]`, and `[SplashRetryBridge.java:Bridge.saveLogs]`.
- `[SplashRetryListener.java:SplashRetryListener]` Callback interface defining `onRetry()`.
- `[LogExporter.java:LogExporter]` Diagnostic bundle utility exposing `collectDiagnostics(Context)`, `copyToClipboard(Context)`, and `saveAndShare(Context)`.

## Invariants

- `[SplashController.java:SplashController]` Eagerly allocates only the container `FrameLayout` and `NativeSplashDotsView`; `errorWebView` is lazily created in `[SplashController.java:ensureErrorWebView]` only for `MODE_TRANSIENT` or `MODE_ERROR` with `Color.TRANSPARENT`, JS/DOM storage enabled, and `SplashRetryBridge` bound.
- `[SplashController.java:destroyed]` `destroy()` is idempotent, marking `destroyed = true` and `dismissed = true` so subsequent calls (`loadInitial`, `showLoading`, `showTransient`, `showFatalError`, `setVisible`, `dismiss`, `refresh`) are no-ops.
- `[SplashController.java:fatalShown]` `showFatalError` latches `fatalShown = true` and blocks subsequent `showTransient` and `showFatalError` updates until `clearFatal()` resets it; `[SplashController.java:showTransient]` deduplicates consecutive identical messages in `MODE_TRANSIENT`.
- `[SplashController.java:dismiss]` Idempotent when `dismissed || destroyed`; fades alpha to `0f` over 280 ms and re-checks `(!dismissed || destroyed)` in `withEndAction` so an intervening `setVisible()` cancels hiding and WebView teardown.
- `[SplashController.java:isDark]` Defaults to dark theme (`ThemeColors.COLOR_DARK`, `FG_DARK_HEX`, `BG_DARK_HEX`) when `theme == null`; `[SplashController.java:refresh]` re-renders error HTML only when `errorWebView != null && !dismissed` in `MODE_TRANSIENT` or `MODE_ERROR`.
- `[NativeSplashDotsView.java:init]` Draws `R.drawable.splash_logo` at `56.0f * density` px, tinted `0xFF9AA0A6` (dark) or `0xFF5F6368` (light) via `PorterDuff.Mode.SRC_IN` (`[NativeSplashDotsView.java:updateTint]`), skips drawing if bitmap is null/recycled (`[NativeSplashDotsView.java:onDraw]`), clamps combined alpha to `[0f, 1f]`, and automatically starts/stops its 600 ms entrance and 2400 ms reverse-infinite pulse `ValueAnimator`s on attach/visibility changes (`[NativeSplashDotsView.java:onAttachedToWindow]`, `[NativeSplashDotsView.java:startAnimation]`).
- `[SplashExitInstaller.java:install]` Calls `SplashAnim31.install` only on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` (API 31+); `[SplashAnim31.java:install]` uses a reflective `Proxy` for `SplashScreen$OnExitAnimationListener` and swallows all `Throwable`s so startup is never blocked, cross-fading over 280 ms (`[SplashAnim31.java:animateExit]`).
- `[SplashOverlayHtml.java:getOverlayHtml]` Defaults null title to `"Antigravity"` and null message to `"unknown error"`, HTML-escapes `&`, `<`, `>` in title and message, and renders Retry / Copy Logs / Save Logs actions only when `retryable == true`; `[SplashOverlayHtml.java:show]` loads with base URL `"https://app.antigravity.local/"` and UTF-8.
- `[SplashRetryBridge.java:MAIN]` Marshals `retry()`, `copyLogs()`, and `saveLogs()` onto `Looper.getMainLooper()`; 2-arg `[SplashRetryBridge.java:bind]` passes `context = null` (turning `copyLogs`/`saveLogs` into no-ops).
- `[LogExporter.java:MAX_LOG_BYTES]` Tail-truncates `<filesDir>/logs/core.log` to at most `300_000` bytes and attaches `Intent.EXTRA_TEXT` in `saveAndShare` only when `report.length() < 300_000`.
- `[LogExporter.java:collectDiagnostics]` Redacts `CoreRuntimeState.lastError` and every log line with `SensitiveDataRedactor.redactLine` and `CoreRuntimeState.serverUrl` with `SensitiveDataRedactor.safeUrl`, falling back to `RuntimeConfig.APP_VERSION_NAME`/`APP_VERSION_CODE` if package lookup fails.
- `[LogExporter.java:saveAndShare]` No-op on null context; attempts UTF-8 write of `antigravity-core.log` across `Environment.DIRECTORY_DOWNLOADS`, `<externalStorage>/Download`, and `context.getExternalFilesDir(null)`, and still launches the `ACTION_SEND` chooser if all writes fail.

## Side Effects

- `[SplashController.java:SplashController]` Allocates and attaches `NativeSplashDotsView` inside its container; `[SplashController.java:setVisible]` brings the container to front; `[SplashController.java:dismiss]` animates alpha, hides the container, stops dot animation, and tears down `errorWebView` (`[SplashController.java:destroyErrorWebView]` detaches, removes `"SplashRetry"`, clears clients, and calls `WebView.destroy()`).
- `[SplashController.java:ensureErrorWebView]` Lazily instantiates a `WebView`, binds `"SplashRetry"` (`[SplashRetryBridge.java:bind]`), and loads HTML (`[SplashOverlayHtml.java:show]`).
- `[NativeSplashDotsView.java:init]` Decodes `R.drawable.splash_logo` into memory; `[NativeSplashDotsView.java:startAnimation]` runs `ValueAnimator`s invoking `invalidate()` per frame.
- `[SplashAnim31.java:install]` Registers a reflective `OnExitAnimationListener` on `activity.getSplashScreen()`, animating and removing the system splash view while fading in `dotSplash`.
- `[SplashRetryBridge.java:Bridge.retry]`, `[SplashRetryBridge.java:Bridge.copyLogs]`, and `[SplashRetryBridge.java:Bridge.saveLogs]` post retry callbacks, clipboard writes, or file-export/share actions onto the main thread.
- `[LogExporter.java:collectDiagnostics]` Reads `<filesDir>/logs/core.log`, `PackageManager`, `Build`, and `CoreRuntimeState`.
- `[LogExporter.java:copyToClipboard]` Writes redacted diagnostics to `ClipboardManager` (`"Antigravity Logs"`) and shows a `Toast`.
- `[LogExporter.java:saveAndShare]` Creates directories, writes `antigravity-core.log` to external/app storage, shows a `Toast`, and launches an `Intent.ACTION_SEND` (`"text/plain"`) chooser activity with `FLAG_ACTIVITY_NEW_TASK`.

## Verification

- Run: `bash app/src/test/run_java_tests.sh`
