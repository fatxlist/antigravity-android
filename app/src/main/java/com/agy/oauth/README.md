# com.agy.oauth

## Overview
Handles external-browser OAuth delegation, read-only detection of the standalone core's persisted OAuth token file, and thread-safe owner-only (`0600`) multi-account snapshot storage (`AccountVaultStore`). Classifies OAuth/sign-in URLs that must leave the in-app WebView, resolves and launches an external browser via `ACTION_VIEW` intents, exposes shared OAuth/Custom Tabs constants, inspects/rotates the core token file, and binds these pieces behind `OAuthManager`.

## Interface
- `AccountVaultStore` (`public final class`, non-instantiable `[AccountVaultStore.java:AccountVaultStore]`):
  - `vaultFile(Context)` — resolves `<filesDir>/.gemini/agy-account-vault.json` `[AccountVaultStore.java:vaultFile]`.
  - `syncCurrentAccount(Context|File, ...)` — captures the current token file plus profile metadata and returns sanitized public account JSON `[AccountVaultStore.java:syncCurrentAccount]`.
  - `listAccountsJson(Context|File)` — lists saved accounts sorted active-first, then most recently used `[AccountVaultStore.java:listAccountsJson]`.
  - `activateAccount(Context|File, ..., accountId, outgoingProfileJson)` — saves outgoing token/metadata, atomically overwrites the active core token file, and updates `active_account_id` `[AccountVaultStore.java:activateAccount]`.
  - `removeAccount(Context|File, ..., accountId)` — removes a saved account entry from the vault `[AccountVaultStore.java:removeAccount]`.
- `AuthUrlMatcher` (`public final class` `[AuthUrlMatcher.java:AuthUrlMatcher]`):
  - `isAuthUrl(String url)` — returns `true` for `http`/`https` URLs matching Google OAuth hosts/paths or `antigravity.google` `/auth` paths `[AuthUrlMatcher.java:isAuthUrl]`.
- `CustomTabLauncher` (`public final class` `[CustomTabLauncher.java:CustomTabLauncher]`):
  - `resolveDefaultBrowserPackage(Context context)` — resolves the explicit default browser package for `https://example.com` excluding system/GMS resolvers `[CustomTabLauncher.java:resolveDefaultBrowserPackage]`.
  - `openInBrowser(Context context, String url)` — validates an `http`/`https` non-credentialed URL and launches it in the resolved default browser or system chooser `[CustomTabLauncher.java:openInBrowser]`.
- `OAuthConstants` (`public final class`, non-instantiable `[OAuthConstants.java:OAuthConstants]`):
  - Logging & browser/Custom Tabs constants: `TAG` (`"OAuthManager"`), `CHROME_PACKAGE` (`"com.android.chrome"`), `EXTRA_SESSION`, `EXTRA_TOOLBAR_COLOR`, `EXTRA_APP_ID`, `TOOLBAR_COLOR_DARK` (`0xFF101010`) `[OAuthConstants.java:TAG]` `[OAuthConstants.java:CHROME_PACKAGE]` `[OAuthConstants.java:EXTRA_SESSION]` `[OAuthConstants.java:EXTRA_TOOLBAR_COLOR]` `[OAuthConstants.java:EXTRA_APP_ID]` `[OAuthConstants.java:TOOLBAR_COLOR_DARK]`.
  - Callback & token path constants: `CALLBACK_SCHEME` (`BuildConfig.OAUTH_SCHEME`), `CALLBACK_HOST` (`"oauth-success"`), `CALLBACK_HOST_COMPAT` (`"auth-success"`), `TOKEN_DIR` (`".gemini"`), `TOKEN_NAME` (`"jetski-standalone-oauth-token"`) `[OAuthConstants.java:CALLBACK_SCHEME]` `[OAuthConstants.java:CALLBACK_HOST]` `[OAuthConstants.java:CALLBACK_HOST_COMPAT]` `[OAuthConstants.java:TOKEN_DIR]` `[OAuthConstants.java:TOKEN_NAME]`.
- `OAuthManager` (`public final class`, facade over `AuthUrlMatcher`, `CustomTabLauncher`, and `OAuthTokenStore` `[OAuthManager.java:OAuthManager]`):
  - `tokenFile(Context context)` — returns the persisted core token `File` via `OAuthTokenStore.tokenFile` `[OAuthManager.java:tokenFile]`.
  - `hasToken(Context context)` — checks whether a valid persisted token exists via `OAuthTokenStore.hasToken` `[OAuthManager.java:hasToken]`.
  - `isAuthUrl(String url)` — delegates URL classification to `AuthUrlMatcher.isAuthUrl` `[OAuthManager.java:isAuthUrl]`.
  - `intercept(Context context, String url)` — logs match status and opens externally via `CustomTabLauncher.openInBrowser` only when `isAuthUrl(url)` is `true` `[OAuthManager.java:intercept]`.
  - `openInBrowser(Context context, String url)` — delegates external launch to `CustomTabLauncher.openInBrowser` `[OAuthManager.java:openInBrowser]`.
