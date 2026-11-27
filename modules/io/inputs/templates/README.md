# Input Templates

## Overview
Reference Bash template (`template_input.sh`) demonstrating how to build a custom AGY event bus input producer that resolves an Android-safe FIFO path, constructs a JSON event envelope, and delivers it non-blockingly to the bus pipe or falls back to stdout.

## Interface
- `[template_input.sh:$1]` — optional positional CLI argument stored in `INPUT_DATA` as `data.value` (defaults to `default_payload` when omitted or empty).
- `[template_input.sh:AGY_BUS_FIFO]` — optional environment variable specifying `FIFO_PATH` (defaults to `/data/user/0/com.agy/files/runtime/agy_bus.pipe`).
- `[template_input.sh:AGY_RUNTIME_DIR]` — optional environment variable providing `SAFE_DIR` when relocating FUSE-backed FIFO paths.
- `[template_input.sh:TMPDIR]` — optional environment variable used as a fallback directory candidate for `SAFE_DIR`.
- `[template_input.sh:JSON_EVENT]` — emitted JSON event line formatted as `{"event":"custom.my_event","timestamp":<TIMESTAMP>,"source":"input:custom","data":{"value":"<INPUT_DATA>"}}`.

## Invariants
- `[template_input.sh:FIFO_PATH]` When matching `/storage/*`, `/sdcard/*`, `/mnt/sdcard/*`, or `/mnt/runtime/*`, rewrites `FIFO_PATH` to `$SAFE_DIR/$(basename "$FIFO_PATH")` using `AGY_RUNTIME_DIR` (if an existing directory) or the first existing writable (`-d` and `-w`) candidate among `/data/user/0/com.agy/files/runtime`, `/data/user/0/com.agy.dev/files/runtime`, `$TMPDIR`, and `/tmp`.
- `[template_input.sh:INPUT_DATA]` Defaults to `"default_payload"` when empty and is interpolated directly into `JSON_EVENT` without JSON escaping.
- `[template_input.sh:TIMESTAMP]` Uses `date +%s%3N` with fallback to `date +%s`.
- `[template_input.sh:os.O_NONBLOCK]` Writes to `FIFO_PATH` only when `[ -p "$FIFO_PATH" ]` is true; uses `python3` with `os.O_WRONLY | os.O_NONBLOCK` (ignoring `OSError`), else `timeout 1s sh -c 'echo "$1" > "$2"'`, else shell redirection `echo "$JSON_EVENT" > "$FIFO_PATH"`, suppressing stderr and errors (`|| true`); falls back to stdout when `FIFO_PATH` is not a FIFO.

## Side Effects
- `[template_input.sh:mkdir]` Creates candidate runtime directories via `mkdir -p` when relocating a FUSE FIFO path and `AGY_RUNTIME_DIR` is unset or absent.
- `[template_input.sh:os.write]` Spawns `python3` to perform a non-blocking write of UTF-8 `JSON_EVENT + '\n'` to `FIFO_PATH` when `FIFO_PATH` is a pipe and `python3` is installed.
- `[template_input.sh:timeout]` Spawns `timeout 1s sh` or performs shell redirection to write `JSON_EVENT` to `FIFO_PATH` when `python3` is unavailable.
- `[template_input.sh:echo]` Writes `JSON_EVENT` to `stdout` when `FIFO_PATH` is not an existing named pipe.

## Verification
- Run: `bash modules/io/tests/run_all.sh`
