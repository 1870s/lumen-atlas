package com.lumenatlas.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every structure kind the maps know about. The order is the order of the layer list on the world map. */
public final class StructureKinds {
    private static final Map<String, StructureKind> BY_KEY = new LinkedHashMap<>();

    // Icon indices match the order of the structure icons in textures/gui/icons.png (row 0 and 1).
    public static final StructureKind VILLAGE = add("village", "Village", 0, 0xF2C14E, 112, true, "minecraft:village_*");
    public static final StructureKind STRONGHOLD = add("stronghold", "Stronghold", 1, 0x7BE0B5, 160, true, "minecraft:stronghold");
    public static final StructureKind ANCIENT_CITY = add("ancient_city", "Ancient City", 2, 0x2FD4D0, 128, true, "minecraft:ancient_city");
    public static final StructureKind TRIAL_CHAMBERS = add("trial_chambers", "Trial Chambers", 3, 0xE8873A, 128, true, "minecraft:trial_chambers");
    public static final StructureKind MANSION = add("mansion", "Woodland Mansion", 4, 0x9C6B3F, 96, true, "minecraft:mansion");
    public static final StructureKind OUTPOST = add("outpost", "Pillager Outpost", 5, 0x8D99A6, 64, true, "minecraft:pillager_outpost");
    public static final StructureKind DESERT_PYRAMID = add("desert_pyramid", "Desert Pyramid", 6, 0xE3C77B, 48, true, "minecraft:desert_pyramid");
    public static final StructureKind JUNGLE_TEMPLE = add("jungle_temple", "Jungle Temple", 7, 0x6DBE45, 48, true, "minecraft:jungle_pyramid");
    public static final StructureKind MONUMENT = add("monument", "Ocean Monument", 8, 0x4FC3B8, 96, true, "minecraft:monument");
    public static final StructureKind SHIPWRECK = add("shipwreck", "Shipwreck", 9, 0xB98A57, 40, true, "minecraft:shipwreck", "minecraft:shipwreck_beached");
    public static final StructureKind FORTRESS = add("fortress", "Nether Fortress", 10, 0xF0A030, 176, true, "minecraft:fortress");
    public static final StructureKind BASTION = add("bastion", "Bastion Remnant", 11, 0xD9B44A, 96, true, "minecraft:bastion_remnant");
    public static final StructureKind END_CITY = add("end_city", "End City", 12, 0xC48BE8, 96, true, "minecraft:end_city");
    public static final StructureKind MINESHAFT = add("mineshaft", "Mineshaft", 13, 0xA0A0A0, 96, true, "minecraft:mineshaft", "minecraft:mineshaft_mesa");
    public static final StructureKind RUINED_PORTAL = add("ruined_portal", "Ruined Portal", 14, 0x9B5DE5, 32, true, "minecraft:ruined_portal*");
    public static final StructureKind IGLOO = add("igloo", "Igloo", 15, 0xDDEEFF, 32, true, "minecraft:igloo");
    public static final StructureKind SWAMP_HUT = add("swamp_hut", "Witch Hut", 16, 0x7E8C4A, 32, true, "minecraft:swamp_hut");
    public static final StructureKind OCEAN_RUIN = add("ocean_ruin", "Ocean Ruins", 17, 0x5DA9E9, 48, true, "minecraft:ocean_ruin_*");
    public static final StructureKind TRAIL_RUINS = add("trail_ruins", "Trail Ruins", 18, 0xC97B5A, 64, true, "minecraft:trail_ruins");
    public static final StructureKind BURIED_TREASURE = add("buried_treasure", "Buried Treasure", 19, 0xFFD24A, 16, false, "minecraft:buried_treasure");
    public static final StructureKind NETHER_FOSSIL = add("nether_fossil", "Nether Fossil", 20, 0xE6E0D0, 32, true, "minecraft:nether_fossil");
    public static final StructureKind ABANDONED_CAMP = add("abandoned_camp", "Abandoned Camp", 21, 0xB07A4F, 32, false, "minecraft:abandoned_camp*");
    public static final StructureKind GEODE = add("geode", "Amethyst Geode", 22, 0xB37BE6, 24, true);
    public static final StructureKind DUNGEON = add("dungeon", "Monster Dungeon", 23, 0x8F8F8F, 16, true);
    /** Seed map entries for structures from data packs. */
    public static final StructureKind OTHER = add("other", "Other Structure", 24, 0xBBBBBB, 32, false);

    /** Drawing priority when icons overlap: rare, important structures win over common ones. */
    private static final List<StructureKind> PRIORITY = List.of(STRONGHOLD, ANCIENT_CITY, MANSION, MONUMENT, END_CITY, TRIAL_CHAMBERS,
        VILLAGE, FORTRESS, BASTION, OUTPOST, DESERT_PYRAMID, JUNGLE_TEMPLE, IGLOO, SWAMP_HUT, TRAIL_RUINS, ABANDONED_CAMP, SHIPWRECK,
        OCEAN_RUIN, RUINED_PORTAL, MINESHAFT, BURIED_TREASURE, NETHER_FOSSIL, GEODE, DUNGEON, OTHER);

    private StructureKinds() {}

    /** Lower is more important. */
    public static int priority(StructureKind kind) {
        int i = PRIORITY.indexOf(kind);
        return i < 0 ? PRIORITY.size() : i;
    }

    /**
     * Very common kinds: no chat message when found, and hidden on a zoomed-out world map so the important
     * structures stay readable.
     */
    public static boolean isMinor(StructureKind kind) {
        return kind == GEODE || kind == DUNGEON || kind == MINESHAFT || kind == NETHER_FOSSIL || kind == BURIED_TREASURE
            || kind == RUINED_PORTAL || kind == SHIPWRECK || kind == OCEAN_RUIN || kind == ABANDONED_CAMP;
    }

    private static StructureKind add(String key, String name, int icon, int color, int mergeRadius, boolean detectable, String... ids) {
        StructureKind kind = new StructureKind(key, name, icon, color, mergeRadius, List.of(ids), detectable);
        BY_KEY.put(key, kind);
        return kind;
    }

    public static StructureKind byKey(String key) {
        StructureKind kind = BY_KEY.get(key);
        return kind != null ? kind : OTHER;
    }

    public static List<StructureKind> all() {
        return Collections.unmodifiableList(new ArrayList<>(BY_KEY.values()));
    }

    /** The kind of a vanilla (or data pack) structure id. */
    public static StructureKind forStructureId(String structureId) {
        for (StructureKind kind : BY_KEY.values()) {
            if (kind.matches(structureId)) return kind;
        }
        return OTHER;
    }
}
