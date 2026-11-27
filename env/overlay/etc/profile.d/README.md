# env/overlay/etc/profile.d

## Overview
Contains `00-env.sh`, a POSIX shell profile snippet sourced by `/etc/profile` to initialize the `$PREFIX` userspace environment: core paths, OpenJDK discovery, shared-library/preload hooks, Termux and AGY identifiers, TLS/Git/locale defaults, Android Bionic Go DNS normalization, and ARMv8.0-A compiler flags.

## Interface
- `[00-env.sh:PREFIX]` Required input environment variable anchoring derived paths, libraries, TLS certificates, and Git directories.
- `[00-env.sh:HOME]`, `[00-env.sh:TMPDIR]`, `[00-env.sh:PATH]` Exports `HOME` (default `dirname "$PREFIX"`), `TMPDIR="$PREFIX/tmp"`, and prepends `$PREFIX/bin:/system/bin` to `PATH` (plus appending `$JAVA_HOME/bin` when discovered and absent).
- `[00-env.sh:JAVA_HOME]` Conditionally auto-discovered and exported when unset by probing `$PREFIX/lib/jvm/java-17-openjdk`, `java-21-openjdk`, then `java-*-openjdk` for executable `bin/java`.
- `[00-env.sh:LD_LIBRARY_PATH]` and `[00-env.sh:LD_PRELOAD]` Exports `LD_LIBRARY_PATH="$PREFIX/lib"` and sets `LD_PRELOAD` to `$PREFIX/lib/liblse_compat.so:$PREFIX/lib/libtermux-exec.so` when `liblse_compat.so` exists as a regular file, else `$PREFIX/lib/libtermux-exec.so`.
- `[00-env.sh:TERMUX__PREFIX]`, `[00-env.sh:TERMUX__ROOTFS]`, `[00-env.sh:TERMUX_APP__DATA_DIR]`, `[00-env.sh:AGY_APPLICATION_ID]` Exports Termux compatibility variables and derives `AGY_APPLICATION_ID` from two levels above `$PREFIX` when unset.
- `[00-env.sh:SSL_CERT_FILE]`, `[00-env.sh:CURL_CA_BUNDLE]`, `[00-env.sh:GIT_SSL_CAINFO]`, `[00-env.sh:OPENSSL_CONF]` Pins CA bundle paths to `$PREFIX/etc/tls/cert.pem` and OpenSSL config to `$PREFIX/etc/tls/openssl.cnf`.
- `[00-env.sh:GIT_CONFIG_NOSYSTEM]`, `[00-env.sh:GIT_ATTR_NOSYSTEM]`, `[00-env.sh:GIT_TEMPLATE_DIR]`, `[00-env.sh:GIT_EXEC_PATH]` Disables system Git config/attributes and directs Git templates and libexec into `$PREFIX`.
- `[00-env.sh:PAGER]`, `[00-env.sh:LANG]`, `[00-env.sh:TZ]` Exports `PAGER=less`, `LANG` (default `C.UTF-8`), and `TZ` (default `UTC`).
- `[00-env.sh:GODEBUG]` Unsets or strips `netdns=go` tokens so Bionic Go binaries use Android's native resolver.
- `[00-env.sh:CFLAGS]` and `[00-env.sh:CXXFLAGS]` Prepends `-march=armv8-a -moutline-atomics ` to both compiler flag variables.

## Invariants
- `[00-env.sh:PREFIX]` Expects `$PREFIX` to be set prior to sourcing and located two levels beneath the Android package directory when deriving `AGY_APPLICATION_ID`.
- `[00-env.sh:JAVA_HOME]` Preserves any pre-set non-empty `JAVA_HOME`; auto-discovery checks `java-17-openjdk` -> `java-21-openjdk` -> `java-*-openjdk` and requires `[ -x "$_agy_jvm/bin/java" ]`.
- `[00-env.sh:LD_PRELOAD]` Always places `$PREFIX/lib/liblse_compat.so` ahead of `$PREFIX/lib/libtermux-exec.so` when present (`-f`) and overwrites any prior `LD_PRELOAD` value.
- `[00-env.sh:GODEBUG]` Guarantees `netdns=go` is absent as a comma-delimited token after sourcing (unsetting `GODEBUG` when it was the sole token or stripping all `,netdns=go,` occurrences via `sed` loop `:a`).
- `[00-env.sh:PATH]` and `[00-env.sh:CFLAGS]` Prepending `$PREFIX/bin:/system/bin` to `PATH` and `-march=armv8-a -moutline-atomics` to `CFLAGS`/`CXXFLAGS` is unconditional (non-idempotent across repeated sourcing), whereas `$JAVA_HOME/bin` is deduplicated against `:$PATH:`.
- `[00-env.sh:_agy_jvm]` Temporary variables `_agy_jvm` and `_agy_pkg_dir` are explicitly unset before the script finishes.

## Side Effects
- `[00-env.sh:export]` Mutates and exports up to 24 environment variables in the sourcing shell and conditionally unsets/rewrites `[00-env.sh:GODEBUG]`.
- `[00-env.sh:mkdir]` Creates `$TMPDIR` and `$HOME` on disk (`mkdir -p "$TMPDIR" "$HOME" 2>/dev/null || true`).
- `[00-env.sh:dirname]` Spawns external helper processes (`dirname`, conditionally `basename`/`sed`, and `mkdir`) during shell startup.

## Verification
- Run: `python3 -B -m unittest discover -s env/tests -v`
- Run: `bash env/build.sh assets && bash env/tests/invariant`
