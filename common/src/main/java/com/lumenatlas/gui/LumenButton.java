package com.lumenatlas.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** A flat, dark button in the Lumen Atlas style. The label can be dynamic (for toggles and cycling options). */
public class LumenButton extends AbstractButton {
    private final Consumer<LumenButton> action;
    private Supplier<Component> label;
    private BooleanSupplier selected = () -> false;
    private boolean lastShift;

    public LumenButton(int x, int y, int width, int height, Component label, Consumer<LumenButton> action) {
        this(x, y, width, height, () -> label, action);
    }

    public LumenButton(int x, int y, int width, int height, Supplier<Component> label, Consumer<LumenButton> action) {
        super(x, y, width, height, label.get());
        this.label = label;
        this.action = action;
    }

    /** Draws the button highlighted while {@code selected} is true (active tab, enabled toggle...). */
    public LumenButton selected(BooleanSupplier selected) {
        this.selected = selected;
        return this;
    }

    public LumenButton tooltip(Component text) {
        setTooltip(Tooltip.create(text));
        return this;
    }

    public LumenButton label(Supplier<Component> label) {
        this.label = label;
        return this;
    }

    /** True while the press that triggered the action had Shift held (used to cycle options backwards). */
    public boolean shiftDown() {
        return lastShift;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        lastShift = input.hasShiftDown();
        action.accept(this);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        setMessage(label.get());
        boolean hovered = isHoveredOrFocused() && active;
        boolean on = selected.getAsBoolean();
        int x0 = getX();
        int y0 = getY();
        int x1 = x0 + getWidth();
        int y1 = y0 + getHeight();
        int fill = !active ? 0xFF15131F : on ? 0xFF2D2550 : hovered ? Theme.CARD_HOVER : Theme.CARD;
        int border = on ? Theme.ACCENT : hovered ? Theme.BORDER_LIGHT : Theme.BORDER;
        g.fill(x0, y0, x1, y1, fill);
        g.outline(x0, y0, getWidth(), getHeight(), border);
        if (on) g.fill(x0 + 1, y1 - 2, x1 - 1, y1 - 1, Theme.ACCENT);
        var font = Minecraft.getInstance().font;
        Component msg = getMessage();
        int color = !active ? Theme.TEXT_FAINT : on || hovered ? Theme.TEXT : 0xFFD6D2E4;
        String text = msg.getString();
        int maxWidth = getWidth() - 6;
        if (font.width(text) > maxWidth) text = font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
        g.text(font, text, x0 + (getWidth() - font.width(text)) / 2, y0 + (getHeight() - 8) / 2, color, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
