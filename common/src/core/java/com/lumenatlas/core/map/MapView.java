package com.lumenatlas.core.map;

/** How map pixels are colored. */
public enum MapView {
    /** Block colors with relief shading and water depth. */
    SHADED,
    /** Block colors only. */
    FLAT,
    /** Height bands with contour lines every 16 blocks. */
    TOPOGRAPHIC,
    /** One color per biome. */
    BIOMES;

    public MapView next() {
        MapView[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
