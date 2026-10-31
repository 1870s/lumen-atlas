package com.lumenatlas.dev;

import com.lumenatlas.LumenAtlas;
import com.lumenatlas.compat.Compat;
import com.lumenatlas.config.ConfigManager;
import com.lumenatlas.core.LumenConfig;
import com.lumenatlas.core.map.MapView;
import com.lumenatlas.core.marker.Marker;
import com.lumenatlas.core.waypoint.Waypoint;
import com.lumenatlas.core.waypoint.WaypointIcon;
import com.lumenatlas.gui.SeedMapScreen;
import com.lumenatlas.gui.SettingsScreen;
import com.lumenatlas.gui.WaypointEditScreen;
import com.lumenatlas.gui.WaypointListScreen;
import com.lumenatlas.gui.WorldMapScreen;
import com.lumenatlas.seed.SeedMapService;
import com.lumenatlas.seed.WorldgenContext;
import com.lumenatlas.seed.WorldgenLoader;
import com.lumenatlas.structure.StructureKind;
import com.lumenatlas.structure.StructureKinds;
import com.lumenatlas.world.WorldSession;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.FileUtil;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/**
 * Development-only end-to-end test (enabled with -Dlumenatlas.selftest=true, excluded from the release jars).
 *
 * <ol>
 *   <li>Creates a normal survival world with a fixed seed.</li>
 *   <li>Lets the seed map predict structures around spawn, then flies (spectator) to the nearest prediction of
 *       each structure type and checks that the block/entity detector marks the same structure: this tests the
 *       predictor and the detector against each other in a real world.</li>
 *   <li>Compares the predictions with vanilla's own structure search (the code behind /locate) and compares the
 *       offline world generation used for manually entered seeds with the integrated server's.</li>
 *   <li>Creates waypoints, dies once (death waypoint), and takes the README screenshots.</li>
 * </ol>
 * Results are written to the log with the prefix [SelfTest]; screenshots go to the game's screenshots folder.
 */
public final class SelfTest {
    private static final long SEED = Long.getLong("lumenatlas.selftest.seed", 20261031L);
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final int CALLER = 9;

    private record Step(String name, Runnable action, BooleanSupplier until, int waitAfter, int timeout) {}

    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static boolean worldRequested;
    private static boolean scripted;
    private static int wait;
    private static Step waitingFor;
    private static int waited;
    private static final Map<String, String> RESULTS = new LinkedHashMap<>();
    private static final Map<String, SeedMapService.Prediction> TARGETS = new LinkedHashMap<>();
    private static int passed;
    private static int failed;

    private SelfTest() {}

