# com.agy.ui.download

## Overview

Handles WebView-initiated URL, `blob:`, and `data:` downloads to the public Android `Downloads` directory. Coordinates SDK-dependent storage permissions, routes trusted core HTTPS URLs directly via `HttpURLConnection` while using `DownloadManager` (with direct fallback) for other web URLs, extracts in-memory WebView blobs over JavaScript, and streams Base64 payloads through temporary cache staging with a 64 MiB cap.

## Interface

- `[FileDownloader.java:FileDownloader]` Non-instantiable utility class (`private FileDownloader()`) orchestrating WebView downloads and storage permission handshakes.
- `[FileDownloader.java:REQUEST_STORAGE_DOWNLOAD]` Permission request code constant (`2001`).
- `[FileDownloader.java:DownloadTask]` Immutable task descriptor with public final fields `url`, `userAgent`, `contentDisposition`, `mimeType`, `contentLength`, and `pendingBlobFile`.
- `[FileDownloader.java:hasStoragePermission]` Static check `hasStoragePermission(Context)` for write access to public `Downloads`.
- `[FileDownloader.java:clearPendingTask]` Synchronized method clearing any queued `pendingTask` and deleting its staged temp file.
- `[FileDownloader.java:requestStoragePermission]` Static method `requestStoragePermission(Activity, DownloadTask)` prompting for storage access according to Android API level.
- `[FileDownloader.java:onPermissionResult]` Static callback `onPermissionResult(Activity, WebView, int, int[])` resuming or discarding the queued task after `REQUEST_STORAGE_DOWNLOAD`.
- `[FileDownloader.java:handleDownload]` Primary entry point `handleDownload(Activity, WebView, String, String, String, String, long)` wired to `WebView.DownloadListener`.
- `[FileDownloader.java:handleBlobData]` Entry point `handleBlobData(Activity, String, String, String)` receiving Base64/data-URL blob payloads from the native JS bridge.
- `[FileDownloader.java:saveBase64ToDownloads]` Direct saver `saveBase64ToDownloads(Context, String, String, String)` decoding and writing a Base64/data-URL string into `Downloads`.

## Invariants

