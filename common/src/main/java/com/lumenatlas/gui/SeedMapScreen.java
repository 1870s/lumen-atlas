package com.lumenatlas.gui;

import com.lumenatlas.config.ConfigManager;
import com.lumenatlas.seed.SeedMapService;
import com.lumenatlas.world.WorldSession;
import java.util.List;
import java.util.OptionalLong;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Seed map settings for the current world: shows the singleplayer seed, or lets the player enter the seed of a
 * server, and pick the world preset that server uses.
 */
public class SeedMapScreen extends LumenScreen {
    private final WorldSession session;
    private EditBox seedBox;
    private Component feedback;
    private int feedbackColor = Theme.TEXT_DIM;

    public SeedMapScreen(Screen parent, WorldSession session) {
        super(parent, Component.translatable("lumenatlas.seed.title"));
        this.session = session;
    }

    @Override
    protected int panelWidth() {
        return 400;
    }

    @Override
    protected int panelHeight() {
        return 250;
    }

    private SeedMapService seeds() {
        return session.seedMap();
    }

    @Override
    protected void initContent() {
        int left = panelLeft + 12;
        int inner = panelRight - panelLeft - 24;
        int y = panelTop + 92;
        addRenderableWidget(new LumenButton(left, panelTop + 32, 150, 18,
            () -> Component.translatable("lumenatlas.seed.enabled", Component.translatable(seeds().isEnabled() ? "options.on" : "options.off")),
            b -> seeds().setEnabled(!seeds().isEnabled())).selected(() -> seeds().isEnabled()));
        addRenderableWidget(new LumenButton(left + 156, panelTop + 32, 150, 18,
            () -> Component.translatable("lumenatlas.seed.show_on_maps", Component.translatable(ConfigManager.get().showPredictedStructures ? "options.on" : "options.off")),
            b -> {
                ConfigManager.get().showPredictedStructures = !ConfigManager.get().showPredictedStructures;
                ConfigManager.save();
            }).selected(() -> ConfigManager.get().showPredictedStructures));
        if (!seeds().isSingleplayerSeed()) {
            seedBox = new EditBox(font, left, y, inner - 84, 18, Component.translatable("lumenatlas.seed.field"));
            seedBox.setMaxLength(64);
            seedBox.setHint(Component.translatable("lumenatlas.seed.hint").withStyle(ChatFormatting.DARK_GRAY));
            String current = seeds().manualSeedText();
            seedBox.setValue(current == null ? "" : current);
            addRenderableWidget(seedBox);
            addRenderableWidget(new LumenButton(left + inner - 80, y, 80, 18, Component.translatable("lumenatlas.seed.apply"), b -> apply()));
            addRenderableWidget(new LumenButton(left, y + 24, 180, 18,
                () -> Component.translatable("lumenatlas.seed.preset", seeds().preset().label),
                b -> {
                    seeds().setPreset(seeds().preset().next());
                    feedback = null;
                }).tooltip(Component.translatable("lumenatlas.seed.preset.tooltip")));
            addRenderableWidget(new LumenButton(left + 184, y + 24, 90, 18, Component.translatable("lumenatlas.seed.clear"), b -> {
                seeds().setManualSeed("");
                seedBox.setValue("");
                feedback = Component.translatable("lumenatlas.seed.cleared");
                feedbackColor = Theme.TEXT_DIM;
            }));
        }
        addRenderableWidget(new LumenButton(panelRight - 90, panelBottom - 26, 80, 18, Component.translatable("gui.done"), b -> onClose()));
    }

    private void apply() {
        if (seedBox.getValue().isBlank()) {
            seeds().setManualSeed("");
            feedback = Component.translatable("lumenatlas.seed.cleared");
            feedbackColor = Theme.TEXT_DIM;
            return;
        }
        if (seeds().setManualSeed(seedBox.getValue())) {
            feedback = Component.translatable("lumenatlas.seed.applied", Long.toString(seeds().seed().orElse(0)));
            feedbackColor = Theme.GOOD;
        } else {
            feedback = Component.translatable("lumenatlas.seed.invalid");
            feedbackColor = Theme.BAD;
        }
    }

    @Override
    protected void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int left = panelLeft + 12;
        int inner = panelRight - panelLeft - 24;
        int y = panelTop + 58;
        OptionalLong seed = seeds().seed();
        if (seeds().isSingleplayerSeed()) {
            g.text(font, Component.translatable("lumenatlas.seed.singleplayer"), left, y, Theme.TEXT, false);
            g.text(font, Component.literal(seed.isPresent() ? Long.toString(seed.getAsLong()) : "?"), left, y + 12, Theme.ACCENT, false);
        } else {
            g.text(font, Component.translatable("lumenatlas.seed.multiplayer"), left, y, Theme.TEXT, false);
            g.text(font, Component.translatable("lumenatlas.seed.field"), left, y + 22, Theme.TEXT_DIM, false);
        }
        int infoY = seeds().isSingleplayerSeed() ? panelTop + 92 : panelTop + 146;
        String dim = session.dimension();
        Component status = switch (seeds().status(dim)) {
            case DISABLED -> Component.translatable("lumenatlas.seed.status.disabled");
            case NO_SEED -> Component.translatable("lumenatlas.seed.status.no_seed");
            case LOADING -> Component.translatable("lumenatlas.seed.status.loading");
            case READY -> Component.translatable("lumenatlas.seed.status.ready", seeds().predictionCount(dim), seeds().pendingTasks());
            case UNSUPPORTED -> Component.translatable("lumenatlas.seed.status.unsupported");
            case ERROR -> Component.translatable("lumenatlas.seed.status.error", String.valueOf(seeds().error(dim)));
        };
        g.text(font, status, left, infoY, Theme.TEXT, false);
        List<FormattedCharSequence> lines = font.split(Component.translatable("lumenatlas.seed.explain"), inner);
        int ly = infoY + 14;
        for (FormattedCharSequence line : lines) {
            if (ly > panelBottom - 44) break;
            g.text(font, line, left, ly, Theme.TEXT_DIM, false);
            ly += 10;
        }
        if (feedback != null) g.text(font, feedback, left, panelBottom - 40, feedbackColor, false);
    }
}
