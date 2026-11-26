package com.agy.theme;

/** Resolved colors and mode names. Three modes collapse to two real themes. */
public final class ThemeColors {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_LIGHT = 2;

    public static final int COLOR_DARK = 0xFF101010;
    public static final int COLOR_LIGHT = 0xFFFFFFFF;

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    public static final String BG_DARK_HEX = "#101010";
    public static final String BG_LIGHT_HEX = "#ffffff";
    public static final String FG_DARK_HEX = "#ffffff";
    public static final String FG_LIGHT_HEX = "#101010";

    private ThemeColors() {
    }

    /**
     * Parses a CSS hex color (#RGB or #RRGGBB) into an opaque ARGB int without
     * relying on android.graphics.Color stubs so it stays testable on host JVMs.
     */
    public static Integer parseHexColor(String hex) {
        if (hex == null) {
            return null;
        }
        String s = hex.trim();
        if (!s.startsWith("#")) {
            return null;
        }
        String digits = s.substring(1);
        if (digits.length() == 3) {
            int r = Character.digit(digits.charAt(0), 16);
            int g = Character.digit(digits.charAt(1), 16);
            int b = Character.digit(digits.charAt(2), 16);
            if (r < 0 || g < 0 || b < 0) {
                return null;
            }
            return 0xFF000000 | (((r << 4) | r) << 16) | (((g << 4) | g) << 8) | ((b << 4) | b);
        }
        if (digits.length() == 6) {
            int rgb = 0;
            for (int i = 0; i < 6; i++) {
                int d = Character.digit(digits.charAt(i), 16);
                if (d < 0) {
                    return null;
                }
                rgb = (rgb << 4) | d;
            }
            return 0xFF000000 | rgb;
        }
        return null;
    }

    /** Returns true when the perceived luminance of {@code argb} calls for light bar icons. */
    public static boolean isDarkColor(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (299 * r + 587 * g + 114 * b) < 128000;
    }
}
