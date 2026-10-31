package com.lumenatlas.core.util;

/** Geometry shared by the minimap and the world map. */
public final class MapMath {
    private static final String[] DIRECTIONS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private MapMath() {}

    /**
     * Compass direction for a Minecraft yaw (0 = south, 90 = west, 180 = north, 270 = east).
     */
    public static String facing(float yaw) {
        float y = ((yaw % 360f) + 360f) % 360f;
        int index = Math.round(y / 45f) % 8;
        return DIRECTIONS[index];
    }

    /**
     * Converts a world offset (dx east, dz south) to map screen coordinates for a map rotated with the player's
     * view. With {@code rotationDeg = yaw + 180} the player's forward direction points up.
     * Returns {x, y}.
     */
    public static double[] rotate(double dx, double dz, double rotationDeg) {
        double r = Math.toRadians(rotationDeg);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        return new double[] {dx * cos + dz * sin, -dx * sin + dz * cos};
    }

    /** Inverse of {@link #rotate}: screen offset back to world offset. */
    public static double[] unrotate(double sx, double sy, double rotationDeg) {
        return rotate(sx, sy, -rotationDeg);
    }

    /** Clamps a point to the edge of a square of half-size {@code half}. Returns {x, y}. */
    public static double[] clampToSquare(double x, double y, double half) {
        double m = Math.max(Math.abs(x), Math.abs(y));
        if (m <= half || m == 0) return new double[] {x, y};
        return new double[] {x * half / m, y * half / m};
    }

    /** Clamps a point to the edge of a circle of radius {@code radius}. Returns {x, y}. */
    public static double[] clampToCircle(double x, double y, double radius) {
        double d = Math.sqrt(x * x + y * y);
        if (d <= radius || d == 0) return new double[] {x, y};
        return new double[] {x * radius / d, y * radius / d};
    }

    /** "1.2 km", "340 m". */
    public static String distance(double blocks) {
        if (blocks >= 10_000) return String.format(java.util.Locale.ROOT, "%.0f km", blocks / 1000);
        if (blocks >= 1000) return String.format(java.util.Locale.ROOT, "%.1f km", blocks / 1000);
        return String.format(java.util.Locale.ROOT, "%.0f m", blocks);
    }
}