- `OAuthTokenStore` (`public final class`, non-instantiable `[OAuthTokenStore.java:OAuthTokenStore]`):
  - `tokenFile(Context context)` — resolves `<filesDir>/.gemini/jetski-standalone-oauth-token` (or `null` if `context` is `null`) `[OAuthTokenStore.java:tokenFile]`.
  - `hasToken(Context context)` — returns `true` only if the token file exists and contains a non-null, non-blank `"token"` value `[OAuthTokenStore.java:hasToken]`.
  - `isNullOrBlankToken(String text)` — returns `true` when `"token"` is absent, malformed, `null`, or `""` `[OAuthTokenStore.java:isNullOrBlankToken]`.

## Invariants
- Only `http` and `https` schemes (case-insensitive) with a non-null host match as auth URLs; host matching lowercases with `Locale.US` and restricts matches to `accounts.google.com`, `*.accounts.google.com`, `accounts.googleusercontent.com`, `oauth2.googleapis.com`, `google.com`/`*.google.com` with path containing `"/o/oauth2"`, or `antigravity.google`/`*.antigravity.google` with path containing `"/auth"` `[AuthUrlMatcher.java:isAuthUrl]`.
- External browser launch rejects `null`/blank strings, unparseable URIs, non-`http`/`https` schemes, missing/empty hosts, and URLs with userinfo (`uri.getUserInfo() != null`) `[CustomTabLauncher.java:openInBrowser]`.
- Default browser resolution accepts only an explicit `MATCH_DEFAULT_ONLY` handler for `https://example.com` with `CATEGORY_BROWSABLE`, rejecting `null`/empty package names as well as `"android"`, `"com.android.internal.app.ResolverActivity"`, `"com.android.internal.app.ChooserActivity"`, `"com.google.android.gms"`, and `"com.google.android.googlequicksearchbox"` `[CustomTabLauncher.java:resolveDefaultBrowserPackage]` `[CustomTabLauncher.java:isRealBrowserPackage]`.
- When no verified default browser is resolved or direct package launch throws, launch falls back to `Intent.createChooser(..., "Open in browser")`; `doOpen` sends standard `ACTION_VIEW` intents without Custom Tab extras `[CustomTabLauncher.java:doOpen]`.
- All URLs logged by `CustomTabLauncher` and `OAuthManager` are sanitized via `SensitiveDataRedactor.safeUrl` `[CustomTabLauncher.java:openInBrowser]` `[CustomTabLauncher.java:doOpen]` `[OAuthManager.java:intercept]`.
- `OAuthManager.intercept` invokes `CustomTabLauncher.openInBrowser` only if `AuthUrlMatcher.isAuthUrl(url)` returns `true`, returning `false` otherwise `[OAuthManager.java:intercept]`.
- `OAuthConstants` and `OAuthTokenStore` are non-instantiable utility classes with private constructors `[OAuthConstants.java:OAuthConstants]` `[OAuthTokenStore.java:OAuthTokenStore]`.
- `OAuthTokenStore.tokenFile` and `OAuthTokenStore.hasToken` tolerate `null` `Context` (returning `null` and `false`) and read strictly from `context.getFilesDir()/.gemini/jetski-standalone-oauth-token` `[OAuthTokenStore.java:tokenFile]` `[OAuthTokenStore.java:hasToken]`.
- `OAuthTokenStore.hasToken` reads at most `MAX_SCAN` (`8192`) bytes as UTF-8 in a single read, returns `false` on missing/non-regular/empty files or I/O exceptions, and rejects placeholder payloads where `"token"` is absent, `null`, or `""` `[OAuthTokenStore.java:MAX_SCAN]` `[OAuthTokenStore.java:hasToken]` `[OAuthTokenStore.java:isNullOrBlankToken]`.
- `AccountVaultStore` protects all vault and token reads/writes under `LOCK`, writes atomically via `.tmp` + `Os.chmod(..., 0600)` + rename, refuses null/blank/corrupt tokens, deduplicates entries by `email|project_id` or token SHA-256 fingerprint, and omits raw `token_json`/`token_fp` secrets from `listAccountsJson` and `activateAccount` JSON responses `[AccountVaultStore.java:writePrivateAtomic]` `[AccountVaultStore.java:toPublicAccount]`.

## Side Effects
- Queries `PackageManager.resolveActivity` with `MATCH_DEFAULT_ONLY` to discover the default HTTPS browser `[CustomTabLauncher.java:resolveDefaultBrowserPackage]`.
- Launches external Android activities via `startActivity` (`ACTION_VIEW`, `CATEGORY_BROWSABLE`, `FLAG_ACTIVITY_NEW_TASK`) using `MainActivity.activeActivity` when non-null or the supplied `Context`, targeting either the default browser package or `Intent.createChooser` `[CustomTabLauncher.java:openInBrowser]` `[CustomTabLauncher.java:doOpen]`.
- Emits `android.util.Log` entries (`Log.i`, `Log.w`, `Log.e`) with redacted URLs `[CustomTabLauncher.java:resolveDefaultBrowserPackage]` `[CustomTabLauncher.java:openInBrowser]` `[CustomTabLauncher.java:doOpen]` `[OAuthManager.java:intercept]`.
- Performs synchronous filesystem `stat` and read I/O (up to `8192` bytes) on `<filesDir>/.gemini/jetski-standalone-oauth-token`; performs no disk writes, `SharedPreferences` mutations, or `KeyStore` operations `[OAuthTokenStore.java:hasToken]`.

## Verification
- Run: `bash app/src/test/run_java_tests.sh`
