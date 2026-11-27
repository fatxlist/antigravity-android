# Browser Shims (`modules/core/shims/browser`)

## Overview
Provides Android POSIX `/system/bin/sh` browser and URL-opener shims where `xdg_open.sh` extracts a URL from CLI arguments, prints structured stdout markers, and dispatches Android Activity Manager intents, while seven browser delegator scripts `exec` a sibling `xdg-open` executable.

## Interface
- `[xdg_open.sh:#!/system/bin/sh]`: Executable Android POSIX shell CLI shim accepting arbitrary positional arguments (`"$@"`).
- `[xdg_open.sh:http://*|https://*]`: Primary argument matcher selecting the first argument starting with `http://` or `https://` as `URL`.
- `[xdg_open.sh:-*|""]`: Fallback argument matcher (used only if no HTTP(S) argument matched) selecting the first non-empty argument not starting with `-` as `URL`.
- `[xdg_open.sh:AGY_OPEN_URL]` / `[xdg_open.sh:ANTIGRAVITY_OPEN_URL]`: Stdout protocol lines emitted as `AGY_OPEN_URL=$URL` and `ANTIGRAVITY_OPEN_URL: $URL` when `URL` is non-empty.
- `[xdg_open.sh:AGY_SHIM_NO_AM]`: Optional environment variable disabling all `/system/bin/am` dispatches whenever set to any non-empty value.
- `[xdg_open.sh:exit 0]`: Exit status contract returning `0` both when no URL is resolved and after dispatch completes.
- `[chrome.sh:exec]` / `[chromium.sh:exec]` / `[gnome_open.sh:exec]` / `[google_chrome.sh:exec]` / `[open.sh:exec]` / `[sensible_browser.sh:exec]` / `[x_www_browser.sh:exec]`: Seven `/system/bin/sh` delegators forwarding `"$@"` via `exec "$(dirname "$0")/xdg-open" "$@"`.

## Invariants
- `[xdg_open.sh:LD_PRELOAD]` / `[xdg_open.sh:LD_LIBRARY_PATH]`: Both environment variables are unconditionally unset before argument parsing or invoking `/system/bin/am`.
- `[xdg_open.sh:URL]`: Exactly one URL is resolved per invocation; any `http://` or `https://` argument takes precedence over earlier non-flag arguments, and an empty resolved `URL` triggers an immediate `exit 0` with no stdout output or `am` calls.
- `[xdg_open.sh:AGY_SHIM_NO_AM]`: `/system/bin/am` commands execute only when `AGY_SHIM_NO_AM` is unset or empty (`[ -z "${AGY_SHIM_NO_AM:-}" ]`) and `/system/bin/am` is executable (`[ -x /system/bin/am ]`).
- `[xdg_open.sh:/system/bin/am]`: All three `am` commands (`com.agy` broadcast, `com.agy.dev` broadcast, and `android.intent.action.VIEW` start) execute sequentially with stdout/stderr redirected to `/dev/null` and failures masked by `|| true`.
- `[chrome.sh:xdg-open]` / `[chromium.sh:xdg-open]` / `[gnome_open.sh:xdg-open]` / `[google_chrome.sh:xdg-open]` / `[open.sh:xdg-open]` / `[sensible_browser.sh:xdg-open]` / `[x_www_browser.sh:xdg-open]`: Each delegator requires `/system/bin/sh` and a sibling executable named `xdg-open` in `$(dirname "$0")`.

## Side Effects
- `[xdg_open.sh:LD_PRELOAD]` / `[xdg_open.sh:LD_LIBRARY_PATH]`: Unsets `LD_PRELOAD` and `LD_LIBRARY_PATH` in the script process environment.
- `[xdg_open.sh:AGY_OPEN_URL]` / `[xdg_open.sh:ANTIGRAVITY_OPEN_URL]`: Writes `AGY_OPEN_URL=$URL` and `ANTIGRAVITY_OPEN_URL: $URL` to `stdout` when `URL` is non-empty.
- `[xdg_open.sh:com.agy.ACTION_OPEN_URL]`: Dispatches `/system/bin/am broadcast -a com.agy.ACTION_OPEN_URL --es url "$URL"` to packages `com.agy` and `com.agy.dev` when enabled.
- `[xdg_open.sh:android.intent.action.VIEW]`: Dispatches `/system/bin/am start -a android.intent.action.VIEW -d "$URL"` when enabled.
- `[chrome.sh:exec]` / `[chromium.sh:exec]` / `[gnome_open.sh:exec]` / `[google_chrome.sh:exec]` / `[open.sh:exec]` / `[sensible_browser.sh:exec]` / `[x_www_browser.sh:exec]`: Replaces the shell process image with `"$(dirname "$0")/xdg-open" "$@"`.

## Verification
- Run: `bash modules/core/tests/run_all.sh`
