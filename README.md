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
  [![Release](https://img.shields.io/badge/App-v2.4.1-blueviolet?style=flat-square)](https://github.com/fatxlist/antigravity-android/releases)
  [![Core](https://img.shields.io/badge/Core-v2.34.0-0A7EA4?style=flat-square)](modules/core)
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

* **Core & Web v2.34.0:** Updated `language_server` core and web bundle with loopback sidecar cookie proxying, SSE stream resume/recovery, and full BiDi/RTL typography support.
* **Native MCP Servers:** Auto-seeded `agy-mcp-android` (device, UI, notifications, gateway tools) and `agy-mcp-browser` (`chrome_devtools`) in `~/.gemini/config/mcp_config.json` with bundled agent skills.
* **Mobile-First Agent UX:** Voice dictation with foreground voice service, interactive tool-approval and subagent notifications (`Approve` / `Deny` / inline reply / `Cancel`), clickable notifications returning to chat, extra keys bar, clipboard image paste, Android Share Sheet intake, local HTML/web preview dialog, Desktop Mode toggle, and double-back exit confirmation.
* **Gateway & Micro-Daemon:** Multi-threaded `SystemGatewayServer` (`doctor`/`repair`, async `exec_*` tasks, clickable notifications) and lightweight `agy-daemon` with ConnectRPC task dispatch, cron/file/GitHub triggers, and `resolv.conf` watchdog.
* **Universal ARMv8.0-A Support:** Transparent atomic & SIMD emulation (`liblse_compat.so` with `SA_ONSTACK`), seccomp syscall compatibility (`faccessat`/`fchmodat`), and length-preserving PTY shell retargeting.
* **Built-in `pkg` & Optional Shizuku:** Fast Termux package manager (`git`, `python`, `nodejs`, `clang`, etc.) plus optional ADB-level control (`uid=2000`) via Shizuku without root.

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
> Tagged CI releases (`v*`) require GitHub Actions secrets `AGY_RELEASE_KEYSTORE_B64`, `AGY_RELEASE_KEY_ALIAS`, `AGY_RELEASE_KEY_PASS`, and `AGY_RELEASE_STORE_PASS`. Never distribute `--allow-debug-sign` builds as production releases.

#### Automated Test Suites

```bash
# Run all 6 modular subsystems + live E2E core test (requires full toolchain & Android/Linux env):
bash test_all_modules.sh

# Individual suites (also run automatically in GitHub Actions CI):
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

<div align="center">
  <sub>Licensed under the <a href="LICENSE">Apache 2.0 License</a> &bull; Forked from <a href="https://github.com/werdio325-png/antigravity-android">werdio325-png/antigravity-android</a></sub>
</div>
