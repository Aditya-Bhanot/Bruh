package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public class SliderOption extends ClickGuiOption {
    private final SmoothAnimation progressAnimation = new SmoothAnimation();
    private final NumberSetting setting;
    private final String suffix;
    private float value;
    private float min;
    private float max;
    private boolean dragging;
    private float sliderX;
    private float sliderY;
    private float sliderWidth;
    private float sliderHeight;

    public SliderOption(NumberSetting setting, String suffix) {
        super(setting.getName());
        this.setting = setting;
        this.value = setting.getFloat();
        this.min = (float) setting.getMin();
        this.max = (float) setting.getMax();
        this.suffix = suffix == null ? "" : suffix;
        progressAnimation.set(progress());
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        value = setting.getFloat();
        min = (float) setting.getMin();
        max = (float) setting.getMax();
        sliderWidth = 40.0f * scale;
        sliderX = x + width - sliderWidth - 7.0f * scale;
        sliderY = y + 14.0f * scale;
        sliderHeight = 3.0f * scale;
        progressAnimation.run(progress(), dragging ? 0.10 : 0.18, Easings.CUBIC_OUT, true);
        progressAnimation.update();
        float progress = progressAnimation.get();
        float displayValue = Math.round((min + (max - min) * progress) * 10.0f) / 10.0f;
        String valueText = formatValue(displayValue);
        float valueSize = 6.2f;
        float valueClipX = sliderX;
        float valueClipWidth = sliderWidth;
        float valueX = valueClipX + 2.0f * scale;
        float knobSize = 7.0f * scale;
        float knobX = clamp(sliderX + sliderWidth * progress - knobSize / 2.0f, sliderX, sliderX + sliderWidth - knobSize);

        renderScrollingText(graphics, "slider:value", valueText, valueX, y + 5.0f * scale, valueSize, color(255, 255, 255, 245, alpha), valueClipX, y + 3.0f * scale, valueClipWidth, 9.0f * scale, sliderX, y, sliderWidth, height);
        renderSliderGlass(sliderX, sliderY - 0.5f * scale, sliderWidth, sliderHeight + 1.0f * scale, 0.5f * scale, alpha);
        Render2D.rect(sliderX, sliderY, sliderWidth * progress, sliderHeight, 0.5f * scale, color(255, 255, 255, 230, alpha));
        Render2D.rect(knobX, sliderY - 2.0f * scale, knobSize, knobSize, 2.5f * scale, color(215, 215, 215, 245, alpha));
        float iconSize = 9.0f;
        renderGuiIcon("H", x + 3.5f * scale, y + 6.5f * scale, iconSize, scale);
        renderOptionLabel(graphics, "slider", x + 15.5f * scale, y + 6.0f * scale, 7.2f, sliderX - 5.0f * scale, y, height);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) {
            return false;
        }
        if (hovered(event.x(), event.y(), sliderX - 3.0f * scale, sliderY - 6.0f * scale, sliderWidth + 6.0f * scale, 15.0f * scale)) {
            dragging = true;
            updateValue(event.x());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (!dragging) {
            return false;
        }
        dragging = false;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!dragging) {
            return false;
        }
        updateValue(event.x());
        return true;
    }

    private void updateValue(double mouseX) {
        float nextProgress = clamp((float) ((mouseX - sliderX) / sliderWidth), 0.0f, 1.0f);
        float raw = min + (max - min) * nextProgress;
        value = Math.round(raw * 10.0f) / 10.0f;
        setting.setValue((double) value);
    }

    private float progress() {
        if (max == min) {
            return 0.0f;
        }
        return clamp((value - min) / (max - min), 0.0f, 1.0f);
    }

    private void renderSliderGlass(float x, float y, float width, float height, float radius, float alpha) {
        Render2D.blur(x, y, width, height, radius, 1.0f, 1.0f, color(255, 255, 255, 255, alpha));
        Render2D.rect(
                x,
                y,
                width,
                height,
                radius,
                color(75, 75, 75, 140, alpha),
                color(75, 75, 75, 136, alpha),
                color(75, 75, 75, 132, alpha),
                color(75, 75, 75, 140, alpha)
        );
        Render2D.glass(x, y, width, height, radius, color(255, 255, 255, 62, alpha), 0.64f * alpha, 42.0f, color(255, 255, 255, 126, alpha), 0.16f * alpha, true, 0.22f, 0.003f, 1.0f, 0.0f);
    }

    private String formatValue(float displayValue) {
        int scaled = Math.round(displayValue * 10.0f);
        String sign = scaled < 0 ? "-" : "";
        int absolute = Math.abs(scaled);
        return sign + (absolute / 10) + "." + (absolute % 10) + suffix;
    }
}
