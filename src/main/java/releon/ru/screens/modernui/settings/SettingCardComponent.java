package releon.ru.screens.modernui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.Setting;
import releon.ru.utils.render.animation.modernfx.Direction;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;
import java.util.List;

abstract class SettingCardComponent<T extends Setting<?>> {
    protected final T setting;
    protected final SettingCardState state;

    SettingCardComponent(T setting) {
        this.setting = setting;
        this.state = new SettingCardState(setting);
    }

    T setting() {
        return setting;
    }

    SettingCardState state() {
        return state;
    }

    boolean visible() {
        return setting.isVisible();
    }

    void hide() {
        state.visible = false;
    }

    void updateVisibility() {
        state.visibilityAnimation.setDirection(setting.isVisible() ? Direction.FORWARDS : Direction.BACKWARDS);
        state.visibilityProgress = Math.max(0.0F, Math.min(1.0F, state.visibilityAnimation.getOutput().floatValue()));
    }

    boolean alive() {
        return setting.isVisible() || state.visibilityProgress > 0.01F;
    }

    abstract int order();

    abstract float height(SettingCardContext context);

    float layoutHeight(SettingCardContext context) {
        return height(context);
    }

    float animatedHeight(SettingCardContext context) {
        return height(context) * state.visibilityProgress;
    }

    float animatedLayoutHeight(SettingCardContext context) {
        return layoutHeight(context) * state.visibilityProgress;
    }

    void renderAnimated(GuiGraphics graphics, SettingCardContext context, float cardX, float y, int mouseX, int mouseY) {
        float progress = state.visibilityProgress;
        if (progress <= 0.01F) {
            return;
        }
        float height = height(context);
        float visibleHeight = Math.max(0.0F, height * progress);
        float offsetY = (1.0F - progress) * -5.0F;
        Render2D.pushScissor(graphics, cardX, y - 4.0F, 150.0F, visibleHeight + 8.0F);
        float previousAlpha = context.visualAlpha();
        context.setVisualAlpha(previousAlpha * progress);
        render(graphics, context, cardX, y + offsetY, mouseX, mouseY);
        context.setVisualAlpha(previousAlpha);
        Render2D.popScissor(graphics);
        state.visible = setting.isVisible() && progress > 0.55F;
    }

    abstract void render(GuiGraphics graphics, SettingCardContext context, float cardX, float y, int mouseX, int mouseY);

    void renderOverlay(GuiGraphics graphics, SettingCardContext context) {
    }

    void renderFloatingOverlay(GuiGraphics graphics, SettingCardContext context) {
    }

    boolean mouseClickedOverlay(MouseButtonEvent event, SettingCardContext context) {
        return false;
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubled, SettingCardContext context) {
        return false;
    }

    boolean mouseReleased(MouseButtonEvent event, SettingCardContext context) {
        return false;
    }

    boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY, SettingCardContext context) {
        return false;
    }

    boolean keyPressed(KeyEvent event, SettingCardContext context) {
        return false;
    }

    boolean charTyped(CharacterEvent event, SettingCardContext context) {
        return false;
    }

    protected void markRow(float cardX, float y, float height) {
        state.row(cardX + 8.0F, y, 132.0F, height);
    }

    protected boolean hasDescription() {
        return !setting.getDescription().isBlank();
    }

    protected List<String> descriptionLines(SettingCardContext context, float width) {
        return hasDescription() ? context.wrapText(FontType.SEMIBOLD, setting.getDescription(), 5.0F, width, 2) : List.of();
    }

    protected float descriptionOffset(SettingCardContext context, float width) {
        return descriptionOffset(descriptionLines(context, width).size());
    }

    protected float descriptionOffset(int lineCount) {
        return lineCount <= 0 ? 0.0F : 7.0F + (lineCount - 1) * 5.5F;
    }

    protected void renderNameAndDescription(GuiGraphics graphics, SettingCardContext context, float x, float y, float titleWidth, float descriptionWidth) {
        context.renderClippedText(graphics, FontType.SEMIBOLD, setting.getName(), x, y, titleWidth, 6.0F, new Color(225, 225, 225, 255).getRGB());
        List<String> lines = descriptionLines(context, descriptionWidth);
        if (!lines.isEmpty()) {
            context.renderWrappedText(graphics, FontType.SEMIBOLD, lines, x, y + 8.5F, descriptionWidth, 5.0F, 5.5F, new Color(145, 145, 145, 190).getRGB());
        }
    }
}
