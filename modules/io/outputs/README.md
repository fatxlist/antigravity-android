# Event Bus Outputs

## Overview
External output sinks and emitters that consume JSON event lines from `stdin` and dispatch them to Android `ACTION_OPEN_URL` broadcasts (`broadcast_emitter.sh`), an append-only JSONL log (`file_sink.py`), the Android notification tray (`notify_tray.sh`), or a UNIX domain stream socket (`unix_socket_emitter.py`).

## Interface
- `[broadcast_emitter.sh:RAW_JSON]` — `/system/bin/sh` script reading one JSON line from `stdin` and extracting the first `"url"` string into `URL` to dispatch `com.agy.ACTION_OPEN_URL`.
- `[file_sink.py:DEFAULT_LOG]` / `[file_sink.py:AGY_EVENT_LOG]` — default log path (`~/.gemini/events.jsonl`) and optional environment override.
- `[file_sink.py:append_event]` — `append_event(line: str, log_path: str = None) -> None`: appends a stripped, newline-terminated UTF-8 event line to the resolved log file.
- `[file_sink.py:main]` — CLI entry point accepting optional `sys.argv[1]` as target log path and appending non-empty lines from `sys.stdin`.
- `[notify_tray.sh:RAW_JSON]` — Bash script reading one JSON line from `stdin` and extracting `"event"` (`EVENT_NAME`), `"message"` (`MESSAGE`), and fallback `"url"` to emit a notification.
- `[unix_socket_emitter.py:emit_to_socket]` — `emit_to_socket(raw_line: str, sock_path: str) -> bool`: connects to `sock_path` over `AF_UNIX`/`SOCK_STREAM`, sends `raw_line.strip() + "\n"` in UTF-8, and returns `True` on success or `False` if missing/failed.
- `[unix_socket_emitter.py:main]` — CLI entry point accepting `sys.argv[1]` (`sock_path`) and forwarding non-empty lines from `sys.stdin` via `emit_to_socket`.
- [Output Templates](./templates/README.md) — reference Bash output sink template (`template_output.sh`).

## Invariants
- `[broadcast_emitter.sh:RAW_JSON]` Exits `0` immediately on read failure or empty input; broadcasts execute only when `URL` is non-empty and `/system/bin/am` is executable, suppressing failures (`|| true`).
- `[file_sink.py:append_event]` Resolves target path in order: explicit `log_path`, `AGY_EVENT_LOG`, then `DEFAULT_LOG`; strips input lines and terminates each line with `"\n"` in UTF-8; `[file_sink.py:main]` skips empty/whitespace lines.
- `[notify_tray.sh:RAW_JSON]` Exits `0` on read failure or empty input; resolves `[notify_tray.sh:MESSAGE]` in fallback order `"message"` -> `"url"` -> `"Event received: $EVENT_NAME"` and dispatches in fallback order `agy-notify` -> executable `/system/bin/am` -> `stdout` echo, always exiting `0`.
- `[unix_socket_emitter.py:emit_to_socket]` Returns `False` without creating a socket when `not os.path.exists(sock_path)` and catches all `Exception`s returning `False`; `[unix_socket_emitter.py:main]` exits `0` if `len(sys.argv) < 2` and skips blank lines.

## Side Effects
- `[broadcast_emitter.sh:/system/bin/am]` Invokes `/system/bin/am broadcast -a com.agy.ACTION_OPEN_URL --es url "$URL"` for both `-p com.agy` and `-p com.agy.dev` with output discarded.
- `[file_sink.py:append_event]` Creates missing parent directories via `os.makedirs(..., exist_ok=True)` and appends UTF-8 lines to disk.
- `[notify_tray.sh:agy-notify]` / `[notify_tray.sh:/system/bin/am]` / `[notify_tray.sh:echo]` Executes `agy-notify post`, or broadcasts `com.agy.ACTION_NOTIFY` via `/system/bin/am`, or prints `[NOTIFY] $EVENT_NAME: $MESSAGE` to `stdout`.
- `[unix_socket_emitter.py:emit_to_socket]` Opens a client `AF_UNIX` `SOCK_STREAM` connection to `sock_path`, writes UTF-8 bytes via `sendall`, and closes the socket per line.

## Verification
- Run: `bash modules/io/tests/run_all.sh`
