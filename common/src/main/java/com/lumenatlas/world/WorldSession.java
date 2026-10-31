package com.lumenatlas.world;

import com.lumenatlas.LumenAtlas;
import com.lumenatlas.config.ConfigManager;
import com.lumenatlas.core.LumenConfig;
import com.lumenatlas.core.io.AtomicFiles;
import com.lumenatlas.core.marker.MarkerSet;
import com.lumenatlas.core.util.Names;
import com.lumenatlas.core.waypoint.Waypoint;
import com.lumenatlas.core.waypoint.WaypointIcon;
import com.lumenatlas.core.waypoint.WaypointStore;
import com.lumenatlas.map.ChunkScanner;
import com.lumenatlas.map.MapStore;
import com.lumenatlas.map.RegionTextures;
import com.lumenatlas.seed.SeedMapService;
import com.lumenatlas.structure.StructureDetector;
import com.lumenatlas.structure.StructureKind;
import com.lumenatlas.structure.StructureKinds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Everything that belongs to the world the player is in: its explored maps (one per dimension), discovered
 * structures, waypoints and seed map settings. Created when a world is joined and closed (saved) when it is left.
 *
 * <p>Worlds are told apart by their save folder (singleplayer), server address (multiplayer) or realm name
 * (Realms), so every world and server has its own data in {@code <game dir>/lumenatlas/worlds/<key>/}.
 */
public final class WorldSession {
    private static final int SAVE_INTERVAL_TICKS = 20 * 30;

    public final String key;
    public final String label;
    public final boolean singleplayer;
    private final Path root;
    private final Map<String, MapStore> maps = new HashMap<>();
    private final Map<String, RegionTextures> textures = new HashMap<>();
    private final MarkerSet markers = new MarkerSet();
    private final WaypointStore waypoints = new WaypointStore();
    private final ChunkScanner scanner = new ChunkScanner();
    private final StructureDetector detector = new StructureDetector();
    private final SeedMapService seedMap;
    private ClientLevel level;
    private String dimension;
    private boolean wasDead;
    private int ticks;

    private WorldSession(String key, String label, boolean singleplayer) {
        this.key = key;
        this.label = label;
        this.singleplayer = singleplayer;
        this.root = LumenAtlas.dataDir().resolve("worlds").resolve(key);
        loadJson("markers.json", markers::loadJson);
        loadJson("waypoints.json", waypoints::loadJson);
        this.seedMap = new SeedMapService(this);
        LumenAtlas.LOGGER.info("Lumen Atlas: opened map data for '{}' ({} waypoints, {} structures)", label, waypoints.all().size(), markers.size());
    }

