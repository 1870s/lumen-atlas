package com.lumenatlas.structure;

import java.util.List;

/**
 * One kind of structure as shown on the maps: its icon, color, how close two detections must be to count as the same
 * structure, and which vanilla structure ids belong to it (used to label seed-map predictions).
 *
 * @param key          stable key used in save files and settings
 * @param name         English display name
 * @param icon         index in the icon atlas
 * @param color        accent color 0xRRGGBB (icon ring, list entries)
 * @param mergeRadius  detections closer than this (blocks) are merged into one marker
 * @param structureIds vanilla structure ids ("minecraft:village_plains"); ids ending in '*' match a prefix
 * @param detectable   whether the client can recognize it from blocks/entities (otherwise seed map only)
 */
public record StructureKind(String key, String name, int icon, int color, int mergeRadius, List<String> structureIds,
                            boolean detectable) {
    public boolean matches(String structureId) {
        for (String id : structureIds) {
            if (id.endsWith("*") ? structureId.startsWith(id.substring(0, id.length() - 1)) : structureId.equals(id)) return true;
        }
        return false;
    }
}
