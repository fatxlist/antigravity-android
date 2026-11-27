package com.agy.bridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.JavascriptInterface;

import com.agy.core.VoiceForegroundService;
import com.agy.gateway.handlers.NotificationHandler;
import com.agy.oauth.AccountVaultStore;
import com.agy.runtime.RuntimeManager;
import com.agy.shell.ShizukuBridge;
import com.agy.theme.ThemeManager;
import com.agy.ui.MainActivity;
import com.agy.ui.extra.ClipboardImageReader;
import com.agy.ui.extra.ExtraKeysBarView;
import com.agy.ui.extra.LocalPreviewDialog;
import com.agy.util.AppFileProvider;
import com.agy.util.UriDispatcher;

import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Minimal, low-privilege JavaScript bridge for the app UI. It is visible to
 * embedded frames, so shell, notification, and arbitrary URL capabilities must
 * not be exposed here. Native-only getters below are intentionally unannotated.
 */
public final class AndroidBridge {

    private static final String REMOTE_PORTAL_URL = "https://antigravity.google.com";

    private final Context appContext;
    private final UiBridge ui = new UiBridge();
    private final ThemeBridge themeBridge;
    private final EngineBridge engine;
    private final EngineConfigJson config;
    private final String blobBridgeToken = createBlobBridgeToken();
    private final Object notifyLock = new Object();
    private volatile NotificationHandler notificationHandler;
    private long lastWebNotifyAtMs = 0L;
    private String lastWebNotifyKey = "";
    private volatile WeakReference<MainActivity> activityRef;

    public AndroidBridge(Context context, RuntimeManager runtime,
                         ShizukuBridge shizuku, ThemeManager theme) {
        this.appContext = context.getApplicationContext();
        this.themeBridge = new ThemeBridge(theme);
        this.engine = new EngineBridge(runtime);
        this.config = new EngineConfigJson(this.appContext, runtime, engine, shizuku, themeBridge);
    }

    /** Host activity, used to dismiss the native splash once the UI has painted. */
    public void setActivity(MainActivity activity) {
        this.activityRef = activity != null ? new WeakReference<MainActivity>(activity) : null;
        ui.setActivity(activity);
    }

