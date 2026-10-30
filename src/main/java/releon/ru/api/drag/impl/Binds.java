package releon.ru.api.drag.impl;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import releon.ru.api.drag.core.ElementScreen;
import releon.ru.api.module.ModuleManager;
import releon.ru.api.module.impl.misc.ServerHelper;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.manager.Manager;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;

import java.util.ArrayList;
import java.util.List;

public final class Binds extends HudPanel {
    private static final int PER_ROW = 5;
    private static final float CARD_WIDTH = 20.0F;
    private static final float CARD_HEIGHT = 16.0F;
    private static final float CARD_GAP = 3.0F;
    private static final float ITEM_SIZE = 8.0F;
    private static final ItemStack[] PREVIEW_STACKS = {
            Items.ENDER_EYE.getDefaultInstance(),
            Items.SUGAR.getDefaultInstance(),
            Items.DRIED_KELP.getDefaultInstance()
    };
    private static final String[] PREVIEW_KEYS = {"A", "B", "C"};

    private final SmoothAnimation panelAnimation = new SmoothAnimation();
    private final List<BindEntry> bindEntries = new ArrayList<>();
    private final List<BindEntry> lastBinds = new ArrayList<>();
    private boolean lastPreview;
    private int lastCount;
    private int lastRows = 1;
    private float lastWidth = 22.0F;
    private float lastHeight = 19.0F;

    public Binds() {
        super("binds", "Binds", 10.0F, 180.0F, 48.0F, 18.0F);
    }

    @Override
    public void render() {
        BindsState state = logics();
        if (state == null) {
            return;
        }
        renderBinds(state);
    }

    private BindsState logics() {
        List<BindEntry> binds = hudBinds();
        boolean preview = binds.isEmpty() && editPreview();
        boolean targetVisible = !binds.isEmpty() || preview;
        if (targetVisible) {
            rememberLayout(binds, preview);
        }

        panelAnimation.update();
        panelAnimation.run(targetVisible ? 1.0 : 0.0, 0.24F, targetVisible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);
        float alpha = panelAnimation.get();
        boolean fading = !targetVisible && lastCount > 0 && (alpha > 0.01F || panelAnimation.isAlive());
        boolean renderVisible = targetVisible || fading;
        contentVisible(renderVisible);
        if (!renderVisible) {
            return null;
        }

        List<BindEntry> renderBinds = targetVisible ? binds : lastBinds;
        boolean renderPreview = targetVisible ? preview : lastPreview;
        int count = targetVisible ? (preview ? PREVIEW_STACKS.length : binds.size()) : lastCount;
        int rows = targetVisible ? Math.max(1, (int) Math.ceil(count / (double) PER_ROW)) : lastRows;
        float width = targetVisible ? layoutWidth(binds, preview, count, rows) : lastWidth;
        float height = targetVisible ? rows * 19.0F : lastHeight;
        sizeInstant(width, height);

        return new BindsState(renderBinds, renderPreview, count, rows, alpha, drag.x(), drag.y(), drag.width());
    }

    private void rememberLayout(List<BindEntry> binds, boolean preview) {
        int count = preview ? PREVIEW_STACKS.length : binds.size();
        int rows = Math.max(1, (int) Math.ceil(count / (double) PER_ROW));
        float width = layoutWidth(binds, preview, count, rows);

        lastBinds.clear();
        if (!preview) {
            lastBinds.addAll(binds);
        }
        lastPreview = preview;
        lastCount = count;
        lastRows = rows;
        lastWidth = width;
        lastHeight = rows * 19.0F;
    }

    private float layoutWidth(List<BindEntry> binds, boolean preview, int count, int rows) {
        float width = 0.0F;
        for (int row = 0; row < rows; row++) {
            int start = row * PER_ROW;
            int end = Math.min(count, start + PER_ROW);
            float rowWidth = 0.0F;
            for (int i = start; i < end; i++) {
                rowWidth += cardWidth();
                if (i + 1 < end) {
                    rowWidth += CARD_GAP;
                }
            }
            width = Math.max(width, rowWidth);
        }
        return Math.max(22.0F, width + 6.0F);
    }

    private void sizeInstant(float width, float height) {
        drag.size((float) Math.ceil(width), (float) Math.ceil(height));
        drag.clamp(ElementScreen.current());
    }

