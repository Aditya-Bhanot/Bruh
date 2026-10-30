package releon.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ui.font.FontType;

public final class CustomHotbar extends Module {
    private static CustomHotbar instance;
    private static final Minecraft mc = Minecraft.getInstance();
    private static float animatedSelectedX;
    private static float lastHotbarX = Float.NaN;
    private static float lastHotbarY = Float.NaN;
    private static long lastAnimationMs;
    private static float offhandProgress;
    private static long lastOffhandAnimationMs;
    private static ItemStack lastOffhandStack = ItemStack.EMPTY;
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    public CustomHotbar() {
        super("CustomHotbar", "Renders a custom hotbar.", ModuleCategory.VISUAL);
        instance = this;
    }

    public static boolean isActive() {
        return instance != null && instance.isEnabled();
    }

    public static void render(GuiGraphics graphics) {
        if (mc.player == null || mc.getWindow() == null) {
            return;
        }

        Render2D.beginFrame(graphics);

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        float slot = 17.5f;
        float gap = 2.5f;
        float width = slot * 9.0f + gap * 8.0f;
        float x = sw / 2.0f - width / 2.0f;
        float y = sh - 21;
        float offhandGap = gap;
        float offhandX = x - offhandGap - slot;
        int armorCount = armorCount();
        float armorSlot = 14.0f;
        float armorGap = 1.8f;
        float armorIcon = 11.5f;
        float armorGroupGap = armorCount > 0 ? gap : 0.0f;
        float armorWidth = armorCount > 0 ? armorCount * armorSlot + (armorCount - 1) * armorGap : 0.0f;
        float armorX = x + width + armorGroupGap;
        ItemStack offhandStack = mc.player.getOffhandItem();
        updateLastOffhandStack(offhandStack);
        float offhandAlpha = animatedOffhandProgress(!offhandStack.isEmpty());
        float offhandArea = (slot + offhandGap) * offhandAlpha;
        float armorArea = armorGroupGap + armorWidth;
        float fullX = x - offhandArea;
        float fullWidth = width + offhandArea + armorArea;
        float paddingX = 4.0f;
        float paddingY = 3.0f;
        int blurMask = 0xFFFFFFFF;
        int background = 0x5530343A;
        int tint = 0x22000000;
        int selectedColor = 0xFFFFFFFF;


        Render2D.blur(
                fullX - paddingX,
                y - paddingY,
                fullWidth + paddingX * 2.0f,
                slot + paddingY * 2.0f,
                6.0f,
                18.0f,
                11.0f,
                blurMask
        );
        Render2D.rect(
                fullX - paddingX,
                y - paddingY,
                fullWidth + paddingX * 2.0f,
                slot + paddingY * 2.0f,
                6.0f,
                background
        );
        Render2D.rect(
                fullX - paddingX,
                y - paddingY,
                fullWidth + paddingX * 2.0f,
                slot + paddingY * 2.0f,
                6.0f,
                tint
        );
        if (offhandAlpha > 0.01f) {
            Render2D.rect(
                    x - offhandGap * 0.5f - 0.5f,
                    y + 3.0f,
                    1.0f,
                    slot - 6.0f,
                    0.0f,
                    ColorUtil.multAlpha(0x22FFFFFF, offhandAlpha)
            );
        }
        if (armorCount > 0) {
            Render2D.rect(
                    x + width + armorGroupGap * 0.5f - 0.5f,
                    y + 3.0f,
                    1.0f,
                    slot - 6.0f,
                    0.0f,
                    0x22FFFFFF
            );
        }

        for (int i = 0; i < 9; i++) {
            float sx = x + i * (slot + gap);

            if (i > 0) {
                Render2D.rect(sx - gap * 0.5f - 0.5f, y + 3.0f, 1.0f, slot - 6.0f, 0.0f, 0x22FFFFFF);
            }
        }
        for (int i = 1; i < armorCount; i++) {
            float sx = armorX + i * (armorSlot + armorGap);
            Render2D.rect(sx - armorGap * 0.5f - 0.5f, y + 4.0f, 1.0f, slot - 8.0f, 0.0f, 0x18FFFFFF);
        }
        Render2D.rect(animatedSelectedX(x, y, slot, gap), y, slot, slot, 5.0f, selectedColor);

        Render2D.flush();

        for (int i = 0; i < 9; i++) {
            float sx = x + i * (slot + gap);
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                renderVanillaItem(graphics, stack, sx + 1.5f, y + 1.5f, 14.0f);
            }
        }
        if (offhandAlpha > 0.01f && !lastOffhandStack.isEmpty()) {
            renderVanillaItem(graphics, lastOffhandStack, offhandX + 1.5f, y + 1.5f, 14.0f);
        }
        renderArmorItems(graphics, armorX, y, slot, armorSlot, armorGap, armorIcon);

        graphics.nextStratum();
        Render2D.beginFrame(graphics);

