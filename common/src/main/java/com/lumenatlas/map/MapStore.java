package com.lumenatlas.map;

import com.lumenatlas.LumenAtlas;
import com.lumenatlas.core.io.AtomicFiles;
import com.lumenatlas.core.map.MapRegion;
import com.lumenatlas.world.IoWorker;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The explored map of one dimension of one world: an LRU cache of {@link MapRegion}s backed by region files.
 *
 * <p>Regions are read on the IO thread; until a read finishes, {@link #get} returns null and {@link #getForWrite}
 * refuses to hand out the region (so a fresh scan can never overwrite data that is still on its way from disk).
 * Everything except the actual file access happens on the client thread.
 */
public final class MapStore {
    private static final int MAX_LOADED = 96;

    private final Path dir;
    private final String dimension;
    private final Long2ObjectLinkedOpenHashMap<MapRegion> loaded = new Long2ObjectLinkedOpenHashMap<>();
    /** Regions that have a file on disk (filled once at startup, then kept up to date). */
    private final LongSet onDisk = new LongOpenHashSet();
    private final LongSet loading = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<MapRegion> finished = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> failed = new ConcurrentLinkedQueue<>();
    private boolean closed;

    public MapStore(Path dir, String dimension) {
        this.dir = dir;
        this.dimension = dimension;
        indexFiles();
    }

    public String dimension() {
        return dimension;
    }

    private void indexFiles() {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "r.*.lamr")) {
            for (Path file : files) {
                String[] parts = file.getFileName().toString().split("\\.");
                if (parts.length != 4) continue;
                try {
                    onDisk.add(MapRegion.key(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            }
        } catch (IOException e) {
            LumenAtlas.LOGGER.warn("Could not list map regions in {}", dir, e);
        }
    }

    /** All regions that exist (on disk or in memory). */
    public List<Long> knownRegions() {
        LongOpenHashSet all = new LongOpenHashSet(onDisk);
        for (MapRegion r : loaded.values()) if (!r.isEmpty()) all.add(r.key());
        return new ArrayList<>(all);
    }

    public boolean exists(int regionX, int regionZ) {
        long key = MapRegion.key(regionX, regionZ);
        return onDisk.contains(key) || loaded.containsKey(key);
    }

    /** The region if it is in memory; otherwise starts loading it (when it exists) and returns null. */
    public MapRegion get(int regionX, int regionZ) {
        long key = MapRegion.key(regionX, regionZ);
        MapRegion region = loaded.getAndMoveToLast(key);
        if (region != null) return region;
        if (onDisk.contains(key)) requestLoad(key, regionX, regionZ);
        return null;
    }

    /** Like {@link #get} but without loading or touching the LRU order. */
    public MapRegion peek(int regionX, int regionZ) {
        return loaded.get(MapRegion.key(regionX, regionZ));
    }

    public boolean isLoading(int regionX, int regionZ) {
        return loading.contains(MapRegion.key(regionX, regionZ));
    }

    /**
     * The region for storing scan results: loaded, or created when it has no file. Returns null while the file is
     * still being read; callers try again later.
     */
    public MapRegion getForWrite(int regionX, int regionZ) {
        long key = MapRegion.key(regionX, regionZ);
        MapRegion region = loaded.getAndMoveToLast(key);
        if (region != null) return region;
        if (onDisk.contains(key)) {
            requestLoad(key, regionX, regionZ);
            return null;
        }
        region = new MapRegion(regionX, regionZ);
        loaded.putAndMoveToLast(key, region);
        return region;
    }

    private void requestLoad(long key, int regionX, int regionZ) {
        if (closed || !loading.add(key)) return;
        Path file = dir.resolve(MapRegion.fileName(regionX, regionZ));
        IoWorker.submit("map region " + file.getFileName(), () -> {
            try {
                MapRegion region = AtomicFiles.read(file, MapRegion::decode);
                if (region != null && region.regionX == regionX && region.regionZ == regionZ) finished.add(region);
                else failed.add(key);
            } catch (IOException e) {
                LumenAtlas.LOGGER.warn("Map region {} is damaged and will be rebuilt as you explore", file, e);
                failed.add(key);
            }
        });
    }

    /** Client thread: takes finished loads and evicts old regions. */
    public void tick() {
        MapRegion region;
        while ((region = finished.poll()) != null) {
            long key = region.key();
            loading.remove(key);
            MapRegion existing = loaded.get(key);
            if (existing == null) loaded.putAndMoveToLast(key, region);
        }
        Long key;
        while ((key = failed.poll()) != null) {
            loading.remove(key.longValue());
            onDisk.remove(key.longValue());
        }
        while (loaded.size() > MAX_LOADED) {
            MapRegion oldest = loaded.removeFirst();
            if (oldest.isDirty()) save(oldest);
        }
    }

    /** Saves every changed region in the background. */
    public void saveDirty() {
        for (MapRegion region : loaded.values()) {
            if (region.isDirty()) save(region);
        }
    }

    private void save(MapRegion region) {
        if (region.isEmpty()) {
            region.markSaved();
            return;
        }
        MapRegion snapshot = region.copy();
        region.markSaved();
        onDisk.add(region.key());
        Path file = dir.resolve(MapRegion.fileName(region.regionX, region.regionZ));
        IoWorker.submit("map region " + file.getFileName(), () -> AtomicFiles.write(file, snapshot.encode()));
    }

    public void close() {
        saveDirty();
        closed = true;
        loaded.clear();
    }

    /** Deletes the explored map of this dimension (used by "Reset map" in the settings). */
    public void deleteAll() {
        loaded.clear();
        List<Long> regions = new ArrayList<>(onDisk);
        onDisk.clear();
        IoWorker.submit("map reset", () -> {
            for (long key : regions) {
                Path file = dir.resolve(MapRegion.fileName(MapRegion.keyX(key), MapRegion.keyZ(key)));
                Files.deleteIfExists(file);
                Files.deleteIfExists(AtomicFiles.backupOf(file));
            }
        });
    }

    // Column lookups used by the minimap; they only read regions that are already in memory.

    /** Packed surface value of a block column, or -1 when unknown. Height goes to {@code heightOut[0]}. */
    public int column(int x, int z, int[] heightOut) {
        MapRegion region = get(MapRegion.regionCoord(x), MapRegion.regionCoord(z));
        if (region == null) return -1;
        int i = MapRegion.index(MapRegion.local(x), MapRegion.local(z));
        int h = region.height(i);
        if (h == MapRegion.UNKNOWN) return -1;
        heightOut[0] = h;
        return region.surface(i);
    }
}
