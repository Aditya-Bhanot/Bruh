package releon.ru.screens.modernui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.utils.math.MathUtils;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;

final class BindSettingCard extends SettingCardComponent<BindSetting> {
    BindSettingCard(BindSetting setting) {
        super(setting);
    }

    @Override
    int order() {
        return 5;
    }

    @Override
    float height(SettingCardContext context) {
        return 15.5F + descriptionOffset(context, 92.0F);
    }

    @Override
    void render(GuiGraphics graphics, SettingCardContext context, float cardX, float y, int mouseX, int mouseY) {
        markRow(cardX, y, height(context));
        float boxX = cardX + 117.0F;
        float boxY = y + 1.5f;
        float boxWidth = 22.5f;
        float boxHeight = 12.5f;
        state.box(boxX, boxY, boxWidth, boxHeight);

        renderNameAndDescription(graphics, context, cardX + 10.0F, y, 92.0F, 92.0F);

        Render2D.rect(boxX, boxY, boxWidth, boxHeight, 2f,
                context.color(185, 185, 185, 50),
                context.color(185, 185, 185, 50),
                context.color(185, 185, 185, 50),
                context.color(185, 185, 185, 50));

        Render2D.outline(
                boxX, boxY, boxWidth, boxHeight, 2f,
                0.15f,
                context.color(255, 255, 255, 255),
                context.color(255, 255, 255, 50),
                context.color(255, 255, 255, 255),
                context.color(255, 255, 255, 50)
        );

        boolean bound = setting.getValue() != null && setting.getValue().isBound();
        String text = context.listeningBind == setting ? "..." : bound ? MathUtils.shortBind(setting.getValue()) : "n/a";
        int textColor = bound || context.listeningBind == setting
                ? context.color(225, 225, 225, 255)
                : context.color(128, 128, 128, 255);
        float textWidth = Render2D.textWidth(FontType.SEMIBOLD, text, 6);
        Render2D.text(FontType.SEMIBOLD, text, boxX + (boxWidth - textWidth) * 0.5F, y + 4.5F, 6, textColor);
        Render2D.text(FontType.GUI_ICONS, "L", boxX - 10, y + 4F, 9, context.color(128, 128, 128, 255));
    }

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubled, SettingCardContext context) {
        if (!context.inside(event.x(), event.y(), state.boxX, state.boxY, state.boxWidth, state.boxHeight)) {
            return false;
        }
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            setting.setValue(KeyBind.NONE);
        } else {
            context.listeningBind = setting;
        }
        context.activeColor = null;
        return true;
    }
}
