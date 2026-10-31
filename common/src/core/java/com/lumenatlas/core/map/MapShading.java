package com.lumenatlas.core.map;

import com.lumenatlas.core.util.Colors;

/** Turns stored column data into final ARGB map pixels. Pure functions, safe on any thread. */
public final class MapShading {
    /** Height used for "no neighbor known". */
    public static final int NO_NEIGHBOR = Integer.MIN_VALUE;
    private static final int WATER_RGB = 0x3F76E4;

    private MapShading() {}

    /**
     * @param surface     the packed surface value (see {@link MapRegion#set})
     * @param height      the column height
     * @param northHeight height of the column to the north (z - 1), or {@link #NO_NEIGHBOR}
     * @param westHeight  height of the column to the west (x - 1), or {@link #NO_NEIGHBOR}
     * @param biomeRgb    biome color, used by {@link MapView#BIOMES}
     */
    public static int pixel(MapView view, int surface, int height, int northHeight, int westHeight, int biomeRgb) {
        int rgb = surface & 0xFFFFFF;
        int depth = surface >>> 24;
        return switch (view) {
            case FLAT -> Colors.opaque(depth > 0 ? water(rgb, depth) : rgb);
            case SHADED -> Colors.opaque(depth > 0 ? water(rgb, depth) : relief(rgb, height, northHeight, westHeight, 1f));
            case TOPOGRAPHIC -> Colors.opaque(topographic(height, northHeight, westHeight, depth));
            case BIOMES -> Colors.opaque(depth > 0 && biomeRgb == 0 ? WATER_RGB : relief(biomeRgb, height, northHeight, westHeight, 0.5f));
        };
    }

    /** Deeper water is darker. */
    static int water(int rgb, int depth) {
        float t = Math.min(depth, 20) / 20f;
        float factor = 1.05f - 0.5f * t;
        return Colors.scale(rgb, factor) & 0xFFFFFF;
    }

    /** Slopes facing north-west are lit, the others are in shadow (like a paper map lit from the top left). */
    static int relief(int rgb, int height, int northHeight, int westHeight, float strength) {
        float diff = 0;
        if (northHeight != NO_NEIGHBOR) diff += height - northHeight;
        if (westHeight != NO_NEIGHBOR) diff += (height - westHeight) * 0.5f;
        diff = Math.max(-6f, Math.min(6f, diff));
        float factor = 1f + diff * 0.055f * strength;
        factor = Math.max(0.68f, Math.min(1.28f, factor));
        return Colors.scale(rgb, factor) & 0xFFFFFF;
    }

    /** Green lowlands, brown hills, white peaks; water in blue tones. Contour line every 16 blocks. */
    static int topographic(int height, int northHeight, int westHeight, int depth) {
        if (depth > 0) {
            float t = Math.min(depth, 30) / 30f;
            return Colors.blend(0xFF6FA8DC, 0xFF1F3F7A, t) & 0xFFFFFF;
        }
        float t = Math.max(0f, Math.min(1f, (height - 40) / 200f));
        int color;
        if (t < 0.25f) color = Colors.blend(0xFF5E9E4E, 0xFFB5C76A, t / 0.25f);
        else if (t < 0.55f) color = Colors.blend(0xFFB5C76A, 0xFF9C7A4F, (t - 0.25f) / 0.3f);
        else if (t < 0.8f) color = Colors.blend(0xFF9C7A4F, 0xFF8A8A8A, (t - 0.55f) / 0.25f);
        else color = Colors.blend(0xFF8A8A8A, 0xFFF4F4F4, (t - 0.8f) / 0.2f);
        boolean contour = (northHeight != NO_NEIGHBOR && Math.floorDiv(height, 16) != Math.floorDiv(northHeight, 16))
            || (westHeight != NO_NEIGHBOR && Math.floorDiv(height, 16) != Math.floorDiv(westHeight, 16));
        if (contour) color = Colors.scale(color, 0.62f);
        return color & 0xFFFFFF;
    }
}
