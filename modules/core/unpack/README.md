# Core Unpack (`modules/core/unpack`)

## Overview
Provides standalone Python utilities and CLIs to detect core archive/ELF formats, extract or copy a target core binary (`language_server`) to a destination path, and apply additive POSIX read/execute permissions.

## Interface
- `[archive_detector.py:detect_type]`: `detect_type(file_path: str) -> str` inspects magic bytes and returns `"not_found"`, `"tar_gz"`, `"elf"`, or `"unknown"`.
- `[archive_detector.py:main]` / `[archive_detector.py:-h/--help]`: CLI entrypoint `archive_detector.py <file_path>` printing the detected type and exiting `0` for `"tar_gz"`/`"elf"` or `1` otherwise (including `-h`/`--help`).
- `[extractor.py:extract_core]`: `extract_core(src_path: str, dest_path: str, binary_name: str = "language_server") -> bool` extracts the matching basename member from a gzip tar archive or copies a raw ELF file to `dest_path`, returning whether `dest_path` is a non-empty file.
- `[extractor.py:main]` / `[extractor.py:-h/--help]`: CLI entrypoint `extractor.py <source_path> <dest_binary_path> [binary_name]` defaulting `binary_name` to `"language_server"`.
- `[permissions.py:make_executable]` / `[permissions.py:make_readable]`: Functions adding `a+rx` (`S_IXUSR|S_IXGRP|S_IXOTH|S_IRUSR|S_IRGRP|S_IROTH`) or `a+r` (`S_IRUSR|S_IRGRP|S_IROTH`) mode bits to `file_path`.
- `[permissions.py:main]` / `[permissions.py:--exec]` / `[permissions.py:--read]` / `[permissions.py:-h/--help]`: CLI entrypoint `permissions.py [--exec|--read] <path>` defaulting to `--exec` when only `<path>` is provided.

## Invariants
- `[archive_detector.py:detect_type]`: Returns `"not_found"` if `os.path.isfile(file_path)` is false; classifies solely from the first 4 bytes (`b"\x1f\x8b"` -> `"tar_gz"`, `b"\x7fELF"` -> `"elf"`, else `"unknown"`).
- `[extractor.py:extract_core]`: Raises `FileNotFoundError` if `src_path` does not exist and `ValueError` for magic headers other than `b"\x1f\x8b"` or `b"\x7fELF"`; selects the first archive member with `os.path.basename(m.name) == binary_name` (raising `ValueError` if missing) and returns `True` iff `dest_path` is an existing file with size `> 0`.
- `[permissions.py:make_executable]` / `[permissions.py:make_readable]`: Require `os.path.exists(file_path)` (else `FileNotFoundError`) and preserve existing `st_mode` bits via bitwise OR.
- `[permissions.py:main]`: With two or more arguments, `--exec` invokes `make_executable` and any other `sys.argv[1]` (including `--read`) invokes `make_readable`.
- `[archive_detector.py:main]` / `[extractor.py:main]` / `[permissions.py:main]`: Passing `-h`/`--help` as `sys.argv[1]` or omitting required positional arguments prints usage and exits with status `1`.

## Side Effects
- `[archive_detector.py:detect_type]`: Reads up to 4 bytes from `file_path` in binary mode.
- `[extractor.py:extract_core]`: Creates missing parent directories for `os.path.abspath(dest_path)` via `os.makedirs(..., exist_ok=True)`, reads `src_path`, and creates or overwrites `dest_path`.
- `[permissions.py:make_executable]` / `[permissions.py:make_readable]`: Mutates POSIX file permissions on `file_path` via `os.chmod`.
- `[archive_detector.py:main]` / `[extractor.py:main]` / `[permissions.py:main]`: Writes status/usage to `stdout`, errors to `stderr`, and terminates via `sys.exit`.

## Verification
- Run: `bash modules/core/tests/run_all.sh`
