# Env Bridge (`modules/env/bridge`)

## Overview
Provides the authenticated HTTP IPC client and typed Android OS command wrappers used to communicate with the local Android System Gateway (`127.0.0.1:<port>/gateway`).

## Interface
- `[client.py:TOKEN_HEADER]`: HTTP authentication header constant (`"X-Agy-Gateway-Token"`).
- `[client.py:DEFAULT_GATEWAY_URL]`: Module-level default gateway URL initialized at import time via `resolve_gateway_url()`.
- `[client.py:resolve_gateway_url]`: `resolve_gateway_url() -> str` — resolves the gateway endpoint URL from `AGY_GATEWAY_URL`, `gateway.port` files, or dev/release defaults.
- `[client.py:resolve_gateway_token]`: `resolve_gateway_token() -> Optional[str]` — resolves the gateway token from `AGY_GATEWAY_TOKEN`, `AGY_GATEWAY_TOKEN_FILE`, or `gateway.token` files.
- `[client.py:send_command]`: `send_command(command: str, payload: Dict[str, Any] = None, gateway_url: str = None) -> Dict[str, Any]` — posts a JSON `{"command", "payload"}` envelope to the gateway with fallback retry.
- `[client.py:exec_start]`: `exec_start(cmd: str, gateway_url: str = None, **payload) -> Dict[str, Any]` — starts an async gateway command (`"exec_start"`).
- `[client.py:exec_status]`: `exec_status(cmd_id: str, gateway_url: str = None, stdout_offset: int = 0, stderr_offset: int = 0, include_output: bool = True) -> Dict[str, Any]` — polls async command state/output (`"exec_status"`).
- `[client.py:exec_cancel]`: `exec_cancel(cmd_id: str, gateway_url: str = None) -> Dict[str, Any]` — cancels a running async command (`"exec_cancel"`).
- `[client.py:exec_list]`: `exec_list(active_only: bool = False, limit: int = 20, gateway_url: str = None) -> Dict[str, Any]` — lists gateway command executions (`"exec_list"`).
- `[client.py:get_status]`: `get_status(gateway_url: str = None) -> Dict[str, Any]` — queries gateway/core health (`"status"`).
- `[client.py:doctor]`: `doctor(gateway_url: str = None) -> Dict[str, Any]` — runs gateway diagnostics (`"doctor"`).
- `[client.py:repair]`: `repair(gateway_url: str = None, **payload) -> Dict[str, Any]` — triggers gateway safe repairs (`"repair"`).
- `[client.py:exec_command]`: `exec_command(cmd: str, gateway_url: str = None, async_mode: bool = False, poll_interval: float = 0.25, timeout_s: Optional[float] = None, **payload) -> Dict[str, Any]` — executes a synchronous `"exec"` command or starts and polls an async command until completion or timeout.
- `[commands.py:notify]`: `notify(title: str, message: str, progress: Optional[int] = None, tag: str = "agy_status", channel: str = "agy_tasks", ongoing: bool = False) -> Dict[str, Any]` — posts an Android status notification (`"notify"`, `action="post"`).
- `[commands.py:cancel_notify]`: `cancel_notify(tag: str = "agy_status", notification_id: int = 1001) -> Dict[str, Any]` — cancels an Android notification (`"notify"`, `action="cancel"`).
- `[commands.py:open_url]`: `open_url(url: str) -> Dict[str, Any]` — opens a URL via Android Intent (`"open_url"`).
- `[commands.py:wakelock]`: `wakelock(acquire: bool = True, tag: str = "agy_task") -> Dict[str, Any]` — acquires or releases an Android wakelock (`"wakelock"`).
- `[commands.py:clipboard_set]`: `clipboard_set(text: str) -> Dict[str, Any]` — writes text to the Android clipboard (`"clipboard"`, `action="set"`).
- `[commands.py:clipboard_get]`: `clipboard_get() -> Dict[str, Any]` — reads text from the Android clipboard (`"clipboard"`, `action="get"`).
- `[commands.py:system_info]`: `system_info() -> Dict[str, Any]` — fetches host battery, network, memory, and storage telemetry (`"system_info"`).
- `[commands.py:status]`: `status() -> Dict[str, Any]` — fetches gateway status (`"status"`).
- `[commands.py:doctor]`: `doctor() -> Dict[str, Any]` — runs gateway health checks (`"doctor"`).
- `[commands.py:repair]`: `repair() -> Dict[str, Any]` — triggers gateway repairs (`"repair"`).

