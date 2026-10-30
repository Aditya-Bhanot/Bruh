package releon.ru.api.module.impl.combat.autoswaputil;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.impl.combat.AutoSwap;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;

public class AutoSwapWheelScreen extends Screen {
    private static final float SECTOR_SIZE = 120.0F;
    private static final float SECTOR_GAP = 2.0F;
    private static final float SECTOR_DEGREE = SECTOR_SIZE - SECTOR_GAP;
    private static final float OUTER_RADIUS_FACTOR = 0.23F;
    private static final float INNER_RADIUS_FACTOR = 0.60F;
    private static final float HOVER_PUSH = 8.0F;
    private static final float HOVER_EXPAND = 4.0F;
    private static final float OUTLINE_THICKNESS = 0.9F;
    private static final int ICON_SIZE = 16;
    private static final String CHANGE_ITEM_HINT = "\u041f\u041a\u041c - \u0438\u0437\u043c\u0435\u043d\u0438\u0442\u044c \u043f\u0440\u0435\u0434\u043c\u0435\u0442";

    private final AutoSwap autoSwap;
    private final IconRect[] iconRects = new IconRect[3];
    private final ItemStack[] lastStacks = new ItemStack[3];
    private int hoveredSector = -1;

    public AutoSwapWheelScreen(AutoSwap autoSwap) {
        super(Component.empty());
        this.autoSwap = autoSwap;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    protected void renderBlurredBackground(GuiGraphics graphics) {
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        Render2D.beginFrame(graphics);
        RenderItem.beginFrame(graphics);

        graphics.fill(0, 0, width, height, 0x14080808);

        float centerX = width / 2.0F;
        float centerY = height / 2.0F;
        float outerRadius = Math.min(width, height) * OUTER_RADIUS_FACTOR;
        float innerRadius = outerRadius * INNER_RADIUS_FACTOR;
        float iconRadius = (outerRadius + innerRadius) * 0.5F;

        hoveredSector = computeHoveredSector(mouseX, mouseY, centerX, centerY, innerRadius, outerRadius);

        for (int i = 0; i < 3; i++) {
            lastStacks[i] = ItemStack.EMPTY;
            iconRects[i] = null;

            ItemStack stack = autoSwap.getWheelSlotStack(i);
            lastStacks[i] = stack;

            float centerAngle = getSectorCenterAngle(i);
            double radians = Math.toRadians(centerAngle);
            float progress = i == hoveredSector ? 1.0F : 0.0F;

            float shift = HOVER_PUSH * progress;
            float expand = HOVER_EXPAND * progress;
            float shiftedCenterX = centerX + (float) Math.cos(radians) * shift;
            float shiftedCenterY = centerY + (float) Math.sin(radians) * shift;
            float currentOuterRadius = outerRadius + expand;
            float currentInnerRadius = innerRadius;
            float currentThickness = currentOuterRadius - currentInnerRadius;

            float fixedX = Render2D.guiToFixed(shiftedCenterX - currentOuterRadius);
            float fixedY = Render2D.guiToFixed(shiftedCenterY - currentOuterRadius);
            float fixedSize = Render2D.guiToFixed(currentOuterRadius * 2.0F);
            float fixedThickness = Render2D.guiToFixed(currentThickness);

            int sectorColor = lerpColor(0x6F8E8E8E, 0xAAD9D9D9, progress);
            int outlineColor = lerpColor(0x90B8B8B8, 0xD4F4F4F4, progress);
            Render2D.arc(
                    fixedX,
                    fixedY,
                    fixedSize,
                    fixedThickness,
                    SECTOR_DEGREE,
                    centerAngle,
                    sectorColor
            );
            Render2D.arcOutline(
                    fixedX,
                    fixedY,
                    fixedSize,
                    fixedThickness,
                    SECTOR_DEGREE,
                    centerAngle,
                    Render2D.guiToFixed(OUTLINE_THICKNESS),
                    0x00000000,
                    outlineColor
            );

            float iconX = shiftedCenterX + (float) Math.cos(radians) * iconRadius;
            float iconY = shiftedCenterY + (float) Math.sin(radians) * iconRadius;
            IconRect rect = new IconRect(Mth.floor(iconX - ICON_SIZE / 2.0F), Mth.floor(iconY - ICON_SIZE / 2.0F), ICON_SIZE, ICON_SIZE);
            iconRects[i] = rect;

            if (!stack.isEmpty()) {
                RenderItem.item(
                        stack,
                        Render2D.guiToFixed(iconX - ICON_SIZE / 2.0F),
                        Render2D.guiToFixed(iconY - ICON_SIZE / 2.0F),
                        Render2D.guiToFixed(ICON_SIZE),
                        RenderItemOptions.noDecorations(1.0F)
                );
            } else {
                int plusColor = lerpColor(0xBFD5D5D5, 0xFFFFFFFF, progress);
                graphics.drawCenteredString(font, Component.literal("+"), rect.x + rect.w / 2, rect.y + 4, plusColor);
            }
        }

        graphics.drawCenteredString(
                font,
                Component.literal(CHANGE_ITEM_HINT),
                width / 2,
                Mth.floor(centerY + outerRadius + 20.0F),
                0xD6FFFFFF
        );

        RenderItem.flush();
        Render2D.flush();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int selectedIndex = findSelectedIndex(event.x(), event.y());
        if (selectedIndex == -1) {
            return super.mouseClicked(event, doubleClick);
        }

        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            onClose();
            autoSwap.startSelectingItem(selectedIndex);
            return true;
        }

        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            ItemStack stack = lastStacks[selectedIndex] == null ? ItemStack.EMPTY : lastStacks[selectedIndex];
            onClose();
            if (!stack.isEmpty()) {
                autoSwap.startSwapToItemStack(stack);
            }
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    private int findSelectedIndex(double mouseX, double mouseY) {
        for (int i = 0; i < iconRects.length; i++) {
            IconRect rect = iconRects[i];
            if (rect != null && rect.contains(mouseX, mouseY)) {
                return i;
            }
        }
        return hoveredSector;
    }

    private int computeHoveredSector(double mouseX, double mouseY, float centerX, float centerY, float innerRadius, float outerRadius) {
        float dx = (float) mouseX - centerX;
        float dy = (float) mouseY - centerY;
        float distanceSq = dx * dx + dy * dy;
        float minRadius = Math.max(0.0F, innerRadius - 8.0F);
        float maxRadius = outerRadius + HOVER_PUSH + HOVER_EXPAND + 8.0F;
        if (distanceSq < minRadius * minRadius || distanceSq > maxRadius * maxRadius) {
            return -1;
        }

        float angle = normalizeAngle((float) Math.toDegrees(Math.atan2(dy, dx)));
        for (int i = 0; i < 3; i++) {
            float center = normalizeAngle(getSectorCenterAngle(i));
            float start = normalizeAngle(center - SECTOR_DEGREE * 0.5F);
            float end = normalizeAngle(center + SECTOR_DEGREE * 0.5F);
            if (isAngleInside(angle, start, end)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isAngleInside(float angle, float startAngle, float endAngle) {
        if (startAngle <= endAngle) {
            return angle >= startAngle && angle <= endAngle;
        }
        return angle >= startAngle || angle <= endAngle;
    }

    private float getSectorCenterAngle(int index) {
        return -90.0F + index * SECTOR_SIZE;
    }

    private float normalizeAngle(float angle) {
        float normalized = angle % 360.0F;
        return normalized < 0.0F ? normalized + 360.0F : normalized;
    }

    private int lerpColor(int from, int to, float progress) {
        int a = Mth.lerpInt(progress, (from >>> 24) & 0xFF, (to >>> 24) & 0xFF);
        int r = Mth.lerpInt(progress, (from >>> 16) & 0xFF, (to >>> 16) & 0xFF);
        int g = Mth.lerpInt(progress, (from >>> 8) & 0xFF, (to >>> 8) & 0xFF);
        int b = Mth.lerpInt(progress, from & 0xFF, to & 0xFF);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private record IconRect(int x, int y, int w, int h) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        }
    }
}
