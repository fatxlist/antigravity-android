# Event Bus Inputs

## Overview
Input transport adapters for the Antigravity I/O event bus: an Android broadcast bridge (`broadcast_receiver.sh`) that formats Intent payloads as JSON envelopes and writes non-blockingly to a FIFO, a named-FIFO listener (`fifo_input.py`) with Android FUSE path redirection and non-blocking write helpers, and a UNIX domain stream socket listener (`unix_socket_input.py`).

## Interface
- `[broadcast_receiver.sh:ACTION]` / `[broadcast_receiver.sh:PAYLOAD]` / `[broadcast_receiver.sh:FIFO]` — CLI entrypoint `broadcast_receiver.sh <action> <extra_json_or_msg> [destination_fifo]` (`ACTION` defaults to `com.agy.ACTION_DEFAULT`, `PAYLOAD` to `{}`, `FIFO` to `/data/user/0/com.agy/files/runtime/agy_bus.pipe`); consults `[broadcast_receiver.sh:AGY_RUNTIME_DIR]` and `[broadcast_receiver.sh:TMPDIR]` during FUSE relocation.
- `[fifo_input.py:SCRIPT_DIR]` / `[fifo_input.py:IO_ROOT]` / `[fifo_input.py:FUSE_PREFIXES]` — module paths and FUSE prefix tuple `("/storage/", "/sdcard/", "/mnt/sdcard/", "/mnt/runtime/")`.
- `[fifo_input.py:get_safe_runtime_dir]` — `get_safe_runtime_dir() -> str`: returns a writable internal runtime directory (`AGY_RUNTIME_DIR`, `/data/user/0/com.agy/files/runtime`, `/data/user/0/com.agy.dev/files/runtime`, `TMPDIR`, or `tempfile.gettempdir()`).
- `[fifo_input.py:get_safe_pipe_path]` — `get_safe_pipe_path(fifo_path: str) -> str`: resolves `abspath` and remaps FUSE-hosted paths into `get_safe_runtime_dir()`.
- `[fifo_input.py:write_fifo_nonblocking]` — `write_fifo_nonblocking(fifo_path: str, data: str) -> bool`: performs `os.O_WRONLY | os.O_NONBLOCK` UTF-8 write returning `False` if missing, `ENXIO`, or any `OSError`.
- `[fifo_input.py:run_fifo_listener]` — `run_fifo_listener(fifo_path: str, broker: EventBroker, max_events: int = 0)`: creates FIFO if absent, deserializes lines via `envelope.deserialize_event`, publishes to `broker`, and returns `events_read`.
- `[fifo_input.py:main]` — CLI `fifo_input.py <fifo_path>` supporting `-h`/`--help`.
- `[unix_socket_input.py:SCRIPT_DIR]` / `[unix_socket_input.py:IO_ROOT]` — module paths for `inputs` and `modules/io`.
- `[unix_socket_input.py:run_socket_listener]` — `run_socket_listener(socket_path: str, broker: EventBroker, max_connections: int = 0)`: binds an `AF_UNIX`/`SOCK_STREAM` server (`listen(5)`), reads newline-delimited JSON events, publishes to `broker`, and returns `connections_handled`.
- `[unix_socket_input.py:main]` — CLI `unix_socket_input.py <socket_path>` supporting `-h`/`--help`.
- [Input Templates](./templates/README.md) — reference Bash input producer template (`template_input.sh`).

## Invariants
- `[broadcast_receiver.sh:FIFO]` Remaps FUSE FIFO targets (`/storage/*`, `/sdcard/*`, `/mnt/sdcard/*`, `/mnt/runtime/*`) to `$SAFE_DIR/$(basename "$FIFO")`; constructs `{"event":"android.intent.$ACTION","timestamp":$NOW_MS,"source":"android:am","data":{"raw":"$PAYLOAD"}}`; writes to `$FIFO` only when `[ -p "$FIFO" ]` using non-blocking `python3` (`O_WRONLY | O_NONBLOCK`) or 1-second timeout fallbacks (`timeout`, `/system/bin/toybox`, or background `kill -9`), otherwise prints `$JSON` to `stdout`.
- `[fifo_input.py:get_safe_pipe_path]` Any absolute path starting with `FUSE_PREFIXES` is redirected to `os.path.join(get_safe_runtime_dir(), os.path.basename(fifo_path))`; `[fifo_input.py:get_safe_runtime_dir]` always falls back to `tempfile.gettempdir()`.
- `[fifo_input.py:write_fifo_nonblocking]` Ensures a trailing `"\n"`, always closes the FD in `finally`, and never raises `OSError`.
- `[fifo_input.py:run_fifo_listener]` Falls back to `get_safe_runtime_dir()` if initial `os.mkfifo` raises `PermissionError`/`OSError`; skips blank lines and malformed events without incrementing `events_read`; stops when `0 < max_events <= events_read` or on `KeyboardInterrupt`.
- `[unix_socket_input.py:run_socket_listener]` Reads 1024-byte UTF-8 chunks (`errors="ignore"`), processes newline-delimited non-empty lines sequentially, catches per-line exceptions without closing the connection, stops when `0 < max_connections <= connections_handled`, and always closes the socket and removes `socket_path` in `finally`.
- `[fifo_input.py:main]` / `[unix_socket_input.py:main]` Print usage and exit with status `1` when `len(sys.argv) < 2` or `sys.argv[1] in ("-h", "--help")`.

## Side Effects
- `[broadcast_receiver.sh:SAFE_DIR]` Creates candidate runtime directories via `mkdir -p` during FUSE redirection; `[broadcast_receiver.sh:FIFO]` spawns `python3`, `timeout`, `/system/bin/toybox`, or background subshells (`sleep 1; kill -9`) to write `$FIFO`, or prints `$JSON` to `stdout`.
- `[fifo_input.py:sys.path]` / `[unix_socket_input.py:sys.path]` Insert `<IO_ROOT>/core_bus` at index `0` of `sys.path` on module import.
- `[fifo_input.py:get_safe_runtime_dir]` Creates candidate directories via `os.makedirs(..., exist_ok=True)`.
- `[fifo_input.py:write_fifo_nonblocking]` Writes UTF-8 bytes directly to the FIFO file on disk; `[fifo_input.py:run_fifo_listener]` creates the FIFO via `os.mkfifo` (persisted on exit), invokes `broker.publish`, and logs bad events to `sys.stderr`.
- `[unix_socket_input.py:run_socket_listener]` Removes any existing file at `socket_path`, creates parent directories, binds/listens on `socket_path`, invokes `broker.publish`, logs invalid events to `sys.stderr`, and unlinks `socket_path` on shutdown.
- `[fifo_input.py:main]` / `[unix_socket_input.py:main]` Print status/usage lines to `stdout` and call `sys.exit(1)` on help/missing args.

## Verification
- Run: `bash modules/io/tests/run_all.sh`
