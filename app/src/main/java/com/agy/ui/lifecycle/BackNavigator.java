package com.agy.ui.lifecycle;

import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebView;
import android.widget.Toast;

/** Back handling: walk WebView history and require a second back press within 2s to exit. */
public final class BackNavigator {

    private static final long BACK_EXIT_WINDOW_MS = 2000;
    private static long lastRootBackAtMs = 0;

    private BackNavigator() {
    }

    /** Returns true when WebView navigation or the exit confirmation guard consumed the back press. */
    public static boolean handle(Context context, WebView webView) {
        if (webView != null && webView.canGoBack()) {
            lastRootBackAtMs = 0;
            webView.goBack();
            return true;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastRootBackAtMs > 0 && (now - lastRootBackAtMs) <= BACK_EXIT_WINDOW_MS) {
            lastRootBackAtMs = 0;
            return false;
        }
        lastRootBackAtMs = now;
        if (context != null) {
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /** Legacy overload without double-back confirmation prompt. */
    public static boolean handle(WebView webView) {
        return handle(null, webView);
    }
}
