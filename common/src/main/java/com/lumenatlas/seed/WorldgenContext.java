package com.lumenatlas.seed;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Everything Minecraft's own structure placement code needs for one dimension and seed: the chunk generator,
 * random state, structure placement state, registries and template manager.
 *
 * <p>In singleplayer these are the integrated server's real objects (so data packs and world presets are honored
 * exactly). With a manually entered seed they are rebuilt from the vanilla data pack by {@link WorldgenLoader}.
 * Used from the seed map worker thread only.
 */
public final class WorldgenContext {
    public final long seed;
    public final RegistryAccess registries;
    public final ChunkGenerator generator;
    public final RandomState randomState;
    public final ChunkGeneratorStructureState structureState;
    public final StructureTemplateManager templates;
    public final LevelHeightAccessor heightAccessor;
    private final Object climateCache;

    public WorldgenContext(long seed, RegistryAccess registries, ChunkGenerator generator, RandomState randomState,
                           ChunkGeneratorStructureState structureState, StructureTemplateManager templates, LevelHeightAccessor heightAccessor) {
        this.seed = seed;
        this.registries = registries;
        this.generator = generator;
        this.randomState = randomState;
        this.structureState = structureState;
        this.templates = templates;
        this.heightAccessor = heightAccessor;
        this.climateCache = com.lumenatlas.compat.Compat.climateCache(randomState);
        structureState.ensureStructuresGenerated();
    }

    public List<Holder<StructureSet>> structureSets() {
        return structureState.possibleStructureSets();
    }

    public BiomeSource biomeSource() {
        return generator.getBiomeSource();
    }

    /**
     * Runs vanilla's structure start logic for one candidate chunk of a structure set: the weighted choice between
     * the set's structures (same random sequence as ChunkGenerator#createStructures) and each structure's own
     * {@link Structure#findValidGenerationPoint} (biome and terrain checks). Returns the start position, or empty.
     */
    public Optional<Found> evaluate(StructureSet set, ChunkPos chunk) {
        List<StructureSet.StructureSelectionEntry> entries = set.structures();
        if (entries.size() == 1) {
            return tryStructure(entries.get(0).structure(), chunk);
        }
        List<StructureSet.StructureSelectionEntry> options = new java.util.ArrayList<>(entries);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, chunk.x(), chunk.z());
        int total = 0;
        for (StructureSet.StructureSelectionEntry option : options) total += option.weight();
        while (!options.isEmpty()) {
            int choice = random.nextInt(total);
            int index = 0;
            for (StructureSet.StructureSelectionEntry option : options) {
                choice -= option.weight();
                if (choice < 0) break;
                index++;
            }
            StructureSet.StructureSelectionEntry selected = options.get(index);
            Optional<Found> found = tryStructure(selected.structure(), chunk);
            if (found.isPresent()) return found;
            options.remove(index);
            total -= selected.weight();
        }
        return Optional.empty();
    }

    private Optional<Found> tryStructure(Holder<Structure> holder, ChunkPos chunk) {
        Structure structure = holder.value();
        Structure.GenerationContext context = com.lumenatlas.compat.Compat.generationContext(registries, generator, randomState,
            climateCache, templates, seed, chunk, heightAccessor, structure.biomes()::contains);
        return structure.findValidGenerationPoint(context).map(stub -> new Found(holder, stub.position().getX(),
            stub.position().getY(), stub.position().getZ()));
    }

    public record Found(Holder<Structure> structure, int x, int y, int z) {
        public String id() {
            return structure.unwrapKey().map(k -> k.identifier().toString()).orElse("unknown");
        }
    }
}
