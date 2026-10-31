package com.lumenatlas.core.marker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The discovered structures of one world. Detections of the same structure type that fall within the type's merge
 * radius of an existing marker update that marker instead of adding a new one, so a village that is detected from
 * many chunks (bells, villagers...) ends up as a single icon near its center.
 *
 * <p>Not thread-safe: used from the client thread; the IO thread only receives {@link #toJson()} output.
 */
public final class MarkerSet {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT = 1;
    /** Detections beyond this weight barely move a marker any more. */
    private static final int MAX_WEIGHT = 32;

    public record MergeResult(Marker marker, boolean created) {}

    private final List<Marker> markers = new ArrayList<>();
    private boolean dirty;

    public List<Marker> all() {
        return Collections.unmodifiableList(markers);
    }

    public int size() {
        return markers.size();
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markSaved() {
        dirty = false;
    }

    /**
     * Adds a detection. If a marker of the same type and dimension is within {@code mergeRadius} blocks
     * (horizontally), it is moved towards the detection (weighted by its hit count) and returned with
     * {@code created == false}; otherwise a new marker is created.
     */
    public MergeResult add(String type, String dimension, int x, int y, int z, int mergeRadius, long now) {
        Marker nearest = nearest(type, dimension, x, z, mergeRadius);
        if (nearest == null) {
            Marker marker = new Marker(type, dimension, x, y, z, now);
            markers.add(marker);
            dirty = true;
            return new MergeResult(marker, true);
        }
        int weight = Math.min(nearest.hits, MAX_WEIGHT);
        int nx = Math.round((nearest.x * (float) weight + x) / (weight + 1));
        int ny = Math.round((nearest.y * (float) weight + y) / (weight + 1));
        int nz = Math.round((nearest.z * (float) weight + z) / (weight + 1));
        if (nx != nearest.x || ny != nearest.y || nz != nearest.z || nearest.hits < MAX_WEIGHT) dirty = true;
        nearest.x = nx;
        nearest.y = ny;
        nearest.z = nz;
        nearest.hits = Math.min(nearest.hits + 1, 1 << 20);
        absorbNeighbours(nearest, mergeRadius);
        return new MergeResult(nearest, false);
    }

    /** After a marker moved, it may now overlap another marker of its type: combine them. */
    private void absorbNeighbours(Marker target, int mergeRadius) {
        long limit = (long) mergeRadius * mergeRadius;
        for (int i = markers.size() - 1; i >= 0; i--) {
            Marker other = markers.get(i);
            if (other == target || !other.type.equals(target.type) || !other.dimension.equals(target.dimension)) continue;
            if (other.horizontalDistanceSq(target.x, target.z) > limit) continue;
            int wa = Math.min(target.hits, MAX_WEIGHT);
            int wb = Math.min(other.hits, MAX_WEIGHT);
            target.x = Math.round((target.x * (float) wa + other.x * (float) wb) / (wa + wb));
            target.y = Math.round((target.y * (float) wa + other.y * (float) wb) / (wa + wb));
            target.z = Math.round((target.z * (float) wa + other.z * (float) wb) / (wa + wb));
            target.hits += other.hits;
            target.hidden |= other.hidden;
            target.firstSeen = Math.min(target.firstSeen, other.firstSeen);
            markers.remove(i);
            dirty = true;
        }
    }

    /** The closest marker of a type within {@code radius} blocks, or null. */
    public Marker nearest(String type, String dimension, int x, int z, int radius) {
        long best = (long) radius * radius;
        Marker found = null;
        for (Marker m : markers) {
            if (!m.type.equals(type) || !m.dimension.equals(dimension)) continue;
            long d = m.horizontalDistanceSq(x, z);
            if (d <= best) {
                best = d;
                found = m;
            }
        }
        return found;
    }

    public boolean remove(Marker marker) {
        boolean removed = markers.remove(marker);
        dirty |= removed;
        return removed;
    }

    public void setHidden(Marker marker, boolean hidden) {
        if (marker.hidden != hidden) {
            marker.hidden = hidden;
            dirty = true;
        }
    }

    public void clear() {
        if (!markers.isEmpty()) dirty = true;
        markers.clear();
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.add("markers", GSON.toJsonTree(markers));
        return GSON.toJson(root);
    }

    /** Replaces the content with the markers in {@code json}. Invalid entries are skipped. */
    public void loadJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Marker> loaded = GSON.fromJson(root.get("markers"), new TypeToken<List<Marker>>() {}.getType());
        markers.clear();
        if (loaded != null) {
            for (Marker m : loaded) {
                if (m != null && m.isValid()) markers.add(m);
            }
        }
        dirty = false;
    }
}
