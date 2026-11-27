# Stream Interceptors

## Overview
Process-stream tee and event extraction layer (`stream_splitter.py`) that mirrors lines from an input stream to an output stream while passing each line through stateless line filters in `./filters/` and asynchronously publishing matched event envelopes to `EventBroker` on a background daemon thread.

## Interface
- `[stream_splitter.py:SCRIPT_DIR]` / `[stream_splitter.py:IO_ROOT]` — module-level paths for `interceptors` and `modules/io`.
- `[stream_splitter.py:FILTERS]` — ordered `List[Callable]` filter pipeline: `[filter_open_url.filter_line, filter_task_events.filter_line, filter_error_alerts.filter_line]`.
- `[stream_splitter.py:process_stream]` — `process_stream(stream_in, stream_out, broker: EventBroker, max_lines: int = 0)`: echoes each line to `stream_out`, evaluates `FILTERS`, enqueues matched envelopes to the worker thread, drains on exit, and returns `(lines_processed, events_emitted)`.
- `[stream_splitter.py:main]` — CLI entry point constructing `EventBroker()` and running `process_stream(sys.stdin, sys.stdout, broker)`.
- [Filters](./filters/README.md) — stateless line-filter modules (`filter_error_alerts.py`, `filter_open_url.py`, `filter_task_events.py`).

## Invariants
- `[stream_splitter.py:FILTERS]` Evaluates filters on every line in fixed order (`filter_open_url.filter_line`, `filter_task_events.filter_line`, `filter_error_alerts.filter_line`).
- `[stream_splitter.py:process_stream]` Writes and flushes each line to `stream_out` (when truthy) before running filters; truthy filter results must contain key `"event"` (`"source"` defaults to `"stream"`, `"data"` defaults to `{}`); enforces line limit only when `max_lines > 0`.
- `[stream_splitter.py:process_stream]` Unconditionally enqueues sentinel `None` and calls `worker_thread.join()` in a `finally` block so all queued events are drained before returning.
- `[stream_splitter.py:process_stream]` / `[stream_splitter.py:_worker_loop]` Catch per-filter and per-publish exceptions and log them to `sys.stderr` without terminating stream processing or the worker thread; `_worker_loop` always calls `event_queue.task_done()`.

## Side Effects
- `[stream_splitter.py:sys.path]` Inserts `<IO_ROOT>/core_bus` and `<SCRIPT_DIR>/filters` at index `0` of `sys.path` on module import.
- `[stream_splitter.py:process_stream]` Spawns a daemon thread named `"StreamSplitterWorker"`, reads `stream_in`, writes/flushes `stream_out`, and logs filter errors to `sys.stderr`.
- `[stream_splitter.py:_worker_loop]` Invokes `broker.publish(item)` on the worker thread and logs publish/loop exceptions to `sys.stderr`.
- `[stream_splitter.py:main]` Instantiates `EventBroker()` and streams `sys.stdin` to `sys.stdout`.

## Verification
- Run: `bash modules/io/tests/run_all.sh`