    public static void install() {
        LumenAtlas.LOGGER.info("[SelfTest] enabled, seed {}", SEED);
        LumenAtlas.addTickListener(SelfTest::tick);
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (clickThroughDialog(mc)) return;
            if (!worldRequested) {
                if (!(Compat.screen(mc) instanceof TitleScreen)) return;
                mc.options.pauseOnLostFocus = false;
                mc.options.tutorialStep = TutorialSteps.NONE;
                mc.getTutorial().setStep(TutorialSteps.NONE);
                mc.options.guiScale().set(2);
                mc.options.renderDistance().set(8);
                mc.resizeGui();
                prepareConfig();
                createTestWorld(mc);
                worldRequested = true;
                return;
            }
            if (!scripted) {
                if (mc.player == null || mc.level == null || Compat.screen(mc) != null || LumenAtlas.session() == null) return;
                script(mc);
                scripted = true;
                wait = 40;
                return;
            }
            if (Compat.screen(mc) instanceof PauseScreen) Compat.setScreen(mc, null);
            if (waitingFor != null) {
                waited++;
                if (waitingFor.until.getAsBoolean() || waited > waitingFor.timeout) {
                    if (waited > waitingFor.timeout) LumenAtlas.LOGGER.warn("[SelfTest] step '{}' timed out", waitingFor.name);
                    wait = waitingFor.waitAfter;
                    waitingFor = null;
                }
                return;
            }
            if (wait > 0) {
                wait--;
                return;
            }
            Step step = STEPS.poll();
            if (step == null) return;
            LumenAtlas.LOGGER.info("[SelfTest] step: {}", step.name);
            step.action.run();
            if (step.until != null) {
                waitingFor = step;
                waited = 0;
            } else {
                wait = step.waitAfter;
            }
        } catch (Exception e) {
            LumenAtlas.LOGGER.error("[SelfTest] failed", e);
            STEPS.clear();
            mc.stop();
        }
    }

    private static final java.util.Set<String> CONTINUE_LABELS = java.util.Set.of("continue", "proceed", "ok", "okay", "i understand",
        "accept", "got it", "done", "yes", "skip");

    /**
     * Start-up notices (accessibility onboarding, warnings, experimental-settings prompts...) wait for a click.
     * Presses their continue-style button so the test runs unattended. Our own screens are never touched.
     */
    private static int clickCooldown;

    private static boolean clickThroughDialog(Minecraft mc) {
        if (clickCooldown > 0) {
            clickCooldown--;
            return true;
        }
        var screen = Compat.screen(mc);
        if (screen == null || screen instanceof TitleScreen || screen instanceof DeathScreen || screen instanceof PauseScreen
            || screen.getClass().getName().startsWith("com.lumenatlas")) return false;
        for (var child : screen.children()) {
            if (child instanceof net.minecraft.client.gui.components.Button button && button.active && button.visible) {
                String label = button.getMessage().getString().trim().toLowerCase(java.util.Locale.ROOT);
                if (CONTINUE_LABELS.contains(label) || label.startsWith("proceed") || label.startsWith("continue")) {
                    LumenAtlas.LOGGER.info("[SelfTest] clicking '{}' on {}", button.getMessage().getString(), screen.getClass().getSimpleName());
                    button.onPress(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0));
                    clickCooldown = 20;
                    return true;
                }
            }
        }
        return false;
    }

    private static void prepareConfig() {
        LumenConfig c = ConfigManager.get();
        c.minimapEnabled = true;
        c.minimapShape = LumenConfig.Shape.CIRCLE;
        c.minimapSize = 128;
        c.minimapZoom = 2;
        c.minimapRotate = true;
        c.detectStructures = true;
        c.showPredictedStructures = true;
        c.showDiscoveredStructures = true;
        c.showPassiveMobs = false;
        c.scanBudgetMs = 6f;
        c.hiddenStructureTypes = new java.util.LinkedHashSet<>(LumenConfig.DEFAULT_HIDDEN_TYPES);
        ConfigManager.save();
    }

    private static void createTestWorld(Minecraft mc) throws Exception {
        String folder = FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), "AtlasTest", "");
        LevelSettings settings = new LevelSettings("AtlasTest", GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(folder, settings, new WorldOptions(SEED, true, false), SelfTest::normalWorld, Compat.screen(mc));
    }

    private static WorldDimensions normalWorld(HolderLookup.Provider registries) {
        return registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions();
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void script(Minecraft mc) {
        WorldSession session = LumenAtlas.session();
        step("setup", () -> {
            command("gamerule advance_time false");
            command("gamerule advance_weather false");
            command("gamerule spawn_mobs false");
            command("time set 6000");
            command("weather clear");
        }, 60);
        step("home waypoint", () -> {
            Waypoint home = new Waypoint("Home", OVERWORLD, mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), 0xA78BFA, WaypointIcon.HOUSE.key());
            session.waypoints().add(home);
            session.saveWaypoints();
        }, 200);

        // Death waypoint.
        step("walk away from home", () -> command("execute as @a at @s run tp @s ~25 ~ ~-20"), 20);
        step("die", () -> command("kill @a"), 1);
        waitUntil("death screen", () -> Compat.screen(mc) instanceof DeathScreen, 40, 200);
        step("respawn", () -> {
            mc.player.respawn();
            Compat.setScreen(mc, null);
        }, 60);
        step("check death waypoint", () -> check("death waypoint created",
            session.waypoints().all().stream().anyMatch(Waypoint::isDeath)), 20);
        step("spectator", () -> command("gamemode spectator @a"), 20);

        // Seed map predictions around spawn.
        waitUntil("seed map ready", () -> {
            session.seedMap().request(CALLER, OVERWORLD, -3000, -3000, 3000, 3000);
            return session.seedMap().status(OVERWORLD) == SeedMapService.Status.READY;
        }, 20, 1200);
        step("request predictions", () -> session.seedMap().request(CALLER, OVERWORLD, -3000, -3000, 3000, 3000), 10);
        waitUntil("predictions computed", () -> session.seedMap().pendingTasks() == 0, 40, 6000);
        step("pick targets", () -> pickTargets(mc, session), 10);
        step("compare with vanilla locate", () -> compareWithVanilla(mc, session), 10);
        step("compare offline worldgen", () -> compareOfflineWorldgen(mc, session), 10);
        step("visit targets", () -> scheduleVisits(mc, session), 0);
    }

    private static void scheduleVisits(Minecraft mc, WorldSession session) {
        // Inserted at the front of the queue in reverse order, so they run next and in order.
        List<Step> visits = new ArrayList<>();
        for (Map.Entry<String, SeedMapService.Prediction> e : TARGETS.entrySet()) {
            SeedMapService.Prediction p = e.getValue();
            StructureKind kind = p.kind();
            visits.add(new Step("fly to " + kind.name(), () -> {
                command("tp @a " + p.x() + " " + Math.max(p.y() + 40, 120) + " " + (p.z() + 40) + " 180 50");
            }, null, 30, 0));
            visits.add(new Step("wait for " + kind.name(), () -> {}, () -> discovered(session, kind, p) != null, 20, 400));
            visits.add(new Step("check " + kind.name(), () -> {
                Marker m = discovered(session, kind, p);
                if (m != null) {
                    double d = Math.sqrt(Math.pow(m.x - p.x(), 2) + Math.pow(m.z - p.z(), 2));
                    check(kind.name() + " detected near prediction (" + Math.round(d) + " blocks apart)", true);
                } else {
                    String detail = kind.detectable() ? "" : " (not detectable from client data by design)";
                    check(kind.name() + " detected near prediction at " + p.x() + ", " + p.z() + detail, !kind.detectable());
                }
            }, null, 5, 0));
            if (kind == StructureKinds.VILLAGE) visits.addAll(villageScreenshots(mc, session, p));
        }
        visits.addAll(finalScreens(mc, session));
        for (int i = visits.size() - 1; i >= 0; i--) STEPS.addFirst(visits.get(i));
    }

    private static Marker discovered(WorldSession session, StructureKind kind, SeedMapService.Prediction p) {
        return session.markers().nearest(kind.key(), OVERWORLD, p.x(), p.z(), Math.max(160, kind.mergeRadius() + 64));
    }

    private static List<Step> villageScreenshots(Minecraft mc, WorldSession session, SeedMapService.Prediction village) {
        List<Step> s = new ArrayList<>();
        s.add(new Step("frame village", () -> command("tp @a " + (village.x() - 30) + " " + (village.y() + 28) + " " + (village.z() + 30) + " 225 35"), null, 120, 0));
        s.add(shot(mc, "minimap_village"));
        s.add(new Step("village waypoint", () -> {
            Waypoint w = new Waypoint("Village", OVERWORLD, village.x(), village.y() + 1, village.z(), 0xF2C14E, WaypointIcon.STAR.key());
            session.waypoints().add(w);
            Waypoint mine = new Waypoint("Iron Mine", OVERWORLD, village.x() + 180, 30, village.z() - 140, 0x60A5FA, WaypointIcon.CAVE.key());
            session.waypoints().add(mine);
            session.saveWaypoints();
        }, null, 10, 0));
        s.add(new Step("square topo minimap", () -> {
            ConfigManager.get().minimapShape = LumenConfig.Shape.SQUARE;
            ConfigManager.get().minimapView = MapView.TOPOGRAPHIC;
            ConfigManager.get().minimapRotate = false;
        }, null, 40, 0));
        s.add(shot(mc, "minimap_square_topographic"));
        s.add(new Step("round minimap again", () -> {
            ConfigManager.get().minimapShape = LumenConfig.Shape.CIRCLE;
            ConfigManager.get().minimapView = MapView.SHADED;
            ConfigManager.get().minimapRotate = true;
        }, null, 10, 0));
        s.add(new Step("open map at village", () -> {
            WorldMapScreen.debugMouseOverride = null;
            Compat.setScreen(mc, new WorldMapScreen(session).centeredOn(village.x(), village.z()).withScale(2).withLayers(false));
        }, null, 100, 0));
        s.add(new Step("hover village icon", () -> {
            Marker m = discovered(session, StructureKinds.VILLAGE, village);
            if (m != null) {
                int w = mc.getWindow().getGuiScaledWidth();
                int h = mc.getWindow().getGuiScaledHeight();
                WorldMapScreen.debugMouseOverride = new int[] {(int) Math.round((m.x + 0.5 - (village.x() + 0.5)) * 2 + w / 2.0),
                    (int) Math.round((m.z + 0.5 - (village.z() + 0.5)) * 2 + h / 2.0)};
            }
        }, null, 10, 0));
        s.add(shot(mc, "worldmap_village"));
        s.add(new Step("close map", () -> {
            WorldMapScreen.debugMouseOverride = null;
            Compat.setScreen(mc, null);
        }, null, 10, 0));
        return s;
    }

    private static List<Step> finalScreens(Minecraft mc, WorldSession session) {
        List<Step> s = new ArrayList<>();
        s.add(new Step("world map overview", () -> {
            WorldMapScreen.debugMouseOverride = new int[] {-100, -100};
            Compat.setScreen(mc, new WorldMapScreen(session).centeredOn(0, 0).withScale(0.25).withLayers(false));
        }, null, 160, 0));
        s.add(shot(mc, "worldmap_overview"));
        s.add(new Step("layers panel", () -> Compat.setScreen(mc, new WorldMapScreen(session).centeredOn(0, 0).withScale(0.5).withLayers(true)), null, 100, 0));
        s.add(shot(mc, "worldmap_layers"));
        s.add(new Step("seed map wide", () -> {
            ConfigManager.get().showDiscoveredStructures = false;
            Compat.setScreen(mc, new WorldMapScreen(session).centeredOn(0, 0).withScale(0.08).withLayers(false));
        }, null, 200, 0));
        s.add(shot(mc, "seedmap_predictions"));
        s.add(new Step("discovered back on", () -> ConfigManager.get().showDiscoveredStructures = true, null, 5, 0));
        s.add(new Step("waypoint list", () -> {
            WorldMapScreen.debugMouseOverride = null;
            Compat.setScreen(mc, new WaypointListScreen(null, session));
        }, null, 30, 0));
        s.add(shot(mc, "waypoints_list"));
        s.add(new Step("waypoint editor", () -> {
            Waypoint w = session.waypoints().all().stream().filter(x -> x.name.equals("Village")).findFirst().orElse(session.waypoints().all().get(0));
            Compat.setScreen(mc, new WaypointEditScreen(null, session, w, false));
        }, null, 30, 0));
        s.add(shot(mc, "waypoint_edit"));
        s.add(new Step("settings", () -> Compat.setScreen(mc, new SettingsScreen(null, SettingsScreen.Tab.MINIMAP)), null, 30, 0));
        s.add(shot(mc, "settings_minimap"));
        s.add(new Step("settings structures", () -> Compat.setScreen(mc, new SettingsScreen(null, SettingsScreen.Tab.STRUCTURES)), null, 30, 0));
        s.add(shot(mc, "settings_structures"));
        s.add(new Step("seed screen", () -> Compat.setScreen(mc, new SeedMapScreen(null, session)), null, 30, 0));
        s.add(shot(mc, "seedmap_screen"));
        s.add(new Step("in-world waypoints", () -> {
            Compat.setScreen(mc, null);
            Waypoint home = session.waypoints().all().stream().filter(x -> x.name.equals("Home")).findFirst().orElse(null);
            if (home != null) {
                command("tp @a " + (home.x + 50) + " " + (home.y + 18) + " " + (home.z + 70) + " 160 12");
            }
        }, null, 140, 0));
        s.add(shot(mc, "waypoints_in_world"));
        // Nether: cave view minimap and a fortress.
        s.add(new Step("nether", () -> command("execute in minecraft:the_nether run tp @a 0 70 0"), null, 200, 0));
        s.add(new Step("nether predictions", () -> session.seedMap().request(CALLER, NETHER, -1500, -1500, 1500, 1500), null, 10, 0));
        s.add(new Step("wait nether predictions", () -> session.seedMap().request(CALLER, NETHER, -1500, -1500, 1500, 1500),
            () -> session.seedMap().status(NETHER) == SeedMapService.Status.READY && session.seedMap().pendingTasks() == 0, 40, 2400));
        s.add(new Step("fly to fortress", () -> {
            SeedMapService.Prediction fortress = nearest(session, NETHER, StructureKinds.FORTRESS, 0, 0);
            if (fortress != null) {
                TARGETS.put("nether_fortress", fortress);
                command("execute in minecraft:the_nether run tp @a " + fortress.x() + " " + (fortress.y() + 20) + " " + (fortress.z() + 25) + " 180 40");
            } else {
                LumenAtlas.LOGGER.warn("[SelfTest] no fortress predicted near the Nether origin");
            }
        }, null, 40, 0));
        s.add(new Step("wait fortress", () -> {}, () -> {
            SeedMapService.Prediction f = TARGETS.get("nether_fortress");
            return f == null || session.markers().nearest(StructureKinds.FORTRESS.key(), NETHER, f.x(), f.z(), 200) != null;
        }, 60, 500));
        s.add(new Step("check fortress", () -> {
            SeedMapService.Prediction f = TARGETS.get("nether_fortress");
            if (f != null) check("Nether Fortress detected near prediction", session.markers().nearest(StructureKinds.FORTRESS.key(), NETHER, f.x(), f.z(), 200) != null);
        }, null, 5, 0));
        s.add(shot(mc, "minimap_nether"));
        s.add(new Step("nether map", () -> {
            SeedMapService.Prediction f = TARGETS.get("nether_fortress");
            Compat.setScreen(mc, new WorldMapScreen(session).centeredOn(f != null ? f.x() : 0, f != null ? f.z() : 0).withScale(1));
        }, null, 100, 0));
        s.add(shot(mc, "worldmap_nether"));
        s.add(new Step("false positive check", () -> falsePositiveCheck(session), null, 5, 0));
        s.add(new Step("summary", SelfTest::summary, null, 20, 0));
        s.add(new Step("quit", () -> {
            Compat.setScreen(mc, null);
            LumenAtlas.LOGGER.info("[SelfTest] finished");
            mc.stop();
        }, null, 0, 0));
        return s;
    }

    // ---------------------------------------------------------------------------------------------------------

    private static final List<StructureKind> VISIT = List.of(StructureKinds.VILLAGE, StructureKinds.DESERT_PYRAMID, StructureKinds.OUTPOST,
        StructureKinds.RUINED_PORTAL, StructureKinds.SHIPWRECK, StructureKinds.MONUMENT, StructureKinds.TRIAL_CHAMBERS,
        StructureKinds.ANCIENT_CITY, StructureKinds.JUNGLE_TEMPLE, StructureKinds.IGLOO, StructureKinds.SWAMP_HUT, StructureKinds.TRAIL_RUINS,
        StructureKinds.OCEAN_RUIN, StructureKinds.MANSION, StructureKinds.STRONGHOLD, StructureKinds.BURIED_TREASURE);

    private static void pickTargets(Minecraft mc, WorldSession session) {
        List<SeedMapService.Prediction> all = session.seedMap().all(OVERWORLD);
        Map<String, Integer> counts = new HashMap<>();
        for (SeedMapService.Prediction p : all) counts.merge(p.kind().key(), 1, Integer::sum);
        LumenAtlas.LOGGER.info("[SelfTest] {} predictions within 3000 blocks: {}", all.size(), counts);
        check("seed map predicted structures", all.size() > 20);
        for (StructureKind kind : VISIT) {
            SeedMapService.Prediction p = nearest(session, OVERWORLD, kind, 0, 0);
            if (p != null && Math.abs(p.x()) < 3000 && Math.abs(p.z()) < 3000) TARGETS.put(kind.key(), p);
            else LumenAtlas.LOGGER.info("[SelfTest] no {} predicted near spawn", kind.name());
        }
        LumenAtlas.LOGGER.info("[SelfTest] targets: {}", TARGETS.keySet());
    }

    private static SeedMapService.Prediction nearest(WorldSession session, String dim, StructureKind kind, int x, int z) {
        return session.seedMap().all(dim).stream().filter(p -> p.kind() == kind)
            .min(Comparator.comparingDouble(p -> Math.pow(p.x() - x, 2) + Math.pow(p.z() - z, 2))).orElse(null);
    }

    /** Vanilla's structure search (the code behind /locate) must find our prediction for each target. */
    private static void compareWithVanilla(Minecraft mc, WorldSession session) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        CompletableFuture<Void> done = CompletableFuture.runAsync(() -> {
            ServerLevel level = server.overworld();
            Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            for (SeedMapService.Prediction p : TARGETS.values()) {
                Holder<Structure> holder = structures.get(Identifier.parse(p.structureId())).orElse(null);
                if (holder == null) continue;
                BlockPos found = level.findNearestMapStructure(HolderSet.direct(holder), new BlockPos(p.x(), 64, p.z()), 4, false);
                boolean match = found != null && Math.abs((found.getX() >> 4) - (p.x() >> 4)) <= 3 && Math.abs((found.getZ() >> 4) - (p.z() >> 4)) <= 3;
                check("vanilla locate agrees for " + p.structureId() + " at " + p.x() + ", " + p.z()
                    + " (vanilla: " + (found == null ? "none" : found.getX() + ", " + found.getZ()) + ")", match);
            }
        }, server);
        mc.managedBlock(done::isDone);
    }

    /**
     * The manual-seed path (registries rebuilt from the vanilla data pack, as on a server) must predict exactly what
     * the integrated server's real generator predicts.
     */
    private static void compareOfflineWorldgen(Minecraft mc, WorldSession session) {
        CompletableFuture<int[]> result = CompletableFuture.supplyAsync(() -> {
            try {
                WorldgenContext offline = WorldgenLoader.create(SEED, WorldgenLoader.Preset.DEFAULT, OVERWORLD);
                MinecraftServer server = mc.getSingleplayerServer();
                ServerLevel level = server.overworld();
                var source = level.getChunkSource();
                WorldgenContext real = new WorldgenContext(level.getSeed(), level.registryAccess(), source.getGenerator(), source.randomState(),
                    source.getGeneratorState(), server.getStructureTemplateManager(), level);
                int same = 0;
                int different = 0;
                for (Holder<StructureSet> holder : real.structureSets()) {
                    StructureSet set = holder.value();
                    if (!(set.placement() instanceof RandomSpreadStructurePlacement spread) || spread.spacing() < 8) continue;
                    String setId = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                    StructureSet offlineSet = offline.registries.lookupOrThrow(Registries.STRUCTURE_SET)
                        .getValue(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse(setId)));
                    if (offlineSet == null) continue;
                    for (int gx = -4; gx <= 4; gx++) {
                        for (int gz = -4; gz <= 4; gz++) {
                            ChunkPos c = spread.getPotentialStructureChunk(SEED, gx * spread.spacing(), gz * spread.spacing());
                            String a = spread.isStructureChunk(real.structureState, c.x(), c.z()) ? real.evaluate(set, c).map(f -> f.id() + "@" + f.x() + "," + f.z()).orElse("-") : "-";
                            String b = offlineSet.placement().isStructureChunk(offline.structureState, c.x(), c.z())
                                ? offline.evaluate(offlineSet, c).map(f -> f.id() + "@" + f.x() + "," + f.z()).orElse("-") : "-";
                            if (a.equals(b)) same++;
                            else {
                                different++;
                                LumenAtlas.LOGGER.warn("[SelfTest] offline worldgen differs for {} {}: server {} vs offline {}", setId, c, a, b);
                            }
                        }
                    }
                }
                return new int[] {same, different};
            } catch (Exception e) {
                LumenAtlas.LOGGER.error("[SelfTest] offline worldgen failed", e);
                return new int[] {0, -1};
            }
        });
        mc.managedBlock(result::isDone);
        int[] r = result.join();
        check("offline (manual seed) worldgen matches the server: " + r[0] + " cells equal, " + r[1] + " different", r[1] == 0 && r[0] > 0);
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void check(String what, boolean ok) {
        if (ok) passed++;
        else failed++;
        RESULTS.put(what, ok ? "PASS" : "FAIL");
        LumenAtlas.LOGGER.info("[SelfTest] {} {}", ok ? "PASS" : "FAIL", what);
    }

    /**
     * Every discovered marker of a kind the seed map can predict must have a prediction nearby; a marker without one
     * is a false detection. Only markers inside the areas predicted during the test are counted.
     */
    private static void falsePositiveCheck(WorldSession session) {
        int checked = 0;
        int unmatched = 0;
        for (Marker m : session.markers().all()) {
            StructureKind kind = StructureKinds.byKey(m.type);
            if (kind.structureIds().isEmpty() || kind == StructureKinds.MINESHAFT || kind == StructureKinds.BURIED_TREASURE) continue;
            int range = m.dimension.equals(OVERWORLD) ? 2700 : 1300;
            if (Math.abs(m.x) > range || Math.abs(m.z) > range) continue;
            if (!m.dimension.equals(OVERWORLD) && !m.dimension.equals(NETHER)) continue;
            checked++;
            int tolerance = Math.max(96, kind.mergeRadius() + 48);
            boolean match = session.seedMap().all(m.dimension).stream().anyMatch(p -> p.kind() == kind
                && Math.abs(p.x() - m.x) <= tolerance && Math.abs(p.z() - m.z) <= tolerance);
            if (!match) {
                unmatched++;
                LumenAtlas.LOGGER.warn("[SelfTest] {} marker at {} {} {} has no seed prediction nearby", kind.name(), m.x, m.y, m.z);
            }
        }
        check("discovered markers match seed predictions: " + (checked - unmatched) + " of " + checked, checked > 0 && unmatched * 10 <= checked);
    }

    private static void summary() {
        WorldSession session = LumenAtlas.session();
        LumenAtlas.LOGGER.info("[SelfTest] ===== summary: {} passed, {} failed =====", passed, failed);
        RESULTS.forEach((k, v) -> LumenAtlas.LOGGER.info("[SelfTest]   {} {}", v, k));
        if (session != null) {
            for (Marker m : session.markers().all()) {
                LumenAtlas.LOGGER.info("[SelfTest] marker {} {} at {} {} {} (hits {})", m.dimension, m.type, m.x, m.y, m.z, m.hits);
            }
        }
    }

    private static void step(String name, Runnable action, int waitAfter) {
        STEPS.add(new Step(name, action, null, waitAfter, 0));
    }

    private static void waitUntil(String name, BooleanSupplier until, int waitAfter, int timeout) {
        STEPS.add(new Step(name, () -> {}, until, waitAfter, timeout));
    }

    private static Step shot(Minecraft mc, String name) {
        return new Step("screenshot " + name, () -> Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
            msg -> LumenAtlas.LOGGER.info("[SelfTest] screenshot {}", name)), null, 15, 0);
    }

    private static void command(String command) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }
}
