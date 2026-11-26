package com.agy.ui.webview;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import com.agy.bridge.AndroidBridge;
import com.agy.theme.EdgeToEdge;
import com.agy.theme.ThemeManager;
import com.agy.util.AssetReader;

/**
 * Loads our asset UI (assets/web/index.html) with the app config injected ahead
 * of every app script, based at the core's https origin so relative assets and
 * ConnectRPC are same-origin.
 */
public final class AppLoader {

    private static final String TAG = "WebViewHost";
    private static final String APP_ASSET = "web/index.html";

    private AppLoader() {
    }

    public static void load(Context context, WebView webView, ThemeManager theme,
                            AndroidBridge bridge) {
        load(context, webView, theme, bridge, null, false);
    }

    public static void load(Context context, WebView webView, ThemeManager theme,
                            AndroidBridge bridge, EdgeToEdge.InsetsRect initialInsets,
                            boolean extraKeysVisible) {
        String html = AssetReader.readString(context, APP_ASSET);
        if (html == null) {
            Log.e(TAG, "cannot read " + APP_ASSET + "; UI not loaded");
            return;
        }
        int port = bridge.getEnginePort();
        // Paint the WebView in the persisted theme BEFORE loading so any frame
        // shown under the fading splash matches the in-app background (no
        // white/dark mismatch flash).
        if (theme != null) {
            theme.applyToWebView(webView);
        }
        String hostTheme;
        if (theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_DARK) {
            hostTheme = "dark";
        } else if (theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_LIGHT) {
            hostTheme = "light";
        } else {
            hostTheme = "system";
        }
        int topDp = 0;
        int bottomDp = 0;
        int leftDp = 0;
        int rightDp = 0;
        if (initialInsets != null && context != null) {
            float density = context.getResources().getDisplayMetrics().density;
            if (density <= 0f) {
                density = 1f;
            }
            topDp = (int) Math.ceil(initialInsets.top / density);
            bottomDp = extraKeysVisible ? 0 : (int) Math.ceil(initialInsets.navBottom / density);
            leftDp = (int) Math.ceil(initialInsets.left / density);
            rightDp = (int) Math.ceil(initialInsets.right / density);
        }
        String origin = "https://127.0.0.1:" + port + "/";
        String historyUrl = origin + "?hostTheme=" + hostTheme;
        webView.loadDataWithBaseURL(origin,
                AppHtmlInjector.inject(html, bridge, theme, topDp, bottomDp, leftDp, rightDp, extraKeysVisible),
                "text/html", "UTF-8", historyUrl);
    }
}
