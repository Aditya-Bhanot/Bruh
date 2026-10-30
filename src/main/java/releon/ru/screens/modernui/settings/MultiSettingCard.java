package releon.ru.screens.modernui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.utils.render.animation.modernfx.Direction;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;
import java.util.List;
import java.util.StringJoiner;

final class MultiSettingCard extends SettingCardComponent<MultiModeSetting> {
    MultiSettingCard(MultiModeSetting setting) {
        super(setting);
    }

    @Override
    int order() {
        return 1;
    }

    @Override
    float height(SettingCardContext context) {
        state.expandAnimation.setDirection(state.expanded ? Direction.FORWARDS : Direction.BACKWARDS);
        return 34.0F + descriptionOffset(context, 110.0F) + setting.getModes().size() * 15.0F * context.clamp(state.expandAnimation.getOutput().floatValue(), 0.0F, 1.0F);
    }

    @Override
    float layoutHeight(SettingCardContext context) {
        return 34.0F + descriptionOffset(context, 110.0F);
    }

    @Override
    void render(GuiGraphics graphics, SettingCardContext context, float cardX, float y, int mouseX, int mouseY) {
        markRow(cardX, y, height(context));
        state.expandAnimation.setDirection(state.expanded ? Direction.FORWARDS : Direction.BACKWARDS);
        List<String> descriptionLines = descriptionLines(context, 110.0F);
        float descriptionOffset = descriptionOffset(descriptionLines.size());
        float boxX = cardX + 8.0F;
        float boxY = y + 10.0F + descriptionOffset;
        float boxWidth = 132.0F;
        float boxHeight = 17.0F;
        state.box(boxX, boxY, boxWidth, boxHeight);

        context.renderClippedText(graphics, FontType.SEMIBOLD, setting.getName(), cardX + 10.0F, y, 110.0F, 6, new Color(225, 225, 225, 255).getRGB());
        String count = setting.selectedCount() + "/" + setting.getModes().size();
        Render2D.text(FontType.SEMIBOLD, count, cardX + 127.0F, y + 1.5f, 6, context.color(225, 225, 225, 255));
        if (!descriptionLines.isEmpty()) {
            context.renderWrappedText(graphics, FontType.SEMIBOLD, descriptionLines, cardX + 10.0F, y + 8.5F, 110.0F, 5F, 5.5F, new Color(145, 145, 145, 190).getRGB());
        }

        Render2D.rect(boxX, boxY, boxWidth, boxHeight, 4,
                context.color(125, 126, 123, 50),
                context.color(115, 116, 113, 50),
                context.color(115, 116, 113, 50),
                context.color(125, 126, 123, 50));

        Render2D.outline(
                boxX, boxY, boxWidth, boxHeight, 4,
                0.15f,
                context.color(255, 255, 255, 255),
                context.color(255, 255, 255, 50),
                context.color(255, 255, 255, 255),
                context.color(255, 255, 255, 50)
        );

        Render2D.pushScissor(graphics, boxX, boxY, boxWidth - 15, boxHeight);

        context.renderClippedText(graphics, FontType.SEMIBOLD, valueText(), boxX + 14.0F, boxY + 5.5F, boxWidth - 31.0F, 5.5f, new Color(255, 255, 255, 255).getRGB());

        Render2D.popScissor(graphics);

        float arrowAnimation = context.clamp(state.expandAnimation.getOutput().floatValue(), 0.0F, 1.0F);
        float arrowX = boxX + 120.0F;
        float arrowY = boxY + 6.0F;
        float arrowSize = 6.0F;
        float arrowWidth = Render2D.textWidth(FontType.MAINMENUSCREEN, "0", arrowSize);
        Render2D.text(
                FontType.MAINMENUSCREEN,
                "0",
                arrowX,
                arrowY,
                arrowSize,
                context.color(161, 162, 167, 255),
                180.0F * arrowAnimation,
                arrowX + arrowWidth * 0.5F,
                arrowY + arrowSize * 0.5F
        );
        Render2D.text(FontType.CATEGORY_ICONS, "l", boxX + 4, boxY + 4.5F, 8, context.color(161, 162, 167, 255));  }

    @Override
    void renderOverlay(GuiGraphics graphics, SettingCardContext context) {
        float animation = context.clamp(state.expandAnimation.getOutput().floatValue(), 0.0F, 1.0F);
        if (!state.visible || animation <= 0.01F) {
            return;
        }
        float optionHeight = 15.0F;
        float popupHeight = setting.getModes().size() * optionHeight * animation;
        float popupY = state.boxY + state.boxHeight + 1.0F;
        state.options(state.boxX, popupY, state.boxWidth, optionHeight, setting.getModes().size());

        Render2D.rect(state.boxX, popupY, state.boxWidth, popupHeight, 5,
                context.color(125, 126, 123, 50),
                context.color(115, 116, 113, 50),
                context.color(115, 116, 113, 50),
                context.color(125, 126, 123, 50));

        Render2D.outline(
                state.boxX, popupY, state.boxWidth, popupHeight, 5,
                0.15f,
                context.color(255, 255, 255, 255),
                context.color(255, 255, 255, 50),
                context.color(255, 255, 255, 255),
                context.color(255, 255, 255, 50)
        );

        Render2D.pushScissor(graphics, state.boxX, popupY, state.boxWidth, popupHeight);
        float y = popupY;
        for (String mode : setting.getModes()) {
            boolean selected = setting.isSelected(mode);
            int alpha = Math.round((selected ? 255.0F : 155.0F) * animation);
            context.renderClippedText(graphics, FontType.SEMIBOLD, mode, state.boxX + 14.0F, y + 5.5F, state.boxWidth - 23.0F, 5.5f, new Color(245, 245, 245, alpha).getRGB());
            Render2D.text(FontType.CATEGORY_ICONS, "l", state.boxX + 4.0F, y + 4.5F, 8, context.color(245, 245, 245, alpha));

            if (setting.isSelected(mode)) {
                Render2D.text(FontType.MAINMENUSCREEN, "F", state.boxX + 120.0F, y + 4.5F, 6, context.color(245, 245, 245, alpha));
            }

            y += optionHeight;
        }
        Render2D.popScissor(graphics);
    }

    @Override
    boolean mouseClickedOverlay(MouseButtonEvent event, SettingCardContext context) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !state.visible || !state.expanded) {
            return false;
        }
        if (!context.inside(event.x(), event.y(), state.optionX, state.optionY, state.optionWidth, state.optionHeight * state.optionCount)) {
            return false;
        }
        int index = (int) ((event.y() - state.optionY) / state.optionHeight);
        if (index >= 0 && index < setting.getModes().size()) {
            String mode = setting.getModes().get(index);
            setting.setSelected(mode, !setting.isSelected(mode));
            return true;
        }
        return false;
    }

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubled, SettingCardContext context) {
        if (context.inside(event.x(), event.y(), state.boxX, state.boxY, state.boxWidth, state.boxHeight)) {
            state.expanded = !state.expanded;
            context.activeColor = null;
            return true;
        }
        return false;
    }

    private String valueText() {
        if (setting.selectedCount() <= 0) {
            return "None";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (String mode : setting.getModes()) {
            if (setting.isSelected(mode)) {
                joiner.add(mode);
            }
        }
        return joiner.toString();
    }

    @Override
    protected boolean hasDescription() {
        return setting.shouldRenderDescription() && super.hasDescription();
    }
}
