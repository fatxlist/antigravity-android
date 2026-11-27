# env/pkg/lib

## Overview
Hybrid Bash/AWK and pure-Python 3 stdlib (`pkg_core.py`) runtime library implementing `pkg` internals with zero external `dpkg`/`apt` dependencies: fast Python core engine (`pkg_core.py`) with POSIX/AWK fallbacks for environment/TLS configuration and prefix rewriting (`util.sh`), flat-file DB & dpkg sync (`db.sh`), `.deb`/`ar` parsing and safe extraction (`deb.sh`), single and parallel HTTP/2 SHA-256-cached downloads (`fetch.sh`), Debian `Packages`-to-TSV indexing (`index.awk`, `index.sh`), `dpkg` version comparison (`vercmp.awk`, `version.sh`), and DFS dependency resolution (`resolver.sh`).

## Interface
- `[pkg_core.py:main]` Single-process Python 3 stdlib engine invoked via `python3 -B` exposing subcommands `vercmp`, `index-parse`, `index-rebuild`, `index-lookup`, `index-provider`, `search`, `sync-dpkg`, `resolve`, `plan`, `deb-members`, `deb-field`, `deb-validate`, `deb-files`, `deb-extract`, `stage-install`, and `rewrite-prefix`.
- `[util.sh:PKG_VERSION]` Constant `"0.1.0"`; requires exported `[util.sh:PREFIX]` and derives `PKG_ROOT` (`$PREFIX/var/lib/pkg`), `PKG_DB`, `PKG_CACHE`, `PKG_INDEX`, `PKG_ETC`, `PKG_CERT`, plus exports `SSL_CERT_FILE`, `CURL_CA_BUNDLE`, `OPENSSL_CONF`.
- `[util.sh:load_conf]` Helper functions `log`, `warn`, `die`, `ensure_dirs`, `rewrite_prefix [FILES_OR_DIRS...]`, and `load_conf` (defaults `PKG_REPOS`, `PKG_CHANNEL=stable`, `PKG_COMPONENT=main`, `PKG_ARCH=aarch64`, `PKG_DEB_STRIP=6`).
- `[db.sh:db_put]` Flat-file DB functions `db_meta`, `db_files`, `db_exists`, `db_get`, `db_put NAME VERSION ARCH` (reads file list on stdin), `db_remove`, `db_list`, `db_owner`, and `db_sync_dpkg`.
- `[deb.sh:ar_members]` Archive and `.deb` helpers: `ar_index`, `ar_slice`, `ar_members`, `ar_member`, `deb_decode` (`.xz`/`.gz`/`.zst`/`.bz2`/raw), `_deb_find_member`, `deb_member_name`, `deb_field`, `_deb_validate_and_list`, `deb_validate_data`, `deb_install_data`, `deb_extract_data`, and `deb_files`.
- `[fetch.sh:fetch]` Single and batch download helpers `human_size`, `fetch URL SHA256 OUT [SIZE] [GET_INDEX]`, and `fetch_batch` (curl `-Z --parallel-max 6` with conditional `--http2` and per-mirror fallback).
- `[index.awk:emit]` 11-column TSV parser (`Package\tVersion\tArchitecture\tEssential\tDepends\tPre-Depends\tProvides\tConflicts\tFilename\tSize\tSHA256`) invoked by `[index.sh:index_parse]`, `index_rebuild`, `index_rebuild_all`, `index_tsvs`, `index_lookup`, `index_provider`, `index_get`, and `index_best`.
- `[vercmp.awk:debcmp]` AWK implementation of `dpkg` version ordering (`-1`/`0`/`1`) wrapped by `[version.sh:deb_vercmp]`, `deb_ge`, and `deb_gt`.
- `[resolver.sh:resolve]` Dependency resolver exports `ver_satisfies`, `valid_package_name`, `choose_dep`, `resolve`, and `r_visit`.

