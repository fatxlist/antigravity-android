# Account Switcher (`web/src/patches/account_switcher`)

## Overview
Persistent Account Vault UI and hot account switcher concatenated via `web/src.manifest` into `patches/account_switcher.js`. Consumes host Settings React primitives injected by `web/patch_main.py` (`{ LU, PU, NU, OU, BI, Dgb, Ew, eE }`) to sync signed-in profile metadata into `AccountVaultStore` (`window.Android.syncCurrentAccount`) and render the **Saved Accounts** section and **Accounts** settings sub-screen.

## Interface
- `[install.js:window.__agAccountSwitcher]` Public singleton API exposing `{ syncProfile, renderSection, renderScreen, listAccounts }`.
- `[install.js:syncProfile]` Normalizes `{ userStatus, authContext }` into `{ email, name, avatarUrl, tier, projectId, location, isGcpTos }`, deduplicates by payload signature, calls `Android.syncCurrentAccount(token, json)`, and dispatches `ag:accounts-updated`.
- `[install.js:renderSection]` / `[install.js:renderScreen]` Renders `<AccountsListBody>` inside native Settings `PU`/`NU`/`OU`/`BI` rows or wrapped in a `LU` Settings screen (`title: 'Accounts'`) with per-account **Switch**, **Remove**, and **Add Account** actions.
- `[install.js:listAccounts]` Parses JSON returned by `Android.listSavedAccounts(token)`.

## Invariants
- `[install.js:syncProfile]` Deduplicates consecutive identical profile signatures via `lastSyncedSignature` and resets `lastSyncedSignature` before executing `runAccountSwitch`.
- `[install.js:runAccountSwitch]` Passes `outgoing` profile metadata to `Android.switchSavedAccount(token, acc.id, outgoingJson)` before invoking `checkCurrentAuthStatus()` + `refreshUserStatus()`, falling back to `Android.restartCoreForAccountSwitch(token)` when post-switch `userStatus.email` mismatches or refresh rejects.
- `[install.js:AccountsListBody]` Disables Switch/Remove/Add actions in remote client mode (`isRemoteClient`) or while a switch is in flight (`busyId`).

## Side Effects
- Calls token-gated native bridge methods `listSavedAccounts`, `syncCurrentAccount`, `switchSavedAccount`, `removeSavedAccount`, and `restartCoreForAccountSwitch` on `window.Android || window.AndroidBridge`.
- Dispatches synthetic `'ag:accounts-updated'` `CustomEvent` notifications on `window` and attaches a window event listener in `React.useEffect` to keep mounted lists synchronized.

## Verification
- Run: `bash web/build.sh && bash web/tests/selftest.sh && node web/tests/test_web_patches.mjs`
