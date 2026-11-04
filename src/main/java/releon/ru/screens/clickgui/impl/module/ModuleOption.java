package releon.ru.screens.clickgui.impl.module;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.screens.clickgui.impl.options.ClickGuiOption;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.List;

public class ModuleOption {
    private static final float OPTION_GAP = 1.0f;
    private static final float AFTER_OPTIONS_GAP = - 2f;

    private final Module module;
    private final List<ClickGuiOption> settings;
    private final SmoothAnimation settingsAnimation = new SmoothAnimation();
    private final SmoothAnimation bindAnimation = new SmoothAnimation();
    private final SmoothAnimation enabledAnimation = new SmoothAnimation();
    private final BindSetting bindSetting;
    private boolean settingsExpanded;
    private boolean binding;
    private float settingsProgress;
    private float bindProgress;
    private float rowX;
    private float rowY;
    private float rowWidth;
    private float rowHeight;
    private float rowClickX;
    private float rowClickY;
    private float rowClickWidth;
    private float rowClickHeight;
    private boolean rowInteractive;

    public ModuleOption(Module module, List<ClickGuiOption> settings) {
        this.module = module;
        this.settings = settings == null ? List.of() : settings;
        this.bindSetting = module.getBindSetting();
        settingsAnimation.set(0.0);
        bindAnimation.set(0.0);
        enabledAnimation.set(module.isEnabled() ? 1.0 : 0.0);
    }

    public void updateAnimation() {
        settingsAnimation.run(settingsExpanded ? 1.0 : 0.0, 0.24, Easings.CUBIC_OUT, true);
        settingsAnimation.update();
        settingsProgress = settingsAnimation.get();
        bindAnimation.run(binding ? 1.0 : 0.0, 0.18, Easings.CUBIC_OUT, true);
        bindAnimation.update();
        bindProgress = bindAnimation.get();
        enabledAnimation.run(module.isEnabled() ? 1.0 : 0.0, 0.22, Easings.CUBIC_OUT, true);
        enabledAnimation.update();
        if (this.module.isExpanded()) {
        for (ClickGuiOption option : settings) {
            option.updateVisibility();
        }
        }
    }

    public void renderRow(float panelX, float rowY, float panelWidth, float rowHeight, float scale, float alpha) {
        this.rowX = panelX;
        this.rowY = rowY;
        this.rowWidth = panelWidth;
        this.rowHeight = rowHeight * scale;
        this.rowInteractive = alpha > 0.01f && panelWidth > 0.0f && this.rowHeight > 0.0f;

        float enabledProgress = enabledAnimation.get();
        int moduleColor = Math.round(185.0f + 70.0f * enabledProgress);
        int textColor = color(moduleColor, moduleColor, moduleColor, (int) (255 * (1.0f - bindProgress)), alpha);
        float moduleTextX = panelX + 11.0f * scale;
        float moduleTextY = rowY + 5.3f * scale;
        float moduleTextWidth = Render2D.textWidth(FontType.SEMIBOLD, module.getDisplayHeaderName(), 8.0f);
        float clickPaddingY = 3.0f * scale;
        this.rowClickX = moduleTextX;
        this.rowClickY = moduleTextY - clickPaddingY;
        this.rowClickWidth = moduleTextWidth;
        this.rowClickHeight = 8.0f + clickPaddingY * 2.0f;
        Render2D.rect(panelX + 7.5f * scale, rowY + 6.5f * scale, 1.0f * scale, 8.0f * scale, 0, color(moduleColor, moduleColor, moduleColor, (int) (50 * (1.0f - bindProgress)), alpha));
        Render2D.text(FontType.SEMIBOLD, module.getDisplayHeaderName(), moduleTextX, moduleTextY, 8.0f, textColor);

        if (hasSettings()) {
            String dots = "...";
            float dotsSize = 9.0f;
            float dotsWidth = Render2D.textWidth(FontType.SEMIBOLD, dots, dotsSize);
            Render2D.text(FontType.SEMIBOLD, dots, panelX + panelWidth - dotsWidth - 14.0f * scale, rowY + 2.5f * scale, dotsSize, color(255, 255, 255, 210, alpha));
        }
        if (bindProgress > 0.01f) {
            String bindText = bindText();
            float bindSize = 8.0f;
            Render2D.text(FontType.SEMIBOLD, bindText, moduleTextX, moduleTextY, bindSize, color(255, 255, 255, (int) (255 * bindProgress), alpha));
        }
    }

    public float renderSettings(GuiGraphics graphics, float panelX, float settingsTop, float width, float scale, float alpha, float clipBottom) {
        float animatedHeight = renderSettingsBackground(panelX, settingsTop, width, scale, alpha, clipBottom);
        renderSettingsContent(graphics, panelX, settingsTop, width, scale, alpha, clipBottom);
        return animatedHeight;
    }

