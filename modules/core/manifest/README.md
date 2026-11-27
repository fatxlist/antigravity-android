# Core Manifest (`modules/core/manifest`)

## Overview
Provides standalone Python 3 standard-library utilities and CLIs for streaming SHA-256 file hashing, generating schema-v1 directory integrity manifests (`manifest.json`), and verifying runtime directories against a manifest.

## Interface
- `[file_hasher.py:hash_file]`: `hash_file(file_path: str) -> str` computes the lowercase SHA-256 hex digest of a regular file using 1 MiB (`1024 * 1024`) streaming reads.
- `[file_hasher.py:main]` / `[file_hasher.py:-h/--help]`: CLI entrypoint `file_hasher.py <file_path>` printing the hex digest to `stdout` (exits `0` on success, `1` on usage/help/error).
- `[manifest_builder.py:hash_file]`: Standalone streaming 1 MiB SHA-256 helper `hash_file(path: str) -> str`.
- `[manifest_builder.py:build_manifest]`: `build_manifest(runtime_dir: str, out_file: str = None) -> dict` walks `runtime_dir`, hashes all files except root `manifest.json`, optionally writes formatted JSON to `out_file`, and returns `{"schema": 1, "generated_at": "<UTC ISO-8601>", "files": {"<rel_path>": "<sha256>"}}`.
- `[manifest_builder.py:main]` / `[manifest_builder.py:-h/--help]`: CLI entrypoint `manifest_builder.py <runtime_dir> [output_manifest_path]` (defaults output to `<runtime_dir>/manifest.json`).
- `[manifest_verifier.py:hash_file]`: Standalone streaming 1 MiB SHA-256 helper `hash_file(path: str) -> str`.
- `[manifest_verifier.py:verify_manifest]`: `verify_manifest(manifest_path: str, target_dir: str) -> bool` checks that every entry in `data.get("files", {})` exists as a regular file under `target_dir` with a matching SHA-256 digest.
- `[manifest_verifier.py:main]` / `[manifest_verifier.py:-h/--help]`: CLI entrypoint `manifest_verifier.py <manifest_json_path> <target_dir>` exiting `0` when all listed files match or `1` on mismatch/usage/error.

## Invariants
- `[file_hasher.py:hash_file]`: Requires `os.path.isfile(file_path)` (raises `FileNotFoundError` otherwise) and always returns a lowercase SHA-256 hex digest via 1 MiB chunks.
- `[manifest_builder.py:build_manifest]`: Requires `os.path.isdir(os.path.abspath(runtime_dir))` (raises `NotADirectoryError` otherwise); emits `schema == 1`, UTC `generated_at` (`%Y-%m-%dT%H:%M:%SZ`), POSIX `/` relative paths, sorted filenames per directory, and excludes only root-level `rel_path == "manifest.json"`.
- `[manifest_builder.py:build_manifest]`: When `out_file` is truthy, writes UTF-8 JSON with `indent=2`, `sort_keys=True`, and a trailing newline.
- `[manifest_verifier.py:verify_manifest]`: Requires `os.path.isfile(manifest_path)` (`FileNotFoundError`) and `os.path.isdir(target_dir)` (`NotADirectoryError`); compares hashes case-insensitively (`actual_hash != expected_hash.lower()`), checks all listed files without short-circuiting, ignores `schema`/`generated_at`, and does not flag unlisted extra files in `target_dir`.
- `[file_hasher.py:main]` / `[manifest_builder.py:main]` / `[manifest_verifier.py:main]`: Passing `-h`/`--help` as `sys.argv[1]` or omitting required positional arguments prints usage to `stdout` and exits with status `1`.

## Side Effects
- `[file_hasher.py:hash_file]`: Reads `file_path` in binary mode.
- `[manifest_builder.py:build_manifest]`: Walks `runtime_dir` reading every included file in binary mode; when `out_file` is truthy, creates missing parent directories via `os.makedirs(..., exist_ok=True)` and writes/overwrites `out_file`.
- `[manifest_verifier.py:verify_manifest]`: Reads `manifest_path` and each listed target file in binary mode, and writes `[FAIL] Missing file:` / `[FAIL] Corrupted file:` diagnostics to `stderr` on failures.
- `[file_hasher.py:main]` / `[manifest_builder.py:main]` / `[manifest_verifier.py:main]`: Prints status/usage to `stdout`, errors to `stderr`, and terminates via `sys.exit`.

## Verification
- Run: `bash modules/core/tests/run_all.sh`
