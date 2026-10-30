package releon.ru.screens.modernui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;
import java.util.Locale;

final class SliderSettingCard extends SettingCardComponent<NumberSetting> {
    SliderSettingCard(NumberSetting setting) {
        super(setting);
    }

    @Override
    int order() {
        return 2;
    }

    @Override
    float height(SettingCardContext context) {
        return 21.0F + descriptionOffset(context, 92.0F);
    }

    @Override
    void render(GuiGraphics graphics, SettingCardContext context, float cardX, float y, int mouseX, int mouseY) {
        markRow(cardX, y, height(context));
        float descriptionOffset = descriptionOffset(context, 92.0F);
        float sliderX = cardX + 8.0F;
        float sliderY = y + 10.0F + descriptionOffset;
        float sliderWidth = 132.0F;
        float progress = progress(context);
        state.sliderProgress = context.lerp(state.sliderProgress, progress, context.draggingSlider == setting ? 0.42F : 0.18F);
        state.slider(sliderX, sliderY - 6.0F, sliderWidth, 15.0F);

        renderNameAndDescription(graphics, context, cardX + 10.0F, y, 92.0F, 92.0F);
        String value = formatValue(setting.getValue(), setting.getStep());
        float valueWidth = Render2D.textWidth(FontType.SEMIBOLD, value, 6);
        Render2D.text(FontType.SEMIBOLD, value, cardX + 130.0F - valueWidth, y + 4.5f, 6, context.color(225, 225, 225, 255));
        Render2D.text(FontType.GUI_ICONS, "H", cardX + 132.0F, y + 4, 9, context.color(128, 128, 128, 255));
        Render2D.rect(sliderX, sliderY, sliderWidth, 3, 0.5f,
                context.color(185, 185, 185, 50),
                context.color(185, 185, 185, 50),
                context.color(185, 185, 185, 50),
                context.color(185, 185, 185, 50));
        Render2D.rect(sliderX, sliderY, sliderWidth * state.sliderProgress, 3, 0.5f,
                context.color(145, 115, 185, 250),
                context.color(195, 135, 195, 250),
                context.color(145, 115, 185, 250),
                context.color(125, 105, 145, 250));
        Render2D.rect(sliderX + sliderWidth * state.sliderProgress - 2.0F, sliderY - 1.5F, 6, 6, 2f, context.color(255, 255, 255, 255));
    }

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubled, SettingCardContext context) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !context.inside(event.x(), event.y(), state.sliderX, state.sliderY, state.sliderWidth, state.sliderHeight)) {
            return false;
        }
        context.draggingSlider = setting;
        updateValue(context, event.x());
        context.activeColor = null;
        return true;
    }

    @Override
    boolean mouseReleased(MouseButtonEvent event, SettingCardContext context) {
        if (context.draggingSlider != setting) {
            return false;
        }
        context.draggingSlider = null;
        return true;
    }

    @Override
    boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY, SettingCardContext context) {
        if (context.draggingSlider != setting) {
            return false;
        }
        updateValue(context, event.x());
        return true;
    }

    private void updateValue(SettingCardContext context, double mouseX) {
        if (state.sliderWidth <= 0.0F) {
            return;
        }
        double min = setting.getMin();
        double max = setting.getMax();
        double nextProgress = context.clamp((float) ((mouseX - state.sliderX) / state.sliderWidth), 0.0F, 1.0F);
        setting.setValue(min + (max - min) * nextProgress);
    }

    private float progress(SettingCardContext context) {
        double min = setting.getMin();
        double max = setting.getMax();
        if (max == min) {
            return 0.0F;
        }
        return context.clamp((float) ((setting.getValue() - min) / (max - min)), 0.0F, 1.0F);
    }

    private String formatValue(double value, double step) {
        if (step >= 1.0D) {
            return String.valueOf(Math.round(value));
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        while (text.contains(".") && text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }
}
