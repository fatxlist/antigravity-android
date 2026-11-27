# Failfast Patch Group

## Overview
Binary patch module (`crash_suppressor.py`) that bypasses the preinit CPU feature check and preinit atomic flag initialization sequences in `language_server` so ARMv8.0-A CPUs do not abort on startup. Uses `.text`-scoped AArch64 instruction matching to stay resilient across upstream releases.

## Interface
- `[crash_suppressor.py:TARGET_CPU]` / `[crash_suppressor.py:REPLACEMENT_CPU]` — 32-byte reference `bytes` constants for the unpatched and patched preinit CPU feature check sequences (`CPU_RAW_TAIL` + `CPU_EPILOGUE`).
- `[crash_suppressor.py:TARGET_ATOMIC]` / `[crash_suppressor.py:REPLACEMENT_ATOMIC]` — 28-byte reference `bytes` constants for the unpatched and patched preinit atomic flag initialization sequences (`ATOMIC_RAW_TAIL` / `ATOMIC_PATCHED_TAIL`).
- `[crash_suppressor.py:find_all]` — `find_all(data: Union[bytes, bytearray, memoryview], pattern: bytes, start: int = 0, end: int = -1) -> List[int]`: returns all non-overlapping offsets of `pattern` in `data[start:end]`.
- `[crash_suppressor.py:apply]` — `apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]`: returns `(ok, info)` with `name="failfast_cpu_and_atomic"`, hex offset lists (`cpu_raw`, `cpu_patched`, `atomic_raw`, `atomic_patched`), and `status` (`"skipped_missing"`, `"missing_signature"`, `"unexpected_count_cpu_<n>_atomic_<m>"`, `"verified"`, `"unverified"`, `"already_patched"`, `"patched"`, or `"dry_run"`).

## Invariants
- `[crash_suppressor.py:_scan_bounds]` Scopes scans to ELF64 AArch64 `.text` via `[engine/elf_scanner.py:get_text_section]` when `is_valid_elf64(data, 0)` holds (ignoring any embedded secondary ELFs), falling back to `(0, len(data))` for raw synthetic buffers.
- `[crash_suppressor.py:_match_cpu_offsets]` / `[crash_suppressor.py:_match_atomic_tail]` Match address-independent AArch64 tails while validating masked `ADRP`/`LDR`/`ADD` prefix opcodes and verifying the `CPU_EPILOGUE` at `+0x64..+0x70`.
- `[crash_suppressor.py:find_all]` Advances `pos += len(pattern)` after each hit, returning non-overlapping ascending offsets.
- `[crash_suppressor.py:apply]` Requires `len(raw_cpu) + len(repl_cpu) == 1` and `len(raw_atomic) + len(repl_atomic) == 1`; if either total is zero, returns `(True, info)` with `"skipped_missing"` when `allow_missing=True` or `(False, info)` with `"missing_signature"` when `allow_missing=False`, and fails closed if any total != 1.
- `[crash_suppressor.py:verify]` When `verify=True` (and both signature counts equal 1), does not mutate `data` and returns `True` (`"verified"`) iff `len(raw_cpu) == 0 and len(repl_cpu) == 1 and len(raw_atomic) == 0 and len(repl_atomic) == 1`.
- `[crash_suppressor.py:dry_run]` Does not mutate `data` when `dry_run=True` (`"dry_run"`) or when `len(raw_cpu) == 0 and len(raw_atomic) == 0` (`"already_patched"`).

## Side Effects
- `[crash_suppressor.py:apply]` Mutates the caller-supplied `data` bytearray in place (`REPLACEMENT_CPU` at `raw_cpu` and `ATOMIC_PATCHED_TAIL` at `pos + 8`, preserving the target binary's 8-byte `ADRP x8; ADD x8` prefix) when `verify=False`, `dry_run=False`, both signature totals equal 1, and at least one raw match exists.

## Verification
- Run: `bash modules/patches/tests/run_all.sh`
