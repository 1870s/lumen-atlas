package com.lumenatlas.gui;

import com.lumenatlas.Keybinds;
import com.lumenatlas.compat.Compat;
import com.lumenatlas.config.ConfigManager;
import com.lumenatlas.core.LumenConfig;
import com.lumenatlas.core.map.MapRegion;
import com.lumenatlas.core.marker.Marker;
import com.lumenatlas.core.util.MapMath;
import com.lumenatlas.core.waypoint.Waypoint;
import com.lumenatlas.core.waypoint.WaypointIcon;
import com.lumenatlas.map.MapStore;
import com.lumenatlas.map.RegionTextures;
import com.lumenatlas.seed.SeedMapService;
import com.lumenatlas.structure.StructureKind;
import com.lumenatlas.structure.StructureKinds;
import com.lumenatlas.world.WorldSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The full-screen world map: everything explored in a dimension, with discovered structures, seed-map predictions,
 * waypoints and the player. Drag to pan, scroll to zoom (around the cursor), right-click for actions.
 */
public class WorldMapScreen extends Screen {
    private static final double MIN_SCALE = 1 / 32.0;
    private static final double MAX_SCALE = 16;
    private static final int TOP_BAR = 26;
    private static final int BOTTOM_BAR = 16;
    private static final int LAYER_PANEL_W = 150;
    private static final String[] VANILLA_DIMENSIONS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};

    private static final java.util.function.Supplier<com.mojang.renderpearl.api.textures.GpuSampler> EDGE_SAMPLER =
        () -> com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST);

    /** Development hook for the self test's screenshots: when set, the map behaves as if the mouse were here. */
    public static int[] debugMouseOverride;

    /** Remembered between openings. */
    private static double savedScale = 1;
    private static boolean layersOpen;

    private final WorldSession session;
    private String dimension;
    private double centerX;
    private double centerZ;
    private double scale = savedScale;
    private double targetScale = savedScale;
    private double anchorWorldX;
    private double anchorWorldZ;
    private double anchorScreenX;
    private double anchorScreenY;
    private boolean zoomAnimating;
    private Double flyToX;
    private Double flyToZ;
    private boolean dragging;
    private boolean dragMoved;
    private long lastFrame = System.nanoTime();
    private EditBox search;
    private final List<SearchResult> results = new ArrayList<>();
    private ContextMenu menu;
    private int layerScroll;
    private Hover hover;

    private record SearchResult(String label, String detail, int x, int z, int color) {}

    /** What is under the mouse. */
    private record Hover(StructureKind kind, Marker marker, SeedMapService.Prediction prediction, Waypoint waypoint, int x, int y, int z) {}

    public WorldMapScreen(WorldSession session) {
        super(Component.translatable("lumenatlas.worldmap.title"));
        this.session = session;
        this.dimension = session.dimension();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            centerX = player.getX();
            centerZ = player.getZ();
        }
    }

    /** Opens the map centered on a position (used by the waypoint list). */
    public WorldMapScreen centeredOn(int x, int z) {
        centerX = x + 0.5;
        centerZ = z + 0.5;
        return this;
    }

    /** Opens at a given zoom (screen pixels per block). */
    public WorldMapScreen withScale(double pixelsPerBlock) {
        scale = targetScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, pixelsPerBlock));
        return this;
    }

    /** Opens the map of another dimension. */
    public WorldMapScreen showingDimension(String dimensionId) {
        dimension = dimensionId;
        return this;
    }

    public WorldMapScreen withLayers(boolean open) {
        layersOpen = open;
        return this;
    }

    private LumenConfig config() {
        return ConfigManager.get();
    }

    @Override
    protected void init() {
        int x = 6;
        int y = 4;
        addRenderableWidget(new LumenButton(x, y, 92, 18, () -> Component.literal(dimensionLabel(dimension)), b -> cycleDimension())
            .tooltip(Component.translatable("lumenatlas.worldmap.dimension.tooltip")));
        x += 96;
        addRenderableWidget(new LumenButton(x, y, 86, 18,
            () -> Component.translatable("lumenatlas.view." + config().worldMapView.name().toLowerCase(Locale.ROOT)), b -> {
                config().worldMapView = config().worldMapView.next();
                ConfigManager.save();
            }).tooltip(Component.translatable("lumenatlas.worldmap.view.tooltip")));
        x += 90;
        addRenderableWidget(new LumenButton(x, y, 60, 18, Component.translatable("lumenatlas.worldmap.layers"), b -> {
            layersOpen = !layersOpen;
            rebuildWidgets();
        }).selected(() -> layersOpen));
        x += 64;
        addRenderableWidget(new LumenButton(x, y, 74, 18, Component.translatable("lumenatlas.worldmap.waypoints"),
            b -> Compat.setScreen(minecraft, new WaypointListScreen(this, session))));
        x += 78;
        addRenderableWidget(new LumenButton(x, y, 70, 18, Component.translatable("lumenatlas.worldmap.seed"),
            b -> Compat.setScreen(minecraft, new SeedMapScreen(this, session)))
            .selected(() -> session.seedMap().isActive(dimension)));
        x += 74;
        addRenderableWidget(new LumenButton(x, y, 20, 18, Component.literal("⌂"), b -> centerOnPlayer())
            .tooltip(Component.translatable("lumenatlas.worldmap.center")));
        x += 24;
        addRenderableWidget(new LumenButton(x, y, 20, 18, Component.literal("⚙"), b -> Compat.setScreen(minecraft, new SettingsScreen(this)))
            .tooltip(Component.translatable("lumenatlas.settings.title")));
        x += 26;
        int searchW = Math.max(80, Math.min(180, width - x - 8));
        String previous = search != null ? search.getValue() : "";
        search = new EditBox(font, width - searchW - 6, y + 1, searchW, 16, Component.translatable("lumenatlas.worldmap.search"));
        search.setHint(Component.translatable("lumenatlas.worldmap.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(48);
        search.setValue(previous);
        search.setResponder(text -> updateSearch());
        addRenderableWidget(search);
        if (layersOpen) initLayerPanel();
        updateSearch();
    }

    private void initLayerPanel() {
        int x0 = width - LAYER_PANEL_W - 6;
        int y = TOP_BAR + 8 - layerScroll;
        int w = LAYER_PANEL_W - 12;
        int xb = x0 + 6;
        LumenConfig c = config();
        addLayerButton(xb, y, w, () -> Component.translatable("lumenatlas.layers.discovered"), () -> c.showDiscoveredStructures,
            () -> c.showDiscoveredStructures = !c.showDiscoveredStructures);
        y += 16;
        addLayerButton(xb, y, w, () -> Component.translatable("lumenatlas.layers.predicted"), () -> c.showPredictedStructures,
            () -> c.showPredictedStructures = !c.showPredictedStructures);
        y += 16;
        addLayerButton(xb, y, w, () -> Component.translatable("lumenatlas.layers.waypoints"), () -> c.showWaypointsOnWorldMap,
            () -> c.showWaypointsOnWorldMap = !c.showWaypointsOnWorldMap);
        y += 16;
        addLayerButton(xb, y, w, () -> Component.translatable("lumenatlas.layers.grid"), () -> c.worldMapGrid, () -> c.worldMapGrid = !c.worldMapGrid);
        y += 22;
        for (StructureKind kind : StructureKinds.all()) {
            addLayerButton(xb + 14, y, w - 14, () -> Component.literal(kind.name()), () -> c.isStructureTypeVisible(kind.key()),
                () -> c.setStructureTypeVisible(kind.key(), !c.isStructureTypeVisible(kind.key())));
            y += 15;
        }
    }

    private void addLayerButton(int x, int y, int w, java.util.function.Supplier<Component> label, java.util.function.BooleanSupplier on, Runnable toggle) {
        if (y < TOP_BAR + 4 || y > height - BOTTOM_BAR - 16) return;
        addRenderableWidget(new LumenButton(x, y, w, 14, label, b -> {
            toggle.run();
            ConfigManager.save();
        }).selected(on));
    }

    private void cycleDimension() {
        Set<String> order = new LinkedHashSet<>();
        for (String d : VANILLA_DIMENSIONS) order.add(d);
        order.add(session.dimension());
        List<String> list = new ArrayList<>(order);
        int i = list.indexOf(dimension);
        dimension = list.get((i + 1) % list.size());
        if (dimension.equals(session.dimension())) centerOnPlayer();
        else {
            centerX = 0;
            centerZ = 0;
        }
    }

    private static String dimensionLabel(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
            default -> dim.contains(":") ? dim.substring(dim.indexOf(':') + 1) : dim;
        };
    }

    private void centerOnPlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && dimension.equals(session.dimension())) flyTo(player.getX(), player.getZ());
    }

    private void flyTo(double x, double z) {
        if (config().worldMapAnimations) {
            flyToX = x;
            flyToZ = z;
        } else {
            centerX = x;
            centerZ = z;
        }
    }

    // ---- coordinates ----

    private double screenX(double worldX) {
        return (worldX - centerX) * scale + width / 2.0;
    }

    private double screenY(double worldZ) {
        return (worldZ - centerZ) * scale + height / 2.0;
    }

    private double worldX(double screenX) {
        return (screenX - width / 2.0) / scale + centerX;
    }

    private double worldZ(double screenY) {
        return (screenY - height / 2.0) / scale + centerZ;
    }

    // ---- rendering ----

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, width, height, Theme.MAP_BACKGROUND);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (debugMouseOverride != null) {
            mouseX = debugMouseOverride[0];
            mouseY = debugMouseOverride[1];
        }
        animate();
        drawMap(g);
        if (config().worldMapGrid) drawGrid(g);
        hover = null;
        drawStructures(g, mouseX, mouseY);
        drawWaypoints(g, mouseX, mouseY);
        drawPlayer(g, a);
        drawBars(g, mouseX, mouseY);
        if (layersOpen) Draw.card(g, width - LAYER_PANEL_W - 6, TOP_BAR + 2, width - 6, height - BOTTOM_BAR - 4);
        super.extractRenderState(g, mouseX, mouseY, a);
        if (layersOpen) drawLayerIcons(g);
        drawSearchResults(g, mouseX, mouseY);
        if (menu != null) menu.render(g, font, mouseX, mouseY);
        else if (hover != null && !dragging) drawHoverTooltip(g, mouseX, mouseY);
    }

    private void animate() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        double k = config().worldMapAnimations ? Math.min(1, dt * 14) : 1;
        if (zoomAnimating) {
            scale += (targetScale - scale) * k;
            if (Math.abs(targetScale - scale) < targetScale * 0.002) {
                scale = targetScale;
                zoomAnimating = false;
            }
            centerX = anchorWorldX - (anchorScreenX - width / 2.0) / scale;
            centerZ = anchorWorldZ - (anchorScreenY - height / 2.0) / scale;
        }
        if (flyToX != null) {
            double kk = Math.min(1, dt * 8);
            centerX += (flyToX - centerX) * kk;
            centerZ += (flyToZ - centerZ) * kk;
            if (Math.abs(flyToX - centerX) < 0.5 / scale && Math.abs(flyToZ - centerZ) < 0.5 / scale) {
                centerX = flyToX;
                centerZ = flyToZ;
                flyToX = null;
                flyToZ = null;
            }
        }
    }

    private void drawMap(GuiGraphicsExtractor g) {
        RegionTextures textures = session.textures(dimension);
        MapStore store = textures.store();
        textures.beginFrame();
        int lod = scale >= 0.75 ? 0 : scale >= 0.19 ? 1 : 2;
        int step = RegionTextures.LOD_STEP[lod];
        int texSize = MapRegion.SIZE / step;
        int rx0 = MapRegion.regionCoord((int) Math.floor(worldX(0)));
        int rx1 = MapRegion.regionCoord((int) Math.floor(worldX(width)));
        int rz0 = MapRegion.regionCoord((int) Math.floor(worldZ(0)));
        int rz1 = MapRegion.regionCoord((int) Math.floor(worldZ(height)));
        // Nearest regions first, so the time budget goes to what is in the middle of the screen.
        List<int[]> visible = new ArrayList<>();
        for (int rz = rz0; rz <= rz1; rz++) {
            for (int rx = rx0; rx <= rx1; rx++) {
                if (store.exists(rx, rz)) visible.add(new int[] {rx, rz});
            }
        }
        double cx = centerX / MapRegion.SIZE;
        double cz = centerZ / MapRegion.SIZE;
        visible.sort(Comparator.comparingDouble(r -> (r[0] + 0.5 - cx) * (r[0] + 0.5 - cx) + (r[1] + 0.5 - cz) * (r[1] + 0.5 - cz)));
        float drawScale = (float) (scale * step);
        for (int[] r : visible) {
            Identifier id = textures.get(r[0], r[1], lod, config().worldMapView);
            if (id == null) continue;
            g.pose().pushMatrix();
            g.pose().translate((float) screenX(r[0] * (double) MapRegion.SIZE), (float) screenY(r[1] * (double) MapRegion.SIZE));
            g.pose().scale(drawScale, drawScale);
            // Clamp-to-edge sampling: with fractional zoom a repeating sampler would bleed the opposite edge in.
            var texture = textures.texture(r[0], r[1], lod);
            if (texture != null) g.blit(texture.getTextureView(), EDGE_SAMPLER.get(), 0, 0, texSize, texSize, 0f, 1f, 0f, 1f);
            g.pose().popMatrix();
        }
    }

    private void drawGrid(GuiGraphicsExtractor g) {
        int spacing = scale >= 2 ? 16 : scale >= 0.25 ? MapRegion.SIZE : MapRegion.SIZE * 8;
        double startX = Math.floor(worldX(0) / spacing) * spacing;
        double startZ = Math.floor(worldZ(0) / spacing) * spacing;
        for (double x = startX; x < worldX(width); x += spacing) {
            int sx = (int) Math.round(screenX(x));
            g.fill(sx, TOP_BAR, sx + 1, height - BOTTOM_BAR, Theme.MAP_GRID);
        }
        for (double z = startZ; z < worldZ(height); z += spacing) {
            int sy = (int) Math.round(screenY(z));
            if (sy > TOP_BAR && sy < height - BOTTOM_BAR) g.fill(0, sy, width, sy + 1, Theme.MAP_GRID);
        }
    }

    private int iconSize() {
        // A little smaller when zoomed far out, where many icons share the screen.
        float zoomFactor = scale < 0.1 ? 0.8f : 1f;
        return Math.round(14 * config().iconScale * zoomFactor);
    }

    /** One structure icon to draw this frame. */
    private record IconDraw(StructureKind kind, int sx, int sy, boolean predicted, Marker marker, SeedMapService.Prediction prediction,
                            int x, int y, int z) {}

    private void drawStructures(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        LumenConfig c = config();
        int minX = (int) Math.floor(worldX(-20));
        int maxX = (int) Math.ceil(worldX(width + 20));
        int minZ = (int) Math.floor(worldZ(-20));
        int maxZ = (int) Math.ceil(worldZ(height + 20));
        int size = iconSize();
        // Zoomed far out, the very common kinds would bury everything else.
        boolean hideMinorPredicted = scale < 0.12;
        boolean hideMinorDiscovered = scale < 0.05;
        List<IconDraw> icons = new ArrayList<>();
        SeedMapService seeds = session.seedMap();
        if (c.showPredictedStructures && seeds.isActive(dimension)) {
            seeds.request(1, dimension, minX, minZ, maxX, maxZ);
            seeds.forEachVisible(dimension, minX, minZ, maxX, maxZ, session.markers(), p -> {
                if (!c.isStructureTypeVisible(p.kind().key()) || (hideMinorPredicted && StructureKinds.isMinor(p.kind()))) return;
                icons.add(new IconDraw(p.kind(), (int) Math.round(screenX(p.x() + 0.5)), (int) Math.round(screenY(p.z() + 0.5)), true,
                    null, p, p.x(), p.y(), p.z()));
            });
        }
        if (c.showDiscoveredStructures) {
            for (Marker m : session.markers().all()) {
                if (m.hidden || !m.dimension.equals(dimension) || m.x < minX || m.x > maxX || m.z < minZ || m.z > maxZ) continue;
                StructureKind kind = StructureKinds.byKey(m.type);
                if (!c.isStructureTypeVisible(kind.key()) || (hideMinorDiscovered && StructureKinds.isMinor(kind))) continue;
                icons.add(new IconDraw(kind, (int) Math.round(screenX(m.x + 0.5)), (int) Math.round(screenY(m.z + 0.5)), false,
                    m, null, m.x, m.y, m.z));
            }
        }
        // Discovered before predicted, then by importance; an icon is skipped when a more important one already
        // covers most of its spot.
        icons.sort(Comparator.<IconDraw>comparingInt(i -> i.predicted ? 1 : 0).thenComparingInt(i -> StructureKinds.priority(i.kind)));
        int cell = Math.max(6, size * 3 / 4);
        java.util.Set<Long> taken = new java.util.HashSet<>();
        List<IconDraw> visible = new ArrayList<>();
        for (IconDraw icon : icons) {
            long key = ((long) Math.floorDiv(icon.sx, cell) << 32) | (Math.floorDiv(icon.sy, cell) & 0xFFFFFFFFL);
            if (!taken.add(key)) continue;
            visible.add(icon);
        }
        // Draw the least important first so the important ones end up on top.
        for (int i = visible.size() - 1; i >= 0; i--) {
            IconDraw icon = visible.get(i);
            boolean hot = Math.abs(mouseX - icon.sx) <= size / 2 && Math.abs(mouseY - icon.sy) <= size / 2;
            Icons.structure(g, icon.kind, icon.sx, icon.sy, size, icon.predicted, hot);
            if (hot) hover = new Hover(icon.kind, icon.marker, icon.prediction, null, icon.x, icon.y, icon.z);
        }
    }

    private void drawWaypoints(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!config().showWaypointsOnWorldMap) return;
        int size = iconSize();
        for (Waypoint w : session.waypoints().all()) {
            if (!w.enabled || !w.dimension.equals(dimension)) continue;
            int sx = (int) Math.round(screenX(w.x + 0.5));
            int sy = (int) Math.round(screenY(w.z + 0.5));
            if (sx < -20 || sy < -20 || sx > width + 20 || sy > height + 20) continue;
            boolean hot = Math.abs(mouseX - sx) <= size / 2 && Math.abs(mouseY - sy) <= size / 2;
            Icons.waypoint(g, WaypointIcon.byKeyOrPin(w.icon).ordinal(), w.color, sx, sy, size, 1f);
            if (scale >= 0.5 || hot) Draw.labelCentered(g, font, w.name, sx, sy + size / 2 + 3, Theme.TEXT);
            if (hot) hover = new Hover(null, null, null, w, w.x, w.y, w.z);
        }
    }

    private void drawPlayer(GuiGraphicsExtractor g, float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !dimension.equals(session.dimension())) return;
        double px = player.xo + (player.getX() - player.xo) * partialTick;
        double pz = player.zo + (player.getZ() - player.zo) * partialTick;
        g.pose().pushMatrix();
        g.pose().translate((float) screenX(px), (float) screenY(pz));
        g.pose().rotate((float) Math.toRadians(player.getViewYRot(partialTick) + 180));
        Icons.drawCentered(g, Icons.PLAYER_ARROW, 12, 0xFFFFFFFF);
        g.pose().popMatrix();
    }

    private void drawBars(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, 0, width, TOP_BAR, Theme.PANEL);
        g.fill(0, TOP_BAR - 1, width, TOP_BAR, Theme.BORDER);
        g.fill(0, height - BOTTOM_BAR, width, height, Theme.PANEL);
        g.fill(0, height - BOTTOM_BAR, width, height - BOTTOM_BAR + 1, Theme.BORDER);
        int wx = (int) Math.floor(worldX(mouseX));
        int wz = (int) Math.floor(worldZ(mouseY));
        StringBuilder left = new StringBuilder();
        if (mouseY > TOP_BAR && mouseY < height - BOTTOM_BAR) {
            left.append("X ").append(wx).append("   Z ").append(wz);
            if (dimension.equals("minecraft:the_nether")) left.append("   (Overworld ").append(wx * 8).append(", ").append(wz * 8).append(")");
            else if (dimension.equals("minecraft:overworld")) left.append("   (Nether ").append(Math.floorDiv(wx, 8)).append(", ").append(Math.floorDiv(wz, 8)).append(")");
            MapRegion region = session.map(dimension).peek(MapRegion.regionCoord(wx), MapRegion.regionCoord(wz));
            if (region != null) {
                int i = MapRegion.index(MapRegion.local(wx), MapRegion.local(wz));
                String biome = region.biome(i);
                if (region.height(i) != MapRegion.UNKNOWN) left.append("   Y ").append(region.height(i));
                if (biome != null) {
                    String[] parts = biome.split(":");
                    left.append("   ").append(Component.translatable("biome." + parts[0] + "." + parts[parts.length - 1]).getString());
                }
            }
        }
        g.text(font, left.toString(), 6, height - BOTTOM_BAR + 4, Theme.TEXT_DIM, false);
        String zoom = scale >= 1 ? String.format(Locale.ROOT, "%.0f:1", scale) : String.format(Locale.ROOT, "1:%.0f", 1 / scale);
        String right = zoom + seedStatus();
        g.text(font, right, width - font.width(right) - 6, height - BOTTOM_BAR + 4, Theme.TEXT_DIM, false);
    }

    private String seedStatus() {
        SeedMapService seeds = session.seedMap();
        if (!config().showPredictedStructures) return "";
        return switch (seeds.status(dimension)) {
            case READY -> {
                int pending = seeds.pendingTasks();
                Set<String> far = seeds.tooFarKinds(dimension);
                String s = pending > 0 ? "   Seed map: calculating…" : "   Seed map";
                if (!far.isEmpty()) s += " (zoom in for " + far.size() + " more types)";
                yield s;
            }
            case LOADING -> "   Seed map: loading…";
            case UNSUPPORTED -> "   Seed map: not available here";
            case ERROR -> "   Seed map: error (see log)";
            default -> "";
        };
    }

    private void drawLayerIcons(GuiGraphicsExtractor g) {
        int x0 = width - LAYER_PANEL_W - 6;
        int y = TOP_BAR + 8 - layerScroll + 16 * 4 + 6;
        for (StructureKind kind : StructureKinds.all()) {
            if (y >= TOP_BAR + 4 && y <= height - BOTTOM_BAR - 16) Icons.structure(g, kind, x0 + 12, y + 7, 12, false, false);
            y += 15;
        }
    }

    private void drawHoverTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        LocalPlayer player = Minecraft.getInstance().player;
        double dist = player != null && dimension.equals(session.dimension())
            ? Math.sqrt(Math.pow(hover.x - player.getX(), 2) + Math.pow(hover.z - player.getZ(), 2)) : -1;
        if (hover.waypoint != null) {
            lines.add(Component.literal(hover.waypoint.name).withStyle(ChatFormatting.WHITE));
            lines.add(Component.translatable("lumenatlas.tooltip.waypoint").withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            lines.add(Component.literal(hover.kind.name()).withStyle(ChatFormatting.WHITE));
            if (hover.prediction != null) {
                lines.add(Component.translatable("lumenatlas.tooltip.predicted").withStyle(ChatFormatting.GOLD));
                lines.add(Component.literal(hover.prediction.structureId()).withStyle(ChatFormatting.DARK_GRAY));
            } else {
                lines.add(Component.translatable("lumenatlas.tooltip.discovered").withStyle(ChatFormatting.GREEN));
            }
        }
        lines.add(Component.literal("X " + hover.x + "  Y " + hover.y + "  Z " + hover.z).withStyle(ChatFormatting.GRAY));
        if (dist >= 0) lines.add(Component.literal(MapMath.distance(dist) + " away").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("lumenatlas.tooltip.rightclick").withStyle(ChatFormatting.DARK_GRAY));
        g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    // ---- search ----

    private void updateSearch() {
        results.clear();
        if (search == null) return;
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        double px = player != null ? player.getX() : centerX;
        double pz = player != null ? player.getZ() : centerZ;
        List<SearchResult> found = new ArrayList<>();
        for (Waypoint w : session.waypoints().all()) {
            if (w.dimension.equals(dimension) && w.name.toLowerCase(Locale.ROOT).contains(q)) {
                found.add(new SearchResult(w.name, "Waypoint", w.x, w.z, 0xFF000000 | w.color));
            }
        }
        for (Marker m : session.markers().all()) {
            StructureKind kind = StructureKinds.byKey(m.type);
            if (m.dimension.equals(dimension) && kind.name().toLowerCase(Locale.ROOT).contains(q)) {
                found.add(new SearchResult(kind.name(), "Discovered", m.x, m.z, 0xFF000000 | kind.color()));
            }
        }
        if (session.seedMap().isActive(dimension)) {
            for (SeedMapService.Prediction p : session.seedMap().all(dimension)) {
                if (p.kind().name().toLowerCase(Locale.ROOT).contains(q)
                    && session.markers().nearest(p.kind().key(), dimension, p.x(), p.z(), p.kind().mergeRadius()) == null) {
                    found.add(new SearchResult(p.kind().name(), "Predicted", p.x(), p.z(), 0xFF000000 | p.kind().color()));
                }
            }
        }
        found.sort(Comparator.comparingDouble(r -> Math.pow(r.x - px, 2) + Math.pow(r.z - pz, 2)));
        results.addAll(found.subList(0, Math.min(8, found.size())));
    }

    private int[] searchListBox() {
        int w = Math.max(search.getWidth(), 200);
        int x1 = search.getX() + search.getWidth();
        return new int[] {x1 - w, search.getY() + search.getHeight() + 2, x1, search.getY() + search.getHeight() + 2 + results.size() * 20};
    }

    private void drawSearchResults(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (results.isEmpty() || !search.isFocused()) return;
        int[] box = searchListBox();
        Draw.panel(g, box[0], box[1], box[2], box[3], Theme.PANEL, Theme.BORDER_LIGHT);
        LocalPlayer player = Minecraft.getInstance().player;
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            int y = box[1] + i * 20;
            if (Draw.inside(mouseX, mouseY, box[0], y, box[2], y + 20)) g.fill(box[0] + 1, y, box[2] - 1, y + 20, Theme.CARD_HOVER);
            g.fill(box[0] + 4, y + 6, box[0] + 8, y + 14, r.color);
            g.text(font, r.label, box[0] + 12, y + 2, Theme.TEXT, false);
            String detail = r.detail + "  " + r.x + ", " + r.z;
            if (player != null) detail += "  · " + MapMath.distance(Math.sqrt(Math.pow(r.x - player.getX(), 2) + Math.pow(r.z - player.getZ(), 2)));
            g.text(font, detail, box[0] + 12, y + 11, Theme.TEXT_DIM, false);
        }
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (menu != null) {
            boolean handled = menu.click(mx, my);
            menu = null;
            if (handled) return true;
        }
        if (!results.isEmpty() && search.isFocused()) {
            int[] box = searchListBox();
            if (Draw.inside(mx, my, box[0], box[1], box[2], box[3])) {
                SearchResult r = results.get((int) ((my - box[1]) / 20));
                flyTo(r.x + 0.5, r.z + 0.5);
                if (targetScale < 1) zoomTo(1, width / 2.0, height / 2.0);
                search.setFocused(false);
                return true;
            }
        }
        if (super.mouseClicked(event, doubleClick)) return true;
        if (my < TOP_BAR || my > height - BOTTOM_BAR) return false;
        if (layersOpen && mx > width - LAYER_PANEL_W - 6) return true;
        setFocused(null);
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = true;
            dragMoved = false;
            flyToX = null;
            flyToZ = null;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            openMenu(mx, my);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            centerX -= dx / scale;
            centerZ -= dy / scale;
            zoomAnimating = false;
            dragMoved = true;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (layersOpen && mx > width - LAYER_PANEL_W - 6 && my > TOP_BAR) {
            int max = Math.max(0, 16 * 4 + 22 + StructureKinds.all().size() * 15 - (height - TOP_BAR - BOTTOM_BAR - 24));
            layerScroll = (int) Math.max(0, Math.min(max, layerScroll - scrollY * 15));
            rebuildWidgets();
            return true;
        }
        if (scrollY == 0) return false;
        zoomTo(targetScale * Math.pow(1.25, scrollY), mx, my);
        return true;
    }

    private void zoomTo(double newScale, double mx, double my) {
        newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        anchorWorldX = worldX(mx);
        anchorWorldZ = worldZ(my);
        anchorScreenX = mx;
        anchorScreenY = my;
        targetScale = newScale;
        zoomAnimating = true;
        flyToX = null;
        flyToZ = null;
        savedScale = newScale;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (search != null && search.isFocused()) {
            if (event.key() == InputConstants.KEY_RETURN && !results.isEmpty()) {
                SearchResult r = results.get(0);
                flyTo(r.x + 0.5, r.z + 0.5);
                search.setFocused(false);
                return true;
            }
            return super.keyPressed(event);
        }
        if (Keybinds.OPEN_WORLD_MAP.matches(event)) {
            onClose();
            return true;
        }
        if (event.key() == InputConstants.KEY_SPACE) {
            centerOnPlayer();
            return true;
        }
        double pan = 48 / scale;
        switch (event.key()) {
            case InputConstants.KEY_LEFT, InputConstants.KEY_A -> centerX -= pan;
            case InputConstants.KEY_RIGHT, InputConstants.KEY_D -> centerX += pan;
            case InputConstants.KEY_UP, InputConstants.KEY_W -> centerZ -= pan;
            case InputConstants.KEY_DOWN, InputConstants.KEY_S -> centerZ += pan;
            case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> zoomTo(targetScale * 1.5, width / 2.0, height / 2.0);
            case InputConstants.KEY_MINUS -> zoomTo(targetScale / 1.5, width / 2.0, height / 2.0);
            default -> {
                return super.keyPressed(event);
            }
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        session.saveMarkers();
        session.saveWaypoints();
    }

    // ---- context menu ----

    private void openMenu(double mx, double my) {
        int wx = (int) Math.floor(worldX(mx));
        int wz = (int) Math.floor(worldZ(my));
        menu = new ContextMenu((int) mx, (int) my);
        Hover h = hover;
        if (h != null && h.waypoint != null) {
            Waypoint w = h.waypoint;
            menu.add(Component.translatable("lumenatlas.menu.edit_waypoint"), () -> Compat.setScreen(minecraft, new WaypointEditScreen(this, session, w, false)));
            menu.add(Component.translatable("lumenatlas.menu.disable_waypoint"), () -> {
                w.enabled = false;
                session.waypoints().markChanged();
            });
            menu.add(Component.translatable("lumenatlas.menu.delete_waypoint"), () -> session.waypoints().remove(w.id));
        } else if (h != null && h.kind != null) {
            menu.add(Component.translatable("lumenatlas.menu.waypoint_from_structure"), () -> {
                Waypoint w = new Waypoint(h.kind.name(), dimension, h.x, h.y, h.z, h.kind.color(), WaypointIcon.FLAG.key());
                Compat.setScreen(minecraft, new WaypointEditScreen(this, session, w, true));
            });
            if (h.marker != null) {
                menu.add(Component.translatable("lumenatlas.menu.hide_marker"), () -> session.markers().setHidden(h.marker, true));
            }
        }
        menu.add(Component.translatable("lumenatlas.menu.add_waypoint"), () -> {
            MapRegion region = session.map(dimension).peek(MapRegion.regionCoord(wx), MapRegion.regionCoord(wz));
            int y = 64;
            if (region != null && region.height(MapRegion.index(MapRegion.local(wx), MapRegion.local(wz))) != MapRegion.UNKNOWN) {
                y = region.height(MapRegion.index(MapRegion.local(wx), MapRegion.local(wz))) + 1;
            } else if (Minecraft.getInstance().player != null) {
                y = Minecraft.getInstance().player.getBlockY();
            }
            Waypoint w = new Waypoint("Waypoint", dimension, wx, y, wz, 0xA78BFA, WaypointIcon.PIN.key());
            Compat.setScreen(minecraft, new WaypointEditScreen(this, session, w, true));
        });
        menu.add(Component.translatable("lumenatlas.menu.copy_coords"), () -> minecraft.keyboardHandler.setClipboard(wx + " " + wz));
    }

    /** A small popup list of actions. */
    private static final class ContextMenu {
        private final int x;
        private final int y;
        private final List<Component> labels = new ArrayList<>();
        private final List<Runnable> actions = new ArrayList<>();

        ContextMenu(int x, int y) {
            this.x = x;
            this.y = y;
        }

        void add(Component label, Runnable action) {
            labels.add(label);
            actions.add(action);
        }

        private int width(net.minecraft.client.gui.Font font) {
            int w = 60;
            for (Component c : labels) w = Math.max(w, font.width(c) + 12);
            return w;
        }

        void render(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, int mouseX, int mouseY) {
            int w = width(font);
            Draw.panel(g, x, y, x + w, y + labels.size() * 14 + 4, Theme.PANEL, Theme.ACCENT_DARK);
            for (int i = 0; i < labels.size(); i++) {
                int ly = y + 2 + i * 14;
                if (Draw.inside(mouseX, mouseY, x, ly, x + w, ly + 14)) g.fill(x + 1, ly, x + w - 1, ly + 14, Theme.CARD_HOVER);
                g.text(font, labels.get(i), x + 6, ly + 3, Theme.TEXT, false);
            }
        }

        boolean click(double mx, double my) {
            int w = width(Minecraft.getInstance().font);
            if (mx < x || mx > x + w) return false;
            int i = (int) ((my - y - 2) / 14);
            if (my < y + 2 || i < 0 || i >= actions.size()) return false;
            actions.get(i).run();
            return true;
        }
    }
}
