package com.lumenatlas.core;

import static org.junit.jupiter.api.Assertions.*;

import com.lumenatlas.core.marker.Marker;
import com.lumenatlas.core.marker.MarkerSet;
import org.junit.jupiter.api.Test;

class MarkerSetTest {
    private static final String OW = "minecraft:overworld";

    @Test
    void detectionsWithinRadiusMerge() {
        MarkerSet set = new MarkerSet();
        assertTrue(set.add("village", OW, 100, 64, 100, 96, 1).created());
        MarkerSet.MergeResult second = set.add("village", OW, 140, 66, 120, 96, 2);
        assertFalse(second.created());
        assertEquals(1, set.size());
        Marker m = set.all().get(0);
        assertEquals(120, m.x, "moved half way (equal weights)");
        assertEquals(110, m.z);
        assertEquals(2, m.hits);
    }

    @Test
    void detectionsOutsideRadiusStaySeparate() {
        MarkerSet set = new MarkerSet();
        set.add("village", OW, 0, 64, 0, 96, 1);
        set.add("village", OW, 500, 64, 0, 96, 1);
        assertEquals(2, set.size());
    }

    @Test
    void typesAndDimensionsNeverMerge() {
        MarkerSet set = new MarkerSet();
        set.add("village", OW, 0, 64, 0, 96, 1);
        set.add("mineshaft", OW, 5, 30, 5, 96, 1);
        set.add("village", "minecraft:the_nether", 0, 64, 0, 96, 1);
        assertEquals(3, set.size());
    }

    @Test
    void heavyMarkersMoveSlowly() {
        MarkerSet set = new MarkerSet();
        set.add("fortress", OW, 0, 64, 0, 200, 1);
        for (int i = 0; i < 40; i++) set.add("fortress", OW, 0, 64, 0, 200, 1);
        set.add("fortress", OW, 100, 64, 0, 200, 1);
        assertTrue(set.all().get(0).x < 10, "an outlier barely moves a well-established marker");
    }

    @Test
    void movedMarkerAbsorbsOverlappingNeighbour() {
        MarkerSet set = new MarkerSet();
        set.add("village", OW, 0, 64, 0, 50, 1);
        set.add("village", OW, 60, 64, 0, 50, 1);
        assertEquals(2, set.size());
        // Closest to the first marker, pulling it to x=10, which is within 50 of the second one.
        set.add("village", OW, 20, 64, 0, 50, 1);
        assertEquals(1, set.size());
    }

    @Test
    void hiddenFlagSurvivesMergeAndJson() {
        MarkerSet set = new MarkerSet();
        Marker m = set.add("igloo", OW, 10, 70, 10, 32, 5).marker();
        set.setHidden(m, true);
        set.add("igloo", OW, 12, 70, 12, 32, 6);
        MarkerSet loaded = new MarkerSet();
        loaded.loadJson(set.toJson());
        assertEquals(1, loaded.size());
        assertTrue(loaded.all().get(0).hidden);
        assertEquals(5, loaded.all().get(0).firstSeen);
        assertFalse(loaded.isDirty());
    }

    @Test
    void loadSkipsInvalidEntries() {
        MarkerSet set = new MarkerSet();
        set.loadJson("{\"format\":1,\"markers\":[{\"type\":\"village\",\"dimension\":\"minecraft:overworld\",\"x\":1},"
            + "{\"x\":5},null,{\"type\":\"\",\"dimension\":\"x\"}]}");
        assertEquals(1, set.size());
    }

    @Test
    void dirtyTracking() {
        MarkerSet set = new MarkerSet();
        assertFalse(set.isDirty());
        Marker m = set.add("village", OW, 0, 0, 0, 10, 1).marker();
        assertTrue(set.isDirty());
        set.markSaved();
        set.remove(m);
        assertTrue(set.isDirty());
    }
}
