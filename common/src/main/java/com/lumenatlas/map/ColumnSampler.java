package com.lumenatlas.map;

import com.lumenatlas.core.util.Colors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Reads the visible surface of one block column from a loaded client chunk: its color (vanilla map color, tinted
 * with the biome's grass/foliage/water color where the block is tinted), height, water depth and biome.
 * Only called on the client thread.
 */
public final class ColumnSampler {
    /** Result of the last {@link #sample} call. */
    public int rgb;
    public int height;
    public int waterDepth;
    public String biome;

    private static final int LAVA_RGB = 0xD8661C;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockColors blockColors = Minecraft.getInstance().getBlockColors();

    /**
     * Samples the column at block (x, z) of {@code chunk}.
     *
     * @param startY highest y to look at, or {@link Integer#MAX_VALUE} to use the surface heightmap
     * @param caveMode when true, start at {@code startY} and look for the first floor below an air gap (used for the
     *                 Nether roof and for the minimap's cave view)
     * @return false when nothing visible was found (the column stays unknown)
     */
    public boolean sample(ClientLevel level, LevelChunk chunk, int x, int z, int startY, boolean caveMode) {
        int minY = level.getMinY();
        int y;
        if (caveMode) {
            y = findFloor(chunk, x, z, startY, minY);
            if (y == Integer.MIN_VALUE) return false;
        } else {
            y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            if (y < minY) return false;
        }
        BlockState state = chunk.getBlockState(pos.set(x, y, z));
        // Look through plants, glass and other see-through decoration so the map shows the ground.
        for (int i = 0; i < 16 && y > minY && isSeeThrough(state); i++) {
            y--;
            state = chunk.getBlockState(pos.set(x, y, z));
        }
        if (state.isAir()) return false;
        height = y;
        biome = biomeId(level.getBiome(pos.set(x, y, z)));
        if (state.getFluidState().is(FluidTags.WATER)) {
            int depth = 0;
            int wy = y;
            while (wy > minY && depth < 255 && chunk.getBlockState(pos.set(x, wy, z)).getFluidState().is(FluidTags.WATER)) {
                depth++;
                wy--;
            }
            waterDepth = Math.max(1, depth);
            rgb = BiomeColors.getAverageWaterColor(level, pos.set(x, y, z)) & 0xFFFFFF;
            return true;
        }
        waterDepth = 0;
        if (state.getFluidState().is(FluidTags.LAVA)) {
            // Vanilla maps use pure red for lava; a molten orange reads much better on a large map.
            rgb = LAVA_RGB;
            return true;
        }
        rgb = color(level, state, pos.set(x, y, z));
        return true;
    }

    private int findFloor(LevelChunk chunk, int x, int z, int startY, int minY) {
        int y = startY;
        // Skip the solid ceiling (or the block the player stands in) until there is air.
        int limit = 64;
        while (y > minY && limit-- > 0 && !isOpen(chunk.getBlockState(pos.set(x, y, z)))) y--;
        if (limit <= 0) return Integer.MIN_VALUE;
        // Then fall through air to the floor.
        limit = 96;
        while (y > minY && limit-- > 0 && isOpen(chunk.getBlockState(pos.set(x, y, z)))) y--;
        if (limit <= 0 || y <= minY) return Integer.MIN_VALUE;
        return y;
    }

    private static boolean isOpen(BlockState state) {
        return state.isAir() || (isSeeThrough(state) && state.getFluidState().isEmpty());
    }

    static boolean isSeeThrough(BlockState state) {
        if (state.isAir()) return true;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.is(Blocks.SNOW)) return false;
        return state.getMapColor(null, BlockPos.ZERO) == MapColor.NONE || state.canBeReplaced();
    }

    private int color(ClientLevel level, BlockState state, BlockPos at) {
        MapColor mapColor = state.getMapColor(level, at);
        int rgb = mapColor.col;
        BlockTintSource tint = blockColors.getTintSource(state, 0);
        if (tint != null) {
            int tinted = tint.colorInWorld(state, level, at);
            if (tinted != -1) {
                float shade = mapColor == MapColor.PLANT ? 0.72f : mapColor == MapColor.GRASS ? 0.9f : 0.82f;
                rgb = Colors.scale(Colors.opaque(tinted), shade) & 0xFFFFFF;
            }
        }
        return rgb;
    }

    static String biomeId(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
    }
}