    /** Keeps {@code current} in sync with the client: opens, switches or closes sessions as needed. */
    public static WorldSession update(WorldSession current, Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            if (current != null) current.close();
            return null;
        }
        // Between respawn/dimension packets the player can be missing for a moment; that is not leaving the world.
        if (mc.player == null) return current;
        String[] id = identify(mc);
        if (current == null || !current.key.equals(id[0])) {
            if (current != null) current.close();
            current = new WorldSession(id[0], id[1], mc.hasSingleplayerServer());
        }
        current.setLevel(level);
        return current;
    }

    /** {folder key, readable label} of the current world. */
    private static String[] identify(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            String folder = worldDir.getFileName() != null ? worldDir.getFileName().toString() : "world";
            return new String[] {"sp_" + Names.folderName(folder), folder};
        }
        ServerData data = mc.getCurrentServer();
        if (data == null) return new String[] {"mp_unknown", "Unknown server"};
        if (data.isRealm()) return new String[] {"realm_" + Names.folderName(data.name), data.name};
        return new String[] {"mp_" + Names.folderName(data.ip), data.ip};
    }

    private void setLevel(ClientLevel newLevel) {
        if (newLevel == level) return;
        level = newLevel;
        String newDimension = newLevel.dimension().identifier().toString();
        if (!newDimension.equals(dimension)) {
            dimension = newDimension;
            scanner.reset();
        }
    }

    public ClientLevel level() {
        return level;
    }

    /** Dimension the player is in, e.g. "minecraft:overworld". */
    public String dimension() {
        return dimension;
    }

    public MapStore map(String dimensionId) {
        return maps.computeIfAbsent(dimensionId, d -> new MapStore(root.resolve("dim").resolve(Names.dimensionFolder(d)), d));
    }

    public MapStore currentMap() {
        return map(dimension);
    }

    public RegionTextures textures(String dimensionId) {
        return textures.computeIfAbsent(dimensionId, d -> new RegionTextures(map(d), "map/" + Names.folderName(key) + "/" + Names.folderName(d)));
    }

    public MarkerSet markers() {
        return markers;
    }

    public WaypointStore waypoints() {
        return waypoints;
    }

    public SeedMapService seedMap() {
        return seedMap;
    }

    public Path root() {
        return root;
    }

    public void tick(Minecraft mc) {
        ticks++;
        LumenConfig config = ConfigManager.get();
        for (MapStore store : maps.values()) store.tick();
        if (mc.player != null && level != null) {
            int radius = Math.max(2, mc.options.getEffectiveRenderDistance() + 1);
            long budget = (long) (config.scanBudgetMs * 1_000_000L);
            scanner.tick(level, currentMap(), mc.player.chunkPosition().x(), mc.player.chunkPosition().z(), radius, budget,
                chunk -> {
                    if (config.detectStructures) detector.scanChunk(level, chunk, this::reportStructure);
                });
            if (config.detectStructures && ticks % 20 == 0) detector.scanEntities(level, this::reportStructure);
            checkDeath(mc, config);
        }
        seedMap.tick();
        if (ticks % SAVE_INTERVAL_TICKS == 0) save();
    }

    private void reportStructure(StructureKind kind, int x, int y, int z) {
        MarkerSet.MergeResult result = markers.add(kind.key(), dimension, x, y, z, kind.mergeRadius(), System.currentTimeMillis());
        if (result.created()) {
            LumenAtlas.LOGGER.info("Lumen Atlas: discovered {} at {} {} {} in {}", kind.name(), x, y, z, dimension);
            Minecraft mc = Minecraft.getInstance();
            if (ConfigManager.get().announceDiscoveries && mc.player != null && !StructureKinds.isMinor(kind)
                && ConfigManager.get().isStructureTypeVisible(kind.key())) {
                mc.player.sendSystemMessage(Component.translatable("lumenatlas.chat.discovered",
                    Component.literal(kind.name()).withStyle(ChatFormatting.LIGHT_PURPLE),
                    Component.literal(x + ", " + z).withStyle(ChatFormatting.GRAY)));
            }
        }
    }

    private void checkDeath(Minecraft mc, LumenConfig config) {
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead && config.deathWaypoints) {
            Waypoint death = new Waypoint("Death " + java.time.LocalTime.now().withNano(0), dimension,
                mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), 0xF87171, WaypointIcon.SKULL.key());
            waypoints.addDeath(death, config.keepDeathWaypoints);
            saveWaypoints();
        }
        wasDead = dead;
    }

    public void saveWaypoints() {
        if (!waypoints.isDirty()) return;
        byte[] json = waypoints.toJson().getBytes(StandardCharsets.UTF_8);
        waypoints.markSaved();
        Path file = root.resolve("waypoints.json");
        IoWorker.submit("waypoints", () -> AtomicFiles.write(file, json));
    }

    public void saveMarkers() {
        if (!markers.isDirty()) return;
        byte[] json = markers.toJson().getBytes(StandardCharsets.UTF_8);
        markers.markSaved();
        Path file = root.resolve("markers.json");
        IoWorker.submit("structure markers", () -> AtomicFiles.write(file, json));
    }

    public void save() {
        for (MapStore store : maps.values()) store.saveDirty();
        saveWaypoints();
        saveMarkers();
        seedMap.save();
    }

    private void close() {
        save();
        for (MapStore store : maps.values()) store.close();
        for (RegionTextures t : textures.values()) t.close();
        seedMap.close();
        IoWorker.flush(5000);
        LumenAtlas.LOGGER.info("Lumen Atlas: saved map data for '{}'", label);
    }

    private interface JsonLoader {
        void load(String json);
    }

    private void loadJson(String name, JsonLoader loader) {
        Path file = root.resolve(name);
        try {
            String json = AtomicFiles.read(file, bytes -> new String(bytes, StandardCharsets.UTF_8));
            if (json != null) loader.load(json);
        } catch (IOException | RuntimeException e) {
            // Keep the unreadable file so the next save cannot destroy data that might still be recoverable by hand.
            Path kept = file.resolveSibling(name + ".broken-" + System.currentTimeMillis());
            try {
                java.nio.file.Files.copy(file, kept);
            } catch (IOException copyFailed) {
                e.addSuppressed(copyFailed);
            }
            LumenAtlas.LOGGER.error("Lumen Atlas could not read {}; the file was kept as {}", file, kept.getFileName(), e);
        }
    }
}
