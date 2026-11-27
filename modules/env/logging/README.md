# Env Logging (`modules/env/logging`)

## Overview
Provides structured console/JSONL logging with ANSI color controls (`log.py`) and emergency JSON post-mortem crash snapshot capture (`flight_recorder.py`).

## Interface
- `[log.py:COLORS]`: Dictionary mapping ANSI escape codes for keys `DEBUG`, `INFO`, `OK`, `WARN`, `ERROR`, and `RESET`.
- `[log.py:log]`: `log(level: str, tag: str, message: str, meta: Dict[str, Any] = None, jsonl_file: str = None) -> None` — emits a formatted log line to stdout/stderr and optionally appends a JSONL record.
- `[flight_recorder.py:record_crash_snapshot]`: `record_crash_snapshot(error_msg: str, dump_path: str, extra_context: Dict[str, Any] = None) -> str` — gathers platform, PID, timestamp, context, and disk telemetry, writes a formatted JSON crash dump, and returns its absolute file path.

## Invariants
- `[log.py:log]`: Disables ANSI escapes whenever `NO_COLOR` is set in `os.environ` or `TERM == "dumb"`.
- `[log.py:log]`: Uppercases `level`, routes `"ERROR"` exclusively to `sys.stderr` and all other levels to `sys.stdout`, and emits `{"ts", "level", "tag", "msg", "meta"}` (`ts` in integer epoch milliseconds, falsy `meta` normalized to `{}`, `ensure_ascii=False`) when `jsonl_file` is truthy while suppressing directory creation and file append exceptions.
- `[flight_recorder.py:record_crash_snapshot]`: Always records `timestamp` (integer epoch milliseconds), `iso_time` (UTC `"%Y-%m-%dT%H:%M:%SZ"`), `error`, `platform`, `arch`, `python`, `pid`, and `extra_context` (falsy normalized to `{}`).
- `[flight_recorder.py:record_crash_snapshot]`: Suppresses `shutil.disk_usage` errors (omitting `"disk"` on failure), resolves `out_file = os.path.abspath(dump_path)`, writes UTF-8 JSON with `indent=2` and a trailing newline, and propagates `os.makedirs`/write errors.

## Side Effects
- `[log.py:log]`: Writes formatted log lines to `sys.stderr` (`ERROR`) or `sys.stdout` (other levels); when `jsonl_file` is truthy, creates parent directories via `os.makedirs(..., exist_ok=True)` and appends a UTF-8 JSONL line to `jsonl_file`.
- `[flight_recorder.py:record_crash_snapshot]`: Queries disk usage via `shutil.disk_usage` on `os.path.dirname(os.path.abspath(dump_path))` (or `os.getcwd()` if `dump_path` is falsy), creates parent directories via `os.makedirs(..., exist_ok=True)`, and writes/overwrites a UTF-8 JSON file at `os.path.abspath(dump_path)`.

## Verification
- Run: `bash modules/env/tests/run_all.sh`
