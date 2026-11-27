package com.agy.ui.webview;

import android.util.Log;

import com.agy.bridge.AndroidBridge;
import com.agy.theme.ThemeManager;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Injects the boot config and the startup_ready script ahead of the app UI. */
public final class AppHtmlInjector {

    private static final String TAG = "WebViewHost";

    /** Web layer generates the ready signal under generated/patches. */
    private static final String READY_SCRIPT = "./generated/patches/startup_ready.js";
    private static final Pattern LOCAL_ASSET_ATTR =
            Pattern.compile("((?:src|href)\\s*=\\s*[\"'])(\\./[^\"'?#]+\\.(?:js|css))([\"'])");
    private static final String CSP = "default-src 'self' https: data: blob:; "
            + "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob: data:; "
            + "style-src 'self' 'unsafe-inline' https:; "
            + "img-src 'self' https: data: blob:; font-src 'self' https: data:; "
            + "media-src 'self' https: data: blob:; "
            + "connect-src 'self' https: wss: data: blob: http://127.0.0.1:* http://localhost:* ws://127.0.0.1:* ws://localhost:*; "
            + "frame-src https: data: blob: http://127.0.0.1:* http://localhost:*; "
            + "worker-src 'self' blob: data:; object-src 'none'; base-uri 'self'";

    private AppHtmlInjector() {
    }

    /** Appends `?v=<assetVersion>` to local `./...` `.js` and `.css` src/href attributes without touching query-bearing or external URLs. */
    public static String appendAssetVersion(String html, String assetVersion) {
        if (html == null || html.isEmpty() || assetVersion == null || assetVersion.isEmpty()) {
            return html;
        }
        String encodedVersion;
        try {
            encodedVersion = URLEncoder.encode(assetVersion, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encodedVersion = assetVersion.replaceAll("[^A-Za-z0-9._-]", "_");
        }
        Matcher matcher = LOCAL_ASSET_ATTR.matcher(html);
        StringBuffer out = new StringBuffer(html.length() + 256);
        while (matcher.find()) {
            String replacement = Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + "?v=" + encodedVersion + matcher.group(3));
            matcher.appendReplacement(out, replacement);
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String inject(String html, AndroidBridge bridge, ThemeManager theme) {
        return inject(html, bridge, theme, 0, 0, 0, 0, false, null);
    }

    public static String inject(String html, AndroidBridge bridge, ThemeManager theme,
                                int topDp, int bottomDp, int leftDp, int rightDp,
                                boolean extraKeysVisible) {
        return inject(html, bridge, theme, topDp, bottomDp, leftDp, rightDp, extraKeysVisible, null);
    }

    public static String inject(String html, AndroidBridge bridge, ThemeManager theme,
                                int topDp, int bottomDp, int leftDp, int rightDp,
                                boolean extraKeysVisible, String assetVersion) {
        int safeTop = Math.max(0, topDp);
        int safeBottom = extraKeysVisible ? 0 : Math.max(0, bottomDp);
        int safeLeft = Math.max(0, leftDp);
        int safeRight = Math.max(0, rightDp);
        String boot;
        try {
            JSONObject config = new JSONObject(bridge.getConfig());
            JSONObject endpoints = new JSONObject();
            String base = bridge.getEngineBaseUrl();
            endpoints.put("core", base);
            endpoints.put("core_http", base);
            endpoints.put("enginePort", bridge.getEnginePort());
            String insetStyle = (safeTop > 0 || safeBottom > 0 || safeLeft > 0 || safeRight > 0 || extraKeysVisible)
                    ? "<style id=\"ag-initial-insets\">:root{"
                    + "--agy-inset-top:" + safeTop + "px;"
                    + "--agy-inset-bottom:" + safeBottom + "px;"
                    + "--agy-inset-left:" + safeLeft + "px;"
                    + "--agy-inset-right:" + safeRight + "px;"
                    + "}</style>"
                    : "";
            String extraClass = extraKeysVisible
                    ? "document.documentElement.classList.add('agy-extra-keys');"
                    : "";
            boot = "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">"
                    + insetStyle
                    + "<script>(function(){"
                    + extraClass
                    + "window.__APP_CONFIG__=" + config.toString() + ";"
                    + "window.__AG_ENDPOINTS__=" + endpoints.toString() + ";"
                    + "window.__AG_CSRF__=" + JSONObject.quote(bridge.getCsrfToken()) + ";"
                    + "window.__AG_HOST_THEME__="
                    + JSONObject.quote(theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_DARK ? "dark"
                            : (theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_LIGHT ? "light" : "system")) + ";"
                    + "window.__AG_RESOLVED_THEME__="
                    + JSONObject.quote(theme != null ? theme.getTheme() : "light") + ";"
                    + "window.__AG_NATIVE_BRIDGE_TOKEN__="
                    + JSONObject.quote(bridge.getBlobBridgeToken()) + ";"
                    + "window.AndroidBridge=window.Android;"
                    + "window.dialog={showOpenDialog:function(){return new Promise(function(resolve){window.__onDirectoryPicked=function(path){resolve(path);};if(window.Android&&typeof window.Android.openDirectoryPicker==='function'){window.Android.openDirectoryPicker();}else{resolve(null);}});}};"
                    + "})();</script>"
                    // Signals first paint back to native (AndroidBridge.onUiReady)
                    // so the splash is dismissed on real content, not a timer.
                    + "<script src=\"" + READY_SCRIPT + "\"></script>";
        } catch (Exception e) {
            Log.w(TAG, "config injection failed", e);
            return html;
        }
        int head = html.indexOf("<head>");
        String result;
        if (head >= 0) {
            int at = head + "<head>".length();
            result = html.substring(0, at) + "\n" + boot + html.substring(at);
        } else {
            result = boot + html;
        }
        return appendAssetVersion(result, assetVersion);
    }
}
