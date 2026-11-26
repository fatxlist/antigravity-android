package com.agy.theme;

import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Coordinates {@link View.OnApplyWindowInsetsListener} with
 * {@link WindowInsetsAnimation.Callback} on Android 11+ (API 30+) so IME transitions
 * animate frame-by-frame without an initial pre-animation jump or end-of-animation snap.
 * Isolated from API 24-29 class loading.
 */
public final class ImeInsetsAnimator {

    /** Listener receiving coordinated apply/progress/end IME transition events. */
    public interface Listener {
        WindowInsets onApplyInsets(WindowInsets rawInsets, EdgeToEdge.InsetsRect resolved, boolean deferringIme);
        void onImeAnimationStart();
        void onImeAnimationProgress(int imeBottomPx, int navBottomPx);
        void onImeAnimationEnd(EdgeToEdge.InsetsRect finalInsets);
    }

    private ImeInsetsAnimator() {
    }

    public static void install(View target, final Listener listener) {
        if (target == null || listener == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        final Set<WindowInsetsAnimation> activeImeAnims =
                Collections.newSetFromMap(new IdentityHashMap<WindowInsetsAnimation, Boolean>());
        final EdgeToEdge.InsetsRect[] latestInsets = new EdgeToEdge.InsetsRect[1];
        final boolean[] started = new boolean[1];

        target.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                EdgeToEdge.InsetsRect resolved = EdgeToEdge.resolveInsets(insets);
                latestInsets[0] = resolved;
                return listener.onApplyInsets(insets, resolved, !activeImeAnims.isEmpty());
            }
        });

        target.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {
            @Override
            public void onPrepare(WindowInsetsAnimation animation) {
                if (animation != null && (animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                    activeImeAnims.add(animation);
                }
            }

            @Override
            public WindowInsetsAnimation.Bounds onStart(
                    WindowInsetsAnimation animation, WindowInsetsAnimation.Bounds bounds) {
                if (animation != null && (animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                    activeImeAnims.add(animation);
                    if (!started[0]) {
                        started[0] = true;
                        listener.onImeAnimationStart();
                    }
                }
                return bounds;
            }

            @Override
            public WindowInsets onProgress(
                    WindowInsets insets, List<WindowInsetsAnimation> runningAnimations) {
                boolean hasIme = false;
                if (runningAnimations != null) {
                    for (int i = 0; i < runningAnimations.size(); i++) {
                        WindowInsetsAnimation anim = runningAnimations.get(i);
                        if (anim != null && (anim.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                            hasIme = true;
                            break;
                        }
                    }
                }
                if (hasIme) {
                    int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    int navBottom = latestInsets[0] != null
                            ? latestInsets[0].navBottom
                            : Math.max(
                                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                                    insets.getInsets(WindowInsets.Type.displayCutout()).bottom);
                    listener.onImeAnimationProgress(imeBottom, navBottom);
                }
                return insets;
            }

            @Override
            public void onEnd(WindowInsetsAnimation animation) {
                if (animation == null || (animation.getTypeMask() & WindowInsets.Type.ime()) == 0) {
                    return;
                }
                activeImeAnims.remove(animation);
                if (activeImeAnims.isEmpty()) {
                    started[0] = false;
                    listener.onImeAnimationEnd(latestInsets[0]);
                }
            }
        });
    }
}