    @JavascriptInterface
    public void onUiReady(String token) {
        if (matchesNativeBridgeToken(token)) {
            ui.onUiReady();
            final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
            if (act != null) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        act.onWebUiReady();
                    }
                });
            }
        }
    }

    @JavascriptInterface
    public void openDirectoryPicker() {
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    act.launchDirectoryPicker();
                }
            });
        }
    }

    @JavascriptInterface
    public void onUiError(String token, final String errorMessage) {
        if (matchesNativeBridgeToken(token)) {
            ui.onUiError(errorMessage);
        }
    }

    @JavascriptInterface
    public boolean setTheme(String mode) {
        return themeBridge.setTheme(mode);
    }

    @JavascriptInterface
    public boolean setThemeByUser(final String mode) {
        return themeBridge.setThemeByUser(mode);
    }

    @JavascriptInterface
    public void onVisualThemeChanged(String visual) {
        themeBridge.onVisualThemeChanged(visual);
    }

    @JavascriptInterface
    public String getTheme() {
        return themeBridge.getTheme();
    }

    /** Updates the native activity surface and bar polarity to match the live web surface color. */
    @JavascriptInterface
    public boolean setSurfaceColor(String token, final String hexColor) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        final Integer parsed = com.agy.theme.ThemeColors.parseHexColor(hexColor);
        if (parsed == null) {
            return false;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act == null) {
            return false;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.applySurfaceColor(parsed.intValue());
            }
        });
        return true;
    }

    @JavascriptInterface
    public boolean isDesktopMode() {
        return ui.isDesktopMode();
    }

    @JavascriptInterface
    public void setDesktopMode(boolean enabled) {
        ui.setDesktopMode(enabled);
    }

    @JavascriptInterface
    public boolean isExtraKeysVisible() {
        return ExtraKeysBarView.isVisiblePref(appContext);
    }

    @JavascriptInterface
    public void setExtraKeysVisible(final boolean visible) {
        ExtraKeysBarView.setVisiblePref(appContext, visible);
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    act.setExtraKeysVisible(visible);
                }
            });
        }
    }

    /** Opens an isolated bridge-free native preview dialog for a loopback HTTP/HTTPS URL. */
    @JavascriptInterface
    public boolean openLocalPreview(String token, final String url) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        if (!LocalPreviewDialog.isAllowedLoopbackUrl(url)) {
            return false;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act == null) {
            return false;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.openLocalPreview(url);
            }
        });
        return true;
    }

    /**
     * Opens the official Antigravity Remote Control portal (https://antigravity.google.com)
     * in an in-app Chrome Custom Tab (with system browser fallback) so the user can drive
     * their PC desktop/CLI sessions and Remote Agents using their existing Google session
     * while keeping AndroidBridge strictly isolated to TrustedCoreOrigin.
     */
    @JavascriptInterface
    public boolean openRemotePortal(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    act.openRemotePortal(REMOTE_PORTAL_URL);
                }
            });
            return true;
        }
        return new com.agy.oauth.CustomTabLauncher().openInBrowser(appContext, REMOTE_PORTAL_URL);
    }

    @JavascriptInterface
    public boolean startVoiceDictation(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act == null) {
            return false;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.startVoiceDictation();
            }
        });
        return true;
    }

    @JavascriptInterface
    public boolean stopVoiceDictation(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act == null) {
            return false;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.stopVoiceDictation();
            }
        });
        return true;
    }

    @JavascriptInterface
    public boolean isVoiceDictating() {
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        return act != null && act.isVoiceDictating();
    }

    @JavascriptInterface
    public boolean startVoiceService(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        VoiceForegroundService.start(appContext);
        return true;
    }

    @JavascriptInterface
    public boolean stopVoiceService(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        VoiceForegroundService.stop(appContext);
        return true;
    }

    @JavascriptInterface
    public boolean setVoiceMuted(String token, boolean muted) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        VoiceForegroundService.setMuted(appContext, muted);
        return true;
    }

    @JavascriptInterface
    public boolean isVoiceServiceActive() {
        return VoiceForegroundService.isActive();
    }

    @JavascriptInterface
    public boolean isVoiceMuted() {
        return VoiceForegroundService.isMuted();
    }

    /** Reports host runtime RECORD_AUDIO permission state as "granted" or "prompt" for WebView pre-checks. */
    @JavascriptInterface
    public String getMicrophonePermissionState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED ? "granted" : "prompt";
        }
        return "granted";
    }

    /** Posts an agent status/completion notification from the trusted top-level web UI. */
    @JavascriptInterface
    public boolean showAgentNotification(String token, String title, String body, String tag, boolean silent) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        String cleanTitle = title != null ? title.trim() : "";
        if (cleanTitle.isEmpty()) {
            cleanTitle = "Antigravity";
        } else if (cleanTitle.length() > 120) {
            cleanTitle = cleanTitle.substring(0, 120);
        }
        String cleanBody = body != null ? body.trim() : "";
        if (cleanBody.length() > 400) {
            cleanBody = cleanBody.substring(0, 400);
        }
        String safeTag = sanitizeWebNotifyTag(tag);
        String dedupeKey = cleanTitle + "\n" + cleanBody;
        long now = System.currentTimeMillis();
        synchronized (notifyLock) {
            if (dedupeKey.equals(lastWebNotifyKey) && (now - lastWebNotifyAtMs) < 2000L) {
                return true;
            }
            lastWebNotifyKey = dedupeKey;
            lastWebNotifyAtMs = now;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("action", "post");
            payload.put("tag", safeTag);
            payload.put("title", cleanTitle);
            payload.put("message", cleanBody);
            payload.put("channel", silent
                    ? NotificationHandler.CHANNEL_SUBAGENTS
                    : NotificationHandler.CHANNEL_ALERTS);
            payload.put("only_alert_once", silent);
            JSONObject resp = getNotificationHandler().handle(payload);
            return resp != null && resp.optBoolean("ok", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private NotificationHandler getNotificationHandler() {
        NotificationHandler handler = notificationHandler;
        if (handler == null) {
            synchronized (notifyLock) {
                handler = notificationHandler;
                if (handler == null) {
                    handler = new NotificationHandler(appContext);
                    notificationHandler = handler;
                }
            }
        }
        return handler;
    }

    private static String sanitizeWebNotifyTag(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "agy_web_agent";
        }
        StringBuilder sb = new StringBuilder("agy_web_");
        String trimmed = raw.trim();
        for (int i = 0; i < trimmed.length() && sb.length() < 48; i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() > "agy_web_".length() ? sb.toString() : "agy_web_agent";
    }

    @JavascriptInterface
    public boolean hasClipboardImage() {
        return ClipboardImageReader.hasClipboardImage(appContext);
    }

    /**
     * Reads the current Android clipboard image (content:// or safe file://, max 12 MiB)
     * as JSON: {"ok":true,"mime":"...","name":"...","base64":"..."} or {"ok":false,"error":"..."}.
     */
    @JavascriptInterface
    public String readClipboardImage(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return "{\"ok\":false,\"error\":\"unauthorized\"}";
        }
        return ClipboardImageReader.readClipboardImageJson(appContext);
    }

    // Native-only helpers used while preparing the trusted app document.
    public boolean isEngineReady() {
        return engine.isReady();
    }

    public int getEnginePort() {
        return engine.getPort();
    }

    public String getEngineBaseUrl() {
        return engine.getBaseUrl();
    }

    public String getCsrfToken() {
        return engine.getCsrfToken();
    }

    public String getConfig() {
        return config.get();
    }

    /** Native-only token inserted into the trusted top-level document. */
    public String getBlobBridgeToken() {
        return blobBridgeToken;
    }

    /** Saves a downloaded blob only for scripts holding the top-level document token. */
    @JavascriptInterface
    public void saveBlob(String token, String base64Data, String filename, String mimeType) {
        if (!matchesNativeBridgeToken(token)) {
            return;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            com.agy.ui.download.FileDownloader.handleBlobData(
                    act, base64Data, filename, mimeType);
        }
    }

    /** Lists saved accounts with sanitized public metadata (no raw tokens exposed). */
    @JavascriptInterface
    public String listSavedAccounts(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return "[]";
        }
        return AccountVaultStore.listAccountsJson(appContext);
    }

    /** Captures the active OAuth token and profile metadata into the owner-only vault. */
    @JavascriptInterface
    public String syncCurrentAccount(String token, String profileJson) {
        if (!matchesNativeBridgeToken(token)) {
            return "[]";
        }
        return AccountVaultStore.syncCurrentAccount(appContext, profileJson);
    }

    /** Switches the active standalone OAuth token file to a saved account ID. */
    @JavascriptInterface
    public String switchSavedAccount(String token, String accountId, String outgoingProfileJson) {
        if (!matchesNativeBridgeToken(token)) {
            return "{\"ok\":false,\"error\":\"unauthorized\"}";
        }
        return AccountVaultStore.activateAccount(appContext, accountId, outgoingProfileJson);
    }

    /** Removes a saved account entry from the vault. */
    @JavascriptInterface
    public String removeSavedAccount(String token, String accountId) {
        if (!matchesNativeBridgeToken(token)) {
            return "[]";
        }
        return AccountVaultStore.removeAccount(appContext, accountId);
    }

    /** Restarts the embedded core cleanly without deleting extracted runtime assets. */
    @JavascriptInterface
    public boolean restartCoreForAccountSwitch(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act == null) {
            return false;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.restartCorePreservingRuntime();
            }
        });
        return true;
    }

    /** Opens a validated local file or its parent folder from clickable chat paths. */
    @JavascriptInterface
    public boolean openLocalPath(String token, String rawPath, boolean openFolder) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        File target = resolveSafeLocalFile(appContext, rawPath);
        if (target == null || !target.exists()) {
            return false;
        }
        return openFolder
                ? UriDispatcher.openParentDirectory(appContext, target)
                : (target.isDirectory()
                        ? UriDispatcher.openDirectory(appContext, target)
                        : UriDispatcher.openFile(appContext, target));
    }

    /** Launches Android's system package installer for a validated {@code .apk} path. */
    @JavascriptInterface
    public boolean installLocalApk(String token, String rawPath) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        File target = resolveSafeLocalFile(appContext, rawPath);
        if (target == null || !target.isFile()) {
            return false;
        }
        return UriDispatcher.installApk(appContext, target);
    }

    private static File resolveSafeLocalFile(Context context, String rawPath) {
        if (context == null || rawPath == null) {
            return null;
        }
        String clean = rawPath.trim();
        if (clean.isEmpty()) {
            return null;
        }
        if (clean.startsWith("file://")) {
            try {
                String p = Uri.parse(clean).getPath();
                clean = p != null ? p : "";
            } catch (Exception ignored) {
                return null;
            }
        }
        File extStorage = Environment.getExternalStorageDirectory();
        if (extStorage != null) {
            String extRoot = extStorage.getAbsolutePath();
            if ("/sdcard".equals(clean)) {
                clean = extRoot;
            } else if (clean.startsWith("/sdcard/")) {
                clean = extRoot + clean.substring("/sdcard".length());
            }
        }
        if (!clean.startsWith("/")) {
            return null;
        }
        try {
            File file = new File(clean);
            String canonical = file.getCanonicalPath();
            File filesDir = context.getFilesDir();
            File cacheDir = context.getCacheDir();
            File extFiles = context.getExternalFilesDir(null);
            File extCache = context.getExternalCacheDir();
            File[] allowedDirs = new File[] { filesDir, cacheDir, extStorage, extFiles, extCache };
            String[] allowedRoots = new String[allowedDirs.length];
            for (int i = 0; i < allowedDirs.length; i++) {
                allowedRoots[i] = allowedDirs[i] != null ? allowedDirs[i].getCanonicalPath() : null;
            }
            String filesCanonical = filesDir != null ? filesDir.getCanonicalPath() : null;
            if (!AppFileProvider.isAllowedPath(canonical, filesCanonical, allowedRoots)) {
                return null;
            }
            return new File(canonical);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean matchesNativeBridgeToken(String candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            byte[] expected = blobBridgeToken.getBytes(Charset.forName("UTF-8"));
            byte[] actual = candidate.getBytes(Charset.forName("UTF-8"));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String createBlobBridgeToken() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        char[] hex = "0123456789abcdef".toCharArray();
        char[] encoded = new char[random.length * 2];
        for (int i = 0; i < random.length; i++) {
            int value = random[i] & 0xff;
            encoded[i * 2] = hex[value >>> 4];
            encoded[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(encoded);
    }
}
