package com.lumenatlas.core;

import static org.junit.jupiter.api.Assertions.*;

import com.lumenatlas.core.io.AtomicFiles;
import com.lumenatlas.core.map.MapView;
import com.lumenatlas.core.util.Colors;
import com.lumenatlas.core.util.MapMath;
import com.lumenatlas.core.util.Names;
import com.lumenatlas.core.util.SeedText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UtilTest {
    @TempDir
    Path dir;

    @Test
    void seedParsingMatchesVanilla() {
        assertEquals(12345L, SeedText.parse(" 12345 ").getAsLong());
        assertEquals(-42L, SeedText.parse("-42").getAsLong());
        assertEquals((long) "Lumen".hashCode(), SeedText.parse("Lumen").getAsLong());
        assertEquals((long) "99999999999999999999".hashCode(), SeedText.parse("99999999999999999999").getAsLong(),
            "numbers that do not fit in a long are hashed, like vanilla");
        assertTrue(SeedText.parse("   ").isEmpty());
        assertTrue(SeedText.parse(null).isEmpty());
    }

    @Test
    void folderNamesAreSafe() {
        assertEquals("play.example.com_25565", Names.folderName("play.example.com:25565"));
        assertEquals("my_world__1_", Names.folderName("My World (1)"));
        assertEquals("unnamed", Names.folderName(".."));
        assertEquals("_con", Names.folderName("CON"));
        assertEquals("minecraft~the_nether", Names.dimensionFolder("minecraft:the_nether"));
        assertTrue(Names.folderName("x".repeat(300)).length() <= 80);
    }

    @Test
    void atomicWriteKeepsBackupAndRecovers() throws IOException {
        Path file = dir.resolve("sub/data.json");
        AtomicFiles.write(file, "one".getBytes(StandardCharsets.UTF_8));
        AtomicFiles.write(file, "two".getBytes(StandardCharsets.UTF_8));
        assertEquals("two", Files.readString(file));
        assertEquals("one", Files.readString(AtomicFiles.backupOf(file)));
        assertFalse(Files.exists(file.resolveSibling("data.json.tmp")));

        // A corrupt main file falls back to the backup.
        Files.writeString(file, "garbage");
        String read = AtomicFiles.read(file, bytes -> {
            String s = new String(bytes, StandardCharsets.UTF_8);
            if (s.equals("garbage")) throw new IOException("corrupt");
            return s;
        });
        assertEquals("one", read);
        assertNull(AtomicFiles.read(dir.resolve("missing"), bytes -> "x"));
    }

    @Test
    void atomicReadThrowsWhenAllCopiesAreBroken() throws IOException {
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "x");
        assertThrows(IOException.class, () -> AtomicFiles.read(file, bytes -> { throw new IOException("bad"); }));
    }

    @Test
    void colors() {
        assertEquals(0xFF102030, Colors.opaque(0x102030));
        assertEquals(0x80102030, Colors.withAlpha(0xFF102030, 0x80));
        assertEquals(0xFF081018, Colors.scale(0xFF102030, 0.5f));
        assertEquals(0x00FF00, Colors.parseHex("#00ff00", -1));
        assertEquals(0xAABBCC, Colors.parseHex("abc", -1));
        assertEquals(-1, Colors.parseHex("#12", -1));
        assertEquals("#00FF00", Colors.toHex(0xFF00FF00));
        assertEquals(0xFF808080, Colors.blend(0xFF000000, 0xFFFFFFFF, 0.5f));
    }

    @Test
    void facing() {
        assertEquals("S", MapMath.facing(0));
        assertEquals("W", MapMath.facing(90));
        assertEquals("N", MapMath.facing(180));
        assertEquals("E", MapMath.facing(-90));
        assertEquals("NE", MapMath.facing(225));
        assertEquals("S", MapMath.facing(720));
    }

    @Test
    void rotationPutsViewDirectionUp() {
        for (float yaw : new float[] {0, 37, 90, 180, 270, -45}) {
            double r = Math.toRadians(yaw);
            double fx = -Math.sin(r);
            double fz = Math.cos(r);
            double[] s = MapMath.rotate(fx, fz, yaw + 180);
            assertEquals(0, s[0], 1e-9, "yaw " + yaw);
            assertEquals(-1, s[1], 1e-9, "yaw " + yaw);
            double[] back = MapMath.unrotate(s[0], s[1], yaw + 180);
            assertEquals(fx, back[0], 1e-9);
            assertEquals(fz, back[1], 1e-9);
        }
        // Facing south, east is on the left of the screen.
        assertTrue(MapMath.rotate(1, 0, 180)[0] < 0);
    }

    @Test
    void edgeClamping() {
        double[] c = MapMath.clampToCircle(30, 40, 10);
        assertEquals(6, c[0], 1e-9);
        assertEquals(8, c[1], 1e-9);
        double[] s = MapMath.clampToSquare(50, 10, 10);
        assertEquals(10, s[0], 1e-9);
        assertEquals(2, s[1], 1e-9);
        assertArrayEquals(new double[] {3, 4}, MapMath.clampToCircle(3, 4, 10));
    }

    @Test
    void distanceText() {
        assertEquals("340 m", MapMath.distance(340.2));
        assertEquals("1.5 km", MapMath.distance(1500));
        assertEquals("12 km", MapMath.distance(12_000));
    }

    @Test
    void configSanitizesAndSurvivesGarbage() {
        LumenConfig config = LumenConfig.fromJson("{\"minimapSize\": 5000, \"minimapZoom\": -3, \"minimapOpacity\": 0,"
            + " \"minimapView\": \"NOT_A_VIEW\", \"keepDeathWaypoints\": 999}");
        assertEquals(256, config.minimapSize);
        assertEquals(0, config.minimapZoom);
        assertEquals(0.2f, config.minimapOpacity);
        assertEquals(MapView.SHADED, config.minimapView);
        assertEquals(20, config.keepDeathWaypoints);
        assertNotNull(LumenConfig.fromJson("this is not json"));
        LumenConfig round = LumenConfig.fromJson(new LumenConfig().toJson());
        assertEquals(new LumenConfig().minimapSize, round.minimapSize);
        config.setStructureTypeVisible("village", false);
        assertFalse(config.isStructureTypeVisible("village"));
        config.setStructureTypeVisible("village", true);
        assertTrue(config.isStructureTypeVisible("village"));
    }
}
