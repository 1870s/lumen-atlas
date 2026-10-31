package com.lumenatlas.gui;

import com.lumenatlas.LumenAtlas;
import com.lumenatlas.compat.Compat;
import com.lumenatlas.config.ConfigManager;
import com.lumenatlas.core.LumenConfig;
import com.lumenatlas.hud.MinimapHud;
import com.lumenatlas.world.WorldSession;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

/** In-game settings, grouped in tabs. Every change is applied and saved immediately. Shift-click cycles backwards. */
public class SettingsScreen extends LumenScreen {
    public enum Tab { MINIMAP, WORLD_MAP, STRUCTURES, WAYPOINTS, ADVANCED }

    private static Tab lastTab = Tab.MINIMAP;
    private Tab tab = lastTab;
    private boolean confirmReset;

    public SettingsScreen(Screen parent) {
        super(parent, Component.translatable("lumenatlas.settings.title"));
    }

    public SettingsScreen(Screen parent, Tab tab) {
        this(parent);
        this.tab = tab;
    }

    @Override
    protected int panelWidth() {
        return 420;
    }

    @Override
    protected int panelHeight() {
        return 280;
    }

    private static LumenConfig c() {
        return ConfigManager.get();
    }

    @Override
    protected void initContent() {
        int tabW = (panelRight - panelLeft - 20) / Tab.values().length;
        int x = panelLeft + 10;
        for (Tab t : Tab.values()) {
            addRenderableWidget(new LumenButton(x, panelTop + 30, tabW - 2, 18,
                Component.translatable("lumenatlas.settings.tab." + t.name().toLowerCase(Locale.ROOT)), b -> {
                    tab = t;
                    lastTab = t;
                    confirmReset = false;
                    rebuildWidgets();
                }).selected(() -> tab == t));
            x += tabW;
        }
        Grid grid = new Grid(panelLeft + 12, panelTop + 56, (panelRight - panelLeft - 30) / 2);
        switch (tab) {
            case MINIMAP -> minimap(grid);
            case WORLD_MAP -> worldMap(grid);
            case STRUCTURES -> structures(grid);
            case WAYPOINTS -> waypoints(grid);
            case ADVANCED -> advanced(grid);
        }
        addRenderableWidget(new LumenButton(panelLeft + 10, panelBottom - 26, 110, 18, Component.translatable("lumenatlas.settings.keys"),
            b -> Compat.setScreen(minecraft, new KeyBindsScreen(this, minecraft.options))));
        addRenderableWidget(new LumenButton(panelRight - 90, panelBottom - 26, 80, 18, Component.translatable("gui.done"), b -> onClose()));
    }

    private void minimap(Grid g) {
        toggle(g, "minimap.enabled", () -> c().minimapEnabled, v -> c().minimapEnabled = v);
        cycle(g, "minimap.shape", () -> name(c().minimapShape), back -> c().minimapShape = step(LumenConfig.Shape.values(), c().minimapShape, back));
        cycle(g, "minimap.size", () -> Component.literal(Integer.toString(c().minimapSize)), back -> c().minimapSize = stepInt(LumenConfig.MINIMAP_SIZES, c().minimapSize, back));
        cycle(g, "minimap.corner", () -> name(c().minimapCorner), back -> c().minimapCorner = step(LumenConfig.Corner.values(), c().minimapCorner, back));
        cycle(g, "minimap.zoom", () -> Component.literal(zoomLabel(LumenConfig.MINIMAP_ZOOMS[c().minimapZoom])), back -> MinimapHud.zoom(back ? -1 : 1));
        cycle(g, "minimap.opacity", () -> Component.literal(Math.round(c().minimapOpacity * 100) + "%"), back -> {
            int pct = Math.round(c().minimapOpacity * 100) + (back ? -10 : 10);
            if (pct > 100) pct = 30;
            if (pct < 30) pct = 100;
            c().minimapOpacity = pct / 100f;
        });
        toggle(g, "minimap.rotate", () -> c().minimapRotate, v -> c().minimapRotate = v);
        cycle(g, "minimap.view", () -> Component.translatable("lumenatlas.view." + c().minimapView.name().toLowerCase(Locale.ROOT)),
            back -> c().minimapView = back ? step(com.lumenatlas.core.map.MapView.values(), c().minimapView, true) : c().minimapView.next());
        cycle(g, "minimap.cave", () -> name(c().caveMode), back -> c().caveMode = step(LumenConfig.CaveMode.values(), c().caveMode, back));
        toggle(g, "minimap.coords", () -> c().showCoordinates, v -> c().showCoordinates = v);
        toggle(g, "minimap.biome", () -> c().showBiome, v -> c().showBiome = v);
        toggle(g, "minimap.cardinals", () -> c().showCardinals, v -> c().showCardinals = v);
        toggle(g, "minimap.players", () -> c().showPlayers, v -> c().showPlayers = v);
        toggle(g, "minimap.hostile", () -> c().showHostileMobs, v -> c().showHostileMobs = v);
        toggle(g, "minimap.passive", () -> c().showPassiveMobs, v -> c().showPassiveMobs = v);
        cycle(g, "minimap.margin", () -> Component.literal(c().minimapMarginX + " px"), back -> {
            int[] steps = {0, 2, 4, 6, 10, 16, 24, 32};
            c().minimapMarginX = stepInt(steps, c().minimapMarginX, back);
            c().minimapMarginY = c().minimapMarginX;
        });
    }

