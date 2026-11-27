package com.agy.ui.splash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.agy.theme.ThemeColors;
import com.agy.theme.ThemeManager;

/**
 * Owns the splash overlay: renders the lightweight native Canvas
 * {@link NativeSplashDotsView} during startup to avoid eager WebView
 * allocation, and lazily creates a WebView only for transient restart status or
 * fail-closed error overlays.
 */
public final class SplashController {

    public static final int MODE_LOADING = 0;
    public static final int MODE_TRANSIENT = 1;
    public static final int MODE_ERROR = 2;

    private final Context context;
    private final ThemeManager theme;
    private final FrameLayout root;
    private final FrameLayout container;
    private final NativeSplashDotsView nativeDotsView;

    private WebView errorWebView;
    private SplashRetryListener retryListener;
    private boolean dismissed = false;
    private boolean destroyed = false;
    private boolean fatalShown = false;
    private int mode = MODE_LOADING;
    private String lastTitle;
    private String lastMessage;
    private boolean lastRetryable = false;

    public SplashController(Context context, ThemeManager theme, FrameLayout root) {
        this.context = context;
        this.theme = theme;
        this.root = root;

        this.container = new FrameLayout(context);
        this.container.setBackgroundColor(backgroundInt());

        this.nativeDotsView = new NativeSplashDotsView(context);
        this.nativeDotsView.setDark(isDark());
        FrameLayout.LayoutParams dotsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        this.container.addView(this.nativeDotsView, dotsParams);
    }

    public View view() {
        return container;
    }

    public int mode() {
        return mode;
    }

    public boolean isFatalShown() {
        return fatalShown;
    }

    public void setRetryListener(SplashRetryListener listener) {
        this.retryListener = listener;
        if (errorWebView != null) {
            SplashRetryBridge.bind(errorWebView, context, listener);
        }
    }

    /** First render of the loading splash (before any show* call). */
    public void loadInitial() {
        if (destroyed) {
            return;
        }
        mode = MODE_LOADING;
        showNativeDots();
    }

    /** Show the animated loading splash again (startup or post-restart ready). */
    public void showLoading() {
        if (destroyed) {
            return;
        }
        setVisible();
        mode = MODE_LOADING;
        showNativeDots();
    }

    /** Non-fatal status (auto-restart running): overlay without a Retry button. */
    public void showTransient(String message) {
        if (destroyed || fatalShown) {
            return;
        }
        setVisible();
        boolean sameState = mode == MODE_TRANSIENT && safeEquals(lastMessage, message);
        mode = MODE_TRANSIENT;
        if (!sameState) {
            lastTitle = "Restarting Antigravity";
            lastMessage = message;
            lastRetryable = false;
            showErrorOverlay();
        }
    }

    /** Fail-closed startup failure: readable message with a Retry action. */
    public void showFatalError(String message) {
        if (destroyed || fatalShown) {
            return;
        }
        fatalShown = true;
        mode = MODE_ERROR;
        setVisible();
        lastTitle = "Antigravity failed to start";
        lastMessage = message;
        lastRetryable = retryListener != null;
        showErrorOverlay();
    }

    /** Called when a fresh ready generation begins; errors can be shown again. */
    public void clearFatal() {
        fatalShown = false;
    }

    public void setVisible() {
        if (destroyed) {
            return;
        }
        dismissed = false;
        container.animate().cancel();
        container.setAlpha(1f);
        if (container.getVisibility() != View.VISIBLE) {
            container.setVisibility(View.VISIBLE);
        }
        if (root != null) {
            root.bringChildToFront(container);
        }
    }

    /** Idempotent; called by AndroidBridge.onUiReady() on the main thread. */
    public void dismiss() {
        if (dismissed || destroyed) {
            return;
        }
        dismissed = true;
        container.animate()
                .alpha(0f)
                .setDuration(280)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!dismissed || destroyed) {
                            return;
                        }
                        container.setVisibility(View.GONE);
                        nativeDotsView.stopAnimation();
                        destroyErrorWebView();
                    }
                })
                .start();
    }

    /** Re-render the splash after a theme/configuration change. */
    public void refresh() {
        if (destroyed) {
            return;
        }
        container.setBackgroundColor(backgroundInt());
        nativeDotsView.setDark(isDark());
        if (errorWebView != null && !dismissed && (mode == MODE_TRANSIENT || mode == MODE_ERROR)) {
            SplashOverlayHtml.show(errorWebView, lastTitle, lastMessage,
                    foreground(), background(), lastRetryable);
        }
    }

    /** Idempotent teardown releasing animations and any lazily allocated WebView. */
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        dismissed = true;
        try {
            container.animate().cancel();
        } catch (Throwable ignored) {
        }
        try {
            nativeDotsView.releaseResources();
        } catch (Throwable ignored) {
        }
        destroyErrorWebView();
        try {
            ViewGroup parent = (ViewGroup) container.getParent();
            if (parent != null) {
                parent.removeView(container);
            }
        } catch (Throwable ignored) {
        }
    }

    /** API31+: cross-fade the system splash into this overlay. */
    public void installExitAnimation(Activity activity) {
        SplashExitInstaller.install(activity, container);
    }

    private void showNativeDots() {
        container.setBackgroundColor(backgroundInt());
        nativeDotsView.setDark(isDark());
        nativeDotsView.setVisibility(View.VISIBLE);
        nativeDotsView.startAnimation();
        if (errorWebView != null) {
            errorWebView.setVisibility(View.GONE);
        }
    }

    private void showErrorOverlay() {
        nativeDotsView.stopAnimation();
        nativeDotsView.setVisibility(View.GONE);
        WebView wv = ensureErrorWebView();
        wv.setVisibility(View.VISIBLE);
        SplashRetryBridge.bind(wv, context, retryListener);
        SplashOverlayHtml.show(wv, lastTitle, lastMessage,
                foreground(), background(), lastRetryable);
    }

    private WebView ensureErrorWebView() {
        if (errorWebView == null) {
            errorWebView = new WebView(context);
            errorWebView.setBackgroundColor(Color.TRANSPARENT);
            WebSettings settings = errorWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            SplashRetryBridge.bind(errorWebView, context, retryListener);
            container.addView(errorWebView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        return errorWebView;
    }

    private void destroyErrorWebView() {
        WebView wv = errorWebView;
        errorWebView = null;
        if (wv == null) {
            return;
        }
        try {
            container.removeView(wv);
        } catch (Throwable ignored) {
        }
        try {
            wv.removeJavascriptInterface("SplashRetry");
        } catch (Throwable ignored) {
        }
        try {
            wv.stopLoading();
            wv.setWebViewClient(null);
            wv.setWebChromeClient(null);
            wv.destroy();
        } catch (Throwable ignored) {
        }
    }

    private boolean isDark() {
        return theme == null || theme.isDark();
    }

    private int backgroundInt() {
        return theme != null ? theme.getBackgroundColor() : ThemeColors.COLOR_DARK;
    }

    private String foreground() {
        return theme != null ? theme.getForegroundHex() : ThemeColors.FG_DARK_HEX;
    }

    private String background() {
        return theme != null ? theme.getBackgroundHex() : ThemeColors.BG_DARK_HEX;
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
