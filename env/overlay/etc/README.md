# env/overlay/etc

## Overview
Configuration and bundled agent assets overlaid into `$PREFIX/etc`. Contains login shell initialization (`profile` sourcing `profile.d/*.sh`), static networking and terminal defaults (`hosts`, `resolv.conf`, `motd`), `pkg` repository mirror configuration (`pkg/repo.conf`), TLS CA bundle and OpenSSL config (`tls/cert.pem`, `tls/openssl.cnf`), and seeded Antigravity rules and skills (`antigravity/AGENTS.md` and `antigravity/skills/`).

## Subdirectories & Boundaries
- `profile.d`: [./profile.d/README.md](./profile.d/README.md) - POSIX shell startup snippet (`00-env.sh`) exporting `$PREFIX` paths, OpenJDK discovery, `LD_LIBRARY_PATH`/`LD_PRELOAD`, Termux/AGY IDs, TLS/Git/locale defaults, `GODEBUG` DNS normalization, and ARMv8.0-A compiler flags.
- `antigravity/skills/android-browser`: [./antigravity/skills/android-browser/SKILL.md](./antigravity/skills/android-browser/SKILL.md) - Bundled skill documentation for headless Android WebView automation via `chrome_devtools` (`agy-mcp-browser`) and `android_browser_automate`.
- `antigravity/skills/android-daemon`: [./antigravity/skills/android-daemon/SKILL.md](./antigravity/skills/android-daemon/SKILL.md) - Bundled skill documentation for scheduling background tasks and watchers via `agy-daemon` and `android_schedule_task`.
- `antigravity/skills/android-device`: [./antigravity/skills/android-device/SKILL.md](./antigravity/skills/android-device/SKILL.md) - Bundled skill documentation for Android host control via `android-device` (`agy-mcp-android`), `SystemGatewayServer`, and Shizuku `rish`.
- Non-code configuration assets (no contract READMEs): `profile` (derives `PREFIX` if unset and sources `$PREFIX/etc/profile.d/*.sh`), `hosts` (`127.0.0.1 localhost`, `::1 ip6-localhost`), `motd` (`tlx environment`), `resolv.conf` (`options timeout:2 attempts:3 rotate`, nameservers `1.1.1.1` and `8.8.8.8`), `pkg/repo.conf` (ordered Termux mirrors `PKG_REPOS`, `PKG_CHANNEL=stable`, `PKG_COMPONENT=main`, `PKG_ARCH=aarch64`), `tls/` (`cert.pem`, `openssl.cnf`), and `antigravity/AGENTS.md` (seeded global Android agent rules).