    private void worldMap(Grid g) {
        cycle(g, "worldmap.view", () -> Component.translatable("lumenatlas.view." + c().worldMapView.name().toLowerCase(Locale.ROOT)),
            back -> c().worldMapView = back ? step(com.lumenatlas.core.map.MapView.values(), c().worldMapView, true) : c().worldMapView.next());
        toggle(g, "worldmap.grid", () -> c().worldMapGrid, v -> c().worldMapGrid = v);
        toggle(g, "worldmap.animations", () -> c().worldMapAnimations, v -> c().worldMapAnimations = v);
        cycle(g, "worldmap.icon_scale", () -> Component.literal(Math.round(c().iconScale * 100) + "%"), back -> {
            int[] steps = {50, 75, 100, 125, 150, 200};
            c().iconScale = stepInt(steps, Math.round(c().iconScale * 100), back) / 100f;
        });
        toggle(g, "worldmap.waypoints", () -> c().showWaypointsOnWorldMap, v -> c().showWaypointsOnWorldMap = v);
    }

    private void structures(Grid g) {
        toggle(g, "structures.detect", () -> c().detectStructures, v -> c().detectStructures = v);
        toggle(g, "structures.discovered", () -> c().showDiscoveredStructures, v -> c().showDiscoveredStructures = v);
        toggle(g, "structures.predicted", () -> c().showPredictedStructures, v -> c().showPredictedStructures = v);
        toggle(g, "structures.minimap", () -> c().structuresOnMinimap, v -> c().structuresOnMinimap = v);
        toggle(g, "structures.announce", () -> c().announceDiscoveries, v -> c().announceDiscoveries = v);
        g.full();
        addRenderableWidget(new LumenButton(g.x, g.y, g.colW * 2 + 6, 18, Component.translatable("lumenatlas.settings.structures.types"), b -> {
            WorldSession session = LumenAtlas.session();
            if (session != null) Compat.setScreen(minecraft, new WorldMapScreen(session));
        }).tooltip(Component.translatable("lumenatlas.settings.structures.types.tooltip")));
    }

    private void waypoints(Grid g) {
        toggle(g, "waypoints.minimap", () -> c().showWaypointsOnMinimap, v -> c().showWaypointsOnMinimap = v);
        toggle(g, "waypoints.world", () -> c().showWaypointsInWorld, v -> c().showWaypointsInWorld = v);
        cycle(g, "waypoints.range", () -> Component.literal(c().inWorldWaypointRange + " m"), back -> {
            int[] steps = {64, 128, 256, 500, 1000, 2000, 5000, 10000, 100000};
            c().inWorldWaypointRange = stepInt(steps, c().inWorldWaypointRange, back);
        });
        toggle(g, "waypoints.death", () -> c().deathWaypoints, v -> c().deathWaypoints = v);
        cycle(g, "waypoints.keep_deaths", () -> Component.literal(Integer.toString(c().keepDeathWaypoints)), back -> {
            int[] steps = {1, 2, 3, 5, 10, 20};
            c().keepDeathWaypoints = stepInt(steps, c().keepDeathWaypoints, back);
        });
    }

