# Resolv Redirect Patch Group

## Overview
In-place 16-byte binary patch (`resolv_redirect.py`) that rewrites the absolute path `/etc/resolv.conf` to the relative path `etc//resolv.conf` while filtering out substring matches embedded in longer paths.

## Interface
- `[resolv_redirect.py:TARGET]` — 16-byte constant `b"/etc/resolv.conf"`.
- `[resolv_redirect.py:REPLACEMENT]` — 16-byte constant `b"etc//resolv.conf"`.
- `[resolv_redirect.py:ADJACENT_REJECT]` — constant `b"/.\\"` listing boundary bytes that disqualify an adjacent `TARGET` match.
- `[resolv_redirect.py:EXPECTED_COUNT]` — constant `1` specifying the expected combined count of clean `TARGET` and `REPLACEMENT` occurrences.
- `[resolv_redirect.py:scan]` — `scan(data: Union[bytes, bytearray, memoryview]) -> Tuple[List[int], List[int], List[int]]`: returns `(clean, rejected, repl)` non-overlapping offset lists.
- `[resolv_redirect.py:apply]` — `apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]`: returns `(ok, info)` with `name="resolv_redirect"`, `expected`, `clean_count`, `rejected_count`, `replacement_count`, `clean_offsets`, `replacement_offsets`, and `status` (`"skipped_missing"`, `"missing_signature"`, `"unexpected_count_<total>"`, `"verified"`, `"unverified"`, `"already_patched"`, `"patched"`, or `"dry_run"`).

## Invariants
- `[resolv_redirect.py:TARGET]` / `[resolv_redirect.py:REPLACEMENT]` Both literals are 16 bytes, preserving `len(data)` during in-place replacement.
- `[resolv_redirect.py:scan]` Classifies any `TARGET` hit whose immediately preceding byte (`pos > 0`) or following byte (`pos + len(TARGET) < len(data)`) is in `[resolv_redirect.py:ADJACENT_REJECT]` as `rejected`, excluding it from `clean` and `total`.
- `[resolv_redirect.py:apply]` Enforces `total = len(clean) + len(repl) == EXPECTED_COUNT (1)`: returns `(True, info)` (`"skipped_missing"`) if `total == 0 and allow_missing`, `(False, info)` (`"missing_signature"`) if `total == 0 and not allow_missing`, and `(False, info)` (`f"unexpected_count_{total}"`) for any other `total != EXPECTED_COUNT`.
- `[resolv_redirect.py:apply]` Does not mutate `data` when `verify=True` (succeeds iff `len(clean) == 0 and len(repl) == EXPECTED_COUNT`), when `len(clean) == 0` (`"already_patched"`), or when `dry_run=True` (`"dry_run"`).

## Side Effects
- `[resolv_redirect.py:apply]` Mutates the caller-supplied `data` bytearray in place (`data[pos:pos + len(REPLACEMENT)] = REPLACEMENT`) for every `pos` in `clean` when `total == EXPECTED_COUNT`, `verify=False`, `len(clean) > 0`, and `dry_run=False`.

## Verification
- Run: `bash modules/patches/tests/run_all.sh`
