package com.lumenatlas.core.marker;

/**
 * A structure found by the player (discovered) in one dimension. Plain data object; serialized with Gson.
 */
public final class Marker {
    /** Structure type key, e.g. "village" (see the structure type list of the mod). */
    public String type;
    /** Dimension id, e.g. "minecraft:overworld". */
    public String dimension;
    public int x;
    public int y;
    public int z;
    /** How many detections were merged into this marker; used to weight position updates. */
    public int hits = 1;
    /** When the structure was first detected (epoch milliseconds). */
    public long firstSeen;
    /** Hidden by the player (kept so the same structure is not detected again). */
    public boolean hidden;

    public Marker() {}

    public Marker(String type, String dimension, int x, int y, int z, long firstSeen) {
        this.type = type;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.firstSeen = firstSeen;
    }

    public long horizontalDistanceSq(int px, int pz) {
        long dx = x - px;
        long dz = z - pz;
        return dx * dx + dz * dz;
    }

    boolean isValid() {
        return type != null && !type.isBlank() && dimension != null && !dimension.isBlank();
    }
}
