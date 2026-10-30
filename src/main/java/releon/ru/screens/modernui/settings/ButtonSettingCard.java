package releon.ru.screens.modernui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.impl.ButtonSetting;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;

final class ButtonSettingCard extends SettingCardComponent<ButtonSetting> {
    ButtonSettingCard(ButtonSetting setting) {
        super(setting);
    }

    @Override
    int order() {
        return 7;
    }

    @Override
    float height(SettingCardContext context) {
        return 15.5F + descriptionOffset(context, 96.0F);
    }

    @Override
    void render(GuiGraphics graphics, SettingCardContext context, float cardX, float y, int mouseX, int mouseY) {
        markRow(cardX, y, height(context));
        float boxX = cardX + 111.0F;
        float boxY = y - 2.5F;
        float boxWidth = 28.5f;
        float boxHeight = 12.5f;
        state.box(boxX, boxY, boxWidth, boxHeight);

        renderNameAndDescription(graphics, context, cardX + 10.0F, y, 96.0F, 96.0F);
        Render2D.rect(boxX, boxY, boxWidth, boxHeight, 2f,
                context.color(185, 185, 185, 50),
                context.color(185, 185, 185, 50),
                context.color(185, 185, 185, 50),
                context.color(185, 185, 185, 50));
        float textWidth = Render2D.textWidth(FontType.SEMIBOLD, "Click", 6);
        Render2D.text(FontType.SEMIBOLD, "Click", boxX + (boxWidth - textWidth) * 0.5F, y + 2.5F, 6, context.color(225, 225, 225, 255));
    }

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubled, SettingCardContext context) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !context.inside(event.x(), event.y(), state.boxX, state.boxY, state.boxWidth, state.boxHeight)) {
            return false;
        }
        setting.press();
        context.activeColor = null;
        return true;
    }
}
