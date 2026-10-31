package com.lumenatlas.structure;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Recognizes structures from what the client actually receives: the blocks of loaded chunks and the entities
 * around the player. The server never tells the client which structures exist, so this works with signatures:
 *
 * <ul>
 *   <li><b>Unique blocks</b> that only generate in one structure and cannot be crafted (end portal frames,
 *       reinforced deepslate, trial spawners, vaults, budding amethyst...). These are reliable.</li>
 *   <li><b>Block combinations</b> that are typical for a structure (blue + orange terracotta for desert pyramids,
 *       nether brick fences for fortresses...). A player build that copies them can cause a false marker.</li>
 *   <li><b>Entities</b> that only spawn with a structure (elder guardians, blazes, piglin brutes, shulkers,
 *       groups of villagers...).</li>
 * </ul>
 *
 * Every chunk is checked once when it arrives; sections are skipped quickly with the palette check
 * {@link LevelChunkSection#maybeHas}, so most chunks cost a few microseconds. Client thread only.
 */
public final class StructureDetector {
    /** Receives detections. */
    public interface Sink {
        void detected(StructureKind kind, int x, int y, int z);
    }

    private record Rule(StructureKind kind, Predicate<ChunkCounts> test, Block[] anchor) {}

    private final List<Rule> rules = new ArrayList<>();
    private final ReferenceOpenHashSet<Block> interesting = new ReferenceOpenHashSet<>();
    private final Predicate<BlockState> sectionFilter;
    private final ChunkCounts counts = new ChunkCounts();

    // Entity types are looked up by id so a missing type (renamed in a future version) only disables its rule.
    private final EntityType<?> villager = entity("villager");
    private final EntityType<?> elderGuardian = entity("elder_guardian");
    private final EntityType<?> blaze = entity("blaze");
    private final EntityType<?> piglinBrute = entity("piglin_brute");
    private final EntityType<?> shulker = entity("shulker");
    private final EntityType<?> evoker = entity("evoker");
    private final EntityType<?> vindicator = entity("vindicator");
    private final EntityType<?> chestMinecart = entity("chest_minecart");

    public StructureDetector() {
        Block frame = block("end_portal_frame");
        Block infestedBricks = block("infested_stone_bricks");
        Block infestedMossy = block("infested_mossy_stone_bricks");
        Block infestedCracked = block("infested_cracked_stone_bricks");
        Block reinforced = block("reinforced_deepslate");
        Block deepslateTiles = block("deepslate_tiles");
        Block deepslateBricks = block("deepslate_bricks");
        Block trialSpawner = block("trial_spawner");
        Block vault = block("vault");
        Block tuffBricks = block("tuff_bricks");
        Block chiseledTuff = block("chiseled_tuff");
        Block bell = block("bell");
        Block blueTerracotta = block("blue_terracotta");
        Block orangeTerracotta = block("orange_terracotta");
        Block chiseledSandstone = block("chiseled_sandstone");
        Block chiseledStoneBricks = block("chiseled_stone_bricks");
        Block mossyCobblestone = block("mossy_cobblestone");
        Block stickyPiston = block("sticky_piston");
        Block tripwireHook = block("tripwire_hook");
        Block darkPrismarine = block("dark_prismarine");
        Block prismarineBricks = block("prismarine_bricks");
        Block seaLantern = block("sea_lantern");
        Block netherBrickFence = block("nether_brick_fence");
        Block netherBricks = block("nether_bricks");
        Block gildedBlackstone = block("gilded_blackstone");
        Block polishedBlackstoneBricks = block("polished_blackstone_bricks");
        Block crackedPolishedBlackstoneBricks = block("cracked_polished_blackstone_bricks");
        Block purpurBlock = block("purpur_block");
        Block purpurPillar = block("purpur_pillar");
        Block endStoneBricks = block("end_stone_bricks");
        Block rail = block("rail");
        Block cobweb = block("cobweb");
        Block oakFence = block("oak_fence");
        Block darkOakFence = block("dark_oak_fence");
        Block cryingObsidian = block("crying_obsidian");
        Block obsidian = block("obsidian");
        Block netherrack = block("netherrack");
        Block magma = block("magma_block");
        Block snowBlock = block("snow_block");
        Block ice = block("ice");
        Block whiteCarpet = block("white_carpet");
        Block redBed = block("red_bed");
        Block pottedRedMushroom = block("potted_red_mushroom");
        Block cauldron = block("cauldron");
        Block spruceFence = block("spruce_fence");
        Block suspiciousGravel = block("suspicious_gravel");
        Block suspiciousSand = block("suspicious_sand");
        Block crackedStoneBricks = block("cracked_stone_bricks");
        Block mossyStoneBricks = block("mossy_stone_bricks");
        Block budding = block("budding_amethyst");
        Block spawner = block("spawner");
        Block boneBlock = block("bone_block");
        Block birchPlanks = block("birch_planks");
        Block darkOakLog = block("dark_oak_log");
        Block cobblestone = block("cobblestone");
        Block[] shipWood = blocks("oak_planks", "spruce_planks", "dark_oak_planks", "jungle_planks", "birch_planks", "acacia_planks",
            "oak_stairs", "spruce_stairs", "dark_oak_stairs", "jungle_stairs", "birch_stairs", "acacia_stairs",
            "oak_slab", "spruce_slab", "dark_oak_slab", "jungle_slab", "birch_slab", "acacia_slab",
            "oak_trapdoor", "spruce_trapdoor", "dark_oak_trapdoor", "jungle_trapdoor", "birch_trapdoor", "acacia_trapdoor");

        // --- reliable: unique blocks ---
        rule(StructureKinds.STRONGHOLD, c -> c.overworld && (c.count(frame) >= 1
            || c.count(infestedBricks, infestedMossy, infestedCracked) >= 3), frame, infestedBricks, infestedMossy, infestedCracked);
        rule(StructureKinds.ANCIENT_CITY, c -> c.overworld && (c.count(reinforced) >= 1
            || (c.count(deepslateTiles, deepslateBricks) >= 40 && c.avgY(deepslateTiles, deepslateBricks) < 0)), reinforced, deepslateTiles, deepslateBricks);
        rule(StructureKinds.TRIAL_CHAMBERS, c -> c.overworld && (c.count(trialSpawner, vault) >= 1
            || (c.count(tuffBricks) >= 30 && c.count(chiseledTuff) >= 2)), trialSpawner, vault, chiseledTuff, tuffBricks);
        rule(StructureKinds.GEODE, c -> c.count(budding) >= 1, budding);
        rule(StructureKinds.BASTION, c -> c.nether && (c.count(gildedBlackstone) >= 1
            || c.count(polishedBlackstoneBricks, crackedPolishedBlackstoneBricks) >= 60), gildedBlackstone, polishedBlackstoneBricks);
        rule(StructureKinds.END_CITY, c -> c.end && c.count(purpurBlock, purpurPillar) >= 12, purpurBlock, purpurPillar);

        // --- strong: typical block combinations ---
        rule(StructureKinds.VILLAGE, c -> c.overworld && c.count(bell) >= 1, bell);
        rule(StructureKinds.DESERT_PYRAMID, c -> c.overworld && c.count(blueTerracotta) >= 1 && c.count(orangeTerracotta) >= 4
            && c.count(chiseledSandstone) >= 1, blueTerracotta);
        rule(StructureKinds.JUNGLE_TEMPLE, c -> c.overworld && c.count(chiseledStoneBricks) >= 1 && c.count(mossyCobblestone) >= 15
            && c.count(stickyPiston, tripwireHook) >= 1, chiseledStoneBricks, stickyPiston);
        rule(StructureKinds.MONUMENT, c -> c.overworld && (c.count(darkPrismarine) >= 8
            || (c.count(prismarineBricks) >= 24 && c.count(seaLantern) >= 2)), darkPrismarine, seaLantern, prismarineBricks);
        rule(StructureKinds.FORTRESS, c -> c.nether && (c.count(netherBrickFence) >= 3 || c.count(netherBricks) >= 64), netherBrickFence, netherBricks);
        rule(StructureKinds.MINESHAFT, c -> c.overworld && c.count(rail) >= 4 && c.count(cobweb) >= 1
            && c.count(oakFence, darkOakFence) >= 2 && c.avgY(rail) < c.seaLevel - 4, rail, oakFence, darkOakFence);
        // Crying obsidian is random in ruined portals, so many have none; in the Overworld netherrack (which never
        // generates there naturally) next to obsidian is the other giveaway.
        rule(StructureKinds.RUINED_PORTAL, c -> !c.end && c.count(obsidian) >= 2 && (c.overworld
            ? (c.count(cryingObsidian) >= 1 && c.count(netherrack, magma) >= 1) || c.count(netherrack) >= 4
            : c.count(cryingObsidian) >= 1 && c.count(gildedBlackstone) == 0), obsidian, cryingObsidian);
        rule(StructureKinds.IGLOO, c -> c.overworld && c.count(snowBlock) >= 20 && c.count(whiteCarpet, redBed, ice) >= 2, whiteCarpet, redBed);
        rule(StructureKinds.SWAMP_HUT, c -> c.overworld && c.count(pottedRedMushroom) >= 1 && (c.count(cauldron) >= 1 || c.count(spruceFence) >= 2), pottedRedMushroom);
        rule(StructureKinds.TRAIL_RUINS, c -> c.overworld && !c.oceanBiome && c.count(suspiciousGravel) >= 1, suspiciousGravel);
        rule(StructureKinds.OCEAN_RUIN, c -> c.overworld && c.oceanBiome && (c.count(suspiciousGravel, suspiciousSand) >= 1
            || (c.count(crackedStoneBricks, mossyStoneBricks) >= 12 && c.avgY(crackedStoneBricks, mossyStoneBricks) < c.seaLevel - 2)),
            suspiciousGravel, suspiciousSand, crackedStoneBricks, mossyStoneBricks);
        // Mineshafts under oceans also have lots of planks; they sit much deeper and come with rails and cobwebs.
        rule(StructureKinds.SHIPWRECK, c -> c.overworld && (c.oceanBiome || c.beachBiome) && c.count(shipWood) >= 40
            && c.avgY(shipWood) < c.seaLevel + 3 && c.avgY(shipWood) > c.seaLevel - 40 && c.count(rail, cobweb) == 0, shipWood);
        rule(StructureKinds.OUTPOST, c -> c.overworld && c.count(birchPlanks) >= 24 && c.count(darkOakLog) >= 6
            && c.count(cobblestone) >= 8, darkOakLog);
        rule(StructureKinds.NETHER_FOSSIL, c -> c.nether && c.count(boneBlock) >= 12, boneBlock);
        rule(StructureKinds.DUNGEON, c -> c.overworld && c.count(spawner) >= 1 && c.count(mossyCobblestone) >= 8
            && c.count(frame) == 0, spawner);

        sectionFilter = state -> interesting.contains(state.getBlock());
    }

    private void rule(StructureKind kind, Predicate<ChunkCounts> test, Block... anchor) {
        List<Block> present = new ArrayList<>();
        for (Block b : anchor) if (b != null) present.add(b);
        if (present.isEmpty()) return;
        rules.add(new Rule(kind, test, present.toArray(new Block[0])));
    }

    private Block block(String id) {
        Block b = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(id)).orElse(null);
        if (b != null) interesting.add(b);
        return b;
    }

    private Block[] blocks(String... ids) {
        List<Block> list = new ArrayList<>();
        for (String id : ids) {
            Block b = block(id);
            if (b != null) list.add(b);
        }
        return list.toArray(new Block[0]);
    }

    private static EntityType<?> entity(String id) {
        return BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.withDefaultNamespace(id)).orElse(null);
    }

    /** Checks a freshly loaded chunk. */
    public void scanChunk(ClientLevel level, LevelChunk chunk, Sink sink) {
        counts.reset(level, chunk);
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        boolean any = false;
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(sectionFilter)) continue;
            int baseY = level.getSectionYFromSectionIndex(s) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block b = section.getBlockState(x, y, z).getBlock();
                        if (interesting.contains(b)) {
                            counts.add(b, baseX + x, baseY + y, baseZ + z);
                            any = true;
                        }
                    }
                }
            }
        }
        if (!any) return;
        counts.prepareBiome(level, chunk);
        for (Rule rule : rules) {
            if (rule.test.test(counts)) {
                BlockPos at = counts.centroid(rule.anchor);
                if (at != null) sink.detected(rule.kind, at.getX(), at.getY(), at.getZ());
            }
        }
    }

    /** Checks the entities the client knows about (call every second or so). */
    public void scanEntities(ClientLevel level, Sink sink) {
        boolean overworld = level.dimension() == Level.OVERWORLD;
        boolean nether = level.dimension() == Level.NETHER;
        boolean end = level.dimension() == Level.END;
        Map<Long, int[]> villagerCells = new HashMap<>();
        for (Entity e : level.entitiesForRendering()) {
            EntityType<?> type = e.getType();
            int x = e.getBlockX();
            int y = e.getBlockY();
            int z = e.getBlockZ();
            if (type == villager && overworld) {
                long cell = (((long) (x >> 6)) << 32) | ((z >> 6) & 0xFFFFFFFFL);
                int[] acc = villagerCells.computeIfAbsent(cell, k -> new int[4]);
                acc[0]++;
                acc[1] += x;
                acc[2] += y;
                acc[3] += z;
            } else if (type == elderGuardian && overworld) {
                sink.detected(StructureKinds.MONUMENT, x, y, z);
            } else if (type == blaze && nether) {
                sink.detected(StructureKinds.FORTRESS, x, y, z);
            } else if (type == piglinBrute && nether) {
                sink.detected(StructureKinds.BASTION, x, y, z);
            } else if (type == shulker && end) {
                sink.detected(StructureKinds.END_CITY, x, y, z);
            } else if ((type == evoker || type == vindicator) && overworld && isMansionBiome(level.getBiome(e.blockPosition()))) {
                sink.detected(StructureKinds.MANSION, x, y, z);
            } else if (type == chestMinecart && overworld && y < level.getSeaLevel() - 8 && !level.canSeeSky(e.blockPosition())) {
                sink.detected(StructureKinds.MINESHAFT, x, y, z);
            }
        }
        for (int[] acc : villagerCells.values()) {
            if (acc[0] >= 3) sink.detected(StructureKinds.VILLAGE, acc[1] / acc[0], acc[2] / acc[0], acc[3] / acc[0]);
        }
    }

    private static boolean isMansionBiome(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> {
            String path = k.identifier().getPath();
            return path.equals("dark_forest") || path.equals("pale_garden");
        }).orElse(false);
    }

    /** Block statistics of one chunk. Reused for every chunk to avoid garbage. */
    private static final class ChunkCounts {
        private final Reference2IntOpenHashMap<Block> count = new Reference2IntOpenHashMap<>();
        private final Reference2ObjectOpenHashMap<Block, long[]> sums = new Reference2ObjectOpenHashMap<>();
        boolean overworld;
        boolean nether;
        boolean end;
        boolean oceanBiome;
        boolean beachBiome;
        int seaLevel;

        void reset(ClientLevel level, LevelChunk chunk) {
            count.clear();
            sums.clear();
            overworld = level.dimension() == Level.OVERWORLD;
            nether = level.dimension() == Level.NETHER;
            end = level.dimension() == Level.END;
            seaLevel = level.getSeaLevel();
            oceanBiome = false;
            beachBiome = false;
        }

        void prepareBiome(ClientLevel level, LevelChunk chunk) {
            BlockPos center = new BlockPos(chunk.getPos().getMiddleBlockX(), seaLevel - 1, chunk.getPos().getMiddleBlockZ());
            Holder<Biome> biome = level.getBiome(center);
            oceanBiome = biome.is(BiomeTags.IS_OCEAN);
            beachBiome = biome.is(BiomeTags.IS_BEACH);
        }

        void add(Block block, int x, int y, int z) {
            count.addTo(block, 1);
            long[] s = sums.computeIfAbsent(block, k -> new long[3]);
            s[0] += x;
            s[1] += y;
            s[2] += z;
        }

        int count(Block... blocks) {
            int total = 0;
            for (Block b : blocks) if (b != null) total += count.getInt(b);
            return total;
        }

        /** Average y of the given blocks, or a huge value when none are present. */
        int avgY(Block... blocks) {
            long sum = 0;
            int n = 0;
            for (Block b : blocks) {
                long[] s = b == null ? null : sums.get(b);
                if (s == null) continue;
                sum += s[1];
                n += count.getInt(b);
            }
            return n == 0 ? Integer.MAX_VALUE : (int) (sum / n);
        }

        BlockPos centroid(Block... blocks) {
            long sx = 0;
            long sy = 0;
            long sz = 0;
            int n = 0;
            for (Block b : blocks) {
                long[] s = b == null ? null : sums.get(b);
                if (s == null) continue;
                sx += s[0];
                sy += s[1];
                sz += s[2];
                n += count.getInt(b);
            }
            return n == 0 ? null : new BlockPos((int) (sx / n), (int) (sy / n), (int) (sz / n));
        }
    }
}
