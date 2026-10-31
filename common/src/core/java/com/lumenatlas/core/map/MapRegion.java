package com.lumenatlas.core.map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The explored surface of one 256 x 256 block area (16 x 16 chunks) of one dimension.
 *
 * <p>Per column it stores the surface color (0xRRGGBB, with the water depth in the top byte), the surface height and
 * a biome index into a small region-local palette. Unknown columns have height {@link #UNKNOWN}. Shading is not
 * stored; it is computed when a texture is built (see {@link MapShading}), so view modes can change at any time.
 *
 * <p>Regions are modified on the client thread only. {@link #copy()} gives the IO thread a private snapshot to encode.
 */
public final class MapRegion {
    public static final int SHIFT = 8;
    public static final int SIZE = 1 << SHIFT;
    public static final int AREA = SIZE * SIZE;
    public static final short UNKNOWN = Short.MIN_VALUE;
    private static final int MAGIC = 0x4C414D52; // "LAMR"
    private static final int FORMAT = 1;
    private static final int MAX_PALETTE = 255;

    public final int regionX;
    public final int regionZ;
    private final int[] surface;
    private final short[] height;
    private final byte[] biome;
    private final List<String> palette;
    private int known;
    private long revision;
    private boolean dirty;

    public MapRegion(int regionX, int regionZ) {
        this(regionX, regionZ, new int[AREA], filledHeights(), new byte[AREA], new ArrayList<>());
    }

    private MapRegion(int regionX, int regionZ, int[] surface, short[] height, byte[] biome, List<String> palette) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.surface = surface;
        this.height = height;
        this.biome = biome;
        this.palette = palette;
        int count = 0;
        for (short h : height) if (h != UNKNOWN) count++;
        this.known = count;
    }

    private static short[] filledHeights() {
        short[] h = new short[AREA];
        Arrays.fill(h, UNKNOWN);
        return h;
    }

    public static int index(int localX, int localZ) {
        return (localZ << SHIFT) | localX;
    }

    public static int regionCoord(int block) {
        return block >> SHIFT;
    }

    public static int local(int block) {
        return block & (SIZE - 1);
    }

    public static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    public long key() {
        return key(regionX, regionZ);
    }

    /**
     * Stores one column. Returns true when anything changed.
     *
     * @param rgb        surface color 0xRRGGBB
     * @param waterDepth 0 for dry land, otherwise the number of water blocks above the floor (capped at 255)
     */
    public boolean set(int localX, int localZ, int rgb, int columnHeight, int waterDepth, String biomeId) {
        int i = index(localX, localZ);
        int packed = (Math.min(255, Math.max(0, waterDepth)) << 24) | (rgb & 0xFFFFFF);
        byte biomeIndex = biomeIndex(biomeId);
        short h = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, columnHeight));
        if (height[i] == h && surface[i] == packed && biome[i] == biomeIndex) return false;
        if (height[i] == UNKNOWN) known++;
        height[i] = h;
        surface[i] = packed;
        biome[i] = biomeIndex;
        revision++;
        dirty = true;
        return true;
    }

    private byte biomeIndex(String biomeId) {
        if (biomeId == null) return 0;
        int i = palette.indexOf(biomeId);
        if (i < 0) {
            if (palette.size() >= MAX_PALETTE) return 0;
            palette.add(biomeId);
            i = palette.size() - 1;
        }
        return (byte) (i + 1);
    }

    public boolean isKnown(int localX, int localZ) {
        return height[index(localX, localZ)] != UNKNOWN;
    }

    public int surface(int index) {
        return surface[index];
    }

    public int height(int index) {
        return height[index];
    }

    /** Biome id of a column, or null when unknown. */
    public String biome(int index) {
        int b = biome[index] & 0xFF;
        return b == 0 || b > palette.size() ? null : palette.get(b - 1);
    }

    public int knownColumns() {
        return known;
    }

    public boolean isEmpty() {
        return known == 0;
    }

    /** Increases on every change; texture caches compare it to know when to rebuild. */
    public long revision() {
        return revision;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markSaved() {
        dirty = false;
    }

    /** Private copy for background encoding. */
    public MapRegion copy() {
        MapRegion copy = new MapRegion(regionX, regionZ, surface.clone(), height.clone(), biome.clone(), new ArrayList<>(palette));
        copy.revision = revision;
        return copy;
    }

    public byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        DataOutputStream header = new DataOutputStream(bytes);
        header.writeInt(MAGIC);
        header.writeByte(FORMAT);
        header.writeInt(regionX);
        header.writeInt(regionZ);
        header.flush();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            out.writeByte(palette.size());
            for (String id : palette) out.writeUTF(id);
            for (short h : height) out.writeShort(h);
            for (int s : surface) out.writeInt(s);
            out.write(biome);
        }
        return bytes.toByteArray();
    }

    public static MapRegion decode(byte[] data) throws IOException {
        DataInputStream header = new DataInputStream(new ByteArrayInputStream(data));
        if (header.readInt() != MAGIC) throw new IOException("Not a Lumen Atlas region file");
        int format = header.readUnsignedByte();
        if (format != FORMAT) throw new IOException("Unsupported region format " + format);
        int rx = header.readInt();
        int rz = header.readInt();
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(header))) {
            int paletteSize = in.readUnsignedByte();
            List<String> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) palette.add(in.readUTF());
            short[] height = new short[AREA];
            for (int i = 0; i < AREA; i++) height[i] = in.readShort();
            int[] surface = new int[AREA];
            for (int i = 0; i < AREA; i++) surface[i] = in.readInt();
            byte[] biome = new byte[AREA];
            in.readFully(biome);
            return new MapRegion(rx, rz, surface, height, biome, palette);
        }
    }

    public static String fileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".lamr";
    }
}
