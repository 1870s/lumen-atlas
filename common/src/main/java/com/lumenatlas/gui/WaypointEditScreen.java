package com.lumenatlas.gui;

import com.lumenatlas.compat.Compat;
import com.lumenatlas.core.util.Colors;
import com.lumenatlas.core.waypoint.Waypoint;
import com.lumenatlas.core.waypoint.WaypointIcon;
import com.lumenatlas.world.WorldSession;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/** Create or edit one waypoint: name, position, color, icon and visibility. */
public class WaypointEditScreen extends LumenScreen {
    public static final int[] PALETTE = {
        0xA78BFA, 0xF472B6, 0xF87171, 0xFB923C, 0xFACC15, 0xA3E635, 0x4ADE80, 0x2DD4BF,
        0x38BDF8, 0x60A5FA, 0x818CF8, 0xE5E7EB, 0x9CA3AF, 0x78716C
    };

    private final WorldSession session;
    private final Waypoint draft;
    private final boolean isNew;
    private EditBox name;
    private EditBox x;
    private EditBox y;
    private EditBox z;
    private EditBox hex;
    private Component error;

    public WaypointEditScreen(Screen parent, WorldSession session, Waypoint waypoint, boolean isNew) {
        super(parent, Component.translatable(isNew ? "lumenatlas.waypoint.new" : "lumenatlas.waypoint.edit"));
        this.session = session;
        this.draft = waypoint.copy();
        this.isNew = isNew;
    }

    /** A new waypoint at the player's feet, with a fresh color. */
    public static WaypointEditScreen atPlayer(Screen parent, WorldSession session) {
        LocalPlayer p = Minecraft.getInstance().player;
        int color = PALETTE[session.waypoints().all().size() % PALETTE.length];
        Waypoint w = new Waypoint("Waypoint " + (session.waypoints().all().size() + 1), session.dimension(),
            p.getBlockX(), p.getBlockY(), p.getBlockZ(), color, WaypointIcon.PIN.key());
        return new WaypointEditScreen(parent, session, w, true);
    }

    @Override
    protected int panelHeight() {
        return 250;
    }

    @Override
    protected void initContent() {
        int left = panelLeft + 12;
        int inner = panelRight - panelLeft - 24;
        int y0 = panelTop + 34;
        String keepName = name != null ? name.getValue() : draft.name;
        name = new EditBox(font, left, y0 + 10, inner, 18, Component.translatable("lumenatlas.waypoint.name"));
        name.setMaxLength(Waypoint.MAX_NAME_LENGTH);
        name.setValue(keepName);
        addRenderableWidget(name);
        setInitialFocus(name);

        int fieldW = (inner - 16) / 3;
        int y1 = y0 + 44;
        x = number(left, y1, fieldW, draft.x, "X");
        y = number(left + fieldW + 8, y1, fieldW, draft.y, "Y");
        z = number(left + 2 * (fieldW + 8), y1, fieldW, draft.z, "Z");
        addRenderableWidget(new LumenButton(left, y1 + 22, 110, 16, Component.translatable("lumenatlas.waypoint.here"), b -> {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return;
            x.setValue(Integer.toString(p.getBlockX()));
            y.setValue(Integer.toString(p.getBlockY()));
            z.setValue(Integer.toString(p.getBlockZ()));
            draft.dimension = session.dimension();
        }));

        int y2 = y1 + 52;
        int sw = 14;
        for (int i = 0; i < PALETTE.length; i++) {
            int color = PALETTE[i];
            int bx = left + i * (sw + 3);
            if (bx + sw > panelRight - 80) break;
            addRenderableWidget(new LumenButton(bx, y2 + 10, sw, sw, Component.empty(), b -> {
                draft.color = color;
                hex.setValue(Colors.toHex(color));
            }).selected(() -> draft.color == color));
        }
        hex = new EditBox(font, panelRight - 72, y2 + 9, 60, 16, Component.translatable("lumenatlas.waypoint.color"));
        hex.setMaxLength(7);
        hex.setValue(Colors.toHex(draft.color));
        hex.setResponder(text -> {
            int parsed = Colors.parseHex(text, -1);
            if (parsed >= 0) draft.color = parsed;
        });
        addRenderableWidget(hex);

        int y3 = y2 + 36;
        int iconW = 18;
        for (WaypointIcon icon : WaypointIcon.values()) {
            int bx = left + icon.ordinal() * (iconW + 3);
            addRenderableWidget(new LumenButton(bx, y3 + 10, iconW, iconW, Component.empty(), b -> draft.icon = icon.key())
                .selected(() -> draft.icon.equals(icon.key())).tooltip(Component.literal(icon.label())));
        }

        int y4 = y3 + 36;
        addRenderableWidget(new LumenButton(left, y4, 120, 16,
            () -> Component.translatable("lumenatlas.waypoint.enabled", onOff(draft.enabled)), b -> draft.enabled = !draft.enabled));
        addRenderableWidget(new LumenButton(left + 124, y4, 140, 16,
            () -> Component.translatable("lumenatlas.waypoint.in_world", onOff(draft.showInWorld)), b -> draft.showInWorld = !draft.showInWorld));

        int by = panelBottom - 26;
        addRenderableWidget(new LumenButton(panelRight - 12 - 80, by, 80, 18, Component.translatable("lumenatlas.button.save"), b -> save()));
        addRenderableWidget(new LumenButton(panelRight - 12 - 164, by, 80, 18, Component.translatable("lumenatlas.button.cancel"), b -> onClose()));
        if (!isNew) {
            addRenderableWidget(new LumenButton(left, by, 80, 18, Component.translatable("lumenatlas.button.delete").withStyle(ChatFormatting.RED), b -> {
                session.waypoints().remove(draft.id);
                session.saveWaypoints();
                onClose();
            }));
        }
    }

