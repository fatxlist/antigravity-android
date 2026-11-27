# native/agy

## Overview
Native Bionic ARM64 (`aarch64-linux-android`) Rust multicall binary (`agy`) replacing shell/Python hot paths for `agy-fix-paths` and `agy-notify`, with fallback compatibility to the existing bash/Python scripts.

## Interface
- `agy version` / `--version` / `-V`: prints binary version.
- `agy fix-paths [--dry-run] [PATH...]` (and multicall `argv[0]=agy-fix-paths`): single-pass rewrite of `/data/(data|user/[0-9]+)/[^/]*/files/usr` and line-1 `#!/usr/bin/env [-S]` shebangs to `$PREFIX`, skipping symlinks and binary files (NUL in first 8192 bytes) and atomically replacing modified files while preserving mode and owner.
- `agy notify [post|cancel|list] ...` (and multicall `argv[0]=agy-notify`): resolves gateway port (`AGY_GATEWAY_PORT -> $HOME/logs/gateway.port -> $HOME/logs/core.state.json -> 45161/45160`) and token (`AGY_GATEWAY_TOKEN -> AGY_GATEWAY_TOKEN_FILE -> $HOME/logs/gateway.token`), posts Python-identical JSON envelopes (`id = (crc32(tag) & 0x7fffffff) || 1`) to `http://127.0.0.1:<port>/gateway` with `X-Agy-Gateway-Token`, and falls back to `rish` using strict POSIX single-quoting (`'...'`).

## Invariants
- Targets `aarch64-linux-android` (Bionic, API 24, `max-page-size=16384`) — never musl or glibc.
- Compiled release binary (`env/overlay/bin/agy`) is staged during APK build by `modules/builder/04b_build_native.sh` and excluded from git via `.gitignore`.
- `[profile.release]` compiles with `opt-level = "z"`, `lto = true`, `codegen-units = 1`, `panic = "abort"`, `strip = true`.

## Verification
- Run: `cargo test --manifest-path native/agy/Cargo.toml`
- Run: `REQUIRE_NATIVE=1 bash modules/builder/04b_build_native.sh`
