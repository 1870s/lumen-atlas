package com.lumenatlas.map;

import com.lumenatlas.LumenAtlas;
import com.lumenatlas.config.ConfigManager;
import com.lumenatlas.core.map.BiomePalette;
import com.lumenatlas.core.map.MapRegion;
import com.lumenatlas.core.map.MapShading;
import com.lumenatlas.core.map.MapView;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * GPU textures of map regions for the world map, in three levels of detail (1, 4 and 16 blocks per pixel) so a
 * zoomed-out map stays cheap. Textures are rebuilt when their region changed, but at most a few per frame and at
 * most every 300 ms per region, and the least recently drawn ones are freed when the cache is full.
 * Render thread only.
 */
public final class RegionTextures {
    public static final int[] LOD_STEP = {1, 4, 16};
    private static final long REBUILD_INTERVAL_MS = 300;
    private static final long FRAME_BUILD_BUDGET_NS = 4_000_000;

    private final MapStore store;
    private final String idPrefix;
    @SuppressWarnings("unchecked")
    private final Long2ObjectLinkedOpenHashMap<Entry>[] entries = new Long2ObjectLinkedOpenHashMap[LOD_STEP.length];
    private long frameDeadline;

    private static final class Entry {
        final DynamicTexture texture;
        final Identifier id;
        long revision = -1;
        MapView view;
        long builtAt;

        Entry(DynamicTexture texture, Identifier id) {
            this.texture = texture;
            this.id = id;
        }
    }

    public RegionTextures(MapStore store, String idPrefix) {
        this.store = store;
        this.idPrefix = idPrefix;
        for (int i = 0; i < entries.length; i++) entries[i] = new Long2ObjectLinkedOpenHashMap<>();
    }

    public MapStore store() {
        return store;
    }

    /** Call once per frame before {@link #get}. */
    public void beginFrame() {
        frameDeadline = System.nanoTime() + FRAME_BUILD_BUDGET_NS;
        int capacity = ConfigManager.get().textureCacheSize;
        for (Long2ObjectLinkedOpenHashMap<Entry> map : entries) {
            while (map.size() > capacity) release(map.removeFirst());
        }
    }

    /** The GPU texture behind an id returned by {@link #get}, or null. */
    public DynamicTexture texture(int regionX, int regionZ, int lod) {
        Entry entry = entries[lod].get(MapRegion.key(regionX, regionZ));
        return entry == null ? null : entry.texture;
    }

    /**
     * The texture of a region at a detail level, building or updating it if needed. Returns null when there is
     * nothing to draw yet (region unexplored or still loading).
     */
    public Identifier get(int regionX, int regionZ, int lod, MapView view) {
        long key = MapRegion.key(regionX, regionZ);
        Long2ObjectLinkedOpenHashMap<Entry> map = entries[lod];
        Entry entry = map.getAndMoveToLast(key);
        boolean hasImage = entry != null && entry.revision >= 0;
        // Only load a region from disk when there is no image of it yet; a zoomed-out map shows far more regions
        // than the region cache holds, and reloading them every frame would just churn the disk.
        MapRegion region = store.peek(regionX, regionZ);
        if (region == null && !(hasImage && entry.view == view)) region = store.get(regionX, regionZ);
        if (region == null || region.isEmpty()) return hasImage ? entry.id : null;
        boolean upToDate = entry != null && entry.revision == region.revision() && entry.view == view;
        if (upToDate) return entry.id;
        long now = System.currentTimeMillis();
        if (hasImage && entry.view == view && now - entry.builtAt < REBUILD_INTERVAL_MS) return entry.id;
        if (System.nanoTime() > frameDeadline) return hasImage ? entry.id : null;
        if (entry == null) {
            int size = MapRegion.SIZE / LOD_STEP[lod];
            Identifier id = LumenAtlas.id(idPrefix + "/" + lod + "/" + regionX + "_" + regionZ);
            DynamicTexture texture = new DynamicTexture(() -> "Lumen Atlas region " + regionX + "," + regionZ, size, size, true);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            entry = new Entry(texture, id);
            map.putAndMoveToLast(key, entry);
        }
        fill(entry.texture.getPixels(), region, LOD_STEP[lod], view);
        entry.texture.upload();
        entry.revision = region.revision();
        entry.view = view;
        entry.builtAt = now;
        return entry.id;
    }

    private void fill(NativeImage image, MapRegion region, int step, MapView view) {
        MapRegion north = store.peek(region.regionX, region.regionZ - 1);
        MapRegion west = store.peek(region.regionX - 1, region.regionZ);
        int size = MapRegion.SIZE / step;
        for (int pz = 0; pz < size; pz++) {
            int lz = pz * step;
            for (int px = 0; px < size; px++) {
                int lx = px * step;
                int i = MapRegion.index(lx, lz);
                int h = region.height(i);
                if (h == MapRegion.UNKNOWN) {
                    image.setPixel(px, pz, 0);
                    continue;
                }
                int northH = lz >= step ? region.height(MapRegion.index(lx, lz - step))
                    : north != null ? north.height(MapRegion.index(lx, MapRegion.SIZE - step)) : MapRegion.UNKNOWN;
                int westH = lx >= step ? region.height(MapRegion.index(lx - step, lz))
                    : west != null ? west.height(MapRegion.index(MapRegion.SIZE - step, lz)) : MapRegion.UNKNOWN;
                int biome = view == MapView.BIOMES ? BiomePalette.color(region.biome(i)) : 0;
                image.setPixel(px, pz, MapShading.pixel(view, region.surface(i), h,
                    northH == MapRegion.UNKNOWN ? MapShading.NO_NEIGHBOR : northH,
                    westH == MapRegion.UNKNOWN ? MapShading.NO_NEIGHBOR : westH, biome));
            }
        }
    }

    private static void release(Entry entry) {
        Minecraft.getInstance().getTextureManager().release(entry.id);
    }

    public void close() {
        for (Long2ObjectLinkedOpenHashMap<Entry> map : entries) {
            for (Entry e : map.values()) release(e);
            map.clear();
        }
    }
}
