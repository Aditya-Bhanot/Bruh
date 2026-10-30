package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.Setting;
import releon.ru.screens.clickgui.impl.ClickGuiController;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

public abstract class ClickGuiOption {
    protected static final float HEIGHT = 22.0f;

    private final String name;
    protected float x;
    protected float y;
    protected float width;
    protected float height;
    protected float scale;
    protected float alpha;
    protected float animationAlpha;
    private final SmoothAnimation visibilityAnimation = new SmoothAnimation();
    private final Map<String, SmoothAnimation> textScrollAnimations = new HashMap<>();
    private BooleanSupplier visibleSupplier = () -> true;
    private float fadeMultiplier = 1.0f;
    private boolean interactiveThisFrame;

    protected ClickGuiOption(String name) {
        this.name = name;
        visibilityAnimation.set(1.0);
    }

    public float getHeight() {
        return HEIGHT;
    }

    public final void render(GuiGraphics graphics, float x, float y, float width, float scale, float alpha) {
        float sw = width * scale;
        float sh = HEIGHT * scale;
        this.x = x;
        this.y = y;
        this.width = sw;
        this.height = sh;
        this.scale = scale;
        this.animationAlpha = alpha;
        this.alpha = alpha * fadeMultiplier;
        this.interactiveThisFrame = this.alpha > 0.01f && sw > 0.0f && sh > 0.0f;

        renderControl(graphics, x, y, sw, sh, scale, this.alpha);
    }

    public void resetInteractionFrame() {
        interactiveThisFrame = false;
    }

    public void setSetting(Setting<?> setting) {
        if (setting == null) {
            setVisibleSupplier(null);
            return;
        }
        setVisibleSupplier(setting::isVisible);
    }

    public void setVisibleSupplier(BooleanSupplier visibleSupplier) {
        this.visibleSupplier = visibleSupplier == null ? () -> true : visibleSupplier;
        visibilityAnimation.set(this.visibleSupplier.getAsBoolean() ? 1.0 : 0.0);
    }

    public void updateVisibility() {
        boolean visible = visibleSupplier.getAsBoolean();
        visibilityAnimation.run(visible ? 1.0 : 0.0, 0.20, Easings.CUBIC_OUT, true);
        visibilityAnimation.update();
        if (!visible) {
            closePopup();
        }
    }

    public float visibilityProgress() {
        return clamp(visibilityAnimation.get(), 0.0f, 1.0f);
    }

    public boolean isVisibleForRender() {
        return visibilityProgress() > 0.01f;
    }

    public boolean isVisibleForInteraction() {
        return interactiveThisFrame && visibleSupplier.getAsBoolean() && visibilityProgress() > 0.85f;
    }

    public void renderOverlay(GuiGraphics graphics) {
    }

    public void setFadeMultiplier(float fadeMultiplier) {
        this.fadeMultiplier = clamp(fadeMultiplier, 0.0f, 1.0f);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return false;
    }

    public boolean wantsMousePriority(MouseButtonEvent event) {
        return false;
    }

