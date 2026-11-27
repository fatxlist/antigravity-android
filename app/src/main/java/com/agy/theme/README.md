# com.agy.theme

## Overview

Provides Android theming, edge-to-edge system bar configuration, API 30+ IME inset animation coordination, and dynamic launcher icon switching. Three persisted user modes (`MODE_SYSTEM`, `MODE_DARK`, `MODE_LIGHT`) resolve to two visual themes (`#101010` dark and `#ffffff` light) behind the `ThemeManager` facade while painting Activities and WebViews and toggling `LauncherDarkActivity`/`LauncherLightActivity` without killing the process.

## Interface

- `[ThemeManager.java:ThemeManager]` Facade wrapping `ThemeController`, `ActivityPainter`, and `WebViewPainter`; exposes `ThemeManager(Context)`, `updateContext(Context)`, `setThemeChangeListener(ThemeController.ThemeChangeListener)`, `mode()`, `setTheme(String)`, `setThemeByUser(String)`, `onVisualThemeChanged(String)`, `getTheme()`, `isDark()`, `getBackgroundColor()`, `getBackgroundHex()`, `getForegroundHex()`, `applyLauncherIcon()`, `applyToActivity(Activity)`, and `applyToWebView(WebView)`.
- `[ThemeController.java:ThemeController]` Core theme state controller exposing `ThemeController(Context)`, `setThemeChangeListener(ThemeChangeListener)`, `updateContext(Context)`, `mode()`, `setMode(int)`, `setTheme(String)`, `setThemeByUser(String)`, `onVisualThemeChanged(String)`, `getTheme()`, `isDark()`, `isDark(Context)`, `getBackgroundColor()`, `getBackgroundHex()`, `getForegroundHex()`, `isLauncherDark()`, `applyLauncherIcon()`, and callback interface `[ThemeController.java:ThemeChangeListener]` (`onThemeApplied(boolean)`).
- `[ThemeColors.java:ThemeColors]` Theme mode/color constants (`MODE_SYSTEM = 0`, `MODE_DARK = 1`, `MODE_LIGHT = 2`, `COLOR_DARK = 0xFF101010`, `COLOR_LIGHT = 0xFFFFFFFF`, `THEME_DARK = "dark"`, `THEME_LIGHT = "light"`, `BG_DARK_HEX = "#101010"`, `BG_LIGHT_HEX = "#ffffff"`, `FG_DARK_HEX = "#ffffff"`, `FG_LIGHT_HEX = "#101010"`) and pure JVM utilities `parseHexColor(String)` and `isDarkColor(int)`.
- `[ThemePrefs.java:ThemePrefs]` SharedPreferences persistence helpers `load(Context)` and `save(Context, int)` backed by `"agy_prefs"` key `"theme_mode_v3"`.
- `[ThemeResolver.java:ThemeResolver]` Static resolver `isDark(int, Context)` mapping mode and system `uiMode` to dark/light polarity.
- `[ActivityPainter.java:ActivityPainter]` Static window/decor painters `apply(Activity, boolean)` and `applySurfaceColor(Activity, int)`.
- `[WebViewPainter.java:WebViewPainter]` Static WebView surface painter `apply(WebView, int)`.
- `[EdgeToEdge.java:EdgeToEdge]` Edge-to-edge window setup and inset math utilities: `LEGACY_LIGHT_SCRIM = 0x66000000`, `enable(Window, boolean)`, `resolveNavigationBarColor(int, boolean)`, `computeImeBottomPadding(int, int)`, `computeEffectiveInsets(...)`, `computeLegacyInsets(...)`, `resolveInsets(WindowInsets)`, `resolveInsets(WindowInsets, int)`, `consumeSystemInsets(WindowInsets)`, `passSafeAreasConsumeIme(WindowInsets, boolean)`, and immutable value type `[EdgeToEdge.java:InsetsRect]` (`left`, `top`, `right`, `bottom`, `navBottom`, `imeBottom`).
- `[ImeInsetsAnimator.java:ImeInsetsAnimator]` API 30+ IME inset animation bridge `install(View, Listener)` and `[ImeInsetsAnimator.java:Listener]` (`onApplyInsets`, `onImeAnimationStart`, `onImeAnimationProgress`, `onImeAnimationEnd`).
- `[LauncherIconSwitcher.java:LauncherIconSwitcher]` Synchronized launcher alias toggle exposing `scheduleSwitch(boolean)`, `apply(Context, boolean)`, and `onAppBackgrounded(Context)`.

## Invariants

