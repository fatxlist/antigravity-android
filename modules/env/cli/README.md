# Env CLI (`modules/env/cli`)

## Overview
Provides the command-line utilities for inspecting Antigravity runtime telemetry/logs (`agy_logs.py`) and controlling Android System Gateway services (`agy_system.py`).

## Interface
- `[agy_logs.py:GATEWAY_BASE]`: Module-level gateway base URL resolved at import time via `get_gateway_base()`, plus derived endpoint constants `[agy_logs.py:GATEWAY_STATUS_URL]` (`/status`), `[agy_logs.py:GATEWAY_LOGS_URL]` (`/logs`), `[agy_logs.py:GATEWAY_COMMANDS_URL]` (`/commands`), and `[agy_logs.py:GATEWAY_CRASH_URL]` (`/crash`).
- `[agy_logs.py:CLR_RESET]`: ANSI color constants (`[agy_logs.py:CLR_RESET]`, `[agy_logs.py:CLR_RED]`, `[agy_logs.py:CLR_GREEN]`, `[agy_logs.py:CLR_YELLOW]`, `[agy_logs.py:CLR_BLUE]`, `[agy_logs.py:CLR_CYAN]`, `[agy_logs.py:CLR_GRAY]`).
- `[agy_logs.py:get_gateway_base]`: `get_gateway_base()` — resolves the gateway base URL (without trailing `/`) via `bridge.client.resolve_gateway_url()` or local env/`gateway.port` fallback.
- `[agy_logs.py:get_gateway_token]`: `get_gateway_token()` — resolves the gateway auth token or `None` from env vars, `bridge.client.resolve_gateway_token()`, or `gateway.token` files.
- `[agy_logs.py:fetch_from_gateway]`: `fetch_from_gateway(url)` — performs an HTTP GET with `User-Agent: agy-logs` and optional `X-Agy-Gateway-Token`, returning decoded JSON or `None`.
- `[agy_logs.py:read_file_fallback]`: `read_file_fallback(max_lines=50)` — reads trailing lines from candidate `core.log` / `core_output.log` files into synthetic log entry dicts.
- `[agy_logs.py:format_log_entry]`: `format_log_entry(entry)` — formats a log dict (`time`, `level`, `channel`, `msg`) into an ANSI-colored line.
- `[agy_logs.py:show_status]`: `show_status(as_json=False)` — prints gateway/core health status from `GATEWAY_STATUS_URL` as text or JSON.
- `[agy_logs.py:show_crash]`: `show_crash(as_json=False)` — prints the latest crash report from `GATEWAY_CRASH_URL` or local `logs/crashes/` files.
- `[agy_logs.py:show_commands]`: `show_commands(limit=20, as_json=False)` — prints recent command audit records from `GATEWAY_COMMANDS_URL` or local `commands.jsonl`.
- `[agy_logs.py:show_command_detail]`: `show_command_detail(cmd_id, as_json=False)` — prints full metadata, stdout, and stderr for `cmd_id` from local `commands.jsonl`.
- `[agy_logs.py:main]`: CLI entry point supporting `[agy_logs.py:--tail]`/`[agy_logs.py:-n]` (default `30`), `[agy_logs.py:--channel]`, `[agy_logs.py:--level]` (`DEBUG|INFO|WARN|ERROR`), `[agy_logs.py:--commands]`/`[agy_logs.py:-c]`, `[agy_logs.py:--cmd-id]`, `[agy_logs.py:--crash]`, `[agy_logs.py:--status]`, and `[agy_logs.py:--json]`.
- `[agy_system.py:cmd_notify]`: Handles `notify` (`post` or `cancel`) via `client.send_command("notify", ...)`.
- `[agy_system.py:cmd_open]`: Handles `open` via `client.send_command("open_url", {"url": args.url})`.
- `[agy_system.py:cmd_wakelock]`: Handles `wakelock` (`on` -> `"acquire"`, `off` -> `"release"`) via `client.send_command("wakelock", ...)`.
- `[agy_system.py:cmd_info]`: Handles `info` via `client.send_command("system_info")`.
- `[agy_system.py:cmd_status]`: Handles `status` via `client.send_command("status")`.
- `[agy_system.py:cmd_doctor]`: Handles `doctor` (`"repair"` if `--repair` is set, else `"doctor"`) via `client.send_command(...)`.
- `[agy_system.py:cmd_clipboard]`: Handles `clipboard` (`set` from positional arg or `sys.stdin`, or `get`) via `client.send_command("clipboard", ...)`.
- `[agy_system.py:main]`: CLI entry point accepting root flag `[agy_system.py:--json]` and subcommands `[agy_system.py:notify]` (`post|cancel`, `-t/--title`, `-m/--message`, `--tag`, `--id`, `--progress`, `--ongoing`, `--channel`), `[agy_system.py:open]` (`url`), `[agy_system.py:wakelock]` (`on|off`, `--tag`), `[agy_system.py:info]`, `[agy_system.py:status]`, `[agy_system.py:doctor]` (`--repair`), and `[agy_system.py:clipboard]` (`get|set`, optional `text`).

