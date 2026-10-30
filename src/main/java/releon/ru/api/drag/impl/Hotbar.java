package releon.ru.api.drag.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import releon.ru.api.drag.core.HudElement;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public final class Hotbar implements HudElement {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final RenderItemOptions ITEM_OPTIONS = RenderItemOptions.noDecorations(1.0f);

    private static float animatedSelectedX;
    private static float lastHotbarX = Float.NaN;
    private static float lastHotbarY = Float.NaN;
    private static long lastAnimationMs;
    private static float offhandProgress;
    private static long lastOffhandAnimationMs;
    private static ItemStack lastOffhandStack = ItemStack.EMPTY;

    @Override
    public void render() {
        HotbarState state = logics();
        if (state == null) {
            return;
        }
        renderHotbar(state);
    }

    private HotbarState logics() {
        if (mc.player == null || mc.getWindow() == null) {
            return null;
        }

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        float slot = 17.5f;
        float gap = 2.5f;
        float width = slot * 9.0f + gap * 8.0f;
        float x = sw / 2.0f - width / 2.0f;
        float y = sh - 21;
        float offhandGap = gap;
        float offhandX = x - offhandGap - slot;
        ItemStack offhandStack = mc.player.getOffhandItem();
        if (!offhandStack.isEmpty()) {
            lastOffhandStack = offhandStack.copy();
        }
        float offhandAlpha = animatedOffhandProgress(!offhandStack.isEmpty());
        float offhandArea = (slot + offhandGap) * offhandAlpha;
        float fullX = x - offhandArea;
        float fullWidth = width + offhandArea;
        float paddingX = 4.0f;
        float paddingY = 3.0f;
        float selectedX = animatedSelectedX(x, y, slot, gap);
        ItemStack renderedOffhand = lastOffhandStack.copy();

        if (offhandAlpha <= 0.01f && offhandStack.isEmpty()) {
            lastOffhandStack = ItemStack.EMPTY;
        }

        return new HotbarState(x, y, slot, gap, fullX, fullWidth, paddingX, paddingY, selectedX, offhandX, offhandAlpha, renderedOffhand);
    }

    private void renderHotbar(HotbarState state) {
        int background = ColorUtil.rgba(0, 0, 0, 255);
        int selected = ColorUtil.rgba(128, 128, 128, 55);

        HudRenderCompat.background(state.fullX - state.paddingX, state.y - state.paddingY, state.fullWidth + state.paddingX * 2.0f, state.slot + state.paddingY * 2.0f, 6.0f, 18.0f, 1.2f, background);
        Render2D.rect(state.selectedX, state.y, state.slot, state.slot, 4.0f, selected);

        for (int i = 0; i < 9; i++) {
            float sx = state.x + i * (state.slot + state.gap);
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                renderSlotNumber(i, sx, state.y, state.slot);
            } else {
                RenderItem.item(stack, sx + 0.75f, state.y + 0.75f, 16.0f, ITEM_OPTIONS);
            }
        }

        if (state.offhandAlpha > 0.01f && !state.offhandStack.isEmpty()) {
            RenderItem.item(state.offhandStack, state.offhandX + 0.75f, state.y + 0.75f, 16.0f, ITEM_OPTIONS.alpha(state.offhandAlpha));
        }

        for (int i = 0; i < 9; i++) {
            float sx = state.x + i * (state.slot + state.gap);
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                renderItemDecorations(stack, sx + 0.75f, state.y + 0.75f, 16.0f, 1.0f);
            }
        }

        if (state.offhandAlpha > 0.01f && !state.offhandStack.isEmpty()) {
            renderItemDecorations(state.offhandStack, state.offhandX + 0.75f, state.y + 0.75f, 16.0f, state.offhandAlpha);
        }
    }

    private static void renderItemDecorations(ItemStack stack, float x, float y, float size, float alpha) {
        if (stack.isEmpty() || alpha <= 0.01f) {
            return;
        }

        renderCooldownOverlay(stack, x, y, size, alpha);
        renderDurabilityBar(stack, x, y, size, alpha);
        renderStackCount(stack, x, y, size, alpha);
    }

    private static void renderCooldownOverlay(ItemStack stack, float x, float y, float size, float alpha) {
        if (mc.player == null) {
            return;
        }

        float cooldown = mc.player.getCooldowns().getCooldownPercent(stack, 0.0f);
        if (cooldown <= 0.0f) {
            return;
        }

        float overlayHeight = size * Math.max(0.0f, Math.min(1.0f, cooldown));
        Render2D.rect(
                x,
                y + size - overlayHeight,
                size,
                overlayHeight,
                1.0f,
                ColorUtil.rgba(0, 0, 0, Math.round(145.0f * alpha))
        );
    }

    private static void renderDurabilityBar(ItemStack stack, float x, float y, float size, float alpha) {
        if (!stack.isBarVisible()) {
            return;
        }

        float barWidth = Math.max(8.0f, size * 0.78f);
        float barHeight = Math.max(1.5f, size * 0.07f);
        float barX = x + (size - barWidth) * 0.5f;
        float barY = y + size - barHeight - Math.max(1.0f, size * 0.08f);
        float fill = Math.max(0.0f, Math.min(1.0f, stack.getBarWidth() / 13.0f));
        int barColor = stack.getBarColor();

        Render2D.rect(barX, barY, barWidth, barHeight, barHeight * 0.5f, ColorUtil.rgba(0, 0, 0, Math.round(150.0f * alpha)));
        Render2D.rect(
                barX,
                barY,
                Math.max(1.0f, barWidth * fill),
                barHeight,
                barHeight * 0.5f,
                ColorUtil.rgba((barColor >>> 16) & 0xFF, (barColor >>> 8) & 0xFF, barColor & 0xFF, Math.round(240.0f * alpha))
        );
    }

    private static void renderStackCount(ItemStack stack, float x, float y, float size, float alpha) {
        if (stack.getCount() <= 1) {
            return;
        }

        String text = Integer.toString(stack.getCount());
        float textSize = Math.max(4.0f, Math.min(6.0f, size * 0.5f));
        float textWidth = Render2D.textWidth(FontType.SEMIBOLD, text, textSize);
        float textX = x + size - textWidth + size * 0.08f;
        float textY = y + size - textSize + size * 0.04f;
        float shadowOffset = Math.max(0.35f, Math.min(0.75f, size * 0.07f));

        Render2D.text(FontType.SEMIBOLD, text, textX + shadowOffset, textY + shadowOffset, textSize, ColorUtil.rgba(0, 0, 0, Math.round(170.0f * alpha)));
        Render2D.text(FontType.SEMIBOLD, text, textX, textY, textSize, ColorUtil.rgba(255, 255, 255, Math.round(255.0f * alpha)));
    }

    private static float animatedSelectedX(float x, float y, float slot, float gap) {
        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        float targetX = x + selectedSlot * (slot + gap);
        long now = System.currentTimeMillis();

        boolean layoutChanged = Float.isNaN(lastHotbarX)
                || Math.abs(lastHotbarX - x) > 1.0f
                || Math.abs(lastHotbarY - y) > 1.0f;
        if (layoutChanged || lastAnimationMs == 0L) {
            animatedSelectedX = targetX;
            lastAnimationMs = now;
            lastHotbarX = x;
            lastHotbarY = y;
            return animatedSelectedX;
        }

        float delta = Math.min((now - lastAnimationMs) / 1000.0f, 0.05f);
        lastAnimationMs = now;
        lastHotbarX = x;
        lastHotbarY = y;

        float speed = 20.0f;
        float factor = 1.0f - (float) Math.exp(-speed * delta);
        animatedSelectedX += (targetX - animatedSelectedX) * factor;
        if (Math.abs(targetX - animatedSelectedX) < 0.05f) {
            animatedSelectedX = targetX;
        }
        return animatedSelectedX;
    }

    private static float animatedOffhandProgress(boolean visible) {
        float target = visible ? 1.0f : 0.0f;
        long now = System.currentTimeMillis();
        if (lastOffhandAnimationMs == 0L) {
            lastOffhandAnimationMs = now;
        }

        float delta = Math.min((now - lastOffhandAnimationMs) / 1000.0f, 0.05f);
        lastOffhandAnimationMs = now;

        float speed = 50.0f;
        float factor = 1.0f - (float) Math.exp(-speed * delta);
        offhandProgress += (target - offhandProgress) * factor;
        if (Math.abs(target - offhandProgress) < 0.01f) {
            offhandProgress = target;
        }
        return offhandProgress;
    }

    private static void renderSlotNumber(int index, float sx, float y, float slot) {
        String number = String.valueOf(index + 1);
        float textSize = 6.0f;
        float textWidth = Render2D.textWidth(FontType.SEMIBOLD, number, textSize);

        Render2D.text(FontType.SEMIBOLD, number, sx + (slot - textWidth) * 0.5f + 0.5f, y + (slot - textSize) * 0.4f, textSize, 0xFF969696);
    }

    private record HotbarState(
            float x,
            float y,
            float slot,
            float gap,
            float fullX,
            float fullWidth,
            float paddingX,
            float paddingY,
            float selectedX,
            float offhandX,
            float offhandAlpha,
            ItemStack offhandStack
    ) {
    }
}