    private static Component onOff(boolean on) {
        return Component.translatable(on ? "options.on" : "options.off");
    }

    private EditBox number(int left, int top, int w, int value, String label) {
        EditBox box = new EditBox(font, left + 12, top, w - 12, 18, Component.literal(label));
        box.setMaxLength(9);
        box.setValue(Integer.toString(value));
        addRenderableWidget(box);
        return box;
    }

    private void save() {
        String n = name.getValue().trim();
        if (n.isEmpty()) {
            error = Component.translatable("lumenatlas.waypoint.error.name");
            return;
        }
        try {
            draft.x = Integer.parseInt(x.getValue());
            draft.y = Integer.parseInt(y.getValue());
            draft.z = Integer.parseInt(z.getValue());
        } catch (NumberFormatException e) {
            error = Component.translatable("lumenatlas.waypoint.error.position");
            return;
        }
        draft.name = n;
        if (isNew) session.waypoints().add(draft);
        else session.waypoints().update(draft);
        session.saveWaypoints();
        onClose();
    }

    @Override
    protected void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int left = panelLeft + 12;
        int y0 = panelTop + 34;
        g.text(font, Component.translatable("lumenatlas.waypoint.name"), left, y0, Theme.TEXT_DIM, false);
        int y1 = y0 + 44;
        g.text(font, Component.translatable("lumenatlas.waypoint.position").append(" · " + dimensionName(draft.dimension)), left, y1 - 10, Theme.TEXT_DIM, false);
        int fieldW = (panelRight - panelLeft - 24 - 16) / 3;
        for (int i = 0; i < 3; i++) g.text(font, new String[] {"X", "Y", "Z"}[i], left + i * (fieldW + 8) + 2, y1 + 5, Theme.ACCENT, false);
        int y2 = y1 + 52;
        g.text(font, Component.translatable("lumenatlas.waypoint.color"), left, y2, Theme.TEXT_DIM, false);
        int y3 = y2 + 36;
        g.text(font, Component.translatable("lumenatlas.waypoint.icon"), left, y3, Theme.TEXT_DIM, false);
        if (error != null) g.text(font, error, left, panelBottom - 40, Theme.BAD, false);
    }

    @Override
    protected void extractOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int left = panelLeft + 12;
        int y2 = panelTop + 34 + 44 + 52;
        int sw = 14;
        for (int i = 0; i < PALETTE.length; i++) {
            int bx = left + i * (sw + 3);
            if (bx + sw > panelRight - 80) break;
            g.fill(bx + 3, y2 + 13, bx + sw - 3, y2 + 10 + sw - 3, 0xFF000000 | PALETTE[i]);
        }
        int y3 = y2 + 36;
        for (WaypointIcon icon : WaypointIcon.values()) {
            int bx = left + icon.ordinal() * 21;
            Icons.draw(g, Icons.WAYPOINT_BASE + icon.ordinal(), bx + 2, y3 + 12, 14, 0xFF000000 | draft.color);
        }
        // Live preview of the marker.
        Icons.waypoint(g, WaypointIcon.byKeyOrPin(draft.icon).ordinal(), draft.color, panelRight - 24, panelTop + 12, 16, 1f);
    }

    static String dimensionName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
            default -> dim;
        };
    }

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }
}
