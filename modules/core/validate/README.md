# Core Validate (`modules/core/validate`)

## Overview
Provides standalone Python validators for SHA-256 checksums and 64-bit little-endian AArch64 ELF binaries, together with an end-to-end 5-step verification pipeline covering the pristine `language_server` archive, glibc loader, runtime assembly, and generated manifest.

## Interface
- `[checksum_checker.py:calculate_sha256]` / `[checksum_checker.py:verify_checksum]`: Computes lowercase SHA-256 hex digest in 1 MiB binary chunks and verifies against `expected_hash.strip().lower()`, returning `True` or raising `ValueError`.
- `[checksum_checker.py:main]`: CLI entrypoint `checksum_checker.py <file_path> <expected_sha256>` (supports `-h`/`--help`).
- `[elf_header.py:validate_elf_header]` / `[elf_header.py:main]`: Validates `\x7fELF` magic, 64-bit `EI_CLASS == 2`, and little-endian `EI_DATA == 1`; CLI `elf_header.py <binary_path>`.
- `[elf_arch.py:EM_AARCH64]` / `[elf_arch.py:validate_aarch64]` / `[elf_arch.py:main]`: Constant `EM_AARCH64 = 183` and validator checking little-endian uint16 `e_machine` at bytes `18..20`; CLI `elf_arch.py <binary_path>`.
- `[elf_compat.py:ET_EXEC]` / `[elf_compat.py:ET_DYN]` / `[elf_compat.py:validate_executable_type]` / `[elf_compat.py:main]`: Constants `ET_EXEC = 2`, `ET_DYN = 3` and validator returning `"ET_EXEC"` or `"ET_DYN"` from little-endian uint16 `e_type` at bytes `16..18`; CLI `elf_compat.py <binary_path>`.
- `[verify_pipeline.py:CORE_DIR]` / `[verify_pipeline.py:run_pipeline]` / `[verify_pipeline.py:__main__]`: Resolves `modules/core` (`CORE_DIR`) and runs the 5-step E2E pipeline using sibling `unpack`, `validate`, `bundle`, and `manifest` modules with no CLI arguments.

## Invariants
- `[checksum_checker.py:calculate_sha256]` / `[checksum_checker.py:verify_checksum]`: Requires `os.path.isfile(file_path)` (else `FileNotFoundError`) and raises `ValueError` on SHA-256 mismatch.
- `[elf_header.py:validate_elf_header]`: Requires `>= 16` bytes, `b"\x7fELF"` magic, `EI_CLASS == 2`, and `EI_DATA == 1`, else raises `ValueError`.
- `[elf_arch.py:validate_aarch64]`: Requires `>= 20` bytes, `b"\x7fELF"` magic, and little-endian uint16 `e_machine == EM_AARCH64` (`183`), else raises `ValueError`.
- `[elf_compat.py:validate_executable_type]`: Requires `>= 18` bytes, `b"\x7fELF"` magic, and little-endian uint16 `e_type` in `{ET_EXEC, ET_DYN}`, else raises `ValueError`.
- `[checksum_checker.py:main]` / `[elf_header.py:main]` / `[elf_arch.py:main]` / `[elf_compat.py:main]`: Passing `-h`/`--help` as `sys.argv[1]` or omitting required positional args prints usage and exits with status `1`.
- `[verify_pipeline.py:run_pipeline]`: Requires `CORE_DIR/pristine/language_server.tar.gz` to exist, match hardcoded SHA-256 `6847c8e03731e801465a253cbb8c286ffa63c9ee4567dd7a8d5f26ccadaba390`, and detect as `"tar_gz"`; validates ELF header/arch/type on extracted `language_server` and header/arch on `CORE_DIR/glibc/ld-linux-aarch64.so.1`; requires `assemble.assemble_runtime` to emit a `manifest.json` passing `manifest_verifier.verify_manifest`.

## Side Effects
- `[checksum_checker.py:calculate_sha256]` / `[elf_header.py:validate_elf_header]` / `[elf_arch.py:validate_aarch64]` / `[elf_compat.py:validate_executable_type]`: Performs read-only binary filesystem reads (1 MiB chunks or 16–20 header bytes).
- `[verify_pipeline.py:sys.path]`: Prepends `CORE_DIR/{unpack,validate,manifest,bundle}` to `sys.path` at import time.
- `[verify_pipeline.py:run_pipeline]`: Creates a temporary sandbox via `tempfile.mkdtemp(prefix="agy_core_val_")`, extracts `language_server`, assembles a full test runtime and `manifest.json`, logs progress to `stdout`, and removes the temp directory in a `finally` block via `shutil.rmtree(..., ignore_errors=True)`.
- `[checksum_checker.py:main]` / `[elf_header.py:main]` / `[elf_arch.py:main]` / `[elf_compat.py:main]` / `[verify_pipeline.py:__main__]`: Writes diagnostics to `stdout`/`stderr` and terminates via `sys.exit(0)` or `sys.exit(1)`.

## Verification
- Run: `bash modules/core/tests/run_all.sh`
- Run: `python3 modules/core/validate/verify_pipeline.py`
