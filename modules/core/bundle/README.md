# Core Runtime Bundle (`modules/core/bundle`)

## Overview
Stages the Antigravity Android AArch64 core runtime bundle (`bin/`, `lib/`, `certs/`, `seed/`, `etc/`) from a staged `language_server` binary, glibc shared libraries and network configs, CA certificates, POSIX shims, and seed assets, then writes `manifest.json`.

## Interface
- `[assemble.py:SCRIPT_DIR]` / `[assemble.py:MODULE_ROOT]`: Path constants resolving `modules/core/bundle` and `modules/core` (used as default `--module-root` and to locate `manifest/manifest_builder.py`).
- `[assemble.py:assemble_runtime]`: `assemble_runtime(core_bin_path: str, module_root: str, out_dir: str, dry_run: bool = False) -> dict` validates inputs (returning `{"dry_run": True}` when `dry_run=True`) or creates the runtime layout, runs `pack_binaries`, `pack_libraries`, `pack_certs`, `pack_seed`, and `manifest_builder.build_manifest(out_dir, "<out_dir>/manifest.json")`, returning the manifest dict.
- `[assemble.py:main]`: `argparse` CLI entrypoint accepting required `[assemble.py:--core-bin]`, required `[assemble.py:--out-dir]`, optional `[assemble.py:--module-root]` (default `MODULE_ROOT`), and optional `[assemble.py:--dry-run]` (`store_true`).
- `[layout.py:SUBDIRS]` / `[layout.py:create_layout]`: Tuple `("bin", "lib", "certs", "seed", "etc")` and `create_layout(base_out_dir: str) -> dict` returning a name-to-path mapping for all created subdirectories.
- `[pack_binaries.py:make_exec]` / `[pack_binaries.py:copy_text_lf]`: Helpers that OR `a+rx` (`0o555`) mode bits onto a file and copy text normalizing CRLF/CR to LF.
- `[pack_binaries.py:pack_binaries]` / `[pack_binaries.py:main]`: `pack_binaries(core_bin_path: str, loader_path: str, shims_dir: str, dest_bin_dir: str) -> list` installs `liblanguage_server.so`, `libloader.so`, `ld-linux-aarch64.so.1`, `xdg-open`, 7 browser delegates (`chrome`, `chromium`, `gnome-open`, `google-chrome`, `open`, `sensible-browser`, `x-www-browser`), `open_helper`, and optional `rish` + `rish_shizuku.dex`; CLI accepts `<core_bin> <loader_path> <shims_dir> <dest_bin_dir>` or `-h`/`--help`.
- `[pack_libraries.py:pack_libraries]` / `[pack_libraries.py:main]`: `pack_libraries(glibc_dir: str, dest_lib_dir: str) -> list` copies top-level `.so` entries into `dest_lib_dir` and `<glibc_dir>/etc` files into `<dest_lib_dir>/../etc`; CLI accepts `<glibc_dir> <dest_lib_dir>` or `-h`/`--help`.
- `[pack_certs.py:pack_certs]` / `[pack_certs.py:main]`: `pack_certs(certs_dir: str, dest_certs_dir: str) -> list` non-recursively copies regular files from `certs_dir` to `dest_certs_dir`; CLI accepts `<certs_dir> <dest_certs_dir>` or `-h`/`--help`.
- `[pack_seed.py:pack_seed]` / `[pack_seed.py:main]`: `pack_seed(seed_dir: str, dest_seed_dir: str) -> list` recursively copies regular seed files preserving directory structure and returns POSIX-slash relative paths; CLI accepts `<seed_dir> <dest_seed_dir>` or `-h`/`--help`.

## Invariants
- `[assemble.py:assemble_runtime]`: Expects `module_root` to contain `glibc/ld-linux-aarch64.so.1`, `glibc/`, `shims/`, `certs/`, and `seed/`; in `dry_run=True` mode, asserts `isfile` on `core_bin_path` and `loader_path` and `isdir` on `shims_dir`, `certs_dir`, and `seed_dir` without creating outputs.
- `[assemble.py:MODULE_ROOT]`: Always imports `manifest_builder` from `<MODULE_ROOT>/manifest` (relative to `__file__`, independent of `--module-root`) and writes `<out_dir>/manifest.json` after all pack steps succeed.
- `[layout.py:SUBDIRS]`: Runtime bundle layout always contains exactly the five subdirectories `bin`, `lib`, `certs`, `seed`, and `etc`.
- `[pack_binaries.py:pack_binaries]`: Raises `FileNotFoundError` if `core_bin_path` or `loader_path` does not exist; duplicates `loader_path` as both `libloader.so` and `ld-linux-aarch64.so.1`; prefers `shims/system/xdg_open.sh` over `shims/browser/xdg_open.sh`; creates browser delegates and `open_helper` as relative symlinks to `xdg-open` with LF-normalized script fallbacks on `OSError`; copies `rish_shizuku.dex` raw without LF normalization or executable bits.
- `[pack_libraries.py:pack_libraries]`: Raises `NotADirectoryError` if `glibc_dir` is not a directory; copies every sorted top-level entry whose name contains `.so` (including `ld-linux-aarch64.so.1` when present in `glibc_dir`) with `a+rx` permissions, and writes regular files from `<glibc_dir>/etc` to `<dest_lib_dir>/../etc` with LF endings and `a+r` permissions.
- `[pack_certs.py:pack_certs]`: Raises `NotADirectoryError` if `certs_dir` is not a directory; copies regular top-level files in sorted order and normalizes CRLF/CR to LF strictly for `.crt` and `.pem` files.
- `[pack_seed.py:pack_seed]`: Raises `NotADirectoryError` if `seed_dir` is not a directory; walks directories and files in deterministic sorted order, normalizes CRLF/CR to LF strictly for `.md`, `.pbtxt`, `.txt`, `.json`, `.sh`, and `.env` files, and returns forward-slash relative paths.
- `[pack_binaries.py:main]` / `[pack_libraries.py:main]` / `[pack_certs.py:main]` / `[pack_seed.py:main]`: Passing `-h`/`--help` or fewer than required positional arguments prints usage and exits with status `1`.

## Side Effects
- `[assemble.py:SCRIPT_DIR]`: Prepends `SCRIPT_DIR` and `<MODULE_ROOT>/manifest` to `sys.path` at import time.
- `[assemble.py:assemble_runtime]`: Creates runtime directories and files under `out_dir`, writes `<out_dir>/manifest.json` when `dry_run=False`, and prints progress to `stdout`.
- `[layout.py:create_layout]`: Creates `<base_out_dir>/{bin,lib,certs,seed,etc}` via `os.makedirs(..., exist_ok=True)`.
- `[pack_binaries.py:pack_binaries]`: Creates `dest_bin_dir`, writes binaries/scripts/DEX, unlinks pre-existing delegate paths (`os.unlink`), creates symlinks (`os.symlink`), and updates POSIX modes via `os.chmod`.
- `[pack_libraries.py:pack_libraries]`: Creates `dest_lib_dir` and sibling `<dest_lib_dir>/../etc`, writes `.so` libraries and LF-normalized config files, and updates POSIX modes via `os.chmod`.
- `[pack_certs.py:pack_certs]` / `[pack_seed.py:pack_seed]`: Creates destination directories and writes copied or LF-normalized files on disk.
- `[assemble.py:main]` / `[pack_binaries.py:main]` / `[pack_libraries.py:main]` / `[pack_certs.py:main]` / `[pack_seed.py:main]`: Writes status to `stdout`, errors to `stderr`, and terminates via `sys.exit`.

## Verification
- Run: `bash modules/core/tests/run_all.sh`
