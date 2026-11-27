# Drift Report (2026-11-27)

## README.md

- Line 164 (`## Global Invariants`): cites `[modules/builder/build_apk.sh:signing_policy]`, a descriptive label rather than a code identifier in `modules/builder/build_apk.sh` (release signing policy uses `KEYSTORE`, `KEY_ALIAS`, `KEY_PASS`, `STORE_PASS`, `ALLOW_DEBUG_SIGN`, `AGY_SIGN_KEY_PASS`, and `AGY_SIGN_STORE_PASS` at `modules/builder/build_apk.sh:75-101`).

## app/README.md

- Lines 38 & 61 (`§1` Mermaid diagram): depicts `agy-daemon` (`AGD`) communicating via `JSON over HTTP` directly with `SystemGatewayServer` (`127.0.0.1:45160`/`45161`). In reality, `env/overlay/lib/daemon/executor.py:78-88` (and `scheduler.py:37-39`) spawns the `agy-notify` CLI subprocess, which posts JSON to `http://127.0.0.1:${AGY_GATEWAY_PORT:-45159}/gateway/notify` without reading `logs/gateway.port` (`env/overlay/bin/agy-notify:78,108-110`), while AI prompt dispatch (`executor.py:226-267`) calls `language_server` directly over HTTPS ConnectRPC using `logs/core.state.json`.
- Lines 43–50 (`§1` Mermaid `Gateway_Layer`): lists 6 handlers (`NotificationHandler`, `BrowserHandler`, `PowerHandler`, `ClipboardHandler`, `DeviceHandler`, `ExecHandler`), omitting `DoctorHandler` and `WebBrowserAutomationHandler` wired in `app/src/main/java/com/agy/gateway/GatewayDispatcher.java:21-25,34,38`.
- Lines 118–131 (`§2.3` System Gateway table): documents only `notify`, `open_url`, `wakelock`, `clipboard`/`clipboard_set`, `system_info`, `vibrate`, `toast`, `exec`, and GET endpoints `/gateway/status`, `/gateway/logs`, `/gateway/commands`, `/gateway/crash`, omitting POST commands `subagent_notify`, `approval_poll`, `voice_service`, `browser_auto`, `torch`, `exec_start`, `exec_status`/`exec_poll`, `exec_cancel`, `exec_list`/`tasks`, `command_get`, `logs`, `commands`, `crash`, `status`/`health`, `doctor`, `repair` (`GatewayDispatcher.java:49-139`) and GET endpoints `/gateway/health`, `/gateway/approval`, `/gateway/voice/status` (`SystemGatewayServer.java:244-261`).
- Line 137 (`§2.4` JavaScript Bridge security): states `blobBridgeToken` protects `onUiReady`, `onUiError`, and `saveBlob`, omitting 9 additional token-gated `@JavascriptInterface` methods calling `matchesNativeBridgeToken(token)` in `app/src/main/java/com/agy/bridge/AndroidBridge.java:108-299` (`setSurfaceColor`, `openLocalPreview`, `openRemotePortal`, `startVoiceDictation`, `stopVoiceDictation`, `startVoiceService`, `stopVoiceService`, `setVoiceMuted`, `readClipboardImage`).
- Lines 201–209 (`## Global Invariants` & `## Global Side Effects`): 25 source citations omit the `src/main/java/com/agy/` path prefix relative to `app/` (e.g., `[core/CoreReadyProbe.java:configurePinnedLoopbackTls]` instead of `[src/main/java/com/agy/core/CoreReadyProbe.java:configurePinnedLoopbackTls]`).

## app/src/README.md

No drift detected

## app/src/main/README.md

No drift detected

## app/src/main/java/README.md

No drift detected

## app/src/main/java/com/README.md

No drift detected

## app/src/main/java/com/agy/README.md

No drift detected

## app/src/main/java/com/agy/bridge/README.md

No drift detected

## app/src/main/java/com/agy/core/README.md

No drift detected

## app/src/main/java/com/agy/gateway/README.md

No drift detected

## app/src/main/java/com/agy/gateway/handlers/README.md

No drift detected

## app/src/main/java/com/agy/install/README.md

No drift detected

## app/src/main/java/com/agy/log/README.md

No drift detected

## app/src/main/java/com/agy/net/README.md

No drift detected

## app/src/main/java/com/agy/oauth/README.md

No drift detected

## app/src/main/java/com/agy/runtime/README.md

No drift detected

## app/src/main/java/com/agy/shell/README.md

No drift detected

## app/src/main/java/com/agy/theme/README.md

No drift detected

## app/src/main/java/com/agy/ui/README.md

No drift detected

## app/src/main/java/com/agy/ui/download/README.md

No drift detected

## app/src/main/java/com/agy/ui/extra/README.md

No drift detected

## app/src/main/java/com/agy/ui/launcher/README.md

No drift detected

## app/src/main/java/com/agy/ui/lifecycle/README.md

No drift detected

## app/src/main/java/com/agy/ui/splash/README.md

No drift detected

## app/src/main/java/com/agy/ui/webview/README.md

No drift detected

## app/src/main/java/com/agy/util/README.md

No drift detected

## env/README.md

No drift detected

## env/bootstrap/README.md

No drift detected

## env/liblse_compat/README.md

No drift detected

## env/overlay/README.md

No drift detected

## env/overlay/bin/README.md

No drift detected

## env/overlay/etc/README.md

No drift detected

## env/overlay/etc/profile.d/README.md

No drift detected

## env/overlay/lib/README.md

No drift detected

## env/overlay/lib/daemon/README.md

No drift detected

## env/pkg/README.md

