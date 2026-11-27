# Privilege Shims (`modules/core/shims/privilege`)

## Overview
Provides the Shizuku privileged shell launcher shim (`rish.sh`), enforcing Android 14+ read-only DEX permissions before executing `rikka.shizuku.shell.ShizukuShellLoader` via `/system/bin/app_process`.

## Interface
- `[rish.sh:#!/system/bin/sh]`: Executable Android POSIX shell wrapper resolving its directory via `dirname "$0"`.
- `[rish.sh:$@]`: Forwards all positional CLI arguments verbatim to `rikka.shizuku.shell.ShizukuShellLoader`.
- `[rish.sh:RISH_APPLICATION_ID]`: Optional environment variable identifying the Shizuku caller package; defaults to `"com.agy"` when unset or empty.
- `[rish.sh:DEX]`: Expects sibling DEX file `"$BASEDIR/rish_shizuku.dex"` alongside the invoked script.
- `[rish.sh:/system/bin/getprop]`: Queries `ro.build.version.sdk` (falling back to `0`) to determine Android API level (`SDK`).
- `[rish.sh:/system/bin/app_process]`: Invoked under `env -u LD_PRELOAD -u LD_LIBRARY_PATH` with `-Djava.class.path="$DEX"`, `/system/bin`, `--nice-name=rish`, and `rikka.shizuku.shell.ShizukuShellLoader`.

## Invariants
- `[rish.sh:DEX]`: `"$BASEDIR/rish_shizuku.dex"` must exist as a regular file (`[ -f "$DEX" ]`); otherwise the script prints an error to `stdout` and exits with status `1`.
- `[rish.sh:SDK]`: On Android 14+ (`SDK >= 34`), `"$DEX"` must be non-writable (`[ ! -w "$DEX" ]`) prior to `app_process` launch; if it remains writable after `chmod 400`, the script prints an error to `stdout` and exits with status `1`.
- `[rish.sh:RISH_APPLICATION_ID]`: Guaranteed non-empty and exported (default `"com.agy"`) before `exec`.
- `[rish.sh:env]`: `LD_PRELOAD` and `LD_LIBRARY_PATH` are always removed (`-u LD_PRELOAD -u LD_LIBRARY_PATH`) before invoking `/system/bin/app_process`.

## Side Effects
- `[rish.sh:chmod]`: On Android 14+ (`SDK >= 34`), mutates `"$DEX"` permissions in place via `chmod 400 "$DEX"` when writable.
- `[rish.sh:RISH_APPLICATION_ID]`: Exports `RISH_APPLICATION_ID="com.agy"` into the process environment when unset or empty.
- `[rish.sh:exec]`: Replaces the shell process image with `/system/bin/app_process` running `rikka.shizuku.shell.ShizukuShellLoader`.
- `[rish.sh:echo]`: Writes error and Android 14+ read-only remediation messages to `stdout` when `"$DEX"` is missing or writable.

## Verification
- Run: `bash modules/core/tests/run_all.sh`
