# Env Diagnostics (`modules/env/diagnostics`)

## Overview
Provides pre-flight host verification checks for ARM64 architecture, free storage, directory writability, and loopback TCP port availability.

## Interface
- `[port_checker.py:check_port_available]`: `check_port_available(port: int, host: str = "127.0.0.1") -> Dict[str, Any]` — tests whether `(host, port)` can be bound with an IPv4 TCP socket, returning `{"port", "available", "error"}`.
- `[preflight.py:run_preflight]`: `run_preflight(app_root: Optional[str] = None, min_mb_free: int = 250) -> Dict[str, Any]` — validates CPU architecture, free disk space, and write permission on `app_root`, returning `{"ok", "arch", "is_aarch64", "free_disk_mb", "space_ok", "write_ok", "app_root"}`.

## Invariants
- `[port_checker.py:check_port_available]`: Uses an IPv4 TCP socket (`socket.AF_INET`, `socket.SOCK_STREAM`) with `0.5s` timeout and default host `"127.0.0.1"`; returns `available=True, error=None` on bind success and `available=False, error=str(e)` on `OSError`.
- `[preflight.py:run_preflight]`: When `app_root is None`, resolves `app_root` by trying `..context.path_resolver.load_paths().get("app_root")`, falling back to top-level `path_resolver.load_paths().get("app_root")`, and finally `os.environ.get("HOME", "/data/user/0/com.agy/files")`.
- `[preflight.py:run_preflight]`: Sets `is_aarch64` strictly to `platform.machine().lower() in ("aarch64", "arm64")`.
- `[preflight.py:run_preflight]`: Computes `free_disk_mb = shutil.disk_usage(app_root).free // (1024 * 1024)` when `app_root` exists (defaulting to `0` if missing or on exception), requires `free_disk_mb >= min_mb_free` (default `250`) for `space_ok`, sets `write_ok = os.access(app_root, os.W_OK)` if `app_root` exists else `False`, and requires `ok = is_aarch64 and space_ok and write_ok`.

## Side Effects
- `[port_checker.py:check_port_available]`: Allocates an IPv4 TCP socket, transiently binds it to `(host, port)`, and closes it when exiting the `with` block.
- `[preflight.py:run_preflight]`: Lazily imports `path_resolver` and reads `paths.json` when `app_root is None`; performs read-only environment, platform, and filesystem metadata checks (`os.environ.get`, `platform.machine`, `os.path.exists`, `shutil.disk_usage`, `os.access`) with no disk writes.

## Verification
- Run: `bash modules/env/tests/run_all.sh`