    private void advanced(Grid g) {
        cycle(g, "advanced.scan_budget", () -> Component.literal(c().scanBudgetMs + " ms"), back -> {
            float[] steps = {1f, 1.5f, 2.5f, 4f, 6f};
            int i = 0;
            for (int k = 0; k < steps.length; k++) if (Math.abs(steps[k] - c().scanBudgetMs) < 0.01) i = k;
            c().scanBudgetMs = steps[Math.floorMod(i + (back ? -1 : 1), steps.length)];
        });
        cycle(g, "advanced.minimap_fps", () -> Component.literal(Integer.toString(c().minimapFps)), back -> {
            int[] steps = {20, 30, 45, 60, 90, 120};
            c().minimapFps = stepInt(steps, c().minimapFps, back);
        });
        cycle(g, "advanced.texture_cache", () -> Component.literal(Integer.toString(c().textureCacheSize)), back -> {
            int[] steps = {64, 96, 160, 256, 384, 512};
            c().textureCacheSize = stepInt(steps, c().textureCacheSize, back);
        });
        g.full();
        addRenderableWidget(new LumenButton(g.x, g.y, g.colW, 18, Component.translatable("lumenatlas.settings.advanced.open_folder"),
            b -> Compat.openPath(LumenAtlas.dataDir())));
        WorldSession session = LumenAtlas.session();
        if (session != null) {
            addRenderableWidget(new LumenButton(g.x + g.colW + 6, g.y, g.colW, 18,
                () -> Component.translatable(confirmReset ? "lumenatlas.settings.advanced.reset_confirm" : "lumenatlas.settings.advanced.reset"), b -> {
                    if (confirmReset) {
                        session.currentMap().deleteAll();
                        confirmReset = false;
                    } else {
                        confirmReset = true;
                    }
                }).tooltip(Component.translatable("lumenatlas.settings.advanced.reset.tooltip")));
        }
    }

    @Override
    protected void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        String hint = Component.translatable("lumenatlas.settings.hint").getString();
        g.text(font, hint, panelLeft + 128, panelBottom - 21, Theme.TEXT_FAINT, false);
    }

    // ---- helpers ----

    /** Two-column layout of option buttons. */
    private static final class Grid {
        final int left;
        final int colW;
        int x;
        int y;
        int col;

        Grid(int left, int top, int colW) {
            this.left = left;
            this.colW = colW;
            this.x = left;
            this.y = top;
        }

        void next() {
            col++;
            if (col == 2) {
                col = 0;
                x = left;
                y += 20;
            } else {
                x = left + colW + 6;
            }
        }

        void full() {
            if (col != 0) {
                col = 0;
                x = left;
                y += 20;
            }
            y += 6;
        }
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private interface CycleAction {
        void run(boolean backwards);
    }

    private void toggle(Grid g, String key, java.util.function.BooleanSupplier get, BoolSetter set) {
        addRenderableWidget(new LumenButton(g.x, g.y, g.colW, 18,
            () -> Component.translatable("lumenatlas.settings." + key).append(": ").append(Component.translatable(get.getAsBoolean() ? "options.on" : "options.off")),
            b -> {
                set.set(!get.getAsBoolean());
                ConfigManager.save();
            }).selected(get).tooltip(Component.translatable("lumenatlas.settings." + key + ".tooltip")));
        g.next();
    }

    private void cycle(Grid g, String key, Supplier<Component> value, CycleAction action) {
        addRenderableWidget(new LumenButton(g.x, g.y, g.colW, 18,
            () -> Component.translatable("lumenatlas.settings." + key).append(": ").append(value.get()),
            b -> {
                action.run(b.shiftDown());
                ConfigManager.save();
            }).tooltip(Component.translatable("lumenatlas.settings." + key + ".tooltip")));
        g.next();
    }

    private static Component name(Enum<?> e) {
        return Component.translatable("lumenatlas.enum." + e.name().toLowerCase(Locale.ROOT));
    }

    private static <E extends Enum<E>> E step(E[] values, E current, boolean back) {
        return values[Math.floorMod(current.ordinal() + (back ? -1 : 1), values.length)];
    }

    private static int stepInt(int[] steps, int current, boolean back) {
        int index = 0;
        for (int i = 0; i < steps.length; i++) if (steps[i] <= current) index = i;
        return steps[Math.floorMod(index + (back ? -1 : 1), steps.length)];
    }

    private static String zoomLabel(float blocksPerPixel) {
        return blocksPerPixel >= 1 ? String.format(Locale.ROOT, "1:%.0f", blocksPerPixel) : String.format(Locale.ROOT, "%.0f:1", 1 / blocksPerPixel);
    }
}
