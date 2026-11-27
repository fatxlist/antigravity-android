# Interceptors Filters

## Overview
Stateless line-inspection filter modules that analyze individual stream lines from `language_server` output and emit partial event dictionaries (`event`, `source`, `data`) when crash signatures, browser/OAuth URLs, or agent task lifecycle markers are detected.

## Interface
- `[filter_error_alerts.py:filter_line]` — `filter_line(line: str) -> Optional[Dict[str, Any]]`: case-insensitively matches `panic:`, `fatal error:`, `sigsegv`, or `sigill` and returns `{"event": "system.error", "source": "language_server:stderr", "data": {"message": line.strip(), "severity": "fatal"}}`, or `None`.
- `[filter_open_url.py:URL_REGEX]` — compiled regex `r"https?://[\w\-\.\~:\?\#\[\]\@\!\$\&\'\(\)\*\+,;=%/]+"` matching HTTP/HTTPS URLs.
- `[filter_open_url.py:filter_line]` — `filter_line(line: str) -> Optional[Dict[str, Any]]`: detects explicit prefixes (`AGY_OPEN_URL=`, `ANTIGRAVITY_OPEN_URL:`, `ANTIGRAVITY_OPEN_URL=`) or OAuth keywords (`accounts.google.com`, `oauth2`, `/auth/callback`) combined with `URL_REGEX`, returning `{"event": "core.url.open", "source": "language_server:stdout", "data": {"url": ..., "type": "explicit_prefix" | "oauth_pattern"}}`, or `None`.
- `[filter_task_events.py:filter_line]` — `filter_line(line: str) -> Optional[Dict[str, Any]]`: matches `[task:start]` / `Starting task` (`agent.task.started`), `[task:done]` / `Task completed` (`agent.task.completed`), or `tool_call:` (`agent.tool.executed`), returning `{"event": ..., "source": "language_server:stdout", "data": {"raw": line.strip()}}`, or `None`.

## Invariants
- `[filter_error_alerts.py:filter_line]` Returns `None` on empty input (`not line`), matches substrings case-insensitively via `line.lower()`, strips whitespace from `line` into `data["message"]`, and fixes `source="language_server:stderr"` and `data["severity"]="fatal"`.
- `[filter_open_url.py:filter_line]` Returns `None` on empty input (`not line`); evaluates explicit prefixes in fixed order (`AGY_OPEN_URL=`, `ANTIGRAVITY_OPEN_URL:`, `ANTIGRAVITY_OPEN_URL=`) before case-sensitive OAuth keyword matching, splitting only on the first prefix occurrence (`split(prefix, 1)[1].strip()`) and emitting only if the extracted URL is non-empty.
- `[filter_open_url.py:filter_line]` OAuth detection requires both an OAuth keyword match and a `URL_REGEX.search(line)` match, returning the first regex match (`m.group(0)`).
- `[filter_task_events.py:filter_line]` Returns `None` on empty input (`not line`) and applies case-sensitive substring checks in mutually exclusive precedence order (`agent.task.started` > `agent.task.completed` > `agent.tool.executed`), trimming `line` into `data["raw"]`.

## Side Effects
- None

## Verification
- Run: `bash modules/io/tests/run_all.sh`
