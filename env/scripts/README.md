# env/scripts

## Overview
Build-time Python 3 tooling for assembling, sanitizing, and verifying staged Android `env` asset bundles: `pack_bootstrap.py` converts `bootstrap-aarch64.zip` into a trimmed `bootstrap.tar.gz` with optional extra `.deb` injection, `sanitize_stage.py` removes Python bytecode caches and normalizes CRLF line endings to LF, and `make_manifest.py` generates or validates deterministic `manifest.json` files with a content-addressed `bundleId`.

## Interface
- `[make_manifest.py:SCHEMA]` Manifest schema constant (`1`); functions `sha256_file(path)`, `tree_digest(root)`, `read_seed_packages(root)`, `make_manifest(root)`, and CLI `main()` accepting positional `[make_manifest.py:root]` and optional flag `[make_manifest.py:--check]`.
- `[pack_bootstrap.py:TRIM_PREFIXES]` Pruned prefix tuple `('share/man/', 'share/doc/', 'share/info/', 'libexec/installed-tests/')`; functions `read_ar_members(deb_path)`, `extract_deb_into_bootstrap(deb_path, dest, trim, new_sl_lines)`, `pack_bootstrap(zip_path, out_tar_gz, trim=True, extra_debs=None)`, and CLI `main()` with `[pack_bootstrap.py:--zip]`, `[pack_bootstrap.py:--out]`, `[pack_bootstrap.py:--no-trim]`, and repeatable `[pack_bootstrap.py:--extra-deb]`.
- `[sanitize_stage.py:BINARY_PROBE_BYTES]` Constants `BINARY_PROBE_BYTES=8192`, `SKIP_DIR_NAMES=frozenset({'__pycache__'})`, `SKIP_FILE_SUFFIXES=('.pyc', '.pyo')`; functions `is_binary_bytes(data)`, `sanitize_tree(root)`, and CLI `main()` taking one or more positional `<path>` arguments.

## Invariants
- `[make_manifest.py:make_manifest]` Requires `<root>/bootstrap/bootstrap.tar.gz`, `<root>/overlay`, and `<root>/pkg` to exist (`FileNotFoundError` otherwise); computes `bundleId` as the SHA-256 of compact sorted-key UTF-8 JSON over `{schema, bootstrapSha256, bootstrapSymlinkCount, overlaySha256, pkgSha256, seedPackages}`.
- `[make_manifest.py:read_seed_packages]` Validates non-empty comment-stripped package names contain only alphanumeric or `+.-_` characters (`ValueError` otherwise), returning a sorted deduplicated list (`[]` if `seed-packages.txt` is absent).
- `[make_manifest.py:tree_digest]` Hashes only regular files in sorted POSIX relative path order (`<rel>\0<bytes>\0`), independent of mtimes and host paths; `[make_manifest.py:--check]` enforces exact formatted string equality against `<root>/manifest.json` (`indent=2`, `sort_keys=True`, trailing `\n`).
- `[pack_bootstrap.py:pack_bootstrap]` Requires existing `zip_path` containing `SYMLINKS.txt` (`target←link_name`), guards every extracted zip/deb path and symlink against escaping the staging root (`dest + os.sep`), prunes `TRIM_PREFIXES` unless `--no-trim` is set, and always deletes the temporary staging dir in `finally`.
- `[pack_bootstrap.py:read_ar_members]` Validates `ar` magic `b"!<arch>\n"` with 2-byte member alignment; `[pack_bootstrap.py:extract_deb_into_bootstrap]` requires both `control.tar*` and `data.tar*`, non-empty `Package`/`Version` fields (defaulting `Architecture` to `'aarch64'`), extracts only subpaths beneath the first `usr` component, avoids duplicate `Package:` stanzas in `var/lib/dpkg/status`, and packs gzip level-9 output atomically via `<out_tar_gz>.tmp` + `os.replace`.
- `[sanitize_stage.py:is_binary_bytes]` Treats any file with a NUL byte (`0x00`) in its first `8192` bytes as binary and leaves it unmodified.
- `[sanitize_stage.py:sanitize_tree]` Returns `(0, 0)` for non-existent paths, skips symlinks during directory walks, deletes `__pycache__` directories and `.pyc`/`.pyo` files during `os.walk`, and normalizes `\r\n` to `\n` in non-binary files; `[sanitize_stage.py:main]` returns exit code `1` if called without path arguments.

## Side Effects
- `[make_manifest.py:main]` Writes or overwrites `<root>/manifest.json` (unless `--check` is set) and prints status to stdout or exits via `SystemExit` on mismatch.
- `[pack_bootstrap.py:pack_bootstrap]` Creates/deletes a temporary `bootstrap_build_*` staging directory, creates output parent directories, writes `<out_tar_gz>.tmp`, atomically replaces `out_tar_gz` via `os.replace`, updates `var/lib/dpkg/status` and `var/lib/pkg/installed/<pkg>.{meta,files}` inside the staged tree, and logs progress to stdout.
- `[sanitize_stage.py:sanitize_tree]` Permanently deletes `__pycache__` directories and `.pyc`/`.pyo` files under traversed directories, rewrites non-binary CRLF files in place with LF endings, and prints sanitization counts to stdout.

## Verification
- Run: `python3 -B -m unittest discover -s env/tests -v`
- Run: `bash env/build.sh assets && bash env/tests/invariant`
