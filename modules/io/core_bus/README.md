# Core Bus

## Overview
Core event bus package implementing Antigravity event envelope creation/validation (`envelope.py`), wildcard pattern-to-destination routing (`subscriber_router.py`), and the central `EventBroker` (`event_broker.py`) that validates and dispatches events to in-memory handlers and external output scripts with timeout enforcement.

## Interface
- `[envelope.py:create_event]` — `create_event(event_type: str, source: str, data: Dict[str, Any] = None) -> Dict[str, Any]`: builds an envelope dictionary with keys `event`, `timestamp` (epoch ms `int`), `source` (defaults to `"unknown"`), and `data` (defaults to `{}`).
- `[envelope.py:serialize_event]` — `serialize_event(envelope: Dict[str, Any]) -> str`: serializes an envelope dict to JSON with `ensure_ascii=False`.
- `[envelope.py:deserialize_event]` — `deserialize_event(raw_text: str) -> Dict[str, Any]`: parses JSON via `json.loads`, validates with `validate_event`, and returns the envelope dict.
- `[envelope.py:validate_event]` — `validate_event(envelope: Dict[str, Any]) -> bool`: verifies required keys and types, returning `True` or raising `ValueError`.
- `[subscriber_router.py:SubscriberRouter]` — wildcard pattern-to-output router storing `self.routes: Dict[str, List[str]]`; exposes `__init__(routes_file: str = None)`, `load_routes(routes_file: str) -> None`, `subscribe(pattern: str, outputs: List[str]) -> None`, and `get_destinations(event_type: str) -> List[str]`.
- `[event_broker.py:SCRIPT_DIR]` / `[event_broker.py:IO_ROOT]` — module-level paths for `core_bus` and its parent `modules/io` directory.
- `[event_broker.py:EventBroker]` — central dispatcher exposing `__init__(routes_path: str = None, outputs_dir: str = None, dispatch_timeout: float = 5.0)`, `register_handler(pattern: str, handler_callable)`, and `publish(event_dict: Dict[str, Any], timeout: float = None) -> List[str]`.

## Invariants
- `[envelope.py:create_event]` Raises `ValueError("event_type cannot be empty")` if `event_type` is falsy; falsy `source` falls back to `"unknown"`; `data` defaults to `{}` only when `None`.
- `[envelope.py:validate_event]` Requires keys `("event", "timestamp", "source", "data")`, a non-empty non-whitespace `str` for `envelope["event"]`, and a `dict` for `envelope["data"]`, raising `ValueError` otherwise; `[envelope.py:deserialize_event]` enforces `validate_event` before returning.
- `[subscriber_router.py:SubscriberRouter.load_routes]` Skips route entries missing a truthy `"pattern"`.
- `[subscriber_router.py:SubscriberRouter.subscribe]` Deduplicates outputs per pattern while preserving insertion order; `[subscriber_router.py:SubscriberRouter.get_destinations]` matches via `fnmatch.fnmatch` and returns a sorted deduplicated list.
- `[event_broker.py:EventBroker.__init__]` Defaults `outputs_dir` to `<IO_ROOT>/outputs`, `routes_file` to `<IO_ROOT>/spec/default_routes.json`, and `dispatch_timeout` to `5.0`.
- `[event_broker.py:EventBroker.publish]` Validates `event_dict` via `envelope.validate_event` before dispatching; uses explicit `timeout` when not `None` else `self.dispatch_timeout`; skips missing script files (`not os.path.isfile(...)`); executes `.sh` scripts via `/system/bin/sh` (if present, else `bash`) and all other scripts via `sys.executable` to comply with Android `noexec` mounts.
- `[event_broker.py:EventBroker.publish]` Isolates handler exceptions and subprocess failures/timeouts so remaining subscribers still execute, killing and reaping timed-out or still-running errored subprocesses.

## Side Effects
- `[event_broker.py:sys.path]` Inserts `SCRIPT_DIR` at index `0` of `sys.path` on module import.
- `[subscriber_router.py:SubscriberRouter.load_routes]` Reads UTF-8 JSON route configuration from disk and mutates `self.routes` (also invoked from `[subscriber_router.py:SubscriberRouter.__init__]` and `[event_broker.py:EventBroker.__init__]` when the routes file exists).
- `[event_broker.py:EventBroker.register_handler]` Mutates `self.in_memory_handlers` in memory.
- `[event_broker.py:EventBroker.publish]` Invokes registered in-memory callables, spawns subprocesses via `subprocess.Popen` feeding serialized JSON plus `\n` to stdin (`stdout=DEVNULL`, `stderr=PIPE`), kills/waits/closes pipes on error or timeout, and logs errors/timeouts to `sys.stderr`.

## Verification
- Run: `bash modules/io/tests/run_all.sh`