- `[FileDownloader.java:hasStoragePermission]` Requires `Environment.isExternalStorageManager()` on API 30+ (`Build.VERSION_CODES.R`), `Manifest.permission.WRITE_EXTERNAL_STORAGE` on API 23-29 (`Build.VERSION_CODES.M`), and returns `true` below API 23.
- `[FileDownloader.java:pendingTask]` At most one pending `DownloadTask` is retained in static `pendingTask`; all accesses (`clearPendingTask`, `setPendingTask`, `takePendingTask`) synchronize on `FileDownloader.class` and delete any superseded `old.pendingBlobFile` when `old != task`.
- `[FileDownloader.java:requestStoragePermission]` Aborts and deletes `task.pendingBlobFile` if `activity` is null, finishing, or destroyed; on API 30+ deletes `task.pendingBlobFile` and clears `pendingTask` rather than queueing across Settings; on API < 23 executes immediately.
- `[FileDownloader.java:onPermissionResult]` Ignores any `requestCode != REQUEST_STORAGE_DOWNLOAD`; treats permission as granted only when `grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED` and deletes `task.pendingBlobFile` on denial.
- `[FileDownloader.java:executeTask]` Strictly routes in order: (1) staged `task.pendingBlobFile != null` -> `moveTempFileToDownloads`, (2) trimmed URL starting with `"blob:"` or `"data:"` -> `extractBlobViaJs` (requiring non-null `webView`), (3) validated `http`/`https` URL matching `TrustedCoreOrigin.matches(uri, RuntimeConfig.httpsPort(activity))` -> `downloadDirect`, (4) other validated `http`/`https` URLs -> `DownloadManager` with fallback to `downloadDirect`.
- `[FileDownloader.java:executeTask]` Rejects non-`http`/`https` URLs, empty hosts, and URLs with non-null `userInfo`; loopback endpoints other than `TrustedCoreOrigin.matches(...)` are not routed as trusted core origins.
- `[FileDownloader.java:extractBlobViaJs]` Escapes arguments via `JSONObject.quote`, accepts `xhr.status === 200 || xhr.status === 0`, converts via `FileReader.readAsDataURL`, and invokes `window.Android.saveBlob` only when `window.Android`, `window.Android.saveBlob`, and `window.__AG_NATIVE_BRIDGE_TOKEN__` are present.
- `[FileDownloader.java:MAX_BLOB_BYTES]` Enforces a hard `64 * 1024 * 1024` (64 MiB) decoded byte limit via upfront estimate (`estimatedBytes > MAX_BLOB_BYTES + 1024L`) and live byte counter (`written > MAX_BLOB_BYTES`), stripping any data-URL prefix up to the first comma and streaming with 16 KiB chunks via `Base64InputStream`.
- `[FileDownloader.java:handleBlobData]` Returns early if `activity == null || base64Data == null`; when storage permission is absent, spills decoded bytes to `<cacheDir>/pending_downloads/blob_*.tmp` on background thread `agy-blob-stage` and re-validates activity lifecycle and permission on `Looper.getMainLooper()`.
- `[FileDownloader.java:safeFileName]` Strips path segments, caps filename length at 180 chars, replaces control characters (`< 0x20` or `0x7f`) with `_`, strips `/` and `\`, trims whitespace, and maps `.`/`..` to empty (falling back to `"download_" + System.currentTimeMillis()`).
- `[FileDownloader.java:getSafeUniqueFile]` Canonicalizes `downloadsDir` and target paths, verifying `!target.equals(root) && PathSafety.isInside(root, target)` both before and after collision renaming (`"base (N).ext"`).
- `[FileDownloader.java:downloadDirect]` Applies 15s connect / 30s read timeouts, sends fixed `User-Agent: Mozilla/5.0 Antigravity` (no cookies or caller UA), fails on HTTP status `>= 400`, prefers response `Content-Disposition`/`Content-Type`, and imposes no size cap.
- `[FileDownloader.java:saveBase64ToDownloads]` Writes directly to `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)` without internal permission checks or `MediaStore` APIs, and does not use the `mimeType` parameter.

## Side Effects

- `[FileDownloader.java:requestStoragePermission]` Mutates static `pendingTask`, deletes staged temp files, displays `Toast` notifications, launches `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` (fallback `Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`) on API 30+, or calls `activity.requestPermissions` for `WRITE_EXTERNAL_STORAGE`/`READ_EXTERNAL_STORAGE` on API 23-29.
- `[FileDownloader.java:clearPendingTask]` Clears static `pendingTask` and deletes its `pendingBlobFile` from disk; `[FileDownloader.java:onPermissionResult]` consumes `pendingTask`, deletes temp files on denial, and shows `Toast` messages.
- `[FileDownloader.java:handleBlobData]` Spawns thread `agy-blob-stage`, creates `<cacheDir>/pending_downloads/blob_*.tmp`, deletes temp files on error/destroyed activity, logs via `Log.e`, and posts failure `Toast` messages on the main looper.
- `[FileDownloader.java:executeTask]` Evaluates JS in `WebView` (`[FileDownloader.java:extractBlobViaJs]`), reads `CookieManager` cookies, enqueues `DownloadManager.Request` items into public `Downloads`, shows `Toast` messages, and logs redacted URLs via `SensitiveDataRedactor.safeUrl`.
- `[FileDownloader.java:saveBase64ToDownloads]` Spawns thread `agy-blob-save`, creates `Downloads` directory if missing, writes target file (deleting partials on error), triggers `MediaScannerConnection.scanFile`, logs via `Log.i`/`Log.e`, and posts main-thread `Toast` messages.
- `[FileDownloader.java:moveTempFileToDownloads]` Spawns thread `agy-blob-move`, copies staged temp file to `Downloads`, unconditionally deletes temp file in `finally`, triggers `MediaScannerConnection.scanFile`, logs, and posts `Toast` messages.
- `[FileDownloader.java:downloadDirect]` Spawns an unnamed background thread, opens an `HttpURLConnection`, writes response bytes to public `Downloads` (deleting partials on error), triggers `MediaScannerConnection.scanFile`, logs, and posts `Toast` messages.

## Verification

- Run: `bash app/src/test/run_java_tests.sh`
