package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public class SingleOption extends ClickGuiOption {
    private final String[] values;
    private final SmoothAnimation openAnimation = new SmoothAnimation();
    private final ModeSetting setting;
    private String value;
    private boolean open;
    private float controlX;
    private float controlY;
    private float controlWidth;
    private float controlHeight;

    public SingleOption(ModeSetting setting) {
        super(setting.getName());
        this.setting = setting;
        this.value = setting.getValue();
        this.values = setting.getModes().toArray(String[]::new);
        openAnimation.set(0.0);
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        value = setting.getValue();
        controlWidth = 53.0f * scale;
        controlHeight = 13.0f * scale;
        controlX = x + width - controlWidth - 5.0f * scale;
        controlY = y + 5.5f * scale;

        renderGlassControl(controlX, controlY, controlWidth, controlHeight, 3.5f * scale, alpha);
        renderScrollingText(graphics, "single:value", value, controlX + 4.0f * scale, controlY + 2.5f * scale, 6.5f, color(255, 255, 255, 245, alpha), controlX + 4.0f * scale, controlY, controlWidth - 8.0f * scale, controlHeight, controlX, controlY, controlWidth, controlHeight);
        float iconSize = 9.0f;
        renderGuiIcon("J", x + 3.5f * scale, controlY + 1.0f * scale, iconSize, scale);
        renderOptionLabel(graphics, "single", x + 15.5f * scale, controlY + 1.0f * scale, 7.2f, controlX - 4.0f * scale, y, height);
    }

    @Override
    public void renderOverlay(GuiGraphics graphics) {
        openAnimation.update();
        float progress = openAnimation.get();
        if (progress <= 0.01f) {
            return;
        }

        float itemHeight = 15.0f * scale;
        float popupY = controlY + controlHeight + 2.0f * scale;
        float visibleHeight = values.length * itemHeight;
        float popupAlpha = alpha * progress;
        float radius = 5.0f * scale;

        Render2D.blur(controlX, popupY, controlWidth, visibleHeight, radius, 1, 1.0f, color(255, 255, 255, 255, popupAlpha));
        Render2D.glass(controlX, popupY, controlWidth, visibleHeight, radius, color(255, 255, 255, 68, popupAlpha), 0.58f * popupAlpha, 42.0f, color(255, 255, 255, 142, popupAlpha), 0.12f * popupAlpha, true, 0.22f, 0.003f, 1.0f, 0.0f);

        Render2D.rect(
                controlX, popupY, controlWidth, visibleHeight,
                radius,
                color(45, 45, 45, 72, popupAlpha),
                color(18, 18, 18, 28, popupAlpha),
                color(45, 45, 45, 72, popupAlpha),
                color(18, 18, 18, 28, popupAlpha)
        );

        Render2D.glassOutline(controlX, popupY, controlWidth, visibleHeight, radius, 0.5f * scale, color(255, 255, 255, 160, popupAlpha), 1.0f, 35.0f, color(168, 168, 174, 118, popupAlpha), 0.42f * popupAlpha, false, 0.45f, 0.0015f, 1.0f, 0.0f);
        Render2D.pushScissor(graphics, controlX, popupY, controlWidth, visibleHeight);
        for (int i = 0; i < values.length; i++) {
            float itemY = popupY + i * itemHeight;
            boolean selected = values[i].equals(value);
            renderScrollingText(graphics, "single:popup:" + i, values[i], controlX + 5.0f * scale, itemY + 4.0f * scale, 6.4f, selected ? color(255, 255, 255, 255, popupAlpha) : color(205, 205, 210, 205, popupAlpha), controlX + 5.0f * scale, itemY, controlWidth - 10.0f * scale, itemHeight, controlX, itemY, controlWidth, itemHeight);
        }
        Render2D.popScissor(graphics);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) {
            return false;
        }

        double mouseX = event.x();
        double mouseY = event.y();
        if (isPopupVisible()) {
            float itemHeight = 15.0f * scale;
            float popupY = controlY + controlHeight + 2.0f * scale;
            if (hovered(mouseX, mouseY, controlX, popupY, controlWidth, values.length * itemHeight)) {
                int index = Math.max(0, Math.min(values.length - 1, (int) ((mouseY - popupY) / itemHeight)));
                value = values[index];
                setting.setValue(value);
                setOpen(false);
                return true;
            }
        }

        if (hovered(mouseX, mouseY, controlX, controlY, controlWidth, controlHeight)) {
            setOpen(!open);
            return true;
        }

        if (open) {
            setOpen(false);
        }
        return false;
    }

    @Override
    public boolean wantsMousePriority(MouseButtonEvent event) {
        if (!isPopupVisible()) {
            return false;
        }
        float itemHeight = 15.0f * scale;
        float popupY = controlY + controlHeight + 2.0f * scale;
        return hovered(event.x(), event.y(), controlX, popupY, controlWidth, values.length * itemHeight);
    }

    @Override
    public boolean hasOpenPopup() {
        return isPopupVisible();
    }

    @Override
    public void closePopup() {
        if (open) {
            setOpen(false);
        }
    }

    private boolean isPopupVisible() {
        return open || openAnimation.get() > 0.01f || openAnimation.isAlive();
    }

    private void setOpen(boolean open) {
        if (this.open == open) {
            return;
        }
        this.open = open;
        openAnimation.run(open ? 1.0 : 0.0, 0.16, Easings.CUBIC_OUT);
    }
}
