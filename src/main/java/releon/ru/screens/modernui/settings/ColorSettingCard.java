package releon.ru.screens.modernui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.utils.render.animation.modernfx.Direction;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;
import java.util.List;

final class ColorSettingCard extends SettingCardComponent<ColorSetting> {
    ColorSettingCard(ColorSetting setting) {
        super(setting);
    }

    @Override
    int order() {
        return 4;
    }

    @Override
    float height(SettingCardContext context) {
        return height(descriptionLines(context, 101.0F).size());
    }

    @Override
    void render(GuiGraphics graphics, SettingCardContext context, float cardX, float y, int mouseX, int mouseY) {
        List<String> descriptionLines = descriptionLines(context, 101.0F);
        float rowHeight = height(descriptionLines.size());
        markRow(cardX, y, rowHeight);
        float rowX = cardX + 8.0F;
        float rowY = y - 3;
        state.box(rowX, rowY, 132.0F, rowHeight);
        state.colorX = cardX + 128.5F;
        state.colorY = rowY + 5.0F;
        state.colorWidth = 9.0F;
        state.colorHeight = 9.0F;
        updateStateFromSetting(context);

        Render2D.outline(
                state.colorX, state.colorY, 9, 9, 3.5f,
                0.5f,
                context.color(255, 255, 255, 255),
                context.color(255, 255, 255, 50),
                context.color(255, 255, 255, 255),
                context.color(255, 255, 255, 50)
        );

        Render2D.rect(state.colorX, state.colorY, 9, 9, 3.5f,
                context.multiplyAlpha(setting.getValue().getRGB(), 1.0F),
                context.multiplyAlpha(setting.getValue().getRGB(), 1.0F),
                context.multiplyAlpha(setting.getValue().getRGB(), 1.0F),
                context.multiplyAlpha(setting.getValue().getRGB(), 1.0F));

        Render2D.text(FontType.MAINMENUSCREEN, "J", state.colorX - 11, state.colorY + 0.5f, 8, context.color(128, 128, 128, 255));


        context.renderClippedText(graphics, FontType.SEMIBOLD, setting.getName(), cardX + 10.0F, rowY + 3.0F, 101.0F, 6, new Color(225, 225, 225, 255).getRGB());

        if (!descriptionLines.isEmpty()) {
            context.renderWrappedText(graphics, FontType.SEMIBOLD, descriptionLines, cardX + 10.0F, rowY + 10.5F, 101.0F, 5.0F, 5.5F, new Color(145, 145, 145, 190).getRGB());
        }
    }

    @Override
    void renderFloatingOverlay(GuiGraphics graphics, SettingCardContext context) {
        boolean active = context.activeColor == setting && state.visible;
        state.colorPickerAnimation.setDirection(active ? Direction.FORWARDS : Direction.BACKWARDS);
        float animation = context.clamp(state.colorPickerAnimation.getOutput().floatValue(), 0.0F, 1.0F);
        if (animation <= 0.01F) {
            return;
        }

        if (active) {
            state.popupWidth = 87.0F;
            state.popupHeight = 76.0F;
            state.popupX = state.colorX - state.popupWidth - 6.0F;
            state.popupY = state.colorY - 4.0F;
            state.paletteX = state.popupX + 7.0F;
            state.paletteY = state.popupY + 7.0F;
            state.paletteSize = 62.0F;
            state.hueX = state.paletteX + state.paletteSize + 5.0F;
            state.hueY = state.paletteY;
            state.hueWidth = 7.0F;
            state.hueHeight = state.paletteSize;
        } else if (state.popupWidth <= 0.0F || state.popupHeight <= 0.0F) {
            return;
        }

 
        Render2D.rect(state.popupX, state.popupY, state.popupWidth, state.popupHeight, 5,
                context.withAlpha(new Color(125, 125, 125, 220), animation),
                context.withAlpha(new Color(125, 125, 125, 220), animation),
                context.withAlpha(new Color(115, 115, 115, 220), animation),
                context.withAlpha(new Color(115, 115, 115, 220), animation));

        Render2D.outline(
                state.popupX,
                state.popupY,
                state.popupWidth,
                state.popupHeight,
                5,
                0.35F,
                context.withAlpha(new Color(255, 255, 255, 90), animation),
                context.withAlpha(new Color(255, 255, 255, 45), animation),
                context.withAlpha(new Color(255, 255, 255, 90), animation),
                context.withAlpha(new Color(255, 255, 255, 45), animation)
        );

        int hueColor = Color.HSBtoRGB(state.hue, 1.0F, 1.0F);
        Render2D.rect(
                state.paletteX,
                state.paletteY,
                state.paletteSize,
                state.paletteSize,
                4,
                colorWithAlpha(255, 255, 255, animation),
                colorWithAlpha(SettingCardContext.red(hueColor), SettingCardContext.green(hueColor), SettingCardContext.blue(hueColor), animation),
                colorWithAlpha(0, 0, 0, animation),
                colorWithAlpha(0, 0, 0, animation)
        );

        float selectorX = state.paletteX + state.saturation * state.paletteSize;
        float selectorY = state.paletteY + (1.0F - state.brightness) * state.paletteSize;
        Render2D.outline(selectorX - 2.5F, selectorY - 2.5F, 5.0F, 5.0F, 1.5F, 1.0F, context.withAlpha(new Color(255, 255, 255, 245), animation));

        int[] hueColors = {
                Color.HSBtoRGB(0.0F, 1.0F, 1.0F),
                Color.HSBtoRGB(1.0F / 6.0F, 1.0F, 1.0F),
                Color.HSBtoRGB(2.0F / 6.0F, 1.0F, 1.0F),
                Color.HSBtoRGB(3.0F / 6.0F, 1.0F, 1.0F),
                Color.HSBtoRGB(4.0F / 6.0F, 1.0F, 1.0F),
                Color.HSBtoRGB(5.0F / 6.0F, 1.0F, 1.0F),
                Color.HSBtoRGB(1.0F, 1.0F, 1.0F)
        };
        float segmentHeight = state.hueHeight / (hueColors.length - 1);
        for (int i = 0; i < hueColors.length - 1; i++) {
            int top = hueColors[i];
            int bottom = hueColors[i + 1];
            float segmentY = state.hueY + i * segmentHeight;
            Render2D.rect(
                    state.hueX,
                    segmentY,
                    state.hueWidth,
                    segmentHeight + 0.8F,
                    i == 0 ? 1.5F : 0.0F,
                    i == 0 ? 1.5F : 0.0F,
                    i == hueColors.length - 2 ? 1.5F : 0.0F,
                    i == hueColors.length - 2 ? 1.5F : 0.0F,
                    colorWithAlpha(SettingCardContext.red(top), SettingCardContext.green(top), SettingCardContext.blue(top), animation),
                    colorWithAlpha(SettingCardContext.red(top), SettingCardContext.green(top), SettingCardContext.blue(top), animation),
                    colorWithAlpha(SettingCardContext.red(bottom), SettingCardContext.green(bottom), SettingCardContext.blue(bottom), animation),
                    colorWithAlpha(SettingCardContext.red(bottom), SettingCardContext.green(bottom), SettingCardContext.blue(bottom), animation)
            );
        }
        Render2D.rect(state.hueX - 1.0F, state.hueY + state.hue * state.hueHeight - 1.0F, state.hueWidth + 2.0F, 2.0F, 0.0F, colorWithAlpha(255, 255, 255, animation));
    }

