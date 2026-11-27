# Syscalls Patch Group

## Overview
AArch64 Go ELF syscall retargeting modules (`faccessat.py`, `fchmodat.py`) that locate `syscall.faccessat2` and `syscall.fchmodat2` through `elf_scanner` and `pclntab_scanner` and rewrite their Android-seccomp-trapped 4-byte `MOVZ X0, #imm` syscall numbers in place (`439 -> 48` and `452 -> 53`).

## Interface
- `[faccessat.py:SCRIPT_DIR]` / `[faccessat.py:PATCHES_ROOT]` — module paths used to resolve `modules/patches/engine`.
- `[faccessat.py:FACCESSAT2_IMM]` / `[faccessat.py:FACCESSAT_IMM]` / `[faccessat.py:FUNC_NAME]` — constants `439`, `48`, and `"syscall.faccessat2"`.
- `[faccessat.py:patch_in_body]` — `patch_in_body(body: bytes) -> Tuple[str, int, int, int]`: locates `MOVZ X0, #439` vs `MOVZ X0, #48` in a function body, returning `(status, rel_off, c_old, c_new)` with `status` in `{"patch", "already", "ambiguous"}`.
- `[faccessat.py:apply]` — `apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]`: scans all embedded ELF regions and returns `(ok, info)` (`name="faccessat"`, `patched_sites`, `already_sites`, and `status`, or `error="no_elf_found"`).
- `[fchmodat.py:SCRIPT_DIR]` / `[fchmodat.py:PATCHES_ROOT]` — module paths used to resolve `modules/patches/engine`.
- `[fchmodat.py:FCHMODAT2_IMM]` / `[fchmodat.py:FCHMODAT_IMM]` / `[fchmodat.py:FUNC_NAME]` — constants `452`, `53`, and `"syscall.fchmodat2"`.
- `[fchmodat.py:patch_in_body]` — `patch_in_body(body: bytes) -> Tuple[str, int, int, int]`: locates `MOVZ X0, #452` vs `MOVZ X0, #53` in a function body, returning `(status, rel_off, c_old, c_new)` with `status` in `{"patch", "already", "ambiguous"}`.
- `[fchmodat.py:apply]` — `apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]`: scans all embedded ELF regions and returns `(ok, info)` (`name="fchmodat"`, `patched_sites`, `already_sites`, and `status`, or `error="no_elf_found"`).

## Invariants
- `[faccessat.py:patch_in_body]` / `[fchmodat.py:patch_in_body]` Return `"patch"` iff `c_old == 1 and c_new == 0`, `"already"` iff `c_old == 0 and c_new >= 1`, and `"ambiguous"` with offset `-1` otherwise.
- `[faccessat.py:apply]` / `[fchmodat.py:apply]` Return `(False, {"name": ..., "error": "no_elf_found"})` when `elf_scanner.find_elf_headers(data)` is empty; bound each ELF's `pclntab` scan to `[base, next_base)` (or `len(data)` for the last ELF); skip ELFs without `.text` or `pclntab`; set `ok = False` if `size <= 0` or `patch_in_body` returns `"ambiguous"`.
- `[faccessat.py:apply]` Sets `ok = False` when `syscall.faccessat2` is missing in the primary ELF (`base == 0`) and `allow_missing=False`, while skipping missing symbols in non-zero base ELFs.
- `[fchmodat.py:apply]` Skips any ELF lacking `syscall.fchmodat2` without setting `ok = False` regardless of `base` or `allow_missing` (`allow_missing` is accepted for signature parity but unused).
- `[faccessat.py:apply]` / `[fchmodat.py:apply]` Never mutate `data` when `verify=True` or `dry_run=True`; when `verify=True`, return `v_ok = ok and (len(info["already_sites"]) >= 1)` (`"verified"` / `"unverified"`).

## Side Effects
- `[faccessat.py:sys.path]` / `[fchmodat.py:sys.path]` Insert `<PATCHES_ROOT>/engine` at index `0` of `sys.path` on module import.
- `[faccessat.py:apply]` / `[fchmodat.py:apply]` Overwrite 4 bytes (`data[file_off:file_off + 4]`) in place with `pclntab_scanner.encode_movz_x0(FACCESSAT_IMM)` or `encode_movz_x0(FCHMODAT_IMM)` when `status == "patch"`, `verify=False`, and `dry_run=False`.

## Verification
- Run: `bash modules/patches/tests/run_all.sh`
