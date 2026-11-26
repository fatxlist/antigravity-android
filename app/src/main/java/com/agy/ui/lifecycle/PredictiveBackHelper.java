package com.agy.ui.lifecycle;

import android.app.Activity;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/**
 * Registers/unregisters Android 13+ (API 33+) {@link OnBackInvokedCallback}.
 * Isolated from API 24-32 class loading.
 */
public final class PredictiveBackHelper {

    private PredictiveBackHelper() {
    }

    public static Object register(Activity activity, final Runnable onBack) {
        if (activity == null || onBack == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return null;
        }
        try {
            OnBackInvokedCallback callback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    onBack.run();
                }
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void unregister(Activity activity, Object handle) {
        if (activity == null || !(handle instanceof OnBackInvokedCallback)
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        try {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (OnBackInvokedCallback) handle);
        } catch (Throwable ignored) {
        }
    }
}
