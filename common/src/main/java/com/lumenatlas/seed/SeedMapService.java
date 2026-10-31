package com.lumenatlas.seed;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lumenatlas.LumenAtlas;
import com.lumenatlas.core.io.AtomicFiles;
import com.lumenatlas.core.marker.MarkerSet;
import com.lumenatlas.core.util.SeedText;
import com.lumenatlas.structure.StructureKind;
import com.lumenatlas.structure.StructureKinds;
import com.lumenatlas.world.IoWorker;
import com.lumenatlas.world.WorldSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/**
 * The offline seed map: predicts where structures generate from the world seed, using Minecraft's own placement code
 * (see {@link WorldgenContext}). Predictions are computed on one background thread, nearest to the viewed area
 * first, and cached per placement cell, so panning around never blocks the game.
 *
 * <p>Seed sources: in singleplayer the real seed of the integrated server is used automatically; on servers and
 * Realms the seed is unknown to the client and can be entered by the player. Without a seed this service is simply
 * inactive; nothing about the rest of the mod depends on it.
 */
public final class SeedMapService {
    public enum Status { DISABLED, NO_SEED, LOADING, READY, UNSUPPORTED, ERROR }

    public record Prediction(StructureKind kind, String structureId, int x, int y, int z) {}

    /** seed.json of a world. */
    static final class Settings {
        String seed;
        String preset = WorldgenLoader.Preset.DEFAULT.name();
        boolean enabled = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** More cells than this per structure type and request means "zoom in to see this type". */
    private static final int MAX_CELLS_PER_SET = 4096;
    private static final int TILE_SHIFT = 9;
    private static final long REQUEST_INTERVAL_MS = 150;

    private final WorldSession session;
    private final Path file;
    private Settings settings = new Settings();
    private final Map<String, DimState> dims = new ConcurrentHashMap<>();
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
    private final Map<Integer, Long> lastRequest = new HashMap<>();
    private volatile int generation;
    private volatile boolean running = true;
    private Thread worker;

    private static final class SetInfo {
        final int index;
        final StructureSet set;
        final RandomSpreadStructurePlacement spread;
        final ConcentricRingsStructurePlacement rings;
        final List<StructureKind> kinds = new ArrayList<>();

        SetInfo(int index, StructureSet set) {
            this.index = index;
            this.set = set;
            this.spread = set.placement() instanceof RandomSpreadStructurePlacement r ? r : null;
            this.rings = set.placement() instanceof ConcentricRingsStructurePlacement c ? c : null;
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                String id = entry.structure().unwrapKey().map(k -> k.identifier().toString()).orElse("");
                StructureKind kind = StructureKinds.forStructureId(id);
                if (!kinds.contains(kind)) kinds.add(kind);
            }
        }
    }

    private final class DimState {
        final String dimension;
        final int gen;
        volatile Status status = Status.LOADING;
        volatile String error;
        volatile WorldgenContext context;
        volatile List<SetInfo> sets = List.of();
        final Map<Long, Boolean> cells = new ConcurrentHashMap<>();
        final Set<Long> pending = ConcurrentHashMap.newKeySet();
        final Map<Long, CopyOnWriteArrayList<Prediction>> tiles = new ConcurrentHashMap<>();
        final Set<String> tooFar = ConcurrentHashMap.newKeySet();
        volatile int found;

        DimState(String dimension, int gen) {
            this.dimension = dimension;
            this.gen = gen;
        }

        void add(Prediction p) {
            long tile = tileKey(p.x() >> TILE_SHIFT, p.z() >> TILE_SHIFT);
            tiles.computeIfAbsent(tile, k -> new CopyOnWriteArrayList<>()).add(p);
            found++;
        }
    }

