package releon.ru.api.drag.impl;

import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleManager;
import releon.ru.manager.Manager;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class HotKeys extends HudPanel {
    private static final float ROW_STEP = 11.0F;
    private static final float PANEL_ANIM = 0.24F;
    private static final float ROW_ANIM = 0.22F;
    private static final Comparator<Module> MODULE_NAME_COMPARATOR = Comparator.comparing(Module::getName);
    private static final Comparator<RowState> ROW_STATE_COMPARATOR = Comparator.comparingDouble(RowState::offset);

    private final List<RowEntry> rowEntries = new ArrayList<>();
    private final List<Module> activeModules = new ArrayList<>();
    private final List<RowState> rowStates = new ArrayList<>();
    private final SmoothAnimation panelAnimation = new SmoothAnimation();
    private final SmoothAnimation iconAlphaAnimation = new SmoothAnimation();
    private boolean iconAlphaForward = true;
    public HotKeys() {
        super("hotkeys", "HotKeys", 300.0F, 40.0F, 80.0F, 23.0F);
    }

    @Override
    public void render() {
        HotKeysState state = logics();
        if (state == null) {
            return;
        }
        renderPanel(state);
    }

    private HotKeysState logics() {
        activeModules.clear();
        ModuleManager manager = Manager.getModules();
        if (manager != null) {
            for (Module module : manager.getModules()) {
                if (module.isEnabled() && !module.isHidden() && module.getBind() != null && module.getBind().isBound()) {
                    activeModules.add(module);
                }
            }
        }
        if (activeModules.size() > 1) {
            activeModules.sort(MODULE_NAME_COMPARATOR);
        }

        boolean preview = activeModules.isEmpty() && editPreview();
        boolean targetVisible = !activeModules.isEmpty() || preview;

        drag.hitExpansion(0.0F, 17.0F, 0.0F, 0.0F);
        panelAnimation.update();
        panelAnimation.run(targetVisible ? 1.0 : 0.0, PANEL_ANIM, targetVisible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);

        iconAlphaAnimation.update();
        if (!iconAlphaAnimation.isAlive()) {
            iconAlphaAnimation.run(iconAlphaForward ? 1.0 : 0.0, 1.55, Easings.EXPO_IN_OUT);
            iconAlphaForward = !iconAlphaForward;
        }

        for (RowEntry entry : rowEntries) {
            entry.active = false;
            entry.alpha.update();
            entry.y.update();
        }

        int targetRows = 0;
        if (preview) {
            RowEntry entry = row("__preview", "Example", "[R]", 5.0F, 0.0F);
            entry.active = true;
            entry.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
            entry.y.run(0.0F, ROW_ANIM, Easings.EXPO_OUT, true);
            targetRows = 1;
        } else {
            for (Module module : activeModules) {
                String bind = "[" + shortBind(module.getBind()) + "]";
                float targetY = targetRows * ROW_STEP;
                RowEntry entry = row(module.getName(), module.getName(), bind, 6.0F, targetY);
                entry.active = true;
                entry.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
                entry.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
                targetRows++;
            }
        }

        for (RowEntry entry : rowEntries) {
            if (!entry.active) {
                entry.alpha.run(0.0, ROW_ANIM, Easings.EXPO_IN, true);
            }
        }
        rowEntries.removeIf(entry -> !entry.active && entry.alpha.get() <= 0.01F && !entry.alpha.isAlive());

        float panelAlpha = panelAnimation.get();
        boolean visible = targetVisible || panelAlpha > 0.01F || !rowEntries.isEmpty();
        contentVisible(visible);
        if (!visible) {
            return null;
        }

        float width = 80.0F;
        for (RowEntry entry : rowEntries) {
            if (entry.alpha.get() > 0.01F || entry.active) {
                width = Math.max(width, Render2D.textWidth(TEXT_FONT, entry.name, 6.0F) + Render2D.textWidth(TEXT_FONT, entry.bind, 5.0F) + 35.0F);
            }
        }
        size(width, Math.max(1, targetRows) * 10.0F + 10.0F);

        rowStates.clear();
        for (RowEntry entry : rowEntries) {
            float alpha = entry.alpha.get();
            if (alpha > 0.01F || entry.active) {
                rowStates.add(new RowState(entry.name, entry.bind, entry.bindSize, entry.y.get(), alpha));
            }
        }
        if (rowStates.size() > 1) {
            rowStates.sort(ROW_STATE_COMPARATOR);
        }

        return new HotKeysState(rowStates, iconAlphaAnimation.get(), panelAlpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderPanel(HotKeysState state) {
        float headerY = state.y - 17.0F;
        float headerHeight = 17.0F;
        float rowY = state.y + 5.0F;
        String headerText = "Hot Keys";
        float headerTextSize = 6.0F;
        float headerTextX = state.x + 10.0F;
        float headerTextY = headerY + (headerHeight - headerTextSize) * 0.5F - 0.5F;
        float headerTextWidth = Render2D.textWidth(TEXT_FONT, headerText, headerTextSize);
        float headerTextDotX = headerTextX + headerTextWidth + 3.0F;
        float iconSize = 12.0F;
        float iconWidth = Render2D.textWidth(FontType.MAINMENUSCREEN, "m", iconSize);
        float iconX = state.x + state.width - iconWidth - 7.0F;
        float iconY = headerY + (headerHeight - iconSize) * 0.5F + 1.0F;
        float glowSize = 22.0F;
        float glowX = iconX + iconWidth * 0.5F - glowSize * 0.5F;
        float glowY = headerY + (headerHeight - glowSize) * 0.5F;
        float sideRectY = headerY + (headerHeight - 9.0F) * 0.5F + 0.5f;
        float sideDotY = headerY + (headerHeight - 4.0F) * 0.5F + 0.5f;
        int iconAlpha = 255;
        int glowAlpha = Math.round(128.0F * state.alpha);
        int glow = ColorUtil.rgba(247, 133, 255, glowAlpha);
        int purple = ColorUtil.rgba(247, 133, 255, Math.round(iconAlpha * state.alpha));
        int headerTextColor = ColorUtil.rgba(255, 255, 255, Math.round(255.0F * state.alpha));
        int rowRectColor = ColorUtil.rgba(247, 133, 255, Math.round(255.0F * state.alpha));

        HudRenderCompat.background(Render2D.blurBuilder()
                .rectangle(state.x, headerY, state.width, headerHeight)
                .radius(4.0F, 4.0F, 2.0F, 2.0F)
                .blurRadius(15.0F)
                .smoothness(1.2F)
                .color(ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)))
                .build());


        HudRenderCompat.background(Render2D.blurBuilder()
                .rectangle(state.x, state.y, state.width, state.height)
                .radius(2.0F, 2.0F, 4.0F, 4.0F)
                .blurRadius(15.0F)
                .smoothness(1.2F)
                .color(ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)))
                .build());

        if (glowAlpha > 0) {
            HudRenderCompat.glow("releon:textures/particles/ghost-glow.png", glowX - 13f, glowY + 0.5f, glowSize + 25, glowSize, 0.0F, glow);
        }

        Render2D.rect(state.x, sideRectY, 2.5f, 9.0F, 0.0F, 2.0F, 2.0F, 0.0F, rowRectColor);

        Render2D.rect(state.x + 3.5F, sideDotY, 4.0F, 4.0F, 4.0F, rowRectColor);
        Render2D.rect(headerTextDotX, sideDotY, 4.0F, 4.0F, 4.0F, rowRectColor);

        Render2D.text(TEXT_FONT, headerText, headerTextX, headerTextY, headerTextSize, headerTextColor);
        Render2D.text(FontType.MAINMENUSCREEN, "m", iconX, iconY, iconSize, purple);

        for (RowState row : state.rows) {
            float rowAlpha = state.alpha * row.alpha;
            float currentY = rowY + row.offset;
            float bindWidth = Render2D.textWidth(TEXT_FONT, row.bind, 5.0F);
            float nameX = state.x + 8.0F;
            float bindX = state.x + state.width - bindWidth - 10F;
            String visibleName = trimToWidth(row.name, TEXT_FONT, 6.0F, bindX - nameX - 5.0F);

            Render2D.text(TEXT_FONT, visibleName, nameX, currentY + 1.5F, 6.0F, ColorUtil.multAlpha(TEXT_COLOR, rowAlpha));
            Render2D.text(TEXT_FONT, row.bind, bindX + 2f, currentY + 1F, row.bindSize, ColorUtil.rgba(255, 255, 255, Math.round(245.0F * rowAlpha)));
        }
    }

    private RowEntry row(String key, String name, String bind, float bindSize, float targetY) {
        for (RowEntry entry : rowEntries) {
            if (entry.key.equals(key)) {
                entry.name = name;
                entry.bind = bind;
                entry.bindSize = bindSize;
                return entry;
            }
        }
        RowEntry entry = new RowEntry(key, name, bind, bindSize);
        entry.alpha.set(0.0);
        entry.y.set(targetY + 4.0F);
        rowEntries.add(entry);
        return entry;
    }

    private static final class RowEntry {
        private final String key;
        private final SmoothAnimation alpha = new SmoothAnimation();
        private final SmoothAnimation y = new SmoothAnimation();
        private String name;
        private String bind;
        private float bindSize;
        private boolean active;

        private RowEntry(String key, String name, String bind, float bindSize) {
            this.key = key;
            this.name = name;
            this.bind = bind;
            this.bindSize = bindSize;
        }
    }

    private record RowState(String name, String bind, float bindSize, float offset, float alpha) {
    }

    private record HotKeysState(List<RowState> rows, float iconAlpha, float alpha, float x, float y, float width, float height) {
    }
}
