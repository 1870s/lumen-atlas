package com.lumenatlas;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** All key mappings of the mod. They show up (and can be rebound) in Options > Controls > Key Binds. */
public final class Keybinds {
    public static final Identifier CATEGORY_ID = LumenAtlas.id("main");

    public static KeyMapping OPEN_WORLD_MAP;
    public static KeyMapping OPEN_WAYPOINTS;
    public static KeyMapping NEW_WAYPOINT;
    public static KeyMapping OPEN_SETTINGS;
    public static KeyMapping TOGGLE_MINIMAP;
    public static KeyMapping ZOOM_IN;
    public static KeyMapping ZOOM_OUT;
    public static KeyMapping CYCLE_VIEW;

    private Keybinds() {}

    static void create(KeyMapping.Category category) {
        OPEN_WORLD_MAP = new KeyMapping("key.lumenatlas.world_map", InputConstants.KEY_M, category);
        OPEN_WAYPOINTS = new KeyMapping("key.lumenatlas.waypoints", InputConstants.KEY_U, category);
        NEW_WAYPOINT = new KeyMapping("key.lumenatlas.new_waypoint", InputConstants.KEY_B, category);
        OPEN_SETTINGS = new KeyMapping("key.lumenatlas.settings", InputConstants.KEY_O, category);
        TOGGLE_MINIMAP = new KeyMapping("key.lumenatlas.toggle_minimap", InputConstants.KEY_N, category);
        ZOOM_IN = new KeyMapping("key.lumenatlas.zoom_in", InputConstants.KEY_EQUALS, category);
        ZOOM_OUT = new KeyMapping("key.lumenatlas.zoom_out", InputConstants.KEY_MINUS, category);
        CYCLE_VIEW = new KeyMapping("key.lumenatlas.cycle_view", InputConstants.UNKNOWN.getValue(), category);
    }

    public static List<KeyMapping> all() {
        return List.of(OPEN_WORLD_MAP, OPEN_WAYPOINTS, NEW_WAYPOINT, OPEN_SETTINGS, TOGGLE_MINIMAP, ZOOM_IN, ZOOM_OUT, CYCLE_VIEW);
    }

    /** Throws away presses that happened while a screen was open. */
    static void drain() {
        for (KeyMapping key : all()) {
            while (key.consumeClick()) {
                // discard
            }
        }
    }
}
