package com.lumenatlas.seed;

import com.lumenatlas.LumenAtlas;
import com.lumenatlas.compat.Compat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * Builds a {@link WorldgenContext} for a manually entered seed, without a server: the vanilla data pack is loaded the
 * same way the "Create World" screen loads it (registries, tags, structure templates), and the chosen world preset
 * provides the chunk generators. The loaded data is shared by every seed and kept until the game closes.
 *
 * <p>This always describes vanilla world generation. Servers with world-generation data packs or mods can place
 * structures differently; the README documents this.
 */
public final class WorldgenLoader {
    /** World presets that can be chosen for manual seeds. */
    public enum Preset {
        DEFAULT(WorldPresets.NORMAL, "Default"),
        LARGE_BIOMES(WorldPresets.LARGE_BIOMES, "Large Biomes"),
        AMPLIFIED(WorldPresets.AMPLIFIED, "Amplified");

        final ResourceKey<WorldPreset> key;
        public final String label;

        Preset(ResourceKey<WorldPreset> key, String label) {
            this.key = key;
            this.label = label;
        }

        public Preset next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private record Loaded(RegistryAccess.Frozen registries, CloseableResourceManager resources, StructureTemplateManager templates) {}

    private static final long LOAD_TIMEOUT_S = 60;
    private static Loaded loaded;
    private static final Map<Preset, WorldDimensions> DIMENSIONS = new HashMap<>();

    private WorldgenLoader() {}

    /** Seed map worker thread. Blocks for a second or two the first time. */
    public static synchronized WorldgenContext create(long seed, Preset preset, String dimensionId) throws Exception {
        Loaded data = load();
        WorldDimensions dims = DIMENSIONS.computeIfAbsent(preset, p ->
            data.registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(p.key).value().createWorldDimensions());
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, Identifier.parse(dimensionId));
        LevelStem stem = dims.get(stemKey).orElse(null);
        if (stem == null) return null;
        ChunkGenerator generator = stem.generator();
        DimensionType type = stem.type().value();
        RandomState randomState = com.lumenatlas.compat.Compat.randomState(data.registries, seed, generator);
        ChunkGeneratorStructureState state = generator.createState(data.registries.lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
        return new WorldgenContext(seed, data.registries, generator, randomState, state, data.templates,
            LevelHeightAccessor.create(type.minY(), type.height()));
    }

    private static Loaded load() throws Exception {
        if (loaded != null) return loaded;
        long start = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        // Only the world generation registries are needed (with their tags), so this does the first half of what
        // WorldLoader does for the "Create World" screen and skips the server resources (recipes, advancements,
        // functions, loot tables). Those are slow, and on some loaders their reload hooks expect a running server.
        PackRepository packs = new PackRepository(new ServerPacksSource(mc.directoryValidator()));
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packs, WorldDataConfiguration.DEFAULT, false, true);
        CloseableResourceManager resources = packConfig.createResourceManager().getSecond();
        try {
            LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
            List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
            RegistryAccess.Frozen worldContext = layers.getAccessForLoading(Compat.worldLayer());
            List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(worldContext, staticTags);
            RegistryAccess.Frozen worldRegistries = Compat.loadWorldRegistries(resources, lookups)
                .get(LOAD_TIMEOUT_S, TimeUnit.SECONDS);
            RegistryAccess.Frozen access = layers.replaceFrom(Compat.worldLayer(), worldRegistries).compositeAccess();
            Path cache = LumenAtlas.dataDir().resolve("seedmap-cache");
            Files.createDirectories(cache);
            LevelStorageSource.LevelStorageAccess storage = LevelStorageSource.createDefault(cache).createAccess("templates");
            StructureTemplateManager templates = new StructureTemplateManager(resources, storage, mc.getFixerUpper(),
                access.lookupOrThrow(Registries.BLOCK));
            loaded = new Loaded(access, resources, templates);
        } catch (Exception e) {
            resources.close();
            throw e;
        }
        LumenAtlas.LOGGER.info("Lumen Atlas: loaded vanilla world generation data for the seed map in {} ms", System.currentTimeMillis() - start);
        return loaded;
    }

    /** True for dimensions the vanilla presets define (data pack dimensions cannot be predicted offline). */
    public static boolean isVanillaDimension(String dimensionId) {
        return dimensionId.equals("minecraft:overworld") || dimensionId.equals("minecraft:the_nether") || dimensionId.equals("minecraft:the_end");
    }
}