    public boolean hasOpenPopup() {
        return false;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        return false;
    }

    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        return false;
    }

    public void closePopup() {
    }

    protected abstract void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha);

    protected String name() {
        return name;
    }

    protected boolean hovered(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    protected float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    protected int mixColor(int fromRed, int fromGreen, int fromBlue, int toRed, int toGreen, int toBlue, float progress, float alphaMultiplier) {
        float clamped = clamp(progress, 0.0f, 1.0f);
        int red = Math.round(fromRed + (toRed - fromRed) * clamped);
        int green = Math.round(fromGreen + (toGreen - fromGreen) * clamped);
        int blue = Math.round(fromBlue + (toBlue - fromBlue) * clamped);
        return color(red, green, blue, 255, alphaMultiplier);
    }

    protected float textWidth(String text, float size) {
        return Render2D.textWidth(FontType.SEMIBOLD, text, size);
    }

    protected int labelColor() {
        float visibleAlpha = alpha >= 0.98f ? 1.0f : alpha;
        return ColorUtil.rgba(255, 255, 255, Math.round(255.0f * visibleAlpha));
    }

    protected int iconColor() {
        float visibleAlpha = alpha >= 0.98f ? 1.0f : alpha;
        return ColorUtil.rgba(175, 175, 175, Math.round(255.0f * visibleAlpha));
    }

    protected void renderGuiIcon(String icon, float x, float y, float size, float scale) {
        Render2D.text(FontType.GUI_ICONS, icon, x, y, size * scale, iconColor());
    }

    protected void renderOptionLabel(GuiGraphics graphics, String key, float textX, float textY, float textSize, float clipRight, float rowY, float rowHeight) {
        float clipWidth = Math.max(0.0f, clipRight - textX);
        renderScrollingText(graphics, "label:" + key, name(), textX, textY, textSize, labelColor(), textX, rowY, clipWidth, rowHeight, textX, rowY, clipWidth, rowHeight);
    }

    protected void renderScrollingText(
            GuiGraphics graphics,
            String key,
            String text,
            float textX,
            float textY,
            float textSize,
            int textColor,
            float clipX,
            float clipY,
            float clipWidth,
            float clipHeight,
            float hoverX,
            float hoverY,
            float hoverWidth,
            float hoverHeight
    ) {
        if (text == null || text.isEmpty() || clipWidth <= 0.25f || clipHeight <= 0.25f) {
            return;
        }

        float textWidth = textWidth(text, textSize);
        float overflow = Math.max(0.0f, textWidth - clipWidth + 3.0f * scale);
        SmoothAnimation scrollAnimation = textScrollAnimations.computeIfAbsent(key, ignored -> {
            SmoothAnimation animation = new SmoothAnimation();
            animation.set(0.0);
            return animation;
        });

        boolean hover = overflow > 0.25f && hovered(ClickGuiController.mouseX(), ClickGuiController.mouseY(), hoverX, hoverY, hoverWidth, hoverHeight);
        float target = 0.0f;
        if (hover) {
            long segment = Math.max(950L, 1200L + Math.round(overflow * 18.0f));
            target = ((System.currentTimeMillis() / segment) & 1L) == 0L ? -overflow : 0.0f;
        }
        scrollAnimation.run(target, hover ? 0.55 : 0.22, Easings.CUBIC_OUT, true);
        scrollAnimation.update();

        float scroll = scrollAnimation.get();
        float fadeWidth = Math.min(10.0f * scale, Math.max(2.0f, clipWidth * 0.35f));
        float leftFade = overflow > 0.25f && scroll < -0.4f ? 0.78f : 0.0f;
        float rightFade = overflow > 0.25f && scroll > -overflow + 0.4f ? 0.88f : 0.0f;

        Render2D.pushScissor(graphics, clipX, clipY, clipWidth, clipHeight);
        if (leftFade > 0.0f || rightFade > 0.0f) {
            Render2D.textFade(FontType.SEMIBOLD, text, textX + scroll, textY, textSize, textColor, clipX, clipX + clipWidth, fadeWidth, leftFade, rightFade);
        } else {
            Render2D.text(FontType.SEMIBOLD, text, textX + scroll, textY, textSize, textColor);
        }
        Render2D.popScissor(graphics);
    }

    protected void renderGlassControl(float x, float y, float width, float height, float radius, float alphaMultiplier) {
        Render2D.glass(x, y, width, height, radius, color(255, 255, 255, 72, alphaMultiplier), 0.64f * alphaMultiplier, 42.0f, color(255, 255, 255, 150, alphaMultiplier), 0.16f * alphaMultiplier, true, 0.22f, 0.003f, 1.0f, 0.0f);
        Render2D.glassOutline(x, y, width, height, radius, 0.5f, color(255, 255, 255, 176, alphaMultiplier), 1.0f, 35.0f, color(168, 168, 174, 118, alphaMultiplier), 0.42f * alphaMultiplier, false, 0.45f, 0.0015f, 1.0f, 0.0f);
    }

    protected int color(int red, int green, int blue, int alpha, float alphaMultiplier) {
        return ColorUtil.rgba(red, green, blue, (int) (alpha * alphaMultiplier));
    }
}
