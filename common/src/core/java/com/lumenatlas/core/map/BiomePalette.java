package com.lumenatlas.core.map;

import java.util.HashMap;
import java.util.Map;

/** Colors for the biome map view. Known vanilla biomes get hand-picked colors, others a stable color from their id. */
public final class BiomePalette {
    private static final Map<String, Integer> COLORS = new HashMap<>();

    static {
        put(0x2B5BA8, "ocean", "cold_ocean", "lukewarm_ocean");
        put(0x1E3F7A, "deep_ocean", "deep_cold_ocean", "deep_lukewarm_ocean", "deep_frozen_ocean");
        put(0x3FA6C9, "warm_ocean");
        put(0x8FB8E8, "frozen_ocean", "frozen_river");
        put(0x4A7FD9, "river");
        put(0xF2E2A0, "beach");
        put(0xB0B6B8, "stony_shore");
        put(0xE8EEF5, "snowy_beach", "snowy_plains", "ice_spikes", "snowy_slopes", "frozen_peaks", "jagged_peaks");
        put(0x8DB360, "plains");
        put(0xA9C96A, "sunflower_plains", "meadow");
        put(0xE7CF7A, "desert");
        put(0xC9B35A, "savanna", "savanna_plateau", "windswept_savanna");
        put(0x3E7A2E, "forest");
        put(0x5A9B3C, "flower_forest", "birch_forest", "old_growth_birch_forest");
        put(0x2C4F1E, "dark_forest");
        put(0xC9CDC4, "pale_garden");
        put(0x3F6E4E, "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga");
        put(0x6E8C86, "snowy_taiga", "grove");
        put(0x2F8F2A, "jungle", "bamboo_jungle");
        put(0x5FAE3A, "sparse_jungle");
        put(0x4B6F45, "swamp");
        put(0x3E5A3C, "mangrove_swamp");
        put(0xC0603A, "badlands", "eroded_badlands", "wooded_badlands");
        put(0x8A8F84, "windswept_hills", "windswept_gravelly_hills", "windswept_forest", "stony_peaks");
        put(0xE8A8C8, "cherry_grove");
        put(0xA86AB0, "mushroom_fields");
        put(0x5C7A44, "lush_caves");
        put(0x7A6E5E, "dripstone_caves");
        put(0x10252E, "deep_dark");
        put(0x7A2A2A, "nether_wastes");
        put(0x9C2E1E, "crimson_forest");
        put(0x1F6E6A, "warped_forest");
        put(0x5E4A3A, "soul_sand_valley");
        put(0x4A4A50, "basalt_deltas");
        put(0x3A2E4A, "the_void");
        put(0xD6D29A, "the_end", "end_highlands", "end_midlands", "end_barrens", "small_end_islands");
    }

    private BiomePalette() {}

    private static void put(int rgb, String... paths) {
        for (String path : paths) COLORS.put("minecraft:" + path, rgb);
    }

    /** 0xRRGGBB for a biome id; 0 for null (unknown). */
    public static int color(String biomeId) {
        if (biomeId == null) return 0;
        Integer known = COLORS.get(biomeId);
        if (known != null) return known;
        int h = biomeId.hashCode();
        // Mid-bright pastel so unknown (data pack) biomes stay readable.
        int r = 80 + ((h >> 16) & 0x7F);
        int g = 80 + ((h >> 8) & 0x7F);
        int b = 80 + (h & 0x7F);
        return (r << 16) | (g << 8) | b;
    }
}
