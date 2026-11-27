# env/bootstrap

## Overview
Holds the base Termux ARM64 bootstrap archive (`bootstrap-aarch64.zip`) and an optional legacy Python 3 CLI utility (`patch-prefix.py`, not invoked by `env/build.sh`) that rewrites compiled Termux prefixes inside `dpkg*` ZIP entries in place for a caller-supplied Android application ID.

## Interface
- `[patch-prefix.py:TERMUX]` Constant `b"com.termux"` (10-byte package ID replaced inside ELF `dpkg*` binaries).
- `[patch-prefix.py:TEXT_OLD]` Constant `b"/data/data/com.termux"` (prefix replaced inside non-ELF `dpkg*` text entries).
- `[patch-prefix.py:is_dpkg]` Function `is_dpkg(name) -> bool`; returns `True` when the entry basename starts with `"dpkg"`.
- `[patch-prefix.py:target_prefix]` Function `target_prefix(application_id) -> tuple[bytes, bytes]`; validates `application_id` and returns `(b"/data/data/" + ascii_id, slash_padded_10_byte_id)`.
- `[patch-prefix.py:patch]` Function `patch(name, data, application_id, text_prefix, binary_package) -> bytes`; rewrites matching `dpkg*` ELF or text entries and returns all other entries unmodified.
- `[patch-prefix.py:main]` Function `main(path, application_id) -> None`; streams `path` into a sibling temporary ZIP preserving `ZipInfo` metadata, atomically replaces `path`, and prints the patched entry count.
- `[patch-prefix.py:__main__]` CLI entry point `patch-prefix.py <bootstrap.zip> [application-id]` falling back to environment variable `[patch-prefix.py:AGY_APPLICATION_ID]` when `application-id` is omitted.

## Invariants
- `[patch-prefix.py:target_prefix]` `application_id` must match `^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$` and encode to `<= 10` ASCII bytes (`len(TERMUX)`), or `ValueError` is raised; shorter IDs are right-padded with `b"/"` to exactly 10 bytes to preserve ELF string lengths.
- `[patch-prefix.py:__main__]` No default application ID is assumed: requires `len(sys.argv) in (2, 3)` and a non-empty ID from `sys.argv[2]` or `[patch-prefix.py:AGY_APPLICATION_ID]`, else terminates via `sys.exit`.
- `[patch-prefix.py:is_dpkg]` Only ZIP entries whose basename (after the last `'/'`) starts with `"dpkg"` are patched; all other entries are copied byte-for-byte.
- `[patch-prefix.py:patch]` `dpkg*` entries starting with `b"\x7fELF"` substitute `b"com.termux"` with the 10-byte slash-padded `binary_package`; non-ELF `dpkg*` entries substitute `b"/data/data/com.termux"` with unpadded `b"/data/data/<application_id>"`.
- `[patch-prefix.py:main]` Preserves `ZipInfo` fields (`filename`, `date_time`, `compress_type`, `external_attr`, `internal_attr`, `create_system`, `flag_bits`), stages the temp file in `os.path.dirname(os.path.abspath(path))` for atomic `os.replace`, and unlinks any leftover temp file in `finally`.
- `[patch-prefix.py:__main__]` Catches `(OSError, ValueError, zipfile.BadZipFile)` from `main()` and exits via `sys.exit` with an error message.

## Side Effects
- `[patch-prefix.py:main]` Reads the ZIP archive at `path`, writes a rebuilt sibling temporary file via `tempfile.NamedTemporaryFile(delete=False)`, atomically replaces `path` via `os.replace`, unlinks the temp file on failure, and prints `"patched <changed> dpkg file(s) for <application_id> in <path>"` to stdout.
- `[patch-prefix.py:__main__]` Reads `[patch-prefix.py:AGY_APPLICATION_ID]` from process environment when `sys.argv[2]` is omitted and terminates the process via `sys.exit` on validation or I/O errors.

## Verification
- Run: `python3 -B -m unittest discover -s env/tests -v`
- Run: `bash env/build.sh assets && bash env/tests/invariant`
