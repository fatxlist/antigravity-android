# com.agy.ui.lifecycle

## Overview

Provides four non-instantiable static helper classes that isolate `MainActivity` lifecycle glue: `WebView` pause/resume/memory-trim and `Handler` teardown (`AppLifecycle`), WebView back-stack navigation with a 2-second double-back exit guard (`BackNavigator`), API 33+ predictive back callback registration isolated from older Android SDKs (`PredictiveBackHelper`), and full-surface theme re-application (`ThemeRefresher`).

## Interface

- `[AppLifecycle.java:AppLifecycle]` Static lifecycle helpers `resume(WebView)`, `pause(WebView)`, `trimMemory(int, Handler, WebViewHost)`, and `destroy(Handler)`.
- `[BackNavigator.java:BackNavigator]` Static back-navigation helpers `handle(Context, WebView)` and `handle(WebView)`.
- `[PredictiveBackHelper.java:PredictiveBackHelper]` Static API 33+ back-dispatcher bridge `register(Activity, Runnable)` and `unregister(Activity, Object)`.
- `[ThemeRefresher.java:ThemeRefresher]` Static theme sync helper `refresh(Activity, ThemeManager, View, SplashController, WebViewHost)`.

## Invariants

- `[AppLifecycle.java:AppLifecycle]`, `[BackNavigator.java:BackNavigator]`, `[PredictiveBackHelper.java:PredictiveBackHelper]`, and `[ThemeRefresher.java:ThemeRefresher]` are all `final` utility classes with private constructors and cannot be instantiated or subclassed.
- `[AppLifecycle.java:trimMemory]` Schedules `host.freeMemory()` only when `level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE` and `host != null`.
- `[BackNavigator.java:handle]` WebView history navigation (`webView != null && webView.canGoBack()`) takes precedence over exit confirmation, resets `lastRootBackAtMs` to `0`, and returns `true`; single-arg `handle(WebView)` delegates to `handle(null, webView)` without showing a `Toast`.
- `[BackNavigator.java:BACK_EXIT_WINDOW_MS]` Root back exit requires a second back press within `2000` ms (measured via `SystemClock.elapsedRealtime()`); only then does `handle` reset `lastRootBackAtMs` to `0` and return `false`.
- `[PredictiveBackHelper.java:register]` Guarded by `activity != null`, `onBack != null`, and `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`, returning `null` and swallowing `Throwable` on lower APIs or failures; `[PredictiveBackHelper.java:unregister]` additionally checks `handle instanceof OnBackInvokedCallback` and swallows `Throwable`.
- `[ThemeRefresher.java:refresh]` Null-checks `theme`, `rootLayout`, `splash`, and `host` independently; falls back to `0xFF101010` for `rootLayout` background when `theme == null`, and applies WebView theming only when both `host != null` and `theme != null`.

## Side Effects

- `[AppLifecycle.java:resume]` and `[AppLifecycle.java:pause]` delegate to `WebViewLifecycle.resume`/`pause`; `[AppLifecycle.java:trimMemory]` posts `host.freeMemory()` onto `handler`; `[AppLifecycle.java:destroy]` clears all pending callbacks/messages via `handler.removeCallbacksAndMessages(null)`.
- `[BackNavigator.java:lastRootBackAtMs]` Mutates static timestamp `lastRootBackAtMs` on every `handle(...)` invocation; `[BackNavigator.java:handle]` calls `webView.goBack()` or shows `Toast` `"Press back again to exit"` when `context != null`.
- `[PredictiveBackHelper.java:register]` Registers an `OnBackInvokedCallback` at `PRIORITY_DEFAULT` on `activity.getOnBackInvokedDispatcher()`; `[PredictiveBackHelper.java:unregister]` unregisters it.
- `[ThemeRefresher.java:refresh]` Updates `theme` context and applies theme to `activity`, mutates `rootLayout` background color, refreshes `splash`, and applies theme to `host.getWebView()`.

## Verification

- Run: `bash app/src/test/run_java_tests.sh`
