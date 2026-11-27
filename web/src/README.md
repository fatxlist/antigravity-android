# Web Source Modules (`web/src`)

## Overview
Modular first-party JavaScript source fragments assembled by `web/build.sh` according to `web/src.manifest` into IIFE bundles under `web/generated/`. The source tree separates early `<head>` bootstrap guards (`boot`), the ConnectRPC transport/shim runtime (`core`), and Android WebView UI/bridge patches (`patches`).

## Subdirectories & Boundaries
- `boot`: [./boot/README.md](./boot/README.md) - Early-boot standalone IIFEs (`boot/custom-elements-guard.js`, `boot/theme-bootstrap.js`) suppressing duplicate custom element registrations and applying initial light/dark theme classes before main bundle execution.
- `core`: [./core/README.md](./core/README.md) - Core WebView bridge runtime (`bridge.core.js` v1.2.1, `window.__agBridgeCore`) providing `onLine`/`Notification`/`process` shims, `__APP_CONFIG__` merging, CSRF sync, endpoint discovery, ConnectRPC/`/voice/health` `fetch` rewriting with retries, and host-resume stream recovery.
- `patches`: [./patches/README.md](./patches/README.md) - Feature-scoped Android WebView patches (`mobile_bridge`, `startup_ready`, `theme_sync`, `touch_model_menu`, `web_optimizations`) compiled into standalone bundles under `web/generated/patches/`.