    @Override
    boolean mouseClickedOverlay(MouseButtonEvent event, SettingCardContext context) {
        if (context.activeColor != setting || event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (!state.visible) {
            context.activeColor = null;
            return false;
        }
        if (context.inside(event.x(), event.y(), state.paletteX, state.paletteY, state.paletteSize, state.paletteSize)) {
            context.draggingColor = setting;
            context.draggingColorPart = 1;
            updateValue(context, event.x(), event.y(), 1);
            return true;
        }
        if (context.inside(event.x(), event.y(), state.hueX, state.hueY, state.hueWidth, state.hueHeight)) {
            context.draggingColor = setting;
            context.draggingColorPart = 2;
            updateValue(context, event.x(), event.y(), 2);
            return true;
        }
        if (!context.inside(event.x(), event.y(), state.colorX, state.colorY, state.colorWidth, state.colorHeight)) {
            context.activeColor = null;
        }
        return false;
    }

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubled, SettingCardContext context) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !context.inside(event.x(), event.y(), state.boxX, state.boxY, state.boxWidth, state.boxHeight)) {
            return false;
        }
        context.activeColor = context.activeColor == setting ? null : setting;
        return true;
    }

    @Override
    boolean mouseReleased(MouseButtonEvent event, SettingCardContext context) {
        if (context.draggingColor != setting) {
            return false;
        }
        context.draggingColor = null;
        context.draggingColorPart = 0;
        return true;
    }

    @Override
    boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY, SettingCardContext context) {
        if (context.draggingColor != setting) {
            return false;
        }
        updateValue(context, event.x(), event.y(), context.draggingColorPart);
        return true;
    }

    private void updateValue(SettingCardContext context, double mouseX, double mouseY, int part) {
        if (part == 1) {
            state.saturation = context.clamp((float) ((mouseX - state.paletteX) / state.paletteSize), 0.0F, 1.0F);
            state.brightness = 1.0F - context.clamp((float) ((mouseY - state.paletteY) / state.paletteSize), 0.0F, 1.0F);
        } else if (part == 2) {
            state.hue = context.clamp((float) ((mouseY - state.hueY) / state.hueHeight), 0.0F, 1.0F);
        }
        int rgb = Color.HSBtoRGB(state.hue, state.saturation, state.brightness);
        setting.setValue(new Color(SettingCardContext.red(rgb), SettingCardContext.green(rgb), SettingCardContext.blue(rgb), setting.getValue().getAlpha()));
        state.colorSynced = true;
    }

    private void updateStateFromSetting(SettingCardContext context) {
        if (context.draggingColor == setting) {
            return;
        }
        Color color = setting.getValue();
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        state.hue = hsb[0];
        state.saturation = hsb[1];
        state.brightness = hsb[2];
        state.colorSynced = true;
    }

    private float height(int descriptionLineCount) {
        if (descriptionLineCount <= 0) {
            return 18.0F;
        }
        return 17.0F + (descriptionLineCount - 1) * 5.5F;
    }

    private int colorWithAlpha(int red, int green, int blue, float alpha) {
        return new Color(red, green, blue, Math.max(0, Math.min(255, Math.round(255.0F * alpha)))).getRGB();
    }
}