    private void renderBinds(BindsState state) {
        int index = 0;
        for (int row = 0; row < state.rows; row++) {
            int start = row * PER_ROW;
            int end = Math.min(state.count, start + PER_ROW);
            float rowWidth = 0.0F;
            for (int i = start; i < end; i++) {
                rowWidth += cardWidth();
                if (i + 1 < end) {
                    rowWidth += CARD_GAP;
                }
            }

            float cardX = state.x + (state.width - rowWidth) * 0.5F;
            float cardY = state.y + row * 19.0F + 1.5F;
            for (int i = start; i < end && index < state.count; i++) {
                ItemStack stack;
                String key;
                int itemCount;
                float cooldownProgress;
                if (state.preview) {
                    stack = PREVIEW_STACKS[index];
                    key = PREVIEW_KEYS[index];
                    itemCount = 64;
                    cooldownProgress = 0.0F;
                } else {
                    BindEntry bind = state.binds.get(index);
                    stack = bind.stack();
                    key = shortBind(bind.bind());
                    itemCount = bind.inventoryCount();
                    cooldownProgress = bind.cooldownProgress();
                }

                float cw = cardWidth();
                boolean hasStack = stack != null && !stack.isEmpty();
                String countText = itemCount > 99 ? "99+" : Integer.toString(Math.max(0, itemCount));
                float countWidth = Render2D.textWidth(TEXT_FONT, countText, 5.0F);
                float overlayHeight = CARD_HEIGHT * clamp(cooldownProgress, 0.0F, 1.0F);
                float itemX = cardX + (cw - ITEM_SIZE) * 0.5F;
                float itemY = cardY + (CARD_HEIGHT - ITEM_SIZE) * 0.5F;
                String visibleKey = trimToWidth(key, TEXT_FONT, 4.8F, cw - 4.0F);

                HudRenderCompat.background(cardX, cardY, cw, CARD_HEIGHT, 4.0F, 15.0F, 1.2F, ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)));
                if (hasStack) {
                    RenderItem.item(stack, itemX, itemY, ITEM_SIZE, RenderItemOptions.noDecorations(state.alpha));
                }
                Render2D.text(TEXT_FONT, countText, cardX + cw - countWidth - 2.0F, cardY + 8.0F, 5.0F, ColorUtil.multAlpha(TEXT_COLOR, state.alpha));
                Render2D.text(TEXT_FONT, visibleKey, cardX + 2.0F, cardY + 2F, 5F, ColorUtil.multAlpha(MUTED_COLOR, state.alpha));

                if (cooldownProgress > 0.0F) {
                    Render2D.rect(cardX, cardY + CARD_HEIGHT - overlayHeight, cw, overlayHeight, 4.0F, ColorUtil.rgba(0, 0, 0, Math.round(80.0F * state.alpha)));
                }

                cardX += cw + CARD_GAP;
                index++;
            }
        }
    }

    private List<BindEntry> hudBinds() {
        ModuleManager manager = Manager.getModules();
        if (manager == null) {
            bindEntries.clear();
            return List.of();
        }
        return manager.getByType(ServerHelper.class)
                .map(this::readHudBinds)
                .orElse(List.of());
    }

    private List<BindEntry> readHudBinds(ServerHelper helper) {
        List<ServerHelper.HudBind> hudBinds = helper.getHudBinds(0.0F);
        bindEntries.clear();
        if (hudBinds == null || hudBinds.isEmpty()) {
            return bindEntries;
        }

        for (ServerHelper.HudBind hudBind : hudBinds) {
            if (hudBind == null) {
                continue;
            }
            ItemStack stack = hudBind.stack() == null ? ItemStack.EMPTY : hudBind.stack().copy();
            KeyBind bind = hudBind.setting() == null ? KeyBind.NONE : hudBind.setting().getValue();
            bindEntries.add(new BindEntry(stack, bind == null ? KeyBind.NONE : bind, hudBind.inventoryCount(), hudBind.cooldownProgress()));
        }
        return bindEntries;
    }

    private float cardWidth() {
        return CARD_WIDTH;
    }

    private record BindEntry(ItemStack stack, KeyBind bind, int inventoryCount, float cooldownProgress) {
    }

    private record BindsState(List<BindEntry> binds, boolean preview, int count, int rows, float alpha, float x, float y, float width) {
    }
}
