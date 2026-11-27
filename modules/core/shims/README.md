# Core Shims (`modules/core/shims`)

## Overview
Groups Android POSIX `/system/bin/sh` compatibility shims packaged into the runtime `bin/` directory by `modules/core/bundle/pack_binaries.py`, bridging Linux browser/URL openers and Shizuku privileged shell invocations to Android system services.

## Subdirectories & Boundaries
- Browser Shims: [browser/README.md](./browser/README.md) - URL-opener shim (`xdg_open.sh`) and seven browser delegators (`chrome.sh`, `chromium.sh`, `gnome_open.sh`, `google_chrome.sh`, `open.sh`, `sensible_browser.sh`, `x_www_browser.sh`) that `exec` sibling `xdg-open`.
- Privilege Shims: [privilege/README.md](./privilege/README.md) - Shizuku shell launcher (`rish.sh`) enforcing Android 14+ read-only DEX rules before invoking `/system/bin/app_process`.
- System Shims: [system/README.md](./system/README.md) - Primary system URL-opener shim (`xdg_open.sh`) and `open_helper.sh` delegator emitting stdout URL markers and dispatching Android Activity Manager intents.

## Invariants
- `[browser/xdg_open.sh:LD_PRELOAD]` / `[system/xdg_open.sh:LD_PRELOAD]` / `[privilege/rish.sh:env]`: All entrypoints strip `LD_PRELOAD` and `LD_LIBRARY_PATH` before invoking Android `/system/bin/am` or `/system/bin/app_process`.
- `[browser/chrome.sh:xdg-open]` / `[system/open_helper.sh:dirname]`: All eight delegator scripts require a sibling executable named `xdg-open` in `$(dirname "$0")` and forward `"$@"` verbatim.
- `[system/xdg_open.sh:AGY_SHIM_NO_AM]` / `[browser/xdg_open.sh:AGY_SHIM_NO_AM]`: Any non-empty `AGY_SHIM_NO_AM` disables `/system/bin/am` intent dispatch while preserving stdout marker emission.
- `[privilege/rish.sh:DEX]` / `[privilege/rish.sh:SDK]`: Requires regular file `"$BASEDIR/rish_shizuku.dex"`, non-writable on Android 14+ (`ro.build.version.sdk >= 34`), and non-empty `RISH_APPLICATION_ID` (default `"com.agy"`).

## Side Effects
- `[system/xdg_open.sh:com.agy.ACTION_OPEN_URL]` / `[system/xdg_open.sh:android.intent.action.VIEW]`: Prints `AGY_OPEN_URL=$URL` and `ANTIGRAVITY_OPEN_URL: $URL` to `stdout`, broadcasts `com.agy.ACTION_OPEN_URL` to `com.agy` and `com.agy.dev`, and starts an Android `VIEW` activity via `/system/bin/am`.
- `[privilege/rish.sh:chmod]` / `[privilege/rish.sh:exec]`: Applies `chmod 400` to `"$DEX"` on Android 14+ if writable and replaces the shell process with `/system/bin/app_process` (`rikka.shizuku.shell.ShizukuShellLoader`).

## Verification
- Run: `bash modules/core/tests/run_all.sh`
