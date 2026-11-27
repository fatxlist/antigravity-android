# Env Context (`modules/env/context`)

## Overview
Resolves Android package-specific filesystem paths from JSON specifications (`spec/paths.json`) and constructs deterministic, isolated process environment dictionaries (`spec/env_vars.json`) without hardcoded package IDs.

## Interface
- `[path_resolver.py:SPEC_PATH]`: Default path specification file (`<modules/env>/spec/paths.json`).
- `[path_resolver.py:get_application_id]`: `get_application_id() -> str` — resolves the Android application package ID from environment variables or default `"com.agy"`.
- `[path_resolver.py:load_paths]`: `load_paths(custom_spec: Optional[str] = None, app_id: Optional[str] = None) -> Dict[str, str]` — loads JSON path templates, interpolates package placeholders, and resolves absolute paths.
- `[path_resolver.py:resolve_subpath]`: `resolve_subpath(key: str, rel_path: str, custom_spec: Optional[str] = None, app_id: Optional[str] = None) -> str` — joins a resolved spec path key with a relative subpath.
- `[env_builder.py:SPEC_DIR]`: Default specification directory (`<modules/env>/spec`).
- `[env_builder.py:build_process_env]`: `build_process_env(custom_spec_dir: str = None, extra_env: Dict[str, str] = None, app_id: str = None) -> Dict[str, str]` — constructs and returns a process environment dictionary from `paths.json` and `env_vars.json`.

## Invariants
- `[path_resolver.py:get_application_id]`: Resolves application ID strictly in order: `AGY_APPLICATION_ID` -> `APPLICATION_ID` (`.strip()`-ed when truthy) -> `"com.agy.dev"` if `DEV_MODE == "1"` -> `"com.agy"`.
- `[path_resolver.py:load_paths]`: Truthy `app_id` overrides `get_application_id()`; all string values are formatted with `package_name`, `app_id`, and `APPLICATION_ID` placeholders and normalized via `os.path.abspath(os.path.expanduser(...))`, while non-string values are preserved verbatim.
- `[path_resolver.py:resolve_subpath]`: Raises `KeyError` if `key` is absent from loaded paths and strips leading `/` characters (`rel_path.lstrip("/")`) before `os.path.join` so leading slashes cannot discard the base directory.
- `[env_builder.py:build_process_env]`: Requires `paths.json` (containing keys `"app_root"` and `"cache"`) and `env_vars.json` in `custom_spec_dir or SPEC_DIR`.
- `[env_builder.py:build_process_env]`: Sets `HOME = paths["app_root"]`, `PREFIX = paths.get("prefix", os.path.join(app_root, "usr"))`, `TMPDIR = paths["cache"]`, `GIT_CONFIG_NOSYSTEM = "1"`, `AGY_APPLICATION_ID = app_id or path_resolver.get_application_id()`, and `PATH` from `spec.get("path_order", [])`, overriding matching keys in `spec.get("static", {})`.
- `[env_builder.py:build_process_env]`: Sets `LD_LIBRARY_PATH` only when at least one formatted entry from `spec.get("ld_library_paths", [])` passes `os.path.isdir(...)`, and applies truthy `extra_env` last via `env.update(extra_env)` without mutating `os.environ`.
- `[env_builder.py:path_resolver]`: Supports both package-relative (`from . import path_resolver`) and top-level (`import path_resolver`) import fallback.

## Side Effects
- `[path_resolver.py:get_application_id]`: Read-only access to `os.environ` (`AGY_APPLICATION_ID`, `APPLICATION_ID`, `DEV_MODE`); performs no state mutations.
- `[path_resolver.py:load_paths]`: Reads JSON path specs from disk (`open(custom_spec or SPEC_PATH)`).
- `[env_builder.py:build_process_env]`: Reads `paths.json` and `env_vars.json` from disk and queries `os.path.isdir(...)` for candidate `ld_library_paths`; performs no file writes or `os.environ` mutations.

## Verification
- Run: `bash modules/env/tests/run_all.sh`
