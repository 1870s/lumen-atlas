package com.lumenatlas.core.waypoint;

import java.util.Objects;
import java.util.UUID;

/** A player waypoint. Plain data object; serialized with Gson. */
public final class Waypoint {
    public static final String KIND_NORMAL = "normal";
    public static final String KIND_DEATH = "death";
    public static final int MAX_NAME_LENGTH = 48;

    public String id = UUID.randomUUID().toString();
    public String name = "Waypoint";
    /** Dimension id, e.g. "minecraft:overworld". */
    public String dimension = "minecraft:overworld";
    public int x;
    public int y;
    public int z;
    /** 0xRRGGBB. */
    public int color = 0xA78BFA;
    /** Icon key, see {@link WaypointIcon}. */
    public String icon = WaypointIcon.PIN.key();
    public boolean enabled = true;
    /** Show the beam/label in the 3D world (in addition to the maps). */
    public boolean showInWorld = true;
    public String kind = KIND_NORMAL;
    public long created = System.currentTimeMillis();

    public Waypoint() {}

    public Waypoint(String name, String dimension, int x, int y, int z, int color, String icon) {
        this.name = name;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.icon = icon;
    }

    public boolean isDeath() {
        return KIND_DEATH.equals(kind);
    }

    public double distanceTo(double px, double py, double pz) {
        double dx = x + 0.5 - px;
        double dy = y + 0.5 - py;
        double dz = z + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Fixes values that could have been broken by hand-editing or an import. Returns false if unusable. */
    boolean sanitize() {
        if (dimension == null || dimension.isBlank()) return false;
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (name == null || name.isBlank()) name = "Waypoint";
        if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH);
        if (icon == null || WaypointIcon.byKey(icon) == null) icon = WaypointIcon.PIN.key();
        if (kind == null) kind = KIND_NORMAL;
        color &= 0xFFFFFF;
        return true;
    }

    /** Same place and name: treated as the same waypoint when importing. */
    boolean sameAs(Waypoint other) {
        return x == other.x && y == other.y && z == other.z
            && Objects.equals(dimension, other.dimension) && Objects.equals(name, other.name);
    }

    public Waypoint copy() {
        Waypoint w = new Waypoint(name, dimension, x, y, z, color, icon);
        w.id = id;
        w.enabled = enabled;
        w.showInWorld = showInWorld;
        w.kind = kind;
        w.created = created;
        return w;
    }
}
