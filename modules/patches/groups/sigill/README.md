# SIGILL Handler Patch Group

## Overview
Binary patch module (`sigill_handler.py`) that patches Go's `rt_sigaction` routine to skip overwriting the `LD_PRELOAD` `SIGILL` (signal 4) handler used by `liblse_compat.so`.

## Interface
- `[sigill_handler.py:SCRIPT_DIR]` / `[sigill_handler.py:PATCHES_ROOT]` — module paths used to locate `modules/patches/engine`.
- `[sigill_handler.py:TARGET]` — 32-byte `bytes` constant (`e00740f9e10b40f9e20f40f9e31340f9c81080d2010000d4e02b00b9c0035fd6`) matching the unpatched `rt_sigaction` sequence.
- `[sigill_handler.py:REPLACEMENT]` — 32-byte `bytes` constant (`e08740a9e28f41a91f1000f1c81080d240000054010000d4ff2b00b9c0035fd6`) skipping `SIGILL` registration.
- `[sigill_handler.py:EXPECTED_COUNT]` — constant `1` specifying the expected combined match count.
- `[sigill_handler.py:apply]` — `apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]`: searches via `byte_replacer.find_offsets` and returns `(ok, info)` with `name="sigill"`, `expected`, `raw_count`, `patched_count`, `raw_offsets`, `patched_offsets`, and `status` (`"skipped_missing"`, `"missing_signature"`, `"unexpected_count_<total>"`, `"verified"`, `"unverified"`, `"already_patched"`, `"patched"`, or `"dry_run"`).

## Invariants
- `[sigill_handler.py:TARGET]` / `[sigill_handler.py:REPLACEMENT]` Both patterns are 32 bytes, preserving `len(data)` during in-place slice replacement.
- `[sigill_handler.py:apply]` Requires `total = len(raw_offsets) + len(pat_offsets) == EXPECTED_COUNT (1)`: `total == 0` returns `(True, info)` (`"skipped_missing"`) if `allow_missing=True` else `(False, info)` (`"missing_signature"`); any other `total != EXPECTED_COUNT` returns `(False, info)` (`f"unexpected_count_{total}"`) without mutating `data`.
- `[sigill_handler.py:apply]` Does not mutate `data` when `verify=True` (succeeds iff `len(raw_offsets) == 0 and len(pat_offsets) == EXPECTED_COUNT`), when `len(raw_offsets) == 0` (`"already_patched"`), or when `dry_run=True` (`"dry_run"`).
- `[sigill_handler.py:PATCHES_ROOT]` Assumes `byte_replacer` is importable from `<PATCHES_ROOT>/engine`.

## Side Effects
- `[sigill_handler.py:sys.path]` Inserts `<PATCHES_ROOT>/engine` at index `0` of `sys.path` on module import.
- `[sigill_handler.py:apply]` Mutates the caller-supplied `data` bytearray in place (`data[off:off + len(REPLACEMENT)] = REPLACEMENT`) for each offset in `raw_offsets` when `total == EXPECTED_COUNT`, `verify=False`, `len(raw_offsets) > 0`, and `dry_run=False`.

## Verification
- Run: `bash modules/patches/tests/run_all.sh`
