package com.lumenatlas.gui;

import com.lumenatlas.LumenAtlas;
import com.lumenatlas.compat.Compat;
import com.lumenatlas.core.io.AtomicFiles;
import com.lumenatlas.core.util.MapMath;
import com.lumenatlas.core.util.Names;
import com.lumenatlas.core.waypoint.Waypoint;
import com.lumenatlas.core.waypoint.WaypointIcon;
import com.lumenatlas.core.waypoint.WaypointStore;
import com.lumenatlas.world.WorldSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * All waypoints of the world: toggle, edit, show on the map, delete, plus export to and import from JSON files
 * (files can also be dropped onto this screen).
 */
public class WaypointListScreen extends LumenScreen {
    private static final int ROW = 24;

    private final WorldSession session;
    private boolean allDimensions;
    private double scroll;
    private Component message;
    private int messageColor = Theme.TEXT_DIM;
    private String confirmDelete;

    public WaypointListScreen(Screen parent, WorldSession session) {
        super(parent, Component.translatable("lumenatlas.waypoints.title"));
        this.session = session;
    }

    @Override
    protected int panelWidth() {
        return 440;
    }

    @Override
    protected int panelHeight() {
        return 300;
    }

    @Override
    protected void initContent() {
        int right = panelRight - 10;
        int top = panelTop + 4;
        addRenderableWidget(new LumenButton(right - 90, top, 90, 16,
            () -> Component.translatable(allDimensions ? "lumenatlas.waypoints.all_dims" : "lumenatlas.waypoints.this_dim"),
            b -> allDimensions = !allDimensions));
        int by = panelBottom - 24;
        int x = panelLeft + 10;
        addRenderableWidget(new LumenButton(x, by, 64, 18, Component.translatable("lumenatlas.waypoints.add"),
            b -> Compat.setScreen(minecraft, WaypointEditScreen.atPlayer(this, session))));
        x += 68;
        addRenderableWidget(new LumenButton(x, by, 64, 18, Component.translatable("lumenatlas.waypoints.export"), b -> exportAll())
            .tooltip(Component.translatable("lumenatlas.waypoints.export.tooltip")));
        x += 68;
        addRenderableWidget(new LumenButton(x, by, 64, 18, Component.translatable("lumenatlas.waypoints.import"), b -> importFromFolder())
            .tooltip(Component.translatable("lumenatlas.waypoints.import.tooltip")));
        x += 68;
        addRenderableWidget(new LumenButton(x, by, 70, 18, Component.translatable("lumenatlas.waypoints.folder"), b -> {
            Path dir = transferDir();
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {
                // opening will report it
            }
            Compat.openPath(dir);
        }));
        addRenderableWidget(new LumenButton(panelRight - 74, by, 64, 18, Component.translatable("gui.done"), b -> onClose()));
    }

    private List<Waypoint> visible() {
        List<Waypoint> list = new ArrayList<>();
        for (Waypoint w : session.waypoints().all()) {
            if (allDimensions || w.dimension.equals(session.dimension())) list.add(w);
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) list.sort(Comparator.comparingDouble(w -> w.dimension.equals(session.dimension()) ? w.distanceTo(p.getX(), p.getY(), p.getZ()) : 1e12));
        return list;
    }

    private int listTop() {
        return panelTop + 30;
    }

    private int listBottom() {
        return panelBottom - 30;
    }