- `[ThemeColors.java:parseHexColor]` Accepts only trimmed `#RGB` or `#RRGGBB` hex strings without Android framework dependencies, returning opaque ARGB (`0xFF000000 | rgb`) or `null` for invalid input; `[ThemeColors.java:isDarkColor]` computes luma `(299 * r + 587 * g + 114 * b) < 128000` ignoring alpha.
- `[ThemePrefs.java:load]` Defaults to `ThemeColors.MODE_SYSTEM` (`0`) when `"theme_mode_v3"` is absent from `"agy_prefs"`.
- `[ThemeResolver.java:isDark]` `MODE_DARK` always returns `true` and `MODE_LIGHT` always returns `false`; `MODE_SYSTEM` (or unrecognized modes) checks `context` then distinct `applicationContext` `Configuration.uiMode & UI_MODE_NIGHT_MASK` and defaults to `false` on null context or exception.
- `[ThemeController.java:setMode]` Rejects mode values outside `[MODE_SYSTEM, MODE_LIGHT]` (`0..2`) without mutating state or preferences; `[ThemeController.java:setTheme]` accepts only case-insensitive `"light"`, `"dark"`, `"system"`, or `"default"`, returning `false` otherwise.
- `[ThemeController.java:setThemeByUser]` Delegates to `setTheme(String)` and unconditionally calls `LauncherIconSwitcher.scheduleSwitch(isDark())`; `[ThemeController.java:onVisualThemeChanged]` updates UI and schedules the launcher switch from `"dark".equalsIgnoreCase(visual)` without altering persisted mode.
- `[ThemeController.java:applyThemeOnMainThread]` Posts `ActivityPainter.apply` (when `currentContext instanceof Activity`) and `ThemeChangeListener.onThemeApplied` onto the main thread via `MainThreadPost.post`, skipping dispatch when both are null.
- `[ThemeController.java:isDark]` Resolves `Context` via fallback chain `context -> currentContext -> appContext`; `[ThemeController.java:updateContext]` ignores `null`.
- `[ActivityPainter.java:applySurfaceColor]` Null-safe for null Activity, Window, or DecorView; derives system bar icon polarity via `ThemeColors.isDarkColor(argbColor)`; `[ThemeManager.java:applyToActivity]` and `[WebViewPainter.java:apply]` are null-safe no-ops.
- `[EdgeToEdge.java:enable]` No-op on null window; enforces contrast disabling on API 29+, display cutout mode on API 28+ (`SHORT_EDGES`) and API 30+ (`ALWAYS`), materializes `window.getDecorView()` before `getInsetsController()` on API 30+, and falls back to `[EdgeToEdge.java:applyLegacySystemUi]` on API < 30 or null controller (`LIGHT_STATUS_BAR` on API 23+, `LIGHT_NAVIGATION_BAR` on API 26+).
- `[EdgeToEdge.java:resolveNavigationBarColor]` Returns `Color.TRANSPARENT` in dark mode or on API 26+, and `LEGACY_LIGHT_SCRIM` (`0x66000000`) in light mode on API < 26.
- `[EdgeToEdge.java:resolveInsets]` Returns zeroed `InsetsRect` on null input and defaults `imeThresholdPx` to `200`; uses `WindowInsets.Type.systemBars()/displayCutout()/ime()` on API 30+ and legacy `getSystemWindowInset*()`/`getStableInsetBottom()` (+ API 28+ cutout) below API 30; `[EdgeToEdge.java:computeEffectiveInsets]` and `[EdgeToEdge.java:computeImeBottomPadding]` clamp all dimensions to `>= 0`; `[EdgeToEdge.java:computeLegacyInsets]` clamps `imeThresholdPx >= 1` and classifies bottom inset as IME only when `(sysBottom - baseNav) >= threshold`.
- `[EdgeToEdge.java:passSafeAreasConsumeIme]` Returns `null` on null insets; on API 30+ strips `Type.ime()` and zeroes bottom `navigationBars()` insets when `consumeBottomNav` is true, falling back to `consumeSystemWindowInsets()` below API 30; `[EdgeToEdge.java:consumeSystemInsets]` returns `WindowInsets.CONSUMED` on API 30+ and `consumeSystemWindowInsets()` below API 30.
- `[ImeInsetsAnimator.java:install]` No-op if `target == null`, `listener == null`, or `SDK_INT < R` (API 30); uses `DISPATCH_MODE_STOP`, tracks active IME animations by object identity (`IdentityHashMap`) filtered by `WindowInsets.Type.ime()`, coalesces `onImeAnimationStart()` once per sequence, and fires `onImeAnimationEnd(latestInsets)` only when all active IME animations finish.
- `[LauncherIconSwitcher.java:apply]` All public methods are `synchronized` on `LauncherIconSwitcher.class`; treats `COMPONENT_ENABLED_STATE_DEFAULT` using manifest defaults (dark enabled, light disabled), skips no-op writes, always enables the target alias before disabling the old alias with `PackageManager.DONT_KILL_APP`, and swallows exceptions; `[LauncherIconSwitcher.java:onAppBackgrounded]` applies and clears `pendingDark` only when both `pendingDark` and `context` are non-null.

## Side Effects

- `[ThemePrefs.java:save]` Persists `"theme_mode_v3"` asynchronously to private SharedPreferences `"agy_prefs"` via `Editor.apply()`; `[ThemeController.java:ThemeController]` reads it on construction.
- `[ThemeController.java:setTheme]` Persists mode, updates `LauncherIconSwitcher` pending state, and posts Activity painting and listener notifications to the main thread (`[ThemeController.java:setThemeByUser]`, `[ThemeController.java:onVisualThemeChanged]`, `[ThemeController.java:applyLauncherIcon]` perform corresponding schedule/apply mutations).
- `[ActivityPainter.java:applySurfaceColor]` Mutates Activity `Window` flags, system bar appearance, background drawable (`ColorDrawable`), and DecorView background color via `[EdgeToEdge.java:enable]`; `[ThemeManager.java:applyToActivity]` delegates here.
- `[WebViewPainter.java:apply]` Mutates `WebView` background color via `setBackgroundColor`; `[ThemeManager.java:applyToWebView]` delegates here.
- `[ImeInsetsAnimator.java:install]` Registers `OnApplyWindowInsetsListener` and `WindowInsetsAnimation.Callback` on the target `View` (API 30+).
- `[LauncherIconSwitcher.java:apply]` Mutates static `pendingDark` and persistent Android `PackageManager` component enabled settings for `com.agy.ui.launcher.LauncherDarkActivity` and `com.agy.ui.launcher.LauncherLightActivity` (`[LauncherIconSwitcher.java:scheduleSwitch]`, `[LauncherIconSwitcher.java:onAppBackgrounded]`).

## Verification

- Run: `bash app/src/test/run_java_tests.sh`
