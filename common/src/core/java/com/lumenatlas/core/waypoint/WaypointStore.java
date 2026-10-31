package com.lumenatlas.core.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * All waypoints of one world (every dimension). Not thread-safe: used from the client thread.
 *
 * <p>The JSON layout is the same for the save file and for exports, so an exported file can be imported into any
 * other world.
 */
public final class WaypointStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int FORMAT = 1;

    public record ImportResult(int added, int skipped) {}

    private final List<Waypoint> waypoints = new ArrayList<>();
    private boolean dirty;

    public List<Waypoint> all() {
        return Collections.unmodifiableList(waypoints);
    }

    public List<Waypoint> inDimension(String dimension) {
        List<Waypoint> result = new ArrayList<>();
        for (Waypoint w : waypoints) {
            if (w.dimension.equals(dimension)) result.add(w);
        }
        return result;
    }

    public Waypoint byId(String id) {
        for (Waypoint w : waypoints) {
            if (w.id.equals(id)) return w;
        }
        return null;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markSaved() {
        dirty = false;
    }

    /** Call after changing a waypoint's fields directly. */
    public void markChanged() {
        dirty = true;
    }

    public void add(Waypoint waypoint) {
        if (!waypoint.sanitize()) throw new IllegalArgumentException("Waypoint has no dimension");
        waypoints.add(waypoint);
        dirty = true;
    }

    /** Replaces the waypoint with the same id (or adds it when there is none). */
    public void update(Waypoint waypoint) {
        if (!waypoint.sanitize()) throw new IllegalArgumentException("Waypoint has no dimension");
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i).id.equals(waypoint.id)) {
                waypoints.set(i, waypoint);
                dirty = true;
                return;
            }
        }
        add(waypoint);
    }

    public boolean remove(String id) {
        boolean removed = waypoints.removeIf(w -> w.id.equals(id));
        dirty |= removed;
        return removed;
    }

    /**
     * Adds a death waypoint and removes the oldest death waypoints so at most {@code keep} remain.
     * {@code keep <= 0} removes all of them (the new one is not added).
     */
    public void addDeath(Waypoint death, int keep) {
        death.kind = Waypoint.KIND_DEATH;
        if (keep > 0) add(death);
        List<Waypoint> deaths = new ArrayList<>();
        for (Waypoint w : waypoints) {
            if (w.isDeath()) deaths.add(w);
        }
        deaths.sort(Comparator.comparingLong(w -> w.created));
        int excess = deaths.size() - Math.max(0, keep);
        for (int i = 0; i < excess; i++) waypoints.remove(deaths.get(i));
        dirty = true;
    }

    public String toJson() {
        return export(waypoints);
    }

    public static String export(List<Waypoint> list) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("generator", "Lumen Atlas");
        root.add("waypoints", GSON.toJsonTree(list));
        return GSON.toJson(root);
    }

    /** Replaces the content with the waypoints in {@code json} (the save file). Invalid entries are skipped. */
    public void loadJson(String json) {
        waypoints.clear();
        waypoints.addAll(parse(json));
        dirty = false;
    }

    /**
     * Adds the waypoints of an exported file. Entries that already exist (same name, position and dimension) are
     * skipped; imported waypoints get new ids so they can never replace existing ones.
     */
    public ImportResult importJson(String json) {
        int added = 0;
        int skipped = 0;
        for (Waypoint incoming : parse(json)) {
            boolean exists = false;
            for (Waypoint w : waypoints) {
                if (w.sameAs(incoming)) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                skipped++;
                continue;
            }
            incoming.id = java.util.UUID.randomUUID().toString();
            waypoints.add(incoming);
            added++;
        }
        if (added > 0) dirty = true;
        return new ImportResult(added, skipped);
    }

    /** Parses a waypoint file. Accepts the object layout and, for convenience, a bare JSON array of waypoints. */
    static List<Waypoint> parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Not a valid JSON file: " + e.getMessage(), e);
        }
        JsonArray array;
        if (root.isJsonArray()) {
            array = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("waypoints")) {
            JsonObject object = root.getAsJsonObject();
            if (object.has("format") && object.get("format").getAsInt() > FORMAT) {
                throw new IllegalArgumentException("The file was made by a newer version of Lumen Atlas");
            }
            array = object.getAsJsonArray("waypoints");
        } else {
            throw new IllegalArgumentException("The file does not contain waypoints");
        }
        List<Waypoint> result = new ArrayList<>();
        for (JsonElement element : array) {
            try {
                Waypoint w = GSON.fromJson(element, Waypoint.class);
                if (w != null && w.sanitize()) result.add(w);
            } catch (RuntimeException ignored) {
                // Skip broken entries, keep the rest.
            }
        }
        return result;
    }
}