## Invariants
- `[client.py:_gateway_candidates]`: Searches candidate files in fixed order: `$HOME/logs/<filename>`, `path_resolver.load_paths().get("app_root")/logs/<filename>`, `/data/user/0/com.agy.dev/files/logs/<filename>`, `/data/user/0/com.agy/files/logs/<filename>`, followed by deduplicated `/data/user/*/com.agy/files/logs/<filename>` and `/data/user/*/com.agy.dev/files/logs/<filename>` glob matches.
- `[client.py:resolve_gateway_url]`: Resolves strictly in order: `AGY_GATEWAY_URL`, first readable `gateway.port` whose stripped text satisfies `str.isdigit()` (`http://127.0.0.1:<port>/gateway`), then `http://127.0.0.1:45161/gateway` if `DEV_MODE == "1"` or `AGY_APPLICATION_ID == "com.agy.dev"` or `"com.agy.dev"` in `$HOME`, else `http://127.0.0.1:45160/gateway`.
- `[client.py:resolve_gateway_token]`: Resolves strictly in order: non-blank `AGY_GATEWAY_TOKEN`, non-blank file at `AGY_GATEWAY_TOKEN_FILE`, first non-blank `gateway.token` candidate, else `None`.
- `[client.py:_request]`: Always sends `Content-Type: application/json`, adds `[client.py:TOKEN_HEADER]` (`X-Agy-Gateway-Token`) only when a truthy token is resolved, enforces a `1.0s` default timeout, and decodes the UTF-8 JSON response.
- `[client.py:send_command]`: Always sends `{"command": command, "payload": payload or {}}`; on `URLError`, `OSError`, or `HTTPError`, retries once against the alternate loopback port (`45160` if `":45161/"` in URL, else `45161`) only when `gateway_url is None`; returns `{"ok": False, "fallback": True, "error": str(e), "packet": packet}` rather than raising transport errors.
- `[client.py:exec_command]`: In `async_mode=True`, immediately returns the `exec_start` response if `not start.get("ok")` or `not start.get("id")`; otherwise polls `exec_status` (`stdout_offset=0`, `stderr_offset=0`) every `poll_interval` until `not last.get("ok")` or `not last.get("running", False)` or `time.time() > deadline` (canceling via `exec_cancel` and setting `last["timeout"] = True`).
- `[commands.py:notify]`: Clamps non-`None` `progress` to `[0, 100]` via `max(0, min(100, progress))` and includes `"ongoing": True` only when `ongoing` is truthy.
- `[commands.py:wakelock]`: Maps `acquire` strictly to `"acquire"` (truthy) or `"release"` (falsy).
- `[commands.py:client]`: Supports both package-relative (`from . import client`) and standalone (`import client`) imports.

## Side Effects
- `[client.py:DEFAULT_GATEWAY_URL]`: Invokes `resolve_gateway_url()` at import time, reading environment variables (`AGY_GATEWAY_URL`, `DEV_MODE`, `AGY_APPLICATION_ID`, `HOME`), lazily importing `..context.path_resolver`, globbing `/data/user/*/com.agy*/files/logs/gateway.port`, and reading candidate `gateway.port` files from disk.
- `[client.py:resolve_gateway_token]`: Reads `AGY_GATEWAY_TOKEN` / `AGY_GATEWAY_TOKEN_FILE` environment variables and candidate `gateway.token` files from disk.
- `[client.py:send_command]`: Performs up to two synchronous outbound HTTP POST requests via `[client.py:_request]` (`urllib.request.urlopen`) to the loopback Android System Gateway; all client (`[client.py:exec_start]`, `[client.py:exec_status]`, `[client.py:exec_cancel]`, `[client.py:exec_list]`, `[client.py:get_status]`, `[client.py:doctor]`, `[client.py:repair]`, `[client.py:exec_command]`) and command (`[commands.py:notify]`, `[commands.py:cancel_notify]`, `[commands.py:open_url]`, `[commands.py:wakelock]`, `[commands.py:clipboard_set]`, `[commands.py:clipboard_get]`, `[commands.py:system_info]`, `[commands.py:status]`, `[commands.py:doctor]`, `[commands.py:repair]`) wrappers trigger these gateway IPC calls and associated Android host actions.
- `[client.py:exec_command]`: Sleeps the calling thread (`time.sleep(poll_interval)`) during async polling and mutates `last["timeout"] = True` in place on timeout.

## Verification
- Run: `bash modules/env/tests/run_all.sh`
