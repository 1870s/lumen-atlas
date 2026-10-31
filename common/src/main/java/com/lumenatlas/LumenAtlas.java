package com.lumenatlas;

import com.lumenatlas.compat.Compat;
import com.lumenatlas.config.ConfigManager;
import com.lumenatlas.gui.SettingsScreen;
import com.lumenatlas.gui.WaypointEditScreen;
import com.lumenatlas.gui.WaypointListScreen;
import com.lumenatlas.gui.WorldMapScreen;
import com.lumenatlas.hud.MinimapHud;
import com.lumenatlas.hud.WaypointHud;
import com.lumenatlas.world.WorldSession;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * Loader independent core of Lumen Atlas. The Fabric and NeoForge entry points only register the key mappings and
 * the HUD layer and forward the client tick to this class.
 */
public final class LumenAtlas {
    public static final String MOD_ID = "lumenatlas";
    public static final String NAME = "Lumen Atlas";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final Identifier HUD_LAYER_ID = id("hud");

    /** Loader specific services. */
    public interface Platform {
        Path configDir();

        String loaderName();
    }

    private static Platform platform;
    private static final List<Runnable> TICK_LISTENERS = new ArrayList<>();
    private static WorldSession session;
    private static boolean initialized;

    private LumenAtlas() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Called from the loader entry point before key mappings are registered. */
    public static void init(Platform loaderPlatform, KeyMapping.Category category) {
        platform = loaderPlatform;
        Keybinds.create(category);
        if (Boolean.getBoolean("lumenatlas.selftest")) installSelfTest();
    }

    /** Runs once the game is initialized far enough to read files. */
    public static void onClientSetup() {
        if (initialized) return;
        initialized = true;
        ConfigManager.load();
        LOGGER.info("{} initialized on {}", NAME, platform.loaderName());
    }

    public static Platform platform() {
        return platform;
    }

    /** Folder for map data: {@code <game dir>/lumenatlas}. Kept out of config/ because it can get large. */
    public static Path dataDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(MOD_ID);
    }

    /** The open world, or null in the main menu. */
    public static WorldSession session() {
        return session;
    }

    public static void addTickListener(Runnable listener) {
        TICK_LISTENERS.add(listener);
    }

    /** Called at the end of every client tick. */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (!initialized) onClientSetup();
        try {
            session = WorldSession.update(session, mc);
            if (session != null) session.tick(mc);
            handleKeys(mc);
        } catch (RuntimeException e) {
            // Never take the game down because of a map problem; log and keep going.
            LOGGER.error("Lumen Atlas tick failed", e);
        }
        for (Runnable listener : TICK_LISTENERS) listener.run();
    }

    private static void handleKeys(Minecraft mc) {
        if (Compat.screen(mc) != null || mc.player == null) {
            Keybinds.drain();
            return;
        }
        while (Keybinds.OPEN_WORLD_MAP.consumeClick()) {
            if (session != null) Compat.setScreen(mc, new WorldMapScreen(session));
        }
        while (Keybinds.OPEN_WAYPOINTS.consumeClick()) {
            if (session != null) Compat.setScreen(mc, new WaypointListScreen(null, session));
        }
        while (Keybinds.NEW_WAYPOINT.consumeClick()) {
            if (session != null) Compat.setScreen(mc, WaypointEditScreen.atPlayer(null, session));
        }
        while (Keybinds.OPEN_SETTINGS.consumeClick()) {
            Compat.setScreen(mc, new SettingsScreen(null));
        }
        while (Keybinds.TOGGLE_MINIMAP.consumeClick()) {
            ConfigManager.get().minimapEnabled = !ConfigManager.get().minimapEnabled;
            ConfigManager.save();
        }
        while (Keybinds.ZOOM_IN.consumeClick()) MinimapHud.zoom(-1);
        while (Keybinds.ZOOM_OUT.consumeClick()) MinimapHud.zoom(1);
        while (Keybinds.CYCLE_VIEW.consumeClick()) {
            ConfigManager.get().minimapView = ConfigManager.get().minimapView.next();
            ConfigManager.save();
            mc.gui.hud.setOverlayMessage(Component.translatable("lumenatlas.view." + ConfigManager.get().minimapView.name().toLowerCase(java.util.Locale.ROOT)), false);
        }
    }

    /** HUD layer: minimap and in-world waypoint labels. */
    public static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (session == null || mc.player == null || mc.gui.hud.isHidden()) return;
        try {
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
            WaypointHud.render(graphics, mc, session, partialTick);
            MinimapHud.render(graphics, mc, session, partialTick);
        } catch (RuntimeException e) {
            LOGGER.error("Lumen Atlas HUD failed", e);
        }
    }

    /** Dev-only smoke test; the class is not part of the release jars, so it is loaded reflectively. */
    private static void installSelfTest() {
        try {
            Class.forName("com.lumenatlas.dev.SelfTest").getMethod("install").invoke(null);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Self test requested but not available", e);
        }
    }
}
