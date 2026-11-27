# Env Command Runner (`modules/env/cmd`)

## Overview
Provides a telemetry-capturing shell command runner and CLI wrapper that records execution metadata, exit status, timing, stdout, and stderr into an append-only `commands.jsonl` audit log.

## Interface
- `[runner.py:run]`: `run(cmd, cwd=None, env=None, timeout=60, source="user", verbose=None) -> dict` — executes a shell command and returns a telemetry record dict (`id`, `time`, `cmd`, `cwd`, `exit_code`, `duration_ms`, `status`, `source`, `stdout`, `stderr`).
- `[runner.py:list_recent]`: `list_recent(limit=20) -> list` — reads `CMD_LOG_FILE` and returns up to the last `limit` parsed execution records.
- `[runner.py:main]`: CLI entry point supporting `list [limit]` (default `20`), `run <cmd...>`, or bare `<cmd...>` (any non-`list` first argument).
- `[runner.py:LOG_DIR]`: Module constant for the resolved logs directory, initialized at import time.
- `[runner.py:CMD_LOG_FILE]`: Module constant `<LOG_DIR>/commands.jsonl`.
- `[runner.py:CLR_RESET]`: ANSI escape code constants (`[runner.py:CLR_RESET]`, `[runner.py:CLR_RED]`, `[runner.py:CLR_GREEN]`, `[runner.py:CLR_YELLOW]`, `[runner.py:CLR_BLUE]`, `[runner.py:CLR_CYAN]`, `[runner.py:CLR_GRAY]`).
- `[runner.py:AGY_VERBOSE]`: Environment variables `[runner.py:AGY_VERBOSE]` and `[runner.py:DEV_MODE]` (`"1"` enables verbose output when `verbose is None`) and `[runner.py:HOME]` (used to resolve `LOG_DIR`).

## Invariants
- `[runner.py:_resolve_log_dir]`: Resolves `LOG_DIR` in order: `<HOME>/logs` (when `HOME` is an existing directory) -> `path_resolver.load_paths().get("app_root")/logs` -> `os.path.expanduser("/data/user/0/com.agy/files/logs")`.
- `[runner.py:run]`: Generates IDs matching `cmd_<unix_seconds>_<4_hex_chars>`, records `time` and `duration_ms` as integer milliseconds, defaults `cwd` to `os.getcwd()`, strips `stdout`/`stderr`, and builds the subprocess environment from `os.environ.copy()` without mutating `os.environ`.
- `[runner.py:run]`: Enforces status and `exit_code` mapping: `"SUCCESS"` when `returncode == 0`, `"FAILED"` when `returncode != 0` (`exit_code = proc.returncode`), `"TIMEOUT"` with `exit_code = 124` on `subprocess.TimeoutExpired`, and `"ERROR"` with `exit_code = 1` on any other exception.
- `[runner.py:run]`: Caps verbose display at 20 stdout lines (with overflow count) and 10 stderr lines, and suppresses `LOG_DIR` creation or JSONL append exceptions (`except Exception: pass`).
- `[runner.py:list_recent]`: Returns `[]` when `CMD_LOG_FILE` is not an existing file, reads UTF-8 with `errors="replace"`, suppresses read/JSON parse errors, and returns `records[-limit:]`.
- `[runner.py:main]`: Exits with code `1` when `len(sys.argv) < 2`, runs CLI commands with `verbose=True`, propagates `rec["exit_code"]` via `sys.exit`, and truncates listed commands longer than 35 chars to 32 chars + `"..."`.

## Side Effects
- `[runner.py:_resolve_log_dir]`: Runs at module import time to set `LOG_DIR`; when `HOME` is unset or not a directory, inserts `<modules/env>/context` at `sys.path[0]` and imports `path_resolver`.
- `[runner.py:run]`: Spawns an arbitrary shell subprocess via `subprocess.run(cmd, shell=True, ...)`.
- `[runner.py:run]`: Creates `LOG_DIR` (`os.makedirs(LOG_DIR, exist_ok=True)`) and appends a UTF-8 JSON line (`ensure_ascii=False`) to `CMD_LOG_FILE`.
- `[runner.py:run]`: Writes ANSI-colored execution trees to `sys.stdout` when `verbose` is enabled.
- `[runner.py:main]`: Writes usage errors to `sys.stderr`, prints command history tables to `sys.stdout`, and exits the process via `sys.exit`.

## Verification
- Run: `bash modules/env/tests/run_all.sh`
