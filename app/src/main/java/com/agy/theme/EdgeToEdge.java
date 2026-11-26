package com.agy.theme;

import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Pure Android-SDK edge-to-edge helper supporting API 24..34+ with transparent
 * system bars, display cutout extension, contrast-scrim control, and bar icon
 * polarity switching.
 */
public final class EdgeToEdge {

    /** Translucent dark scrim protecting white navigation icons on API 24-25 light mode. */
    public static final int LEGACY_LIGHT_SCRIM = 0x66000000;

    private EdgeToEdge() {
    }

    /** Resolved safe insets (in pixels) for system bars, cutouts, and IME. */
    public static final class InsetsRect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;
        public final int navBottom;
        public final int imeBottom;

        public InsetsRect(int left, int top, int right, int bottom, int navBottom, int imeBottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.navBottom = navBottom;
            this.imeBottom = imeBottom;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof InsetsRect)) {
                return false;
            }
            InsetsRect other = (InsetsRect) o;
            return left == other.left
                    && top == other.top
                    && right == other.right
                    && bottom == other.bottom
                    && navBottom == other.navBottom
                    && imeBottom == other.imeBottom;
        }

        @Override
        public int hashCode() {
            int result = left;
            result = 31 * result + top;
            result = 31 * result + right;
            result = 31 * result + bottom;
            result = 31 * result + navBottom;
            result = 31 * result + imeBottom;
            return result;
        }
    }

    /**
     * Resolves navigation bar color for edge-to-edge rendering. Light theme on
     * API 24-25 lacks SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, so a dark translucent
     * scrim keeps white navigation icons readable.
     */
    public static int resolveNavigationBarColor(int sdkInt, boolean dark) {
        if (dark || sdkInt >= Build.VERSION_CODES.O) {
            return Color.TRANSPARENT;
        }
        return LEGACY_LIGHT_SCRIM;
    }

    /**
     * Returns additional bottom padding needed above the persistent navigation bar /
     * extra-keys bar while the IME is open.
     */
    public static int computeImeBottomPadding(int imeBottom, int navBottom) {
        return Math.max(0, imeBottom - Math.max(0, navBottom));
    }

    /** Combines system-bar, display-cutout, and IME insets into effective edge paddings. */
    public static InsetsRect computeEffectiveInsets(
            int sysLeft, int sysTop, int sysRight, int sysBottom,
            int cutoutLeft, int cutoutTop, int cutoutRight, int cutoutBottom,
            int imeBottom) {
        int left = Math.max(0, Math.max(sysLeft, cutoutLeft));
        int top = Math.max(0, Math.max(sysTop, cutoutTop));
        int right = Math.max(0, Math.max(sysRight, cutoutRight));
        int navBottom = Math.max(0, Math.max(sysBottom, cutoutBottom));
        int bottom = Math.max(navBottom, Math.max(0, imeBottom));
        return new InsetsRect(left, top, right, bottom, navBottom, Math.max(0, imeBottom));
    }

    /**
     * Separates persistent bottom navigation bar height from software keyboard (IME)
     * height on API 24-29 where both share {@code systemWindowInsetBottom}.
     */
    public static InsetsRect computeLegacyInsets(
            int sysLeft, int sysTop, int sysRight, int sysBottom, int stableBottom,
            int cutoutLeft, int cutoutTop, int cutoutRight, int cutoutBottom,
            int imeThresholdPx) {
        int baseNav = Math.max(0, Math.max(stableBottom, cutoutBottom));
        int threshold = Math.max(1, imeThresholdPx);
        boolean isIme = sysBottom > baseNav && (sysBottom - baseNav) >= threshold;
        int effectiveSysBottom = isIme ? baseNav : Math.max(0, sysBottom);
        int imeBottom = isIme ? sysBottom : 0;
        return computeEffectiveInsets(
                sysLeft, sysTop, sysRight, effectiveSysBottom,
                cutoutLeft, cutoutTop, cutoutRight, cutoutBottom,
                imeBottom);
    }

    /** Extracts combined system bar, display cutout, and IME insets across API levels. */
    public static InsetsRect resolveInsets(WindowInsets insets) {
        return resolveInsets(insets, 200);
    }

    /**
     * Extracts combined system bar, display cutout, and IME insets across API levels,
     * using {@code imeThresholdPx} to distinguish IME overlap on API 24-29.
     */
    @SuppressWarnings("deprecation")
    public static InsetsRect resolveInsets(WindowInsets insets, int imeThresholdPx) {
        if (insets == null) {
            return new InsetsRect(0, 0, 0, 0, 0, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
            Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            return computeEffectiveInsets(
                    sys.left, sys.top, sys.right, sys.bottom,
                    cutout.left, cutout.top, cutout.right, cutout.bottom,
                    ime.bottom);
        }
        int sysLeft = insets.getSystemWindowInsetLeft();
        int sysTop = insets.getSystemWindowInsetTop();
        int sysRight = insets.getSystemWindowInsetRight();
        int sysBottom = insets.getSystemWindowInsetBottom();
        int stableBottom = insets.getStableInsetBottom();
        int cutoutLeft = 0;
        int cutoutTop = 0;
        int cutoutRight = 0;
        int cutoutBottom = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                cutoutLeft = cutout.getSafeInsetLeft();
                cutoutTop = cutout.getSafeInsetTop();
                cutoutRight = cutout.getSafeInsetRight();
                cutoutBottom = cutout.getSafeInsetBottom();
            }
        }
        return computeLegacyInsets(
                sysLeft, sysTop, sysRight, sysBottom, stableBottom,
                cutoutLeft, cutoutTop, cutoutRight, cutoutBottom,
                imeThresholdPx);
    }

    /** Marks system insets as consumed on all supported API levels. */
    @SuppressWarnings("deprecation")
    public static WindowInsets consumeSystemInsets(WindowInsets insets) {
        if (insets == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return WindowInsets.CONSUMED;
        }
        return insets.consumeSystemWindowInsets();
    }

    /**
     * Forwards system bar and cutout safe-area insets to the WebView subtree while
     * stripping IME insets already handled by the native container (and bottom nav
     * inset when the native extra-keys bar occupies the bottom edge).
     */
    @SuppressWarnings("deprecation")
    public static WindowInsets passSafeAreasConsumeIme(WindowInsets insets, boolean consumeBottomNav) {
        if (insets == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets.Builder builder = new WindowInsets.Builder(insets);
            builder.setInsets(WindowInsets.Type.ime(), Insets.NONE);
            builder.setVisible(WindowInsets.Type.ime(), false);
            if (consumeBottomNav) {
                Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
                if (nav.bottom > 0) {
                    builder.setInsets(WindowInsets.Type.navigationBars(),
                            Insets.of(nav.left, nav.top, nav.right, 0));
                }
                Insets navIgnoring = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
                if (navIgnoring.bottom > 0) {
                    builder.setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(),
                            Insets.of(navIgnoring.left, navIgnoring.top, navIgnoring.right, 0));
                }
            }
            return builder.build();
        }
        return insets.consumeSystemWindowInsets();
    }

    /** Enables edge-to-edge drawing and sets status/navigation icon polarity on {@code window}. */
    @SuppressWarnings("deprecation")
    public static void enable(Window window, boolean dark) {
        if (window == null) {
            return;
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(resolveNavigationBarColor(Build.VERSION.SDK_INT, dark));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp != null) {
                lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
        }

        // Ensure DecorView exists before querying WindowInsetsController on API 30+.
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
                return;
            }
        }
        applyLegacySystemUi(decor, dark);
    }

    @SuppressWarnings("deprecation")
    private static void applyLegacySystemUi(View decor, boolean dark) {
        if (decor == null) {
            return;
        }
        int flags = decor.getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }
}
