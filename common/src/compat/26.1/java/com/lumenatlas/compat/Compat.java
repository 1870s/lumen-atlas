package com.lumenatlas.compat;

import java.nio.file.Path;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * The calls that differ between Minecraft versions. There is one copy of this class per version family under
 * common/src/compat/<family>; versions/<mc>/target.properties selects the family. This is the 26.1.x variant.
 */
public final class Compat {
    private Compat() {}

    public static Screen screen(Minecraft mc) {
        return mc.screen;
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    /** Opens a folder in the system file manager. */
    public static void openPath(Path path) {
        net.minecraft.util.Util.getPlatform().openPath(path);
    }

    public static StructureTemplateManager templates(MinecraftServer server) {
        return server.getStructureManager();
    }

    /** Draws a texture into (0, 0)-(size, size) of the current pose without wrapping at its edges. */
    public static void blitClamped(GuiGraphicsExtractor g, AbstractTexture texture, int size) {
        g.blit(texture.getTextureView(), com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST), 0, 0, size, size, 0f, 1f, 0f, 1f);
    }

    /** The random state the server would create for this generator and seed. */
    public static RandomState randomState(RegistryAccess registries, long seed, ChunkGenerator generator) {
        NoiseGeneratorSettings settings = generator instanceof NoiseBasedChunkGenerator noise ? noise.generatorSettings().value()
            : NoiseGeneratorSettings.dummy();
        return RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), seed);
    }

    /** Per-thread state needed by {@link #generationContext} (a climate sampler on 26.3+, nothing before). */
    public static Object climateCache(RandomState randomState) {
        return null;
    }

    public static Structure.GenerationContext generationContext(RegistryAccess registries, ChunkGenerator generator, RandomState randomState,
                                                                 Object climateCache, StructureTemplateManager templates, long seed, ChunkPos chunk,
                                                                 LevelHeightAccessor heightAccessor, Predicate<Holder<Biome>> validBiome) {
        return new Structure.GenerationContext(registries, generator, generator.getBiomeSource(), randomState, templates, seed, chunk,
            heightAccessor, validBiome);
    }

    public static net.minecraft.client.Camera camera(Minecraft mc) {
        return mc.gameRenderer.getMainCamera();
    }

    public static void overlayMessage(Minecraft mc, net.minecraft.network.chat.Component text) {
        mc.gui.setOverlayMessage(text, false);
    }

    /** True while the HUD is hidden (F1). */
    public static boolean hudHidden(Minecraft mc) {
        return mc.options.hideGui;
    }

    /** The registry layer that holds the data-driven world generation registries. */
    public static net.minecraft.server.RegistryLayer worldLayer() {
        return net.minecraft.server.RegistryLayer.WORLDGEN;
    }

    /** Loads the world generation registries (biomes, structures, noise, world presets...) and their tags. */
    // Marked deprecated, but it is still exactly the call vanilla's WorldLoader makes.
    @SuppressWarnings("deprecation")
    public static java.util.concurrent.CompletableFuture<RegistryAccess.Frozen> loadWorldRegistries(
            net.minecraft.server.packs.resources.ResourceManager resources, java.util.List<net.minecraft.core.HolderLookup.RegistryLookup<?>> lookups) {
        return net.minecraft.resources.RegistryDataLoader.load(resources, lookups,
            net.minecraft.resources.RegistryDataLoader.WORLDGEN_REGISTRIES, net.minecraft.util.Util.backgroundExecutor());
    }
}
