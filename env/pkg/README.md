# env/pkg

## Overview
Standalone POSIX/Bash + AWK package manager CLI (`pkg`) with an optional pure-Python 3 stdlib acceleration engine (`lib/pkg_core.py`), designed for Antigravity's Android Bionic userspace without external `dpkg` or `apt` dependencies. Orchestrates repository index updates, dependency-resolved parallel `.deb` downloads and installs, upgrades, removals, cache cleanup, index/DB queries, and offline self-tests by delegating core primitives to `./lib/`.

## Interface
- `[pkg:main]` CLI entrypoint (`#!/data/data/com.agy/files/usr/bin/bash`, `set -uo pipefail`) sourcing `util.sh`, `version.sh`, `index.sh`, `db.sh`, `fetch.sh`, `deb.sh`, and `resolver.sh` from `$PKG_SELF/lib` (`[pkg:PKG_LIB]`).
- `[pkg:update]` (`[pkg:cmd_update]`) Conditionally fetches (`curl -z "$gz"`) `Packages.gz` from `$PKG_REPOS`, writes `$PKG_INDEX/repo.<n>.{Packages,url}`, and rebuilds TSV indexes via `index_rebuild_all`.
- `[pkg:install]` (`[pkg:cmd_install]`) Accepts apt-compat flags (`-y`/`--yes`, `-q`/`--quiet`, `-f`/`--fix-broken`) plus `--keep-cache`, resolves dependencies via `pkg_core.py plan` (or `resolve` fallback), downloads `.deb` batches via `fetch_batch` (`curl -Z --http2`), and installs via `install_cached_deb` / `install_one`.
- `[pkg:upgrade]` (`[pkg:cmd_upgrade]`) Syncs dpkg DB, rebuilds indexes, batch-fetches outdated packages where repo version exceeds installed version, and upgrades via `install_cached_deb` / `install_one`.
- `[pkg:remove]` / `[pkg:uninstall]` (`[pkg:cmd_remove]`) Accepts no-op flags (`-y`/`--yes`, `-q`/`--quiet`), removes files listed in `$PKG_DB/<pkg>.files`, prunes empty parent dirs, and calls `db_remove`.
- `[pkg:clean]` (`[pkg:cmd_clean]`) Removes cached `.deb` archives in `$PKG_CACHE/*.deb` (also pruned post-install unless `--keep-cache` is passed).
- `[pkg:list]` / `[pkg:list-all]` (`[pkg:cmd_list]`), `[pkg:installed]` / `[pkg:list-installed]` (`[pkg:cmd_installed]`), `[pkg:info]` / `[pkg:show]` (`[pkg:cmd_info]`), `[pkg:search]` (`[pkg:cmd_search]`), `[pkg:why]` (`[pkg:cmd_why]`) Index and installed-DB query subcommands (`cmd_list` and `cmd_search` fast-path via `pkg_core.py`).
- `[pkg:selftest]` (`[pkg:cmd_selftest]`), `[pkg:version]`, `[pkg:help]` / `-h` / `--help` (`[pkg:cmd_help]`) Offline environment verification and CLI metadata commands.
- `[pkg:PKG_DEB_STRIP]` Reads optional `PKG_DEB_STRIP` (default `6`) plus `PREFIX`, `PKG_ROOT`, `PKG_VERSION`, `PKG_DB`, `PKG_CACHE`, `PKG_INDEX`, `PKG_ETC`, `PKG_CERT`, `PKG_REPOS`, `PKG_CHANNEL`, `PKG_COMPONENT`, and `PKG_ARCH` from `lib/util.sh` and `load_conf`.
- Child library contract: [./lib/README.md](./lib/README.md) — Python acceleration module (`pkg_core.py`) and Bash/AWK modules (`util.sh`, `db.sh`, `deb.sh`, `fetch.sh`, `index.sh`, `index.awk`, `version.sh`, `vercmp.awk`, `resolver.sh`).

