# modules

## Overview
Decoupled build, runtime distribution, binary patching, IPC event bus, environment/CLI, and standalone lifecycle orchestration modules for Antigravity for Android (ARM64).

## Subdirectories & Boundaries
- `builder`: [./builder/README.md](./builder/README.md) - Gradle-free 8-phase Android APK build, asset staging, alignment, fail-closed signing, verification, and version-parity pipeline (`build_apk.sh`, `check_versions.py`).
- `core`: [./core/README.md](./core/README.md) - Pristine AArch64 `language_server` unpacking, ELF/SHA-256 validation, runtime bundle assembly, integrity manifests, POSIX shims, glibc libraries, CA certs, and seed state/skills.
- `env`: [./env/README.md](./env/README.md) - Dynamic Android package/path resolution, isolated process environment building, pre-flight/port diagnostics, structured logging/crash recording, command runner, authenticated gateway HTTP client, and CLI tools.
- `io`: [./io/README.md](./io/README.md) - Publish/subscribe JSON Event Bus (`EventBroker`, `SubscriberRouter`), FUSE-safe FIFO and UNIX socket inputs, async `stream_splitter.py` with line filters, and Android/file/socket output sinks.
- `patches`: [./patches/README.md](./patches/README.md) - Atomic, idempotent AArch64 Go ELF binary patch pipeline (`pipeline.py`) with ELF/pclntab scanners and six compatibility patch groups (`faccessat`, `fchmodat`, `sigill`, `resolv`, `failfast`, `pty_shell`).
- `runner`: [./runner/README.md](./runner/README.md) - Standalone runtime preparation (`prepare_runtime.py`), process-group core launch (`start_core.sh`), HTTP/PID readiness probing (`probe_server.py`), and PID-safe graceful shutdown (`stop_core.sh`).