## Invariants
- `[agy_logs.py:get_gateway_base]`: Strips trailing `/` and resolves strictly in order: `resolve_gateway_url()` -> `AGY_GATEWAY_URL` -> first all-digit `gateway.port` candidate -> `http://127.0.0.1:45161/gateway` (`DEV_MODE == "1"` or `AGY_APPLICATION_ID == "com.agy.dev"`) else `45160`.
- `[agy_logs.py:get_gateway_token]`: Resolves strictly in order: non-blank `AGY_GATEWAY_TOKEN` -> `AGY_GATEWAY_TOKEN_FILE` -> `resolve_gateway_token()` -> first non-blank `gateway.token` candidate -> `None`.
- `[agy_logs.py:fetch_from_gateway]`: Enforces a `1.0s` request timeout, re-reads `get_gateway_token()` per call, and returns `None` on any exception.
- `[agy_logs.py:main]`: Dispatches modes strictly in order `[agy_logs.py:--status]` > `[agy_logs.py:--commands]` > `[agy_logs.py:--cmd-id]` > `[agy_logs.py:--crash]` > default log view; default view falls back to `read_file_fallback(args.tail)` only when gateway logs are `None`, filters `--channel` case-insensitively and `--level` exactly, and slices `entries[-args.tail:]`.
- `[agy_logs.py:show_status]`: Maps core state to `"READY"` (`core.ready`), `"RESTARTING"` (`core.restarting`), or `"STARTING/NOT READY"` (matching `[agy_system.py:cmd_status]`).
- `[agy_logs.py:show_crash]`: Treats falsy crash or literal `"null"` from gateway as absent, loads `sorted(os.listdir(crashes_dir), reverse=True)[0]` on fallback, and prints plain text `[OK]` before checking `as_json` when no crash exists.
- `[agy_logs.py:show_command_detail]`: Searches only local `commands.jsonl` (never the gateway), matches the first line with `obj.get("id") == cmd_id`, and returns without calling `sys.exit` if not found.
- `[agy_system.py:main]`: Parses `[agy_system.py:--json]` on the root parser and enforces argparse choices for `notify` (`post|cancel`, default `post`), `wakelock` (`on|off`), and `clipboard` (`get|set`).
- `[agy_system.py:cmd_status]`: Both `[agy_system.py:cmd_status]` and `[agy_system.py:cmd_doctor]` exit with code `1` whenever `res.get("ok")` is falsy, even under `--json`.
- `[agy_system.py:cmd_notify]`: `[agy_system.py:cmd_notify]`, `[agy_system.py:cmd_open]`, `[agy_system.py:cmd_wakelock]`, and `[agy_system.py:cmd_clipboard]` exit with code `1` on falsy `res.get("ok")` only when `--json` is false; `[agy_system.py:cmd_info]` never calls `sys.exit(1)`.
- `[agy_system.py:cmd_clipboard]`: On `set`, reads `sys.stdin.read()` whenever `args.text` is empty/falsy.

## Side Effects
- `[agy_logs.py:MODULES_ENV_DIR]`: Mutates `sys.path` at import time (inserts parent `modules/env` at index `0`) and evaluates `[agy_logs.py:GATEWAY_BASE]` via `get_gateway_base()` (env reads, `gateway.port` file reads, and `/data/user/*/com.agy*/files/logs/gateway.port` globs).
- `[agy_logs.py:fetch_from_gateway]`: Performs outbound HTTP GET requests to local gateway endpoints and reads `gateway.token` files/env vars.
- `[agy_logs.py:read_file_fallback]`: Reads local `core.log` / `core_output.log` files; `[agy_logs.py:show_crash]` lists and reads JSON files under candidate `logs/crashes` directories; `[agy_logs.py:_find_commands_log]` probes and reads local `commands.jsonl`.
- `[agy_logs.py:main]`: Writes text or JSON output to `sys.stdout`; `[agy_logs.py:show_command_detail]` writes missing-ID errors to `sys.stderr`.
- `[agy_system.py:MODULES_ENV_DIR]`: Mutates `sys.path` at import time and imports `bridge.client` / `bridge.commands`.
- `[agy_system.py:cmd_notify]`: Sends gateway HTTP POST IPC commands (`notify`, `open_url`, `wakelock`, `system_info`, `status`, `doctor`/`repair`, `clipboard`) across `[agy_system.py:cmd_notify]`, `[agy_system.py:cmd_open]`, `[agy_system.py:cmd_wakelock]`, `[agy_system.py:cmd_info]`, `[agy_system.py:cmd_status]`, `[agy_system.py:cmd_doctor]`, and `[agy_system.py:cmd_clipboard]`, mutating Android host state (notifications, browser intents, wakelocks, clipboard, repairs), reading `sys.stdin` on `clipboard set`, printing to `sys.stdout`/`sys.stderr`, and calling `sys.exit(1)` on failures.

## Verification
- Run: `bash modules/env/tests/run_all.sh`
