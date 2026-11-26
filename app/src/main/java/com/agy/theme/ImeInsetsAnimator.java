package com.agy.theme;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;

import java.util.List;

/**
 * Drives smooth 60/120fps IME padding updates on Android 11+ (API 30+) using
 * {@link WindowInsetsAnimation.Callback}. Isolated from API 24-29 class loading.
 */
public final class ImeInsetsAnimator {

    /** Callback receiving per-frame inset snapshots and final completion signal. */
    public interface Listener {
        void onImeProgress(EdgeToEdge.InsetsRect insets);
        void onImeEnd();
    }

    private ImeInsetsAnimator() {
    }

    public static void install(View target, final Listener listener) {
        if (target == null || listener == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        target.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            @Override
            public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> runningAnimations) {
                listener.onImeProgress(EdgeToEdge.resolveInsets(insets));
                return insets;
            }

            @Override
            public void onEnd(WindowInsetsAnimation animation) {
                listener.onImeEnd();
            }
        });
    }
}
