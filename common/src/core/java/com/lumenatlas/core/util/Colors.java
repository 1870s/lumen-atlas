package com.lumenatlas.core.util;

/** Small helpers for packed ARGB / RGB colors. */
public final class Colors {
    private Colors() {}

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    public static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    public static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    public static int blue(int argb) {
        return argb & 0xFF;
    }

    public static int argb(int a, int r, int g, int b) {
        return (clamp8(a) << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
    }

    /** Opaque color from a 0xRRGGBB value. */
    public static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    public static int withAlpha(int argb, int alpha) {
        return (clamp8(alpha) << 24) | (argb & 0xFFFFFF);
    }

    /** Multiplies the RGB channels by {@code factor}, keeping alpha. */
    public static int scale(int argb, float factor) {
        return argb(alpha(argb), Math.round(red(argb) * factor), Math.round(green(argb) * factor), Math.round(blue(argb) * factor));
    }

    /** Channel-wise multiply of two colors (alpha of {@code a}). */
    public static int multiply(int a, int b) {
        return argb(alpha(a), red(a) * red(b) / 255, green(a) * green(b) / 255, blue(a) * blue(b) / 255);
    }

    /** Linear blend from {@code a} (t = 0) to {@code b} (t = 1), including alpha. */
    public static int blend(int a, int b, float t) {
        float u = 1f - t;
        return argb(Math.round(alpha(a) * u + alpha(b) * t), Math.round(red(a) * u + red(b) * t),
            Math.round(green(a) * u + green(b) * t), Math.round(blue(a) * u + blue(b) * t));
    }

    /** "#RRGGBB" for an RGB or ARGB value. */
    public static String toHex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    /** Parses "#RRGGBB", "RRGGBB" or "#RGB"; returns {@code fallback} when the text is not a color. */
    public static int parseHex(String text, int fallback) {
        if (text == null) return fallback;
        String s = text.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (s.length() != 6) return fallback;
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
