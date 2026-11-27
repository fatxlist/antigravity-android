# Failfast Patch Group

## Overview
Binary patch module (`crash_suppressor.py`) that bypasses the preinit CPU feature check and preinit atomic flag initialization sequences in `language_server` so ARMv8.0-A CPUs do not abort on startup.

## Interface
- `[crash_suppressor.py:TARGET_CPU]` / `[crash_suppressor.py:REPLACEMENT_CPU]` — 32-byte `bytes` constants for the unpatched and patched preinit CPU feature check sequences.
- `[crash_suppressor.py:TARGET_ATOMIC]` / `[crash_suppressor.py:REPLACEMENT_ATOMIC]` — 28-byte `bytes` constants for the unpatched and patched preinit atomic flag initialization sequences.
- `[crash_suppressor.py:find_all]` — `find_all(data: Union[bytes, bytearray, memoryview], pattern: bytes) -> List[int]`: returns all non-overlapping offsets of `pattern` in `data`.
- `[crash_suppressor.py:apply]` — `apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]`: returns `(ok, info)` with `name="failfast_cpu_and_atomic"`, hex offset lists (`cpu_raw`, `cpu_patched`, `atomic_raw`, `atomic_patched`), and `status` (`"skipped_missing"`, `"missing_signature"`, `"verified"`, `"unverified"`, `"already_patched"`, `"patched"`, or `"dry_run"`).

## Invariants
- `[crash_suppressor.py:TARGET_CPU]` / `[crash_suppressor.py:REPLACEMENT_CPU]` (32 bytes) and `[crash_suppressor.py:TARGET_ATOMIC]` / `[crash_suppressor.py:REPLACEMENT_ATOMIC]` (28 bytes) are equal-length pairs, preserving `len(data)` during in-place slice assignment.
- `[crash_suppressor.py:find_all]` Advances `pos += len(pattern)` after each hit, returning non-overlapping ascending offsets.
- `[crash_suppressor.py:apply]` Requires both `len(raw_cpu) + len(repl_cpu) > 0` and `len(raw_atomic) + len(repl_atomic) > 0`; if either is zero, returns `(True, info)` with `"skipped_missing"` when `allow_missing=True` or `(False, info)` with `"missing_signature"` when `allow_missing=False`.
- `[crash_suppressor.py:verify]` When `verify=True` (and both signature totals are non-zero), does not mutate `data` and returns `True` (`"verified"`) iff `len(raw_cpu) == 0 and len(repl_cpu) == 1 and len(raw_atomic) == 0 and len(repl_atomic) == 1`.
- `[crash_suppressor.py:dry_run]` Does not mutate `data` when `dry_run=True` (`"dry_run"`) or when `len(raw_cpu) == 0 and len(raw_atomic) == 0` (`"already_patched"`).

## Side Effects
- `[crash_suppressor.py:apply]` Mutates the caller-supplied `data` bytearray in place at all `raw_cpu` and `raw_atomic` offsets when `verify=False`, `dry_run=False`, both signature totals are non-zero, and at least one raw match exists.

## Verification
- Run: `bash modules/patches/tests/run_all.sh`