    @Override
    protected void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        List<Waypoint> list = visible();
        int top = listTop();
        int bottom = listBottom();
        int left = panelLeft + 8;
        int right = panelRight - 8;
        double maxScroll = Math.max(0, list.size() * ROW - (bottom - top));
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        g.enableScissor(left, top, right, bottom);
        LocalPlayer p = Minecraft.getInstance().player;
        for (int i = 0; i < list.size(); i++) {
            Waypoint w = list.get(i);
            int y = top + i * ROW - (int) scroll;
            if (y + ROW < top || y > bottom) continue;
            boolean hovered = Draw.inside(mouseX, mouseY, left, y, right, y + ROW - 2) && mouseY >= top && mouseY < bottom;
            g.fill(left, y, right, y + ROW - 2, hovered ? Theme.CARD_HOVER : Theme.CARD);
            Icons.waypoint(g, WaypointIcon.byKeyOrPin(w.icon).ordinal(), w.color, left + 12, y + 11, 16, w.enabled ? 1f : 0.4f);
            int textColor = w.enabled ? Theme.TEXT : Theme.TEXT_FAINT;
            g.text(font, w.name, left + 26, y + 3, textColor, false);
            String detail = w.x + ", " + w.y + ", " + w.z;
            if (!w.dimension.equals(session.dimension())) detail += "  · " + WaypointEditScreen.dimensionName(w.dimension);
            else if (p != null) detail += "  · " + MapMath.distance(w.distanceTo(p.getX(), p.getY(), p.getZ()));
            if (w.isDeath()) detail += "  · death";
            g.text(font, detail, left + 26, y + 13, Theme.TEXT_DIM, false);
            // Row actions (drawn as text buttons, handled in mouseClicked).
            String[] actions = rowActions(w);
            int ax = right - 4;
            for (int k = actions.length - 1; k >= 0; k--) {
                int aw = font.width(actions[k]) + 8;
                ax -= aw;
                boolean hot = Draw.inside(mouseX, mouseY, ax, y + 4, ax + aw, y + 18);
                int color = k == actions.length - 1 && confirmDelete != null && confirmDelete.equals(w.id) ? Theme.BAD : hot ? Theme.ACCENT : Theme.TEXT_DIM;
                g.outline(ax, y + 4, aw, 14, hot ? Theme.ACCENT : Theme.BORDER);
                g.text(font, actions[k], ax + 4, y + 7, color, false);
                ax -= 3;
            }
        }
        g.disableScissor();
        if (list.isEmpty()) g.centeredText(font, Component.translatable("lumenatlas.waypoints.empty"), (panelLeft + panelRight) / 2, top + 30, Theme.TEXT_DIM);
        if (message != null) g.text(font, message, panelLeft + 10, panelBottom - 36, messageColor, false);
    }

    private String[] rowActions(Waypoint w) {
        String del = confirmDelete != null && confirmDelete.equals(w.id) ? "Sure?" : "Delete";
        return new String[] {w.enabled ? "Hide" : "Show", "Map", "Edit", del};
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double mx = event.x();
        double my = event.y();
        int top = listTop();
        int bottom = listBottom();
        if (my < top || my >= bottom) return false;
        List<Waypoint> list = visible();
        int index = (int) ((my - top + scroll) / ROW);
        if (index < 0 || index >= list.size()) return false;
        Waypoint w = list.get(index);
        int y = top + index * ROW - (int) scroll;
        String[] actions = rowActions(w);
        int ax = panelRight - 8 - 4;
        for (int k = actions.length - 1; k >= 0; k--) {
            int aw = font.width(actions[k]) + 8;
            ax -= aw;
            if (Draw.inside(mx, my, ax, y + 4, ax + aw, y + 18)) {
                runAction(w, k);
                return true;
            }
            ax -= 3;
        }
        if (doubleClick) Compat.setScreen(minecraft, new WaypointEditScreen(this, session, w, false));
        return true;
    }

    private void runAction(Waypoint w, int action) {
        switch (action) {
            case 0 -> {
                w.enabled = !w.enabled;
                session.waypoints().markChanged();
                session.saveWaypoints();
            }
            case 1 -> {
                WorldMapScreen map = new WorldMapScreen(session).centeredOn(w.x, w.z);
                Compat.setScreen(minecraft, map);
            }
            case 2 -> Compat.setScreen(minecraft, new WaypointEditScreen(this, session, w, false));
            default -> {
                if (w.id.equals(confirmDelete)) {
                    session.waypoints().remove(w.id);
                    session.saveWaypoints();
                    confirmDelete = null;
                } else {
                    confirmDelete = w.id;
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        scroll -= scrollY * ROW;
        return true;
    }

    // ---- import / export ----

    private static Path transferDir() {
        return LumenAtlas.dataDir().resolve("waypoint-files");
    }

    private void exportAll() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = transferDir().resolve("waypoints-" + Names.folderName(session.label) + "-" + stamp + ".json");
        try {
            AtomicFiles.write(file, WaypointStore.export(session.waypoints().all()).getBytes(StandardCharsets.UTF_8));
            setMessage(Component.translatable("lumenatlas.waypoints.exported", session.waypoints().all().size(), file.getFileName().toString()), Theme.GOOD);
        } catch (IOException e) {
            LumenAtlas.LOGGER.error("Waypoint export failed", e);
            setMessage(Component.translatable("lumenatlas.waypoints.error", e.getMessage()), Theme.BAD);
        }
    }

    /** Imports every .json file in the waypoint folder that is not one of our own exports of this world. */
    private void importFromFolder() {
        Path dir = transferDir();
        List<Path> files = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(files::add);
            }
        } catch (IOException e) {
            setMessage(Component.translatable("lumenatlas.waypoints.error", e.getMessage()), Theme.BAD);
            return;
        }
        if (files.isEmpty()) {
            setMessage(Component.translatable("lumenatlas.waypoints.import.none"), Theme.WARN);
            return;
        }
        importFiles(files);
    }

    private void importFiles(List<Path> files) {
        int added = 0;
        int skipped = 0;
        int failed = 0;
        for (Path file : files) {
            try {
                WaypointStore.ImportResult result = session.waypoints().importJson(Files.readString(file, StandardCharsets.UTF_8));
                added += result.added();
                skipped += result.skipped();
            } catch (IOException | RuntimeException e) {
                failed++;
                LumenAtlas.LOGGER.warn("Could not import waypoints from {}: {}", file, e.getMessage());
            }
        }
        session.saveWaypoints();
        setMessage(Component.translatable("lumenatlas.waypoints.imported", added, skipped, failed), failed > 0 ? Theme.WARN : Theme.GOOD);
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        List<Path> json = new ArrayList<>();
        for (Path p : files) if (p.getFileName().toString().endsWith(".json")) json.add(p);
        if (json.isEmpty()) setMessage(Component.translatable("lumenatlas.waypoints.import.none").withStyle(ChatFormatting.YELLOW), Theme.WARN);
        else importFiles(json);
    }

    private void setMessage(Component text, int color) {
        message = text;
        messageColor = color;
    }
}
