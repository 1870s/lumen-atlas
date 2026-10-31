package com.lumenatlas.map;

import com.lumenatlas.core.map.MapRegion;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Copies the surface of loaded client chunks into the {@link MapStore}, nearest chunks first, within a per-tick time
 * budget so it never causes a frame drop.
 *
 * <p>Every loaded chunk is scanned once when it arrives (a new {@link LevelChunk} instance, also after the server
 * re-sends it). Chunks right around the player are scanned again every second so block changes show up quickly.
 */
public final class ChunkScanner {
    private static final long NEARBY_RESCAN_MS = 1000;
    private static final long RESCAN_MS = 30_000;

    private final ColumnSampler sampler = new ColumnSampler();
    /** Chunk -> identity of the LevelChunk instance we scanned. */
    private final Long2IntOpenHashMap scannedInstance = new Long2IntOpenHashMap();
    private final Long2LongOpenHashMap scannedAt = new Long2LongOpenHashMap();
    private int[] offsets = new int[0];
    private int offsetsRadius = -1;
    private int cursor;
    private int scannedTotal;

    public int scannedTotal() {
        return scannedTotal;
    }

    /**
     * @param onNewChunk called for every chunk instance scanned for the first time (structure detection)
     */
    public void tick(ClientLevel level, MapStore store, int playerChunkX, int playerChunkZ, int radius, long budgetNanos,
                     Consumer<LevelChunk> onNewChunk) {
        long deadline = System.nanoTime() + budgetNanos;
        long now = System.currentTimeMillis();
        ensureOffsets(radius);
        boolean ceiling = level.dimensionType().hasCeiling();
        int ceilingStart = Math.min(level.getMaxY(), level.getMinY() + level.dimensionType().logicalHeight()) - 2;

        // Nearby chunks first, so what the player is doing right now is always on the map.
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                LevelChunk chunk = loadedChunk(level, playerChunkX + dx, playerChunkZ + dz);
                if (chunk == null) continue;
                long key = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
                if (now - scannedAt.getOrDefault(key, 0L) >= NEARBY_RESCAN_MS) {
                    scan(level, chunk, store, ceiling, ceilingStart, now, onNewChunk);
                }
            }
        }

        int checked = 0;
        while (checked < offsets.length / 2 && System.nanoTime() < deadline) {
            if (cursor >= offsets.length) cursor = 0;
            int cx = playerChunkX + offsets[cursor];
            int cz = playerChunkZ + offsets[cursor + 1];
            cursor += 2;
            checked++;
            LevelChunk chunk = loadedChunk(level, cx, cz);
            if (chunk == null) continue;
            long key = ChunkPos.pack(cx, cz);
            int identity = System.identityHashCode(chunk);
            boolean fresh = !scannedInstance.containsKey(key) || scannedInstance.get(key) != identity;
            if (fresh || now - scannedAt.getOrDefault(key, 0L) >= RESCAN_MS) {
                scan(level, chunk, store, ceiling, ceilingStart, now, onNewChunk);
            }
        }
        if (scannedInstance.size() > 20_000) prune(playerChunkX, playerChunkZ, radius + 8);
    }

    private static LevelChunk loadedChunk(ClientLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null || chunk.isEmpty()) return null;
        return chunk;
    }

    private void scan(ClientLevel level, LevelChunk chunk, MapStore store, boolean ceiling, int ceilingStart, long now,
                      Consumer<LevelChunk> onNewChunk) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        MapRegion region = store.getForWrite(MapRegion.regionCoord(baseX), MapRegion.regionCoord(baseZ));
        if (region == null) return; // still loading from disk; try again on a later pass
        long key = ChunkPos.pack(chunk.getPos().x(), chunk.getPos().z());
        int identity = System.identityHashCode(chunk);
        boolean firstTime = !scannedInstance.containsKey(key) || scannedInstance.get(key) != identity;
        int lx0 = MapRegion.local(baseX);
        int lz0 = MapRegion.local(baseZ);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (sampler.sample(level, chunk, baseX + x, baseZ + z, ceiling ? ceilingStart : Integer.MAX_VALUE, ceiling)) {
                    region.set(lx0 + x, lz0 + z, sampler.rgb, sampler.height, sampler.waterDepth, sampler.biome);
                }
            }
        }
        scannedInstance.put(key, identity);
        scannedAt.put(key, now);
        scannedTotal++;
        if (firstTime) onNewChunk.accept(chunk);
    }

    private void ensureOffsets(int radius) {
        if (radius == offsetsRadius) return;
        offsetsRadius = radius;
        List<int[]> list = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) list.add(new int[] {dx, dz});
        }
        list.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        offsets = new int[list.size() * 2];
        for (int i = 0; i < list.size(); i++) {
            offsets[i * 2] = list.get(i)[0];
            offsets[i * 2 + 1] = list.get(i)[1];
        }
        cursor = 0;
    }

    private void prune(int cx, int cz, int keep) {
        scannedInstance.keySet().removeIf(key -> Math.abs(ChunkPos.getX(key) - cx) > keep || Math.abs(ChunkPos.getZ(key) - cz) > keep);
        scannedAt.keySet().removeIf(key -> !scannedInstance.containsKey(key));
    }

    /** Forget everything (dimension change). */
    public void reset() {
        scannedInstance.clear();
        scannedAt.clear();
        cursor = 0;
    }
}
