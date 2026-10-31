package com.lumenatlas.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.lumenatlas.core.map.MapView;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * All user settings. Serialized as JSON with Gson; unknown or missing fields fall back to the defaults below and
 * {@link #sanitize()} clamps everything into its valid range, so a hand-edited file can never break the mod.
 */
public final class LumenConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Shape { SQUARE, CIRCLE }

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public enum CaveMode { AUTO, OFF }

    /** Minimap zoom levels in blocks per GUI pixel. */
    public static final float[] MINIMAP_ZOOMS = {0.25f, 0.5f, 1f, 2f, 4f};
    public static final int[] MINIMAP_SIZES = {64, 80, 96, 112, 128, 160, 192, 224, 256};

    /** Very common structure types that would cover the map; they are still detected and can be switched on. */
    public static final Set<String> DEFAULT_HIDDEN_TYPES = Set.of("geode", "dungeon");

    public int configVersion = 1;

    // Minimap
    public boolean minimapEnabled = true;
    public Shape minimapShape = Shape.CIRCLE;
    public int minimapSize = 112;
    public Corner minimapCorner = Corner.TOP_RIGHT;
    public int minimapMarginX = 6;
    public int minimapMarginY = 6;
    public float minimapOpacity = 1f;
    public int minimapZoom = 2;
    public boolean minimapRotate = true;
    public MapView minimapView = MapView.SHADED;
    public CaveMode caveMode = CaveMode.AUTO;
    public boolean showCoordinates = true;
    public boolean showBiome = true;
    public boolean showCardinals = true;
    public boolean showFacing = true;
    public boolean showPlayers = true;
    public boolean showHostileMobs = false;
    public boolean showPassiveMobs = false;
    public int minimapFps = 60;

    // World map
    public MapView worldMapView = MapView.SHADED;
    public boolean worldMapGrid = false;
    public boolean worldMapAnimations = true;
    public float iconScale = 1f;

    // Structures
    public boolean detectStructures = true;
    public boolean showDiscoveredStructures = true;
    public boolean showPredictedStructures = true;
    public boolean structuresOnMinimap = true;
    /** Structure type keys the player switched off. */
    public Set<String> hiddenStructureTypes = new LinkedHashSet<>(DEFAULT_HIDDEN_TYPES);
    public boolean announceDiscoveries = true;

    // Waypoints
    public boolean showWaypointsOnMinimap = true;
    public boolean showWaypointsOnWorldMap = true;
    public boolean showWaypointsInWorld = true;
    public int inWorldWaypointRange = 1000;
    public boolean deathWaypoints = true;
    public int keepDeathWaypoints = 3;

    // Performance
    /** Milliseconds per client tick spent scanning chunks for the map. */
    public float scanBudgetMs = 2.5f;
    /** Number of region textures the world map keeps in video memory. */
    public int textureCacheSize = 160;

    public float minimapBlocksPerPixel() {
        return MINIMAP_ZOOMS[minimapZoom];
    }

    public boolean isStructureTypeVisible(String key) {
        return !hiddenStructureTypes.contains(key);
    }

    public void setStructureTypeVisible(String key, boolean visible) {
        if (visible) hiddenStructureTypes.remove(key);
        else hiddenStructureTypes.add(key);
    }

    /** Clamps every value into its valid range. */
    public LumenConfig sanitize() {
        if (minimapShape == null) minimapShape = Shape.CIRCLE;
        if (minimapCorner == null) minimapCorner = Corner.TOP_RIGHT;
        if (minimapView == null) minimapView = MapView.SHADED;
        if (worldMapView == null) worldMapView = MapView.SHADED;
        if (caveMode == null) caveMode = CaveMode.AUTO;
        if (hiddenStructureTypes == null) hiddenStructureTypes = new LinkedHashSet<>();
        minimapSize = clamp(minimapSize, MINIMAP_SIZES[0], MINIMAP_SIZES[MINIMAP_SIZES.length - 1]);
        minimapMarginX = clamp(minimapMarginX, 0, 400);
        minimapMarginY = clamp(minimapMarginY, 0, 400);
        minimapOpacity = Float.isNaN(minimapOpacity) ? 1f : Math.max(0.2f, Math.min(1f, minimapOpacity));
        minimapZoom = clamp(minimapZoom, 0, MINIMAP_ZOOMS.length - 1);
        minimapFps = clamp(minimapFps, 10, 240);
        iconScale = Float.isNaN(iconScale) ? 1f : Math.max(0.5f, Math.min(2f, iconScale));
        inWorldWaypointRange = clamp(inWorldWaypointRange, 16, 100_000);
        keepDeathWaypoints = clamp(keepDeathWaypoints, 0, 20);
        scanBudgetMs = Float.isNaN(scanBudgetMs) ? 2.5f : Math.max(0.5f, Math.min(10f, scanBudgetMs));
        textureCacheSize = clamp(textureCacheSize, 32, 1024);
        return this;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Parses a config file; returns defaults for anything that cannot be read. */
    public static LumenConfig fromJson(String json) {
        try {
            LumenConfig config = GSON.fromJson(json, LumenConfig.class);
            return (config == null ? new LumenConfig() : config).sanitize();
        } catch (JsonParseException | IllegalStateException e) {
            return new LumenConfig();
        }
    }
}
