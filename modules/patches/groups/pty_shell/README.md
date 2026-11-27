# PTY Shell Patch Group

## Overview
Modular length-preserving 9-byte binary patch (`pty_shell.py`) that replaces `/bin/bash` with `/bin///sh` in place so the `language_server` PTY spawner operates on Android environments where `/bin/bash` does not exist.

## Interface
- `[pty_shell.py:TARGET]` — 9-byte constant `b"/bin/bash"`.
- `[pty_shell.py:REPLACEMENT]` — 9-byte constant `b"/bin///sh"`.
- `[pty_shell.py:EXPECTED_COUNT]` — constant `1` defining the expected combined count of clean and patched occurrences.
- `[pty_shell.py:scan]` — `scan(data: Union[bytes, bytearray, memoryview]) -> Tuple[List[int], List[int]]`: returns `(clean, repl)` non-overlapping byte offset lists for `TARGET` and `REPLACEMENT`.
- `[pty_shell.py:apply]` — `apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]`: returns `(ok, info)` with `name="pty_shell"`, `expected`, `clean_count`, `replacement_count`, `clean_offsets`, `replacement_offsets`, and `status` (`"skipped_missing"`, `"missing_signature"`, `"unexpected_count_<total>"`, `"verified"`, `"unverified"`, `"already_patched"`, `"patched"`, or `"dry_run"`).

## Invariants
- `[pty_shell.py:TARGET]` / `[pty_shell.py:REPLACEMENT]` Both literals are 9 bytes, preserving `len(data)` during in-place slice assignment.
- `[pty_shell.py:scan]` Advances search offset by `len(TARGET)` / `len(REPLACEMENT)` after each hit, producing non-overlapping offsets.
- `[pty_shell.py:apply]` Enforces `total = len(clean) + len(repl) == EXPECTED_COUNT (1)`: `total == 0` returns `(True, info)` (`"skipped_missing"`) if `allow_missing=True` else `(False, info)` (`"missing_signature"`); any other `total != EXPECTED_COUNT` returns `(False, info)` (`f"unexpected_count_{total}"`) without mutating `data`.
- `[pty_shell.py:apply]` When `verify=True` (with `total == EXPECTED_COUNT`), leaves `data` unmodified and succeeds (`"verified"`) iff `len(clean) == 0 and len(repl) == EXPECTED_COUNT`.
- `[pty_shell.py:apply]` Leaves `data` unmodified when `len(clean) == 0` (`"already_patched"`) or `dry_run=True` (`"dry_run"`).

## Side Effects
- `[pty_shell.py:apply]` Mutates the caller-supplied `data` bytearray in place (`data[pos:pos + len(REPLACEMENT)] = REPLACEMENT`) for each offset in `clean` when `total == EXPECTED_COUNT`, `verify=False`, `len(clean) > 0`, and `dry_run=False`.

## Verification
- Run: `bash modules/patches/tests/run_all.sh`
