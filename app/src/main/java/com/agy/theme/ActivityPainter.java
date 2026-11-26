package com.agy.theme;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;

/** Paints the activity window (edge-to-edge transparent bars, background, icon polarity). */
public final class ActivityPainter {

    private ActivityPainter() {
    }

    public static void apply(Activity activity, boolean dark) {
        applySurfaceColor(activity, dark ? ThemeColors.COLOR_DARK : ThemeColors.COLOR_LIGHT);
    }

    /** Paints the window background and aligns edge-to-edge bar polarity with {@code argbColor}. */
    public static void applySurfaceColor(Activity activity, int argbColor) {
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        EdgeToEdge.enable(window, ThemeColors.isDarkColor(argbColor));
        // Window background comes from a day/night resource (system-resolved);
        // override it so a forced in-app mode or custom surface color never flashes.
        window.setBackgroundDrawable(new ColorDrawable(argbColor));
        View decor = window.getDecorView();
        if (decor != null) {
            decor.setBackgroundColor(argbColor);
        }
    }
}
