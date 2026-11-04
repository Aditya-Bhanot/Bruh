package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public class BooleanOption extends ClickGuiOption {
    private final SmoothAnimation toggleAnimation = new SmoothAnimation();
    private final BooleanSetting setting;
    private boolean enabled;
    private float switchX;
    private float switchY;
    private float switchWidth;
    private float switchHeight;

    public BooleanOption(BooleanSetting setting) {
        super(setting.getName());
        this.setting = setting;
        this.enabled = setting.getValue();
        toggleAnimation.set(enabled ? 1.0 : 0.0);
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        enabled = setting.getValue();
        switchWidth = 22.0f * scale;
        switchHeight = 12.0f * scale;
        switchX = x + width - switchWidth - 6.0f * scale;
        switchY = y + 6.0f * scale;
        toggleAnimation.run(enabled ? 1.0 : 0.0, 0.18, Easings.CUBIC_OUT, true);
        toggleAnimation.update();
        float progress = toggleAnimation.get();
        float knobX = switchX + (2.0f + 10.0f * progress) * scale;

        renderBooleanGlass(switchX, switchY, switchWidth, switchHeight, 5.0f * scale, alpha, progress);
        Render2D.rect(knobX, switchY + 2.0f * scale, 8.0f * scale, 8.0f * scale, 3.0f * scale, color(255, 255, 255, 245, alpha));
        float iconSize = 15.0f;
        renderGuiIcon("T", x + 3.5f * scale, switchY - 4.0f * scale, iconSize, scale);
        renderOptionLabel(graphics, "boolean", x + 15.5f * scale, switchY + 1.0f * scale, 7.2f, switchX - 4.0f * scale, y, height);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0 && event.button() != 1) {
            return false;
        }
        if (hovered(event.x(), event.y(), switchX - 2.0f * scale, switchY - 2.0f * scale, switchWidth + 4.0f * scale, switchHeight + 4.0f * scale)) {
            enabled = !enabled;
            setting.setValue(enabled);
            toggleAnimation.run(enabled ? 1.0 : 0.0, 0.18, Easings.CUBIC_OUT);
            return true;
        }
        return false;
    }

    private void renderBooleanGlass(float x, float y, float width, float height, float radius, float alpha, float progress) {
        Render2D.glass(x, y, width, height, radius, color(255, 255, 255, 72, alpha), 0.64f * alpha, 42.0f, color(255, 255, 255, 150, alpha), 0.16f * alpha, true, 0.22f, 0.003f, 1.0f, 0.0f);
        Render2D.glassOutline(x, y, width, height, radius, 0.9f, color(255, 255, 255, 176, alpha), 1.0f, 35.0f, color(168, 168, 174, 118, alpha), 0.42f * alpha, false, 0.45f, 0.0015f, 1.0f, 0.0f);
    }
}
