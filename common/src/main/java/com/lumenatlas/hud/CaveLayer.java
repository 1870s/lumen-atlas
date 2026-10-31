package com.lumenatlas.hud;

import com.lumenatlas.core.map.MapShading;
import com.lumenatlas.core.map.MapView;
import com.lumenatlas.map.ColumnSampler;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * The minimap's live cave view: for each column, the first floor below the player's head height. Computed per chunk
 * on demand (a few chunks per frame) and thrown away when the player changes height or dimension. Not persisted;
 * the world map always shows the surface. Render thread only.
 */
final class CaveLayer {
    private static final int CHUNKS_PER_FRAME = 6;
    private static final long MAX_AGE_MS = 3000;

    private record ChunkData(int[] pixels, long createdAt) {}

    private final Long2ObjectOpenHashMap<ChunkData> chunks = new Long2ObjectOpenHashMap<>();
    private final ColumnSampler sampler = new ColumnSampler();
    private ClientLevel level;
    private int startY = Integer.MIN_VALUE;
    private int budget;

    void beginFrame(ClientLevel level, int playerY) {
        if (level != this.level || Math.abs(playerY - startY) >= 3) {
            chunks.clear();
            this.level = level;
            this.startY = playerY;
        }
        budget = CHUNKS_PER_FRAME;
        if (chunks.size() > 1024) chunks.clear();
    }

    /** ARGB pixel of a block column, 0 for unknown (not computed yet, or solid rock). */
    int pixel(int x, int z, MapView view) {
        int cx = x >> 4;
        int cz = z >> 4;
        long key = ChunkPos.pack(cx, cz);
        ChunkData data = chunks.get(key);
        long now = System.currentTimeMillis();
        if (data == null || (now - data.createdAt > MAX_AGE_MS && budget > 0)) {
            if (budget <= 0) return data != null ? data.pixels[((z & 15) << 4) | (x & 15)] : 0;
            budget--;
            data = compute(cx, cz, view, now);
            chunks.put(key, data);
        }
        return data.pixels[((z & 15) << 4) | (x & 15)];
    }

    private ChunkData compute(int cx, int cz, MapView view, long now) {
        int[] pixels = new int[256];
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null || chunk.isEmpty()) return new ChunkData(pixels, now);
        int[] heights = new int[256];
        int[] surface = new int[256];
        boolean[] known = new boolean[256];
        int baseX = cx << 4;
        int baseZ = cz << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (sampler.sample(level, chunk, baseX + x, baseZ + z, startY, true)) {
                    int i = (z << 4) | x;
                    known[i] = true;
                    heights[i] = sampler.height;
                    surface[i] = (Math.min(255, sampler.waterDepth) << 24) | sampler.rgb;
                }
            }
        }
        MapView shading = view == MapView.BIOMES ? MapView.SHADED : view;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = (z << 4) | x;
                if (!known[i]) continue;
                int north = z > 0 && known[i - 16] ? heights[i - 16] : MapShading.NO_NEIGHBOR;
                int west = x > 0 && known[i - 1] ? heights[i - 1] : MapShading.NO_NEIGHBOR;
                int color = MapShading.pixel(shading, surface[i], heights[i], north, west, 0);
                // Floors far below the player fade into darkness, which reads well as depth.
                int depthBelow = Math.max(0, startY - heights[i]);
                float dim = Math.max(0.35f, 1f - depthBelow / 40f);
                pixels[i] = com.lumenatlas.core.util.Colors.scale(color, dim);
            }
        }
        return new ChunkData(pixels, now);
    }

    void clear() {
        chunks.clear();
        level = null;
    }
}
