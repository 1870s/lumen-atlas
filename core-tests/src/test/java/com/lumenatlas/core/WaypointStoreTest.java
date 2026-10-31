package com.lumenatlas.core;

import static org.junit.jupiter.api.Assertions.*;

import com.lumenatlas.core.waypoint.Waypoint;
import com.lumenatlas.core.waypoint.WaypointIcon;
import com.lumenatlas.core.waypoint.WaypointStore;
import org.junit.jupiter.api.Test;

class WaypointStoreTest {
    private static final String OW = "minecraft:overworld";

    private static Waypoint wp(String name, int x, int z) {
        return new Waypoint(name, OW, x, 64, z, 0xFF0000, WaypointIcon.HOUSE.key());
    }

    @Test
    void saveAndLoadRoundTrip() {
        WaypointStore store = new WaypointStore();
        Waypoint home = wp("Home", 10, -20);
        home.enabled = false;
        store.add(home);
        store.add(new Waypoint("Fortress", "minecraft:the_nether", 1, 2, 3, 0x00FF00, "portal"));
        WaypointStore loaded = new WaypointStore();
        loaded.loadJson(store.toJson());
        assertEquals(2, loaded.all().size());
        Waypoint h = loaded.byId(home.id);
        assertNotNull(h);
        assertEquals("Home", h.name);
        assertFalse(h.enabled);
        assertEquals(-20, h.z);
        assertEquals(1, loaded.inDimension("minecraft:the_nether").size());
    }

    @Test
    void importSkipsDuplicatesAndAssignsNewIds() {
        WaypointStore store = new WaypointStore();
        Waypoint home = wp("Home", 0, 0);
        store.add(home);
        String exported = WaypointStore.export(java.util.List.of(home, wp("Mine", 100, 100)));
        WaypointStore.ImportResult result = store.importJson(exported);
        assertEquals(1, result.added());
        assertEquals(1, result.skipped());
        assertEquals(2, store.all().size());
        assertEquals(2, store.all().stream().map(w -> w.id).distinct().count());
    }

    @Test
    void importAcceptsBareArray() {
        WaypointStore store = new WaypointStore();
        WaypointStore.ImportResult result = store.importJson("[{\"name\":\"A\",\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3}]");
        assertEquals(1, result.added());
        Waypoint w = store.all().get(0);
        assertEquals(WaypointIcon.PIN.key(), w.icon, "missing icon falls back to the pin");
    }

    @Test
    void importRejectsGarbageAndNewerFormats() {
        WaypointStore store = new WaypointStore();
        assertThrows(IllegalArgumentException.class, () -> store.importJson("not json {"));
        assertThrows(IllegalArgumentException.class, () -> store.importJson("{\"something\":1}"));
        assertThrows(IllegalArgumentException.class, () -> store.importJson("{\"format\":99,\"waypoints\":[]}"));
        assertTrue(store.all().isEmpty());
    }

    @Test
    void brokenEntriesAreSkippedNotFatal() {
        WaypointStore store = new WaypointStore();
        store.loadJson("{\"format\":1,\"waypoints\":[{\"name\":\"ok\",\"dimension\":\"minecraft:overworld\"},"
            + "{\"name\":\"no dimension\"},{\"x\":\"not a number\",\"dimension\":\"d\"},{\"dimension\":\"\"}]}");
        assertEquals(2, store.all().size());
        assertEquals("minecraft:overworld", store.all().get(1).dimension, "a missing dimension means the overworld");
    }

    @Test
    void sanitizeTrimsLongNamesAndColor() {
        WaypointStore store = new WaypointStore();
        Waypoint w = wp("x".repeat(200), 0, 0);
        w.color = 0xFF123456;
        store.add(w);
        assertEquals(Waypoint.MAX_NAME_LENGTH, w.name.length());
        assertEquals(0x123456, w.color);
    }

    @Test
    void deathWaypointsKeepOnlyTheNewest() {
        WaypointStore store = new WaypointStore();
        store.add(wp("Home", 0, 0));
        for (int i = 0; i < 5; i++) {
            Waypoint death = wp("Death " + i, i, i);
            death.created = 1000 + i;
            store.addDeath(death, 3);
        }
        assertEquals(4, store.all().size());
        assertTrue(store.all().stream().noneMatch(w -> w.name.equals("Death 0") || w.name.equals("Death 1")));
        assertTrue(store.all().stream().anyMatch(w -> w.name.equals("Death 4")));
        store.addDeath(wp("Death x", 9, 9), 0);
        assertEquals(1, store.all().size(), "keep = 0 removes all death waypoints");
    }

    @Test
    void updateReplacesById() {
        WaypointStore store = new WaypointStore();
        Waypoint w = wp("Old", 0, 0);
        store.add(w);
        Waypoint edited = w.copy();
        edited.name = "New";
        store.update(edited);
        assertEquals(1, store.all().size());
        assertEquals("New", store.byId(w.id).name);
        assertTrue(store.remove(w.id));
        assertFalse(store.remove(w.id));
    }

    @Test
    void distance() {
        Waypoint w = wp("A", 0, 0);
        assertEquals(0.0, w.distanceTo(0.5, 64.5, 0.5), 1e-9);
        assertEquals(10.0, w.distanceTo(10.5, 64.5, 0.5), 1e-9);
    }
}