    public float renderSettingsBackground(float panelX, float settingsTop, float width, float scale, float alpha, float clipBottom) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return 0.0f;
        }

        float settingsX = panelX + 5.0f * scale;
        float settingsY = settingsTop + 2.0f * scale;
        float settingsWidth = width - 10.0f * scale;
        float settingsHeight = computeSettingsHeight();
        if (settingsHeight <= 0.01f) {
            return 0.0f;
        }
        float settingsFullHeight = settingsHeight * scale;
        float settingsVisibleHeight = settingsFullHeight * settingsProgress;
        float settingsAlpha = alpha * settingsProgress;
        float clippedHeight = Math.max(0.0f, Math.min(settingsVisibleHeight, clipBottom - settingsY));
        float animatedHeight = (settingsHeight + AFTER_OPTIONS_GAP + 2.0f) * scale * settingsProgress;
        if (clippedHeight <= 0.0f) {
            return animatedHeight;
        }

        Render2D.rect(settingsX, settingsY, settingsWidth, clippedHeight, 4, color(128, 128, 128, (int) (25 * (1.0f - bindProgress)), alpha));

        return animatedHeight;
    }

    public void renderSettingsContent(GuiGraphics graphics, float panelX, float settingsTop, float width, float scale, float alpha, float clipBottom) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return;
        }

        float settingsX = panelX + 5.0f * scale;
        float settingsY = settingsTop + 2.0f * scale;
        float settingsWidth = width - 10.0f * scale;
        float settingsHeight = computeSettingsHeight();
        if (settingsHeight <= 0.01f) {
            return;
        }
        float settingsFullHeight = settingsHeight * scale;
        float settingsVisibleHeight = settingsFullHeight * settingsProgress;
        float clippedHeight = Math.max(0.0f, Math.min(settingsVisibleHeight, clipBottom - settingsY));
        if (clippedHeight <= 0.0f) {
            return;
        }

        Render2D.pushScissor(graphics, settingsX, settingsY, settingsWidth, clippedHeight);
        try {
            float optionY = settingsY;
            if (this.module.isExpanded()) {
            for (ClickGuiOption option : settings) {
                float visibility = option.visibilityProgress();
                float slotHeight = (option.getHeight() + OPTION_GAP) * scale;
                if (visibility > 0.01f) {
                    option.setFadeMultiplier(settingsProgress * visibility);
                    float renderY = optionY - (1.0f - visibility) * 5.0f * scale;
                    option.render(graphics, panelX + 6.0f * scale, renderY, width / scale - 12.0f, scale, alpha);
                }
                optionY += slotHeight * visibility;
            }
            }
        } finally {
            Render2D.popScissor(graphics);
        }
    }

    public void renderOverlay(GuiGraphics graphics) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return;
        }
        if (this.module.isExpanded()) {
        for (ClickGuiOption option : settings) {
            float visibility = option.visibilityProgress();
            if (visibility <= 0.01f) {
                continue;
            }
            option.setFadeMultiplier(settingsProgress * visibility);
            option.renderOverlay(graphics);
        }
        }
    }

    public void beginInteractionFrame() {
        rowInteractive = false;
        rowClickX = 0.0f;
        rowClickY = 0.0f;
        rowClickWidth = 0.0f;
        rowClickHeight = 0.0f;
        if (this.module.isExpanded()) {
        for (ClickGuiOption option : settings) {
            option.resetInteractionFrame();
        }
        }
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() == 1 && isRowHovered(event.x(), event.y())) {
            this.module.toggleExpanded();
            return true;
        }
        if (mouseClickedSettings(event, doubled)) {
            return true;
        }
        return mouseClickedRow(event, doubled);
    }

    public boolean mouseClickedRow(MouseButtonEvent event, boolean doubled) {
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && isRowHovered(event.x(), event.y())) {
            startBinding();
            return true;
        }
        if (binding) {
            if (event.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                setBind(KeyBind.mouse(event.button()));
            }
            return true;
        }
        if (event.button() == 1 && isRowHovered(event.x(), event.y())) {
            this.module.toggleExpanded();
            return true;
        }
        if (event.button() == 0 && isRowHovered(event.x(), event.y())) {
            module.toggle();
            return true;
        }
        if (event.button() == 1 && isRowHovered(event.x(), event.y()) && hasSettings()) {
            settingsExpanded = !settingsExpanded;
            settingsAnimation.run(settingsExpanded ? 1.0 : 0.0, 0.24, Easings.CUBIC_OUT);
            closePopupsExcept(null);
            return true;
        }
        return false;
    }

    public boolean mouseClickedSettings(MouseButtonEvent event, boolean doubled) {
        if (!hasSettings() || settingsProgress <= 0.85f) {
            return false;
        }

        ClickGuiOption handled = null;
        if (this.module.isExpanded()) {
        for (ClickGuiOption option : settings) {
            if (!option.isVisibleForInteraction()) {
                continue;
            }
            if (option.wantsMousePriority(event) && option.mouseClicked(event, doubled)) {
                handled = option;
                break;
            }
        }
        }
        if (handled == null) {
            if (this.module.isExpanded()) {
            for (int i = settings.size() - 1; i >= 0; i--) {
                ClickGuiOption option = settings.get(i);
                if (!option.isVisibleForInteraction()) {
                    continue;
                }
                if (option.mouseClicked(event, doubled)) {
                    handled = option;
                    break;
                }
            }
            }
        }
        if (handled != null) {
            closePopupsExcept(handled);
            return true;
        }

        closePopupsExcept(null);
        return false;
    }

    public boolean mouseClickedPopup(MouseButtonEvent event, boolean doubled) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return false;
        }

        boolean hasOpenPopup = false;
        ClickGuiOption handled = null;
        if (this.module.isExpanded()) {
        for (int i = settings.size() - 1; i >= 0; i--) {
            ClickGuiOption option = settings.get(i);
            if (!option.isVisibleForRender() || !option.hasOpenPopup()) {
                continue;
            }
            hasOpenPopup = true;
            if (option.wantsMousePriority(event) && option.mouseClicked(event, doubled)) {
                handled = option;
                break;
            }
        }
        }

        if (handled != null) {
            closePopupsExcept(handled);
            return true;
        }
        if (hasOpenPopup) {
            closePopupsExcept(null);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return false;
        }
        if (this.module.isExpanded()) {
        for (int i = settings.size() - 1; i >= 0; i--) {
            ClickGuiOption option = settings.get(i);
            if (!option.isVisibleForInteraction()) {
                continue;
            }
            if (option.mouseReleased(event)) {
                return true;
            }
        }
        }
        return false;
    }

    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return false;
        }
        if (this.module.isExpanded()) {
        for (int i = settings.size() - 1; i >= 0; i--) {
            ClickGuiOption option = settings.get(i);
            if (!option.isVisibleForInteraction()) {
                continue;
            }
            if (option.mouseDragged(event, dragX, dragY)) {
                return true;
            }
        }
        }
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        if (binding) {
            if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_ESCAPE) {
                setBind(KeyBind.NONE);
            } else {
                setBind(KeyBind.keyboard(event.key()));
            }
            return true;
        }
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return false;
        }
        if (this.module.isExpanded()) {
        for (int i = settings.size() - 1; i >= 0; i--) {
            ClickGuiOption option = settings.get(i);
            if (!option.isVisibleForInteraction()) {
                continue;
            }
            if (option.keyPressed(event)) {
                return true;
            }
        }
        }
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        if (!hasSettings() || settingsProgress <= 0.01f) {
            return false;
        }
        if (this.module.isExpanded()) {
        for (int i = settings.size() - 1; i >= 0; i--) {
            ClickGuiOption option = settings.get(i);
            if (!option.isVisibleForInteraction()) {
                continue;
            }
            if (option.charTyped(event)) {
                return true;
            }
        }
        }
        return false;
    }

    public float getAnimatedExtraHeight() {
        if (!hasSettings()) {
            return 0.0f;
        }
        float settingsHeight = computeSettingsHeight();
        if (settingsHeight <= 0.01f) {
            return 0.0f;
        }
        return (settingsHeight + AFTER_OPTIONS_GAP + 2.0f) * settingsProgress;
    }

    public boolean hasSettings() {
        return !settings.isEmpty();
    }

    public void revealSettings() {
        if (!hasSettings()) {
            return;
        }
        settingsExpanded = true;
        settingsAnimation.run(1.0, 0.18, Easings.CUBIC_OUT);
    }

    private float computeSettingsHeight() {
        float height = 0.0f;
        if (this.module.isExpanded()) {
        for (ClickGuiOption option : settings) {
            float visibility = option.visibilityProgress();
            if (visibility <= 0.01f) {
                continue;
            }
            height += (option.getHeight() + OPTION_GAP) * visibility;
        }
        }
        return height <= 0.01f ? 0.0f : height + 1.0f;
    }

    private boolean isRowHovered(double mouseX, double mouseY) {
        return rowInteractive
                && mouseX >= rowClickX
                && mouseX <= rowClickX + rowClickWidth
                && mouseY >= rowClickY
                && mouseY <= rowClickY + rowClickHeight;
    }

    private void startBinding() {
        binding = true;
        bindAnimation.run(1.0, 0.18, Easings.CUBIC_OUT);
        closePopupsExcept(null);
    }

    private void setBind(KeyBind bind) {
        bindSetting.setValue(bind);
        binding = false;
        bindAnimation.run(0.0, 0.18, Easings.CUBIC_OUT);
    }

    private String bindText() {
        if (!bindSetting.getValue().isBound()) {
            return "...";
        }
        return bindSetting.getValue().getDisplayName() + "...";
    }

    private void closePopupsExcept(ClickGuiOption keepOpen) {
        if (this.module.isExpanded()) {
        for (ClickGuiOption option : settings) {
            if (option != keepOpen) {
                option.closePopup();
            }
        }
        }
    }

    private int color(int red, int green, int blue, int alpha, float alphaMultiplier) {
        return ColorUtil.rgba(red, green, blue, (int) (alpha * alphaMultiplier));
    }
}
