# com.agy.ui.launcher

## Overview

Defines the two concrete `MainActivity` subclasses used as dark-theme and light-theme Android launcher entry points. Using real `Activity` subclasses (rather than `<activity-alias>` entries, whose themes are ignored by the Android system splash screen) allows the OS starting window to display `SplashThemeDark` or `SplashThemeLight` according to the active launcher component managed by `LauncherIconSwitcher`.

## Interface

- `[LauncherDarkActivity.java:LauncherDarkActivity]` Public final empty subclass of `com.agy.ui.MainActivity` registered as the dark-theme launcher activity.
- `[LauncherLightActivity.java:LauncherLightActivity]` Public final empty subclass of `com.agy.ui.MainActivity` registered as the light-theme launcher activity.

## Invariants

- `[LauncherDarkActivity.java:LauncherDarkActivity]` Final class extending `MainActivity` with no added members or overrides; only one launcher activity is enabled at a time (coordinated by `LauncherIconSwitcher`) so the OS starting window binds `SplashThemeDark`.
- `[LauncherLightActivity.java:LauncherLightActivity]` Final class extending `MainActivity` with no added members or overrides; only one launcher activity is enabled at a time (coordinated by `LauncherIconSwitcher`) so the OS starting window binds `SplashThemeLight`.

## Side Effects

- None

## Verification

- Run: `bash app/src/test/run_java_tests.sh`