    public SeedMapService(WorldSession session) {
        this.session = session;
        this.file = session.root().resolve("seed.json");
        try {
            Settings loaded = AtomicFiles.read(file, bytes -> GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Settings.class));
            if (loaded != null) settings = loaded;
        } catch (IOException | RuntimeException e) {
            LumenAtlas.LOGGER.warn("Could not read {}", file, e);
        }
    }

    // ---- settings ----

    public boolean isSingleplayerSeed() {
        return session.singleplayer;
    }

    public boolean isEnabled() {
        return settings.enabled;
    }

    public void setEnabled(boolean enabled) {
        if (settings.enabled == enabled) return;
        settings.enabled = enabled;
        reset();
        save();
    }

    /** The seed text the player entered (multiplayer), or null. */
    public String manualSeedText() {
        return settings.seed;
    }

    /** Sets the multiplayer seed (blank clears it). Returns false when the text is not usable. */
    public boolean setManualSeed(String text) {
        String clean = text == null || text.isBlank() ? null : text.trim();
        if (clean != null && SeedText.parse(clean).isEmpty()) return false;
        settings.seed = clean;
        reset();
        save();
        return true;
    }

    public WorldgenLoader.Preset preset() {
        try {
            return WorldgenLoader.Preset.valueOf(settings.preset);
        } catch (RuntimeException e) {
            return WorldgenLoader.Preset.DEFAULT;
        }
    }

    public void setPreset(WorldgenLoader.Preset preset) {
        settings.preset = preset.name();
        reset();
        save();
    }

    /** The seed in use: the server's in singleplayer, the entered one otherwise. */
    public OptionalLong seed() {
        if (session.singleplayer) {
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) return OptionalLong.of(server.overworld().getSeed());
        }
        return SeedText.parse(settings.seed);
    }

    public void save() {
        byte[] json = GSON.toJson(settings).getBytes(StandardCharsets.UTF_8);
        IoWorker.submit("seed settings", () -> AtomicFiles.write(file, json));
    }

    // ---- status ----

    public boolean isActive(String dimension) {
        if (!settings.enabled || seed().isEmpty()) return false;
        DimState state = dims.get(dimension);
        return state == null || (state.status != Status.UNSUPPORTED && state.status != Status.ERROR);
    }

    public Status status(String dimension) {
        if (!settings.enabled) return Status.DISABLED;
        if (seed().isEmpty()) return Status.NO_SEED;
        DimState state = dims.get(dimension);
        return state == null ? Status.LOADING : state.status;
    }

    public String error(String dimension) {
        DimState state = dims.get(dimension);
        return state == null ? null : state.error;
    }

    public int pendingTasks() {
        return queue.size();
    }

    public int predictionCount(String dimension) {
        DimState state = dims.get(dimension);
        return state == null ? 0 : state.found;
    }

    /** Structure kinds skipped in the last request because the view is too large for them. */
    public Set<String> tooFarKinds(String dimension) {
        DimState state = dims.get(dimension);
        return state == null ? Set.of() : Collections.unmodifiableSet(state.tooFar);
    }

    // ---- requests ----

    /**
     * Makes sure predictions for the area are (being) computed. Cheap to call every frame: a caller (identified by
     * {@code caller}) is served at most every 150 ms.
     */
    public void request(int caller, String dimension, int minX, int minZ, int maxX, int maxZ) {
        if (!isActive(dimension)) return;
        long now = System.currentTimeMillis();
        Long last = lastRequest.get(caller);
        if (last != null && now - last < REQUEST_INTERVAL_MS) return;
        lastRequest.put(caller, now);
        DimState state = state(dimension);
        if (state.status != Status.READY) return;
        WorldgenContext ctx = state.context;
        int minCX = minX >> 4;
        int minCZ = minZ >> 4;
        int maxCX = maxX >> 4;
        int maxCZ = maxZ >> 4;
        double centerX = (minCX + maxCX) / 2.0;
        double centerZ = (minCZ + maxCZ) / 2.0;
        List<long[]> work = new ArrayList<>();
        state.tooFar.clear();
        for (SetInfo info : state.sets) {
            if (info.spread == null) continue;
            int spacing = info.spread.spacing();
            int gx0 = Math.floorDiv(minCX, spacing);
            int gz0 = Math.floorDiv(minCZ, spacing);
            int gx1 = Math.floorDiv(maxCX, spacing);
            int gz1 = Math.floorDiv(maxCZ, spacing);
            long cellCount = (long) (gx1 - gx0 + 1) * (gz1 - gz0 + 1);
            if (cellCount > MAX_CELLS_PER_SET) {
                for (StructureKind k : info.kinds) state.tooFar.add(k.key());
                continue;
            }
            for (int gz = gz0; gz <= gz1; gz++) {
                for (int gx = gx0; gx <= gx1; gx++) {
                    long key = cellKey(info.index, gx, gz);
                    if (state.cells.containsKey(key) || !state.pending.add(key)) continue;
                    double dx = (gx + 0.5) * spacing - centerX;
                    double dz = (gz + 0.5) * spacing - centerZ;
                    work.add(new long[] {key, info.index, gx, gz, (long) (dx * dx + dz * dz)});
                }
            }
        }
        if (work.isEmpty()) return;
        // Far cells are pushed first so the nearest end up at the front of the queue.
        work.sort((a, b) -> Long.compare(b[4], a[4]));
        int gen = state.gen;
        for (long[] w : work) {
            SetInfo info = state.sets.get((int) w[1]);
            int gx = (int) w[2];
            int gz = (int) w[3];
            long key = w[0];
            queue.addFirst(() -> computeCell(state, gen, ctx, info, gx, gz, key));
        }
        if (queue.size() > 30_000) trimQueue();
    }

    /** Calls {@code action} for every prediction in the area that has not been confirmed by a discovered marker. */
    public void forEachVisible(String dimension, int minX, int minZ, int maxX, int maxZ, MarkerSet markers, Consumer<Prediction> action) {
        DimState state = dims.get(dimension);
        if (state == null || state.status != Status.READY || !isActive(dimension)) return;
        int tx0 = minX >> TILE_SHIFT;
        int tz0 = minZ >> TILE_SHIFT;
        int tx1 = maxX >> TILE_SHIFT;
        int tz1 = maxZ >> TILE_SHIFT;
        if ((long) (tx1 - tx0 + 1) * (tz1 - tz0 + 1) > 40_000) return;
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                List<Prediction> list = state.tiles.get(tileKey(tx, tz));
                if (list == null) continue;
                for (Prediction p : list) {
                    if (p.x() < minX || p.x() > maxX || p.z() < minZ || p.z() > maxZ) continue;
                    if (markers.nearest(p.kind().key(), dimension, p.x(), p.z(), p.kind().mergeRadius()) != null) continue;
                    action.accept(p);
                }
            }
        }
    }

    /** All predictions computed so far in a dimension (for search). */
    public List<Prediction> all(String dimension) {
        DimState state = dims.get(dimension);
        if (state == null) return List.of();
        List<Prediction> all = new ArrayList<>();
        for (List<Prediction> list : state.tiles.values()) all.addAll(list);
        return all;
    }

    private DimState state(String dimension) {
        int gen = generation;
        DimState state = dims.get(dimension);
        if (state != null && state.gen == gen) return state;
        DimState fresh = new DimState(dimension, gen);
        dims.put(dimension, fresh);
        startWorker();
        queue.addFirst(() -> prepare(fresh));
        return fresh;
    }

    // ---- worker ----

    private void prepare(DimState state) {
        if (state.gen != generation) return;
        try {
            OptionalLong seed = seed();
            if (seed.isEmpty()) {
                state.status = Status.NO_SEED;
                return;
            }
            WorldgenContext ctx = session.singleplayer ? singleplayerContext(state.dimension)
                : WorldgenLoader.isVanillaDimension(state.dimension) ? WorldgenLoader.create(seed.getAsLong(), preset(), state.dimension) : null;
            if (ctx == null) {
                state.status = Status.UNSUPPORTED;
                return;
            }
            List<SetInfo> sets = new ArrayList<>();
            for (Holder<StructureSet> holder : ctx.structureSets()) sets.add(new SetInfo(sets.size(), holder.value()));
            state.sets = sets;
            state.context = ctx;
            for (SetInfo info : sets) {
                if (info.rings == null) continue;
                List<ChunkPos> positions = ctx.structureState.getRingPositionsFor(info.rings);
                if (positions == null) continue;
                for (ChunkPos pos : positions) {
                    ctx.evaluate(info.set, pos).ifPresent(f -> state.add(prediction(f)));
                }
            }
            state.status = Status.READY;
            LumenAtlas.LOGGER.info("Lumen Atlas seed map ready for {} ({} structure sets)", state.dimension, sets.size());
        } catch (Throwable t) {
            state.status = Status.ERROR;
            state.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            LumenAtlas.LOGGER.error("Lumen Atlas could not prepare the seed map for {}", state.dimension, t);
        }
    }

    private static WorldgenContext singleplayerContext(String dimension) {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return null;
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
        if (level == null) return null;
        var source = level.getChunkSource();
        return new WorldgenContext(level.getSeed(), level.registryAccess(), source.getGenerator(), source.randomState(),
            source.getGeneratorState(), com.lumenatlas.compat.Compat.templates(server), level);
    }

    private void computeCell(DimState state, int gen, WorldgenContext ctx, SetInfo info, int gx, int gz, long key) {
        if (gen != generation || state.gen != gen) return;
        try {
            int spacing = info.spread.spacing();
            ChunkPos candidate = info.spread.getPotentialStructureChunk(ctx.seed, gx * spacing, gz * spacing);
            if (info.spread.isStructureChunk(ctx.structureState, candidate.x(), candidate.z())) {
                ctx.evaluate(info.set, candidate).ifPresent(f -> state.add(prediction(f)));
            }
        } catch (Throwable t) {
            LumenAtlas.LOGGER.debug("Seed map cell failed", t);
        } finally {
            state.cells.put(key, Boolean.TRUE);
            state.pending.remove(key);
        }
    }

    private static Prediction prediction(WorldgenContext.Found f) {
        String id = f.id();
        return new Prediction(StructureKinds.forStructureId(id), id, f.x(), f.y(), f.z());
    }

    private synchronized void startWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(() -> {
            while (running) {
                try {
                    Runnable task = queue.pollFirst(1, TimeUnit.SECONDS);
                    if (task != null) task.run();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    LumenAtlas.LOGGER.error("Lumen Atlas seed map task failed", t);
                }
            }
        }, "Lumen Atlas Seed Map");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.start();
    }

    private void trimQueue() {
        queue.clear();
        for (DimState s : dims.values()) s.pending.clear();
    }

    /** Drops all results (seed, preset or enabled state changed). */
    public void reset() {
        generation++;
        queue.clear();
        dims.clear();
        lastRequest.clear();
    }

    public void tick() {
        // Nothing to do per tick at the moment; predictions are driven by what the maps show.
    }

    public void close() {
        running = false;
        queue.clear();
        if (worker != null) worker.interrupt();
    }

    private static long cellKey(int setIndex, int gx, int gz) {
        return ((long) setIndex << 50) | ((gx & 0x1FFFFFFL) << 25) | (gz & 0x1FFFFFFL);
    }

    private static long tileKey(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
    }

    /** Dimension id -> vanilla Level key, for code that needs one. */
    static ResourceKey<Level> levelKey(String dimension) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
    }
}
