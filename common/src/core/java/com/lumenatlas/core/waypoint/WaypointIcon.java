package com.lumenatlas.core.waypoint;

import java.util.Locale;

/** The icons a waypoint can use. Each one has a glyph in the icon atlas (same order). */
public enum WaypointIcon {
    PIN("Pin"),
    HOUSE("House"),
    STAR("Star"),
    DIAMOND("Diamond"),
    SKULL("Skull"),
    FLAG("Flag"),
    CAVE("Cave"),
    PORTAL("Portal"),
    FARM("Farm"),
    SWORD("Danger");

    private final String label;

    WaypointIcon(String label) {
        this.label = label;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String label() {
        return label;
    }

    public WaypointIcon next() {
        WaypointIcon[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public static WaypointIcon byKey(String key) {
        if (key == null) return null;
        for (WaypointIcon icon : values()) {
            if (icon.key().equals(key)) return icon;
        }
        return null;
    }

    public static WaypointIcon byKeyOrPin(String key) {
        WaypointIcon icon = byKey(key);
        return icon == null ? PIN : icon;
    }
}
