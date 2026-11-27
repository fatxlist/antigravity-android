# com.agy

## Overview
Root Java package for the Antigravity Android native host shell (`com.agy` / `com.agy.dev`), organizing twelve top-level packages covering the WebView UI, JavaScript bridge, core `language_server` supervisor, loopback System Gateway, Bionic userspace installer, runtime lifecycle orchestration, OAuth handoff, Shizuku execution, networking, theming, logging, and shared platform utilities.

## Subdirectories & Boundaries
- `bridge`: [./bridge/README.md](./bridge/README.md) - Token-gated `AndroidBridge` WebView `@JavascriptInterface` surface and engine/theme/UI adapter bridges.
- `core`: [./core/README.md](./core/README.md) - Foreground services (`CoreServerService`, `VoiceForegroundService`), `language_server` process supervisor, pinned-TLS readiness probe, stream/URL/event watchers, and interactive ConnectRPC `ApprovalActionReceiver`.
- `gateway`: [./gateway/README.md](./gateway/README.md) - Authenticated loopback-only `SystemGatewayServer` (`127.0.0.1`, default port `45160`), `GatewayDispatcher`, and domain handlers (`./gateway/handlers/README.md`).
- `install`: [./install/README.md](./install/README.md) - Bionic/Termux userspace prefix installer (`EnvInstaller`), manifest verification, tar extraction, overlay/`pkg`/bus staging, Termux path/shebang/symlink/hook rewriters, and seed package installation.
- `log`: [./log/README.md](./log/README.md) - Synchronized ring buffers for runtime logs (`LogRingBuffer`) and command audits (`CommandHistory`), secret redaction (`LogRedactor`), and crash snapshot persistence (`CrashRecorder`).
- `net`: [./net/README.md](./net/README.md) - IPv4-first Android DNS discovery (`DnsCollector`) and API 24+ default network change observation (`NetworkMonitor`).
- `oauth`: [./oauth/README.md](./oauth/README.md) - External-browser Google OAuth URL matching/delegation (`AuthUrlMatcher`, `CustomTabLauncher`, `OAuthManager`) and read-only standalone token detection (`OAuthTokenStore`).
- `runtime`: [./runtime/README.md](./runtime/README.md) - Phase 1 core/web extraction and manifest verification (`CoreInstaller`, `ManifestVerifier`), Phase 2 background env/seed scheduling (`EnvInstallScheduler`), DNS config updates, onboarding/MCP/skills seeding (`OnboardingSeeder`), shell/URL/torch bridge generation (`ShellBridgeInstaller`), and Camera2 flashlight control (`TorchReceiver`).
- `shell`: [./shell/README.md](./shell/README.md) - Rootless Shizuku `rish` binary discovery (`RishLocator`) and sanitized, timeout- and output-bounded elevated command execution (`RishExecutor`, `ShizukuBridge`).
- `theme`: [./theme/README.md](./theme/README.md) - Theme resolution/persistence (`ThemeManager`, `ThemeController`, `ThemePrefs`), edge-to-edge bar styling (`EdgeToEdge`, `ActivityPainter`), API 30+ IME inset animation (`ImeInsetsAnimator`), and dynamic launcher icon switching (`LauncherIconSwitcher`).
- `ui`: [./ui/README.md](./ui/README.md) - Host `MainActivity`, Share Sheet trampoline `ShareReceiverActivity`, and six child UI packages (`download`, `extra`, `launcher`, `lifecycle`, `splash`, `webview`).
- `util`: [./util/README.md](./util/README.md) - Shared file/asset/atomic I/O, POSIX permissions, canonical path and loopback origin validators, secret redaction, SHA-256 hashing, shell quoting, SAF tree-URI helpers, `AppFileProvider`, and external URI/activity dispatch.