No drift detected

## env/pkg/lib/README.md

No drift detected

## env/scripts/README.md

No drift detected

## modules/README.md

No drift detected

## modules/builder/README.md

- Line 180 (`§6.6 Неработающий флаг --fast`): states `--fast` is not passed to child scripts and `build/` is deleted; `modules/builder/build_apk.sh:31` exports `FAST_BUILD=1` to child environments, although `build_apk.sh:122` still deletes `build/{gen,res,obj,dex,staging,unaligned.apk,aligned.apk}` and neither `05_stage_assets.py` nor `06_package.sh` reads `FAST_BUILD` (as accurately described in `## Invariants` line 209).
- Lines 196–226 (`## Interface`, `## Invariants`, `## Side Effects`): cite non-symbol descriptive labels and positional-argument shorthands (`[build_apk.sh:tool_check]`, `[build_apk.sh:signing_policy]`, `[08_sign.sh:signing_policy]`, `[build_apk.sh:MIN_API]` where `build_apk.sh:142` passes literal `24`, `[build_apk.sh:env]`, and `$1..$5`, `$1..$4`, `$1..$7`, `$1..$6`, `$1`).

## modules/core/README.md

No drift detected

## modules/core/bundle/README.md

No drift detected

## modules/core/manifest/README.md

No drift detected

## modules/core/shims/README.md

No drift detected

## modules/core/shims/browser/README.md

No drift detected

## modules/core/shims/privilege/README.md

No drift detected

## modules/core/shims/system/README.md

No drift detected

## modules/core/unpack/README.md

No drift detected

## modules/core/validate/README.md

No drift detected

## modules/env/README.md

- Lines 49, 79, and 258–282 (`§1`, `§2`, `§9.1`): describe `cli/agy_system.py` with only `notify`, `open`, `wakelock`, `info`, and `clipboard`, omitting the `status` and `doctor [--repair]` subcommands implemented in `modules/env/cli/agy_system.py:186-194` (though `§7.1`/`§7.2` and `## Invariants` mention them).

## modules/env/bridge/README.md

No drift detected

## modules/env/cli/README.md

No drift detected

## modules/env/cmd/README.md

No drift detected

## modules/env/context/README.md

No drift detected

## modules/env/diagnostics/README.md

No drift detected

## modules/env/logging/README.md

No drift detected

## modules/io/README.md

No drift detected

## modules/io/core_bus/README.md

No drift detected

## modules/io/inputs/README.md

- Line 7 (`## Interface`): cites synthetic combined tag `[broadcast_receiver.sh:$1,$2,$3]` rather than shell variables `ACTION`, `PAYLOAD`, and `FIFO` (`modules/io/inputs/broadcast_receiver.sh:5-7`).

## modules/io/inputs/templates/README.md

No drift detected

## modules/io/interceptors/README.md

No drift detected

## modules/io/interceptors/filters/README.md

No drift detected

## modules/io/outputs/README.md

No drift detected

## modules/io/outputs/templates/README.md

No drift detected

## modules/patches/README.md

- Line 44 (`§2` patch table): cites fixed binary offsets `0x7701180` and `0x7765c60` for `crash_suppressor`, whereas `modules/patches/spec/patch_manifest.json:7` specifies `"no_fixed_offsets": true` and `modules/patches/groups/failfast/crash_suppressor.py:6-28` performs full-binary byte-pattern search (`find_all` on `TARGET_CPU` and `TARGET_ATOMIC`) with no fixed offsets.

## modules/patches/engine/README.md

No drift detected

## modules/patches/groups/README.md

No drift detected

## modules/patches/groups/failfast/README.md

No drift detected

## modules/patches/groups/pty_shell/README.md

No drift detected

## modules/patches/groups/resolv/README.md

No drift detected

## modules/patches/groups/sigill/README.md

No drift detected

## modules/patches/groups/syscalls/README.md

No drift detected

## modules/runner/README.md

No drift detected

## web/README.md

- Line 69 (`§2` tree) vs `web/index.html:15`: `web/index.html:15` references `<link rel="icon" type="image/png" href="./antigravity-icon.png" />`, which is absent from `web/` on disk (line 69 lists only existing `dark.png / light.png` and does not note the missing favicon asset).
- Lines 161–173 (`§5` `window.Android` interface table): lists 9 methods (`onUiReady`, `onUiError`, `setThemeByUser`, `onVisualThemeChanged`, `getTheme`, `isDesktopMode`, `setDesktopMode`, `openDirectoryPicker`, `saveBlob`), omitting 16 additional `@JavascriptInterface` methods present in `app/src/main/java/com/agy/bridge/AndroidBridge.java:51-338` (`setTheme`, `setSurfaceColor`, `isExtraKeysVisible`, `setExtraKeysVisible`, `openLocalPreview`, `openRemotePortal`, `startVoiceDictation`, `stopVoiceDictation`, `isVoiceDictating`, `startVoiceService`, `stopVoiceService`, `setVoiceMuted`, `isVoiceServiceActive`, `isVoiceMuted`, `hasClipboardImage`, `readClipboardImage`).

## web/src/README.md

No drift detected

## web/src/boot/README.md

No drift detected

## web/src/core/README.md

No drift detected

## web/src/patches/README.md

No drift detected

## web/src/patches/mobile_bridge/README.md

No drift detected

## web/src/patches/startup_ready/README.md

No drift detected

## web/src/patches/theme_sync/README.md

No drift detected

## web/src/patches/touch_model_menu/README.md

No drift detected

## web/src/patches/web_optimizations/README.md

No drift detected

## web/workers/README.md

No drift detected
