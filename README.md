<div align="center">

  <p>
    <b>English</b> &nbsp;&bull;&nbsp; <a href="README.ru.md">Русский</a>
  </p>

  <br/>

  <img src="docs/assets/logo.png" alt="Antigravity" width="88" height="88" style="border-radius: 20px;" />

  # Antigravity for Android

  **Autonomous AI development environment running natively on Android & ARM64.**

  [![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
  [![Arch](https://img.shields.io/badge/ARM64-v8.0--A%2B-blue?style=flat-square)](https://arm.com)
  [![Release](https://img.shields.io/badge/App-v2.6.0-blueviolet?style=flat-square)](https://github.com/fatxlist/antigravity-android/releases)
  [![Core](https://img.shields.io/badge/Core-v2.35.2-0A7EA4?style=flat-square)](modules/core)
  [![CI](https://github.com/fatxlist/antigravity-android/actions/workflows/build-and-release.yml/badge.svg)](https://github.com/fatxlist/antigravity-android/actions/workflows/build-and-release.yml)
  [![License](https://img.shields.io/badge/License-Apache_2.0-orange?style=flat-square)](LICENSE)

  <br/>

  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero_dark.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/hero_light.png">
    <img alt="Antigravity Interface" src="docs/assets/hero_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128,128,128,0.2);" />
  </picture>

</div>

---

### Overview

**Antigravity for Android** brings autonomous agentic coding directly to mobile hardware.

No PRoot, chroot, or virtualization overhead. Antigravity runs **bare-metal inside native Android userspace (Bionic)** alongside a glibc loader for the embedded `language_server`, unlocking the full I/O bandwidth of UFS storage and physical CPU cores. Driven by Gemini models, the agent inspects repositories, edits code across project trees, executes shell and browser tools, and runs tests on-device.

---

### Highlights

* **Core & Web v2.35.2:** Updated `language_server` core and web bundle with loopback sidecar cookie proxying, SSE stream resume/recovery, full BiDi/RTL typography support, and upstream v2.35.x harness/UI improvements.
* **Native MCP Servers:** Auto-seeded `agy-mcp-android` (device, UI, notifications, gateway tools) and `agy-mcp-browser` (`chrome_devtools`) in `~/.gemini/config/mcp_config.json` with bundled agent skills.
* **Mobile-First Agent UX:** Account Vault & Settings account switcher, clickable local paths & APK install action card, relaxed composer file attachments, broken video badge fallback, network reconnect banner, voice dictation with foreground voice service, interactive tool-approval and subagent notifications (`Approve` / `Deny` / inline reply / `Cancel`), extra keys bar, clipboard image paste, Android Share Sheet intake, local HTML/web preview dialog, Desktop Mode toggle, passive GitHub release update checker, and double-back exit confirmation.
* **Gateway & Micro-Daemon:** Multi-threaded `SystemGatewayServer` (`doctor`/`repair`, async `exec_*` tasks, clickable notifications), bounded `CrashRecorder` retention (`MAX_CRASH_REPORTS = 10`), and lightweight `agy-daemon` (`flock`-locked singleton, ConnectRPC task dispatch, cron/file/GitHub triggers, `resolv.conf` watchdog).
* **Universal ARMv8.0-A Support:** Transparent atomic & SIMD emulation (`liblse_compat.so` with `SA_ONSTACK`), seccomp syscall compatibility (`faccessat`/`fchmodat`), and length-preserving PTY shell retargeting.
* **Built-in `pkg` & Optional Shizuku:** Python-accelerated Termux package manager (`pkg_core.py`, `curl -Z --http2` parallel fetch, `--keep-cache`) plus optional ADB-level control (`uid=2000`) via Shizuku without root.

---

### Security & Hardening

* **Authenticated Local Gateway:** Per-install 256-bit secret stored with `0600` permissions in `files/logs/gateway.token`, enforced in constant time via `X-Agy-Gateway-Token` or `Authorization: Bearer` across all `/gateway/*` callers (`agy-mcp-android`, `agy-mcp-browser`, `agy-notify`, Python CLI/bridge).
* **Pinned Loopback TLS:** Approval callbacks and ConnectRPC notifications validate the bundled core certificate (`res/raw/agy_core_cert`) and loopback hostnames (`127.0.0.1` / `localhost`) via `CoreReadyProbe.configurePinnedLoopbackTls`.
* **Fail-Closed File Provider & Isolation:** `AppFileProvider` fails closed on missing context, removes broad app-data root exposure, and blocks access to `.gemini`, `gateway.token`, `core.state.json`, `approvals.json`, `shared_prefs`, and `databases`.
* **Per-Install Identity & Bounded Execution:** `OnboardingSeeder` replaces template UUIDs with a unique per-install `UUID`; `RishExecutor` enforces a 60s timeout, `destroyForcibly()`, and 256 KB output cap; `agy-upgrade` uses single-quoted Python heredocs with environment passing and `printf %q` quoting.
* **Fail-Closed Release Signing & Policy Integrity:** Account/region eligibility gate bypasses have been removed (`modules/patches` runs core compatibility patches only). Production release builds refuse to run without explicit `AGY_RELEASE_*` signing credentials (`--dev` or `--allow-debug-sign` required for debug keystores).

---

### Quick Start

1. **Download `antigravity.apk`** from [Releases](https://github.com/fatxlist/antigravity-android/releases) (or CI workflow artifacts).
2. **Grant Permissions:**
   * Allow **All Files Access** (`MANAGE_EXTERNAL_STORAGE`) so the workspace and downloads are accessible on Android 11+.
   * Allow **Notifications** on Android 13+ (`POST_NOTIFICATIONS`) for tool approval prompts and background tasks.
   * *(Optional)* Enable **Shizuku** for non-root ADB privileges (`uid=2000`).
3. **Install Runtimes** via built-in terminal or agent:
   ```bash
   pkg update && pkg install git python nodejs
   ```

---

### Building, Signing & Testing

#### APK Build

```bash
# Development debug APK (com.agy.dev, port 45158, debuggable):
bash build.sh --dev

# Standard release APK signed with the debug keystore (for local testing only):
bash build.sh --allow-debug-sign

# Production release APK (dist/antigravity.apk; fails closed unless signing env vars are set):
export AGY_RELEASE_KEYSTORE=/path/to/release.keystore
export AGY_RELEASE_KEY_ALIAS=release_alias
export AGY_RELEASE_KEY_PASS=...
export AGY_RELEASE_STORE_PASS=...
bash build.sh
```

> [!IMPORTANT]
> Tagged CI releases (`v*`) require GitHub Actions secrets `AGY_RELEASE_KEYSTORE_B64`, `AGY_RELEASE_KEY_ALIAS`, `AGY_RELEASE_KEY_PASS`, and `AGY_RELEASE_STORE_PASS`. Never distribute `--allow-debug-sign` builds as production releases. Release descriptions are sourced from `RELEASE_NOTES.md` after `modules/builder/check_versions.py` verifies version parity with `config/app.env`.

#### Automated Test Suites

```bash
# Run all CI suites + live E2E core test (requires full toolchain & Android/Linux env):
bash test_all_modules.sh

# Individual suites (also run automatically in GitHub Actions CI):
python3 modules/builder/check_versions.py
bash modules/core/tests/run_all.sh
bash modules/patches/tests/run_all.sh
bash modules/io/tests/run_all.sh
bash modules/env/tests/run_all.sh
bash modules/builder/tests/run_all.sh
python3 -B -m unittest discover -s env/tests -v
bash env/build.sh assets && bash env/tests/invariant
bash web/build.sh && bash web/tests/selftest.sh && node web/tests/test_web_patches.mjs
bash app/src/test/run_java_tests.sh
```

---

### Repository Structure

```text
antigravity-android/
├── .github/workflows/  # CI test suite, APK build/verification, and tagged releases
├── config/             # App constants (app.env, tools.env) and signing config template
├── modules/            # Decoupled subsystems
│   ├── core/           # Pristine core binary, glibc loader, shims, seed state, SHA-256 manifest
│   ├── patches/        # Core compatibility patcher (syscalls, SIGILL, resolv, failfast, PTY)
│   ├── io/             # High-performance I/O Bus: event broker, FIFO pipes, sockets
│   ├── env/            # Path resolver, authenticated bridge client, diagnostics, CLI
│   ├── builder/        # Gradle-free APK builder (javac, D8, aapt, align.py, fail-closed signer)
│   └── runner/         # Standalone runtime preparation, health probe, E2E test
├── app/                # Android host app
│   ├── src/main/       # Native splash, WebView, gateway, notifications, MCP/Shizuku bridges
│   └── src/test/       # Host JVM unit tests (UnitTests.java, run_java_tests.sh)
├── web/                # Local web frontend, SSE recovery, theme sync, BiDi, desktop mode toggle
├── env/                # Bionic userspace, Termux overlay, pkg manager, agy-daemon, liblse_compat
├── docs/assets/        # Screenshots and branding assets
├── build.sh            # Root APK build script
└── test_all_modules.sh # Full modular test runner
```

> [!TIP]
> Each subsystem in `modules/`, `app/`, `web/`, and `env/` includes its own `README.md` with deeper technical details.

---

## Local Interface & Invariants
- `[build.sh:exec]`: Root APK build wrapper forwarding all CLI arguments (`--dev`, `--allow-debug-sign`, `--fast`, `--prefix`, `--out`) to `modules/builder/build_apk.sh` under `set -euo pipefail`.
- `[test_all_modules.sh:PROJECT_ROOT]`: Master 11-stage verification runner executing `check_versions.py`, `modules/{core,patches,io,env,builder}/tests/run_all.sh`, `env/tests` unittest discovery, `env/build.sh assets` + `env/tests/invariant`, `web/build.sh` + `web/tests/selftest.sh` + optional `node web/tests/test_web_patches.mjs`, `app/src/test/run_java_tests.sh`, and `modules/runner/test_e2e.sh` under `set -euo pipefail`.
- Invariants: `[build.sh:SCRIPT_DIR]` resolves paths dynamically relative to `BASH_SOURCE[0]` without modifying source state directly; `[test_all_modules.sh:command -v node]` skips `web/tests/test_web_patches.mjs` if `node` is absent on `PATH`, while failing fast (`set -euo pipefail`) on any other stage failure.
- Side effects: `[build.sh:exec]` invokes `modules/builder/build_apk.sh` (wiping `build/` and `dist/` and producing signed APKs); `[test_all_modules.sh:PROJECT_ROOT]` changes `cwd` to `$PROJECT_ROOT`, rebuilds `env/dist/` and `web/generated/**`, creates temporary test sandboxes, and starts/stops a live standalone `language_server` during stage 11 (`modules/runner/test_e2e.sh`).

## Subdirectories & System Boundaries
- `app`: [./app/README.md](./app/README.md) - Android native host shell (`targetSdk 28`, `minSdk 24`): `MainActivity`/`ShareReceiverActivity` WebView UI, token-gated `AndroidBridge`, `CoreServerService` supervisor, pinned-TLS `CoreReadyProbe`, loopback `SystemGatewayServer` (`45160` release / `45161` dev), Bionic prefix installer (`EnvInstaller`), and Shizuku `RishExecutor`.
- `env`: [./env/README.md](./env/README.md) - Bionic ARM64 rootfs builder (`build.sh`), Termux bootstrap & prefix rewriter, `liblse_compat.so` SIGILL ARMv8.1-8.4 instruction emulator, `pkg` Bash/AWK package manager, `agy-daemon` scheduler, `agy-mcp-android`/`agy-mcp-browser` stdio MCP servers, `agy-notify`, and `agy-upgrade`.
- `modules`: [./modules/README.md](./modules/README.md) - Decoupled build and runtime subsystem: Gradle-free APK pipeline (`builder`), AArch64 runtime bundler/validator/shims (`core`), path/env/gateway client & CLI (`env`), pub/sub IPC bus (`io`), Go ELF binary patcher (`patches`), and standalone core lifecycle runner (`runner`).
- `web`: [./web/README.md](./web/README.md) - Local React SPA shell (`index.html`), manifest-driven bundle builder (`src.manifest`, `build.sh` -> `web/generated/**`), Desktop Mode `patch_main.py`, boot/core/mobile/theme/IME patches (`./web/src/README.md`), AudioWorklet/Myers diff/page automation scripts, and PDF.js/Shiki workers (`./web/workers/README.md`).

## Global Invariants
- **Single Source of Truth & Version Parity**: `[config/app.env:APP_VERSION_NAME]` and `[config/app.env:CORE_VERSION]` (`2.5.2` / `2.35.2`) drive `[modules/builder/01_gen_config.py:main]` and are enforced against `README.md`, `README.ru.md`, `RELEASE_NOTES.md`, and `refs/tags/v*` by `[modules/builder/check_versions.py:main]`.
- **Android W^X & ARMv8.0-A Compatibility**: `[app/src/main/AndroidManifest.xml:targetSdkVersion]` pins `targetSdkVersion="28"` so Android allows `exec` from `files/runtime/bin`; `[modules/patches/pipeline.py:ACTIVE_MODULES]` patches `language_server` (`faccessat`, `fchmodat`, `sigill_handler`, `resolv_redirect`, `crash_suppressor`, `pty_shell`) while `[env/liblse_compat/liblse_compat.c:sigill_handler]` emulates ARMv8.1-8.4 LSE/SIMD instructions on `SIGILL`.
- **Loopback Isolation, TLS Pinning & Token Auth**: `[app/src/main/java/com/agy/gateway/SystemGatewayServer.java:start]` binds strictly to `127.0.0.1` (`45160`/`45161`) with constant-time token verification against `0600` `logs/gateway.token`; `[app/src/main/java/com/agy/core/CoreReadyProbe.java:configurePinnedLoopbackTls]` pins loopback core HTTPS to `res/raw/agy_core_cert`; `[app/src/main/java/com/agy/bridge/AndroidBridge.java:matchesNativeBridgeToken]` gates sensitive WebView calls with a per-instance 256-bit token; `[app/src/main/java/com/agy/util/AppFileProvider.java:isAllowedPath]` blocks `.gemini`, `gateway.token`, `core.state.json`, `approvals.json`, `shared_prefs`, and `databases`.
- **No-Exec / FUSE Containment & Fail-Closed Signing**: `[modules/io/core_bus/event_broker.py:EventBroker.publish]` and `[modules/io/inputs/fifo_input.py:get_safe_pipe_path]` invoke `.sh` scripts via explicit shell interpreters and relocate FIFOs off `/storage/`/`/sdcard/`; `[modules/builder/build_apk.sh:KEYSTORE]` and `[modules/builder/build_apk.sh:ALLOW_DEBUG_SIGN]` refuse production release builds without explicit `AGY_RELEASE_*` credentials.

## Global Side Effects
- **Build & Packaging Outputs**: `build.sh` / `modules/builder/build_apk.sh`, `env/build.sh`, and `web/build.sh` wipe and regenerate `build/`, `dist/`, `env/dist/`, `env/bootstrap/bootstrap.tar.gz`, and `web/generated/**` on disk.
- **Device Runtime State & Processes**: At runtime on Android, `app/` and `env/` unpack `runtime/`, `web/`, and `usr/` into `<filesDir>`, mutate `.gemini/` state and `logs/` files, supervise `language_server` and `agy-daemon` processes, bind loopback TCP ports (`45157`/`45158`, `45160`/`45161`), and dispatch Android Intents, notifications, clipboard, wake locks, audio focus, and Camera2 torch actions.

## System Verification
- Run: `bash test_all_modules.sh`

---

<div align="center">
  <sub>Licensed under the <a href="LICENSE">Apache 2.0 License</a> &bull; Forked from <a href="https://github.com/werdio325-png/antigravity-android">werdio325-png/antigravity-android</a></sub>
</div>
