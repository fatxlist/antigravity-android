# Patch Engine

## Overview
Core binary-patching primitives for AArch64 Go ELF binaries: exact non-overlapping byte pattern matching and replacement (`byte_replacer.py`), 64-bit little-endian AArch64 ELF header and `.text`/executable-`PT_LOAD` discovery (`elf_scanner.py`), Go `pclntab` function lookup and AArch64 `MOVZ X0` instruction encoding (`pclntab_scanner.py`), session state/logging (`patch_context.py`), and schema-v1 JSON report generation (`reporter.py`).

## Interface
- `[byte_replacer.py:find_offsets]` — `find_offsets(data: Union[bytes, bytearray, memoryview], pattern: bytes) -> List[int]`: finds all non-overlapping offsets of `pattern` in `data`.
- `[byte_replacer.py:replace_exact]` — `replace_exact(data: bytes, target: bytes, replacement: bytes, expected_count: int, allow_missing: bool = False) -> Tuple[bool, bytes, int, int, List[int]]`: replaces exact `expected_count` matches of `target` with equal-length `replacement`, returning `(ok, data_out, count_raw, total_patched, offsets)`.
- `[elf_scanner.py:ELF_MAGIC]` / `[elf_scanner.py:EM_AARCH64]` — constants `b"\x7fELF"` and `183`.
- `[elf_scanner.py:is_valid_elf64]` — `is_valid_elf64(data: Union[bytes, bytearray, memoryview], base: int) -> bool`: validates a 64-bit little-endian AArch64 ELF header (`ET_EXEC`/`ET_DYN`) and program/section header table bounds at `base`.
- `[elf_scanner.py:find_elf_headers]` — `find_elf_headers(data: Union[bytes, bytearray, memoryview]) -> List[int]`: returns all offsets passing `is_valid_elf64`.
- `[elf_scanner.py:get_text_section]` — `get_text_section(data: Union[bytes, bytearray, memoryview], base: int) -> Optional[Tuple[int, int]]`: returns `(start, size)` of `.text` via section headers, or falls back to the first executable `PT_LOAD` segment.
- `[patch_context.py:PatchContext]` — dataclass with fields `target_path: str`, `dry_run: bool = False`, `verify: bool = False`, `allow_missing: bool = False`, `log_messages: list`, `results: Dict[str, Any]`, and method `[patch_context.py:PatchContext.log]` (`log(self, msg: str)`).
- `[pclntab_scanner.py:PCLN_MAGICS]` — Go `pclntab` magic tuple `(b"\xf1\xff\xff\xff", b"\xf2\xff\xff\xff", b"\xf0\xff\xff\xff")`.
- `[pclntab_scanner.py:encode_movz_x0]` — `encode_movz_x0(imm: int) -> bytes`: encodes little-endian 32-bit `MOVZ X0, #imm` (`0xD2800000 | (imm << 5)`).
- `[pclntab_scanner.py:find_pclntab]` — `find_pclntab(data: Union[bytes, bytearray, memoryview], base: int, region_end: int) -> Optional[Dict[str, Any]]`: locates the first valid Go `pclntab` in `[base, region_end)` and returns `{"pcln_base", "funcname_off", "pcln_off", "nfunc"}`.
- `[pclntab_scanner.py:find_function]` — `find_function(data: Union[bytes, bytearray, memoryview], pcln: Dict[str, Any], func_name: str) -> Optional[Tuple[int, int]]`: resolves `func_name` to `(entry_off, size)` with per-table caching.
- `[reporter.py:build_report]` — `build_report(core_path: str, sha256_initial: str, sha256_final: str, size: int, group_results: List[Dict[str, Any]], mode: Dict[str, bool], ok: bool) -> Dict[str, Any]`: constructs a schema-v1 patch report dictionary.

## Invariants
- `[byte_replacer.py:find_offsets]` Advances by `pos + len(pattern)` after each hit, returning strictly non-overlapping offsets.
- `[byte_replacer.py:replace_exact]` Asserts `len(target) == len(replacement)`; treats `count_raw == 0 and count_patched == expected_count` as already patched (`True`), permits `(0, 0)` only when `allow_missing=True`, and otherwise returns `False` without modifying `data`.
- `[elf_scanner.py:is_valid_elf64]` Requires `base + 64 <= len(data)`, `ELF_MAGIC`, 64-bit (`e_ident[4] == 2`), little-endian (`e_ident[5] == 1`), `e_machine == EM_AARCH64 (183)`, `e_type in (2, 3)`, valid program header bounds (`e_phentsize == 56`) when present, and valid section header bounds (`e_shentsize == 64`, `e_shnum <= 4096`, `e_shstrndx < e_shnum`) when present.
- `[elf_scanner.py:get_text_section]` Checks section headers for `b".text"` first, then falls back to the first `PT_LOAD` (`p_type == 1`) segment with `PF_X` (`p_flags & 1 != 0`) and `p_filesz > 0`, clamping returned size to `min(p_filesz, len(data) - seg_start)`.
- `[pclntab_scanner.py:find_pclntab]` Scans `PCLN_MAGICS` in tuple order advancing `pos += 4`, requiring `pos + 72 <= len(data)`, `min_lc == 4`, `ptr_size == 8`, `1 <= nfunc <= 1000000`, `ft` and `pt` inside `[base, region_end)`, and `pt + nfunc * 8 <= len(data)`.
- `[pclntab_scanner.py:find_function]` Matches exact NUL-terminated UTF-8 function names (`pos == ft or data[pos - 1] == 0`) within `[ft, pt if pt > ft else len(data))`; computes `size = next_entry - entry_off` for `i + 1 < nfunc` and `0` for the final table entry.
- `[reporter.py:build_report]` Fixes `"schema": 1` and formats `"generated_at"` as UTC `"%Y-%m-%dT%H:%M:%SZ"`.

## Side Effects
- `[patch_context.py:PatchContext.log]` Appends `msg` to `self.log_messages` and prints `f"  {msg}"` to `stdout`.
- `[pclntab_scanner.py:find_function]` Mutates the caller-provided `pcln` dictionary in place by storing cached lookup results under `pcln["_cache"][func_name]`.
- `[reporter.py:build_report]` Reads current UTC wall-clock time via `time.gmtime()`.

## Verification
- Run: `bash modules/patches/tests/run_all.sh`