## Invariants
- `[pkg:cmd_update]` Probes `PKG_REPOS` in preference order and stops on the first mirror that downloads with `--cacert "$PKG_CERT"` and gunzips cleanly; aborts via `die` if zero indexes succeed.
- `[pkg:install_one]` Requires a valid `index_lookup` hit with non-empty `Filename` (column 9), extracts and lists files in a single `.deb` scan via `deb_install_data` at strip depth `${PKG_DEB_STRIP:-6}`, prefers `.deb` control `Version` when present, and scopes `rewrite_prefix` strictly to extracted `pkg_files` (falling back to `$PREFIX` only when `pkg_files` is empty).
- `[pkg:install_one]` Exposes OpenJDK executables from `lib/jvm/java-*-openjdk/bin/*` into `$PREFIX/bin/` strictly for the 8-tool allowlist (`java`, `javac`, `jar`, `jarsigner`, `javap`, `javadoc`, `keytool`, `jps`) when executable (`-x`) and not already existing (`! -e`).
- `[pkg:install_one]` Generates `$PREFIX/../runtime/bin/<bname>` bridges only when `$PREFIX/../runtime/bin` exists and scoped strictly to direct `bin/*` entries of the newly installed package (never rescanning all of `$PREFIX/bin` and never overwriting existing regular files).
- `[pkg:cmd_install]` Rejects unknown `-*` flags, requires non-empty `index_tsvs` and at least one package name, verifies all uninstalled targets resolve via `index_lookup` or `index_provider`, aborts cleanly with zero installs if `resolve` fails, and skips already-installed packages (`db_exists`).
- `[pkg:cmd_upgrade]` Upgrades only packages present in `$PKG_DB/*.meta` with `deb_vercmp "$repo_ver" "$cur_ver" == 1`, calling `install_one` directly without re-running `resolve`.
- `[pkg:cmd_selftest]` Executes strictly offline and fails (`1`) if `$PREFIX/bin/sh` or `$PREFIX/bin/bash` cannot run `-c 'exit 0'`, `$PKG_CERT` is unreadable, or `$PKG_ETC/repo.conf` lacks a repo, channel, or arch (`$PREFIX/bin/git` is optional).

## Side Effects
- `[pkg:cmd_update]` Creates pkg directories, performs HTTP(S) `curl` downloads, writes `$PKG_CACHE/repo.<n>.Packages.gz` and `$PKG_INDEX/repo.<n>.{Packages,url}`, and rebuilds TSV indexes.
- `[pkg:install_one]` Downloads `.deb` archives into `$PKG_CACHE`, unpacks files into `$PREFIX`, rewrites Termux prefixes in place, writes `$PKG_DB/<pkg>.{meta,files}`, creates OpenJDK symlinks in `$PREFIX/bin`, and writes `chmod 755` `#!/system/bin/sh` wrappers in `$PREFIX/../runtime/bin/<bname>` (setting `PREFIX`, `LD_PRELOAD`, `LD_LIBRARY_PATH`, `PATH`, TLS vars, and stripping `netdns=go` from `GODEBUG`).
- `[pkg:cmd_install]`, `[pkg:cmd_upgrade]`, and `[pkg:cmd_installed]` Call `ensure_dirs` and `db_sync_dpkg` (importing dpkg status into `$PKG_DB`); `[pkg:cmd_info]` also invokes `ensure_dirs` and `index_rebuild_all`.
- `[pkg:cmd_remove]` Unlinks files recorded in `$PKG_DB/<pkg>.files` under `$PREFIX`, prunes empty parent directories, and deletes the DB entry (leaving post-`db_put` runtime bridges and OpenJDK symlinks untouched).
- `[pkg:cmd_clean]` Deletes `$PKG_CACHE/*.deb`; `[pkg:cmd_selftest]` spawns `$PREFIX/bin/sh` and `$PREFIX/bin/bash` without modifying disk state.

## Verification
- Run: `python3 -B -m unittest discover -s env/tests -v`
- Run: `bash env/build.sh assets && bash env/tests/invariant`
