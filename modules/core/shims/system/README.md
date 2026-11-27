# System Shims (`modules/core/shims/system`)

## Overview
Provides the primary system URL-opener shim (`xdg_open.sh`) and its `open_helper.sh` delegator used by the runtime bundle to emit structured open-URL stdout markers and dispatch Android Activity Manager broadcasts and `VIEW` intents.

## Interface
- `[open_helper.sh:$@]`: Executable `/system/bin/sh` wrapper forwarding all positional CLI arguments unchanged via `exec "$(dirname "$0")/xdg-open" "$@"`.
- `[xdg_open.sh:$@]`: Executable `/system/bin/sh` URL-opener CLI selecting the first `http://*` or `https://*` argument, or falling back to the first non-empty argument not starting with `-`.
- `[xdg_open.sh:AGY_SHIM_NO_AM]`: Optional environment variable disabling all `/system/bin/am` broadcast and activity dispatches whenever set to any non-empty value.
- `[xdg_open.sh:AGY_OPEN_URL]` / `[xdg_open.sh:ANTIGRAVITY_OPEN_URL]`: Stdout protocol lines emitted as `AGY_OPEN_URL=$URL` and `ANTIGRAVITY_OPEN_URL: $URL` when a non-empty `URL` is resolved.
- `[xdg_open.sh:com.agy.ACTION_OPEN_URL]` / `[xdg_open.sh:android.intent.action.VIEW]`: Android intent actions dispatched via `/system/bin/am broadcast` (to `com.agy` and `com.agy.dev` with `--es url "$URL"`) and `/system/bin/am start -a android.intent.action.VIEW -d "$URL"`.
- `[xdg_open.sh:exit 0]`: Exit status contract returning `0` both when no URL is found and after dispatch completes.

## Invariants
- `[open_helper.sh:dirname]`: Delegates strictly to a sibling executable named `xdg-open` in `$(dirname "$0")` while preserving positional arguments verbatim (`"$@"`).
- `[xdg_open.sh:LD_PRELOAD]` / `[xdg_open.sh:LD_LIBRARY_PATH]`: Both variables are unconditionally unset at startup before argument parsing or invoking `/system/bin/am`.
- `[xdg_open.sh:URL]`: Any `http://*` or `https://*` argument takes precedence over earlier non-flag positional arguments; the fallback pass skips empty strings and `-`-prefixed tokens; empty `URL` exits `0` immediately without stdout output or `am` calls.
- `[xdg_open.sh:AGY_SHIM_NO_AM]`: `/system/bin/am` runs only when `AGY_SHIM_NO_AM` is unset/empty and `/system/bin/am` is executable (`[ -x /system/bin/am ]`).
- `[xdg_open.sh:/system/bin/am]`: All three `/system/bin/am` commands redirect output to `/dev/null` and mask errors with `|| true`, guaranteeing exit code `0`.

## Side Effects
- `[open_helper.sh:exec]`: Replaces the current shell process with `"$(dirname "$0")/xdg-open" "$@"`.
- `[xdg_open.sh:LD_PRELOAD]` / `[xdg_open.sh:LD_LIBRARY_PATH]`: Unsets `LD_PRELOAD` and `LD_LIBRARY_PATH` in the script process environment.
- `[xdg_open.sh:AGY_OPEN_URL]` / `[xdg_open.sh:ANTIGRAVITY_OPEN_URL]`: Writes `AGY_OPEN_URL=$URL` and `ANTIGRAVITY_OPEN_URL: $URL` to `stdout`.
- `[xdg_open.sh:com.agy.ACTION_OPEN_URL]`: Broadcasts `com.agy.ACTION_OPEN_URL` sequentially to packages `com.agy` and `com.agy.dev` via `/system/bin/am broadcast` when enabled.
- `[xdg_open.sh:android.intent.action.VIEW]`: Starts an Android `VIEW` activity for `URL` via `/system/bin/am start` when enabled.

## Verification
- Run: `bash modules/core/tests/run_all.sh`