## Invariants
- `[util.sh:PREFIX]` Sourcing `util.sh` aborts immediately if `PREFIX` is unset or empty; `[util.sh:rewrite_prefix]` escapes `\`, `&`, `@` for `sed`, skips binary files (`grep -I`) and symlinks, and reads newline-delimited stdin when invoked without path arguments on a non-TTY.
- `[db.sh:db_put]` Writes `.meta.tmp` and `.files.tmp` before atomic `mv`; `[db.sh:db_sync_dpkg]` imports only `Status:*installed` stanzas absent from `$PKG_DB` (defaulting missing `Architecture` to `aarch64` with empty `.files`); `[db.sh:db_owner]` fails on empty args and searches via fixed-string `grep -rlF`.
- `[deb.sh:ar_index]` and `[deb.sh:ar_members]` Scan 60-byte `ar` headers starting at offset `8` via `dd bs=1 skip="$off" count=60` with 2-byte alignment padding and extract slices via `ar_slice`; `deb_field`, `deb_files`, and `deb_install_data` resolve `_deb_find_member` in a single `ar_index` pass (`[deb.sh:deb_member_name]` enforces `^<kind>\.tar\.`).
- `[deb.sh:_deb_validate_and_list]` Mandatory pre-check for `deb_validate_data`, `deb_install_data`, `deb_extract_data`, and `deb_files` rejecting empty paths, leading `/`, `\`, missing `./`, `.`/`..` components, and paths outside `./data/data/<pkg>/files/usr/...`, stripping by component depth (`PKG_DEB_STRIP`, default `6`) and sorting unique relative paths when `LIST_OUT` is supplied.
- `[fetch.sh:fetch]` Treats cache hits as valid only when `$out` exists and matches a non-empty SHA-256; downloads to `$out.part` with `--cacert "$PKG_CERT"` (`curl -fL --retry 3 --connect-timeout 20`), deletes `$out.part` and calls `die` on mismatch/error, and atomically moves `$out.part` to `$out` on success.
- `[index.awk:emit]` Suppresses stanzas without `Package` and appends continuation lines only to `Depends`, `Pre-Depends`, `Provides`, and `Conflicts`; `[index.sh:index_lookup]` selects the highest version across `repo.*.tsv` via `deb_gt`, while `[index.sh:index_provider]` returns the first exact space-stripped comma-token match in `Provides`.
- `[vercmp.awk:debcmp]` Enforces `dpkg` epoch/upstream/revision precedence with `~` (`-1`) < empty (`0`) < letters < other ASCII and numeric digit-run comparison.
- `[resolver.sh:resolve]` Enforces `[resolver.sh:valid_package_name]` (`^[A-Za-z0-9][A-Za-z0-9+._-]*$`), detects cycles via `$work/doing/$name`, fails closed on any error before emitting post-order `$work/order`, and skips already-installed packages (`db_exists`).

## Side Effects
- `[util.sh:SSL_CERT_FILE]` Sourcing `util.sh` exports TLS environment variables and may exit if `PREFIX` is unset; `[util.sh:load_conf]` sources `$PKG_ETC/repo.conf`; `[util.sh:ensure_dirs]` creates `$PKG_DB`, `$PKG_CACHE`, `$PKG_INDEX`, and `$PKG_ROOT/tmp`; `[util.sh:rewrite_prefix]` rewrites matching text files in place via `sed -i -E`.
- `[db.sh:db_put]`, `[db.sh:db_remove]`, and `[db.sh:db_sync_dpkg]` Create, atomically replace, or remove `.meta` and `.files` records under `$PKG_DB`.
- `[deb.sh:deb_extract_data]` Extracts `data.tar.*` payload files onto the filesystem at the target destination.
- `[fetch.sh:fetch]` Issues network requests via `curl`, creates parent directories, writes/renames `$out.part` to `$out`, logs `Get:` lines to stdout and progress to stderr, and calls `die` on failure.
- `[index.sh:index_rebuild]` Writes/overwrites `.tsv` index files under `$PKG_INDEX`.
- `[resolver.sh:resolve]` Creates and cleans up temporary directory `$PKG_ROOT/tmp/resolve.$$` and prints warnings to stderr via `warn`.

## Verification
- Run: `python3 -B -m unittest discover -s env/tests -v`
- Run: `bash env/build.sh assets && bash env/tests/invariant`