        for (int i = 0; i < 9; i++) {
            float sx = x + i * (slot + gap);
            ItemStack stack = mc.player.getInventory().getItem(i);

            if (stack.isEmpty()) {
                drawSlotNumber(i, sx, y, slot);
            } else {
                drawItemDecorations(stack, sx + 1.5f, y + 1.5f, 14.0f, 1.0f);
                drawStackCount(stack, sx, y, slot);
            }
        }
        if (!lastOffhandStack.isEmpty()) {
            drawItemDecorations(lastOffhandStack, offhandX + 1.5f, y + 1.5f, 14.0f, offhandAlpha);
        }
        drawStackCount(lastOffhandStack, offhandX, y, slot, offhandAlpha);
        drawArmorDecorations(armorX, y, slot, armorSlot, armorGap, armorIcon);
        if (offhandAlpha <= 0.01f && offhandStack.isEmpty()) {
            lastOffhandStack = ItemStack.EMPTY;
        }

        Render2D.flush();
        graphics.nextStratum();
    }

    private static void renderVanillaItem(GuiGraphics graphics, ItemStack stack, float x, float y, float size) {
        if (graphics == null || stack.isEmpty() || size <= 0.0f) {
            return;
        }

        float scale = size / 16.0f;
        graphics.pose().pushMatrix();
        try {
            Render2DCoordinateSpace.applyGuiScaleIndependence(graphics.pose());
            graphics.pose().translate(x, y);
            graphics.pose().scale(scale);
            graphics.renderItem(stack, 0, 0);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static int armorCount() {
        int count = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static void renderArmorItems(GuiGraphics graphics, float x, float y, float slotSize, float armorSlot, float gap, float iconSize) {
        int index = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            float sx = x + index * (armorSlot + gap);
            renderVanillaItem(graphics, stack, sx + (armorSlot - iconSize) * 0.5f, y + (slotSize - iconSize) * 0.5f, iconSize);
            index++;
        }
    }

    private static void drawArmorDecorations(float x, float y, float slotSize, float armorSlot, float gap, float iconSize) {
        int index = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            float sx = x + index * (armorSlot + gap);
            drawItemDecorations(stack, sx + (armorSlot - iconSize) * 0.5f, y + (slotSize - iconSize) * 0.5f, iconSize, 1.0f);
            index++;
        }
    }

    private static void updateLastOffhandStack(ItemStack offhandStack) {
        if (offhandStack.isEmpty()) {
            return;
        }
        if (shouldRefreshCachedStack(lastOffhandStack, offhandStack)) {
            lastOffhandStack = offhandStack.copy();
        }
    }

    private static boolean shouldRefreshCachedStack(ItemStack cached, ItemStack current) {
        return cached.isEmpty()
                || cached.getItem() != current.getItem()
                || cached.getCount() != current.getCount()
                || cached.getDamageValue() != current.getDamageValue()
                || cached.isBarVisible() != current.isBarVisible()
                || cached.getBarWidth() != current.getBarWidth()
                || cached.getBarColor() != current.getBarColor();
    }

    private static void drawItemDecorations(ItemStack stack, float x, float y, float size, float alpha) {
        if (stack.isEmpty() || alpha <= 0.01f) {
            return;
        }

        drawCooldownOverlay(stack, x, y, size, alpha);
        drawDurabilityBar(stack, x, y, size, alpha);
    }

    private static void drawDurabilityBar(ItemStack stack, float x, float y, float size, float alpha) {
        if (!stack.isBarVisible()) {
            return;
        }

        float barWidth = Math.max(7.0f, size * 0.78f);
        float barHeight = Math.max(1.2f, size * 0.08f);
        float barX = x + (size - barWidth) * 0.5f;
        float barY = y + size - barHeight - Math.max(0.8f, size * 0.08f);
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

    private static void drawCooldownOverlay(ItemStack stack, float x, float y, float size, float alpha) {
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

        float speed = 14.0f;
        float factor = 1.0f - (float) Math.exp(-speed * delta);
        offhandProgress += (target - offhandProgress) * factor;
        if (Math.abs(target - offhandProgress) < 0.01f) {
            offhandProgress = target;
        }
        return offhandProgress;
    }

    private static void drawSlotNumber(int index, float sx, float y, float slot) {
        String number = String.valueOf(index + 1);
        float textSize = 7.0f;
        float textWidth = Render2D.textWidth(FontType.SEMIBOLD, number, textSize);
        Render2D.text(
                FontType.SEMIBOLD,
                number,
                sx + (slot - textWidth) * 0.5f,
                y + (slot - textSize) * 0.4f,
                textSize,
                0xFF969696
        );
    }

    private static void drawStackCount(ItemStack stack, float sx, float y, float slot) {
        drawStackCount(stack, sx, y, slot, 1.0f);
    }

    private static void drawStackCount(ItemStack stack, float sx, float y, float slot, float alpha) {
        if (stack.isEmpty() || stack.getCount() <= 1) {
            return;
        }

        String count = stack.getCount() > 99 ? "99+" : String.valueOf(stack.getCount());
        float textSize = 5.5f;
        float textWidth = Render2D.textWidth(FontType.SEMIBOLD, count, textSize);
        float textX = sx + slot - textWidth - 0.5f;
        float textY = y + slot - textSize - 0.5f;

        Render2D.text(FontType.SEMIBOLD, count, textX + 0.7f, textY + 0.7f, textSize, ColorUtil.multAlpha(0xAA000000, alpha));
        Render2D.text(FontType.SEMIBOLD, count, textX, textY, textSize, ColorUtil.multAlpha(0xFFFFFFFF, alpha));
    }
}
