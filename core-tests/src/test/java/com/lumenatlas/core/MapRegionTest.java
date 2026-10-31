package com.lumenatlas.core;

import static org.junit.jupiter.api.Assertions.*;

import com.lumenatlas.core.map.MapRegion;
import com.lumenatlas.core.map.MapShading;
import com.lumenatlas.core.map.MapView;
import com.lumenatlas.core.util.Colors;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class MapRegionTest {
    @Test
    void newRegionIsUnknown() {
        MapRegion region = new MapRegion(3, -2);
        assertTrue(region.isEmpty());
        assertFalse(region.isKnown(0, 0));
        assertEquals(MapRegion.UNKNOWN, region.height(MapRegion.index(10, 10)));
    }

    @Test
    void setStoresColumnAndTracksChanges() {
        MapRegion region = new MapRegion(0, 0);
        long before = region.revision();
        assertTrue(region.set(5, 7, 0x123456, 70, 0, "minecraft:plains"));
        assertFalse(region.set(5, 7, 0x123456, 70, 0, "minecraft:plains"), "identical data is not a change");
        int i = MapRegion.index(5, 7);
        assertEquals(0x123456, region.surface(i) & 0xFFFFFF);
        assertEquals(70, region.height(i));
        assertEquals("minecraft:plains", region.biome(i));
        assertEquals(1, region.knownColumns());
        assertTrue(region.revision() > before);
        assertTrue(region.isDirty());
        region.markSaved();
        assertFalse(region.isDirty());
    }

    @Test
    void waterDepthIsPackedInTopByte() {
        MapRegion region = new MapRegion(0, 0);
        region.set(1, 1, 0x3F76E4, 62, 9, null);
        assertEquals(9, region.surface(MapRegion.index(1, 1)) >>> 24);
        region.set(2, 2, 0x3F76E4, 62, 999, null);
        assertEquals(255, region.surface(MapRegion.index(2, 2)) >>> 24, "depth is capped");
    }

    @Test
    void encodeDecodeRoundTrip() throws IOException {
        MapRegion region = new MapRegion(-4, 9);
        for (int x = 0; x < MapRegion.SIZE; x += 3) {
            for (int z = 0; z < MapRegion.SIZE; z += 5) {
                region.set(x, z, (x * 31 + z) & 0xFFFFFF, -64 + x, z % 4, z % 2 == 0 ? "minecraft:forest" : "minecraft:desert");
            }
        }
        MapRegion decoded = MapRegion.decode(region.encode());
        assertEquals(-4, decoded.regionX);
        assertEquals(9, decoded.regionZ);
        assertEquals(region.knownColumns(), decoded.knownColumns());
        for (int i = 0; i < MapRegion.AREA; i++) {
            assertEquals(region.height(i), decoded.height(i));
            assertEquals(region.surface(i), decoded.surface(i));
            assertEquals(region.biome(i), decoded.biome(i));
        }
        assertFalse(decoded.isDirty());
    }

    @Test
    void decodeRejectsForeignData() {
        assertThrows(IOException.class, () -> MapRegion.decode(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}));
    }

    @Test
    void copyIsIndependent() {
        MapRegion region = new MapRegion(0, 0);
        region.set(0, 0, 0xFF0000, 10, 0, null);
        MapRegion copy = region.copy();
        region.set(0, 0, 0x00FF00, 11, 0, null);
        assertEquals(0xFF0000, copy.surface(0) & 0xFFFFFF);
        assertEquals(10, copy.height(0));
    }

    @Test
    void keyRoundTripsNegativeCoordinates() {
        long key = MapRegion.key(-7, 123456);
        assertEquals(-7, MapRegion.keyX(key));
        assertEquals(123456, MapRegion.keyZ(key));
        assertEquals(-1, MapRegion.regionCoord(-1));
        assertEquals(255, MapRegion.local(-1));
    }

    @Test
    void shadingLightsNorthWestSlopes() {
        int surface = 0x808080;
        int flat = MapShading.pixel(MapView.SHADED, surface, 70, 70, 70, 0);
        int uphill = MapShading.pixel(MapView.SHADED, surface, 74, 70, 70, 0);
        int downhill = MapShading.pixel(MapView.SHADED, surface, 66, 70, 70, 0);
        assertTrue(Colors.red(uphill) > Colors.red(flat));
        assertTrue(Colors.red(downhill) < Colors.red(flat));
        assertEquals(0xFF, Colors.alpha(flat));
    }

    @Test
    void deeperWaterIsDarker() {
        int shallow = MapShading.pixel(MapView.SHADED, (1 << 24) | 0x3F76E4, 62, 62, 62, 0);
        int deep = MapShading.pixel(MapView.SHADED, (15 << 24) | 0x3F76E4, 62, 62, 62, 0);
        assertTrue(Colors.blue(deep) < Colors.blue(shallow));
    }

    @Test
    void flatViewIgnoresRelief() {
        int a = MapShading.pixel(MapView.FLAT, 0x808080, 90, 70, 70, 0);
        int b = MapShading.pixel(MapView.FLAT, 0x808080, 50, 70, 70, 0);
        assertEquals(a, b);
    }

    @Test
    void topographicDrawsContourLines() {
        int inside = MapShading.pixel(MapView.TOPOGRAPHIC, 0, 70, 70, 70, 0);
        int onLine = MapShading.pixel(MapView.TOPOGRAPHIC, 0, 80, 79, 80, 0);
        int next = MapShading.pixel(MapView.TOPOGRAPHIC, 0, 81, 81, 81, 0);
        assertNotEquals(onLine, next);
        assertTrue(Colors.green(onLine) < Colors.green(next));
        assertEquals(0xFF, Colors.alpha(inside));
    }
}
