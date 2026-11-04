package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.settings.impl.ButtonSetting;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public class ButtonOption extends ClickGuiOption {
    private final String action;
    private final SmoothAnimation scaleAnimation = new SmoothAnimation();
    private final ButtonSetting setting;
    private float controlX;
    private float controlY;
    private float controlWidth;
    private float controlHeight;

    public ButtonOption(ButtonSetting setting, String action) {
        super(setting.getName());
        this.setting = setting;
        this.action = action;
        scaleAnimation.set(1.0);
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        controlWidth = 39.0f * scale;
        controlHeight = 13.0f * scale;
        controlX = x + width - controlWidth - 6.0f * scale;
        controlY = y + 5.5f * scale;
        scaleAnimation.update();
        float visualScale = scaleAnimation.get();
        float renderWidth = controlWidth * visualScale;
        float renderHeight = controlHeight * visualScale;
        float renderX = controlX + (controlWidth - renderWidth) / 2.0f;
        float renderY = controlY + (controlHeight - renderHeight) / 2.0f;
        float textSize = 6.2f * visualScale;
        float actionWidth = textWidth(action, textSize);
        float actionClipX = renderX + 4.0f * scale * visualScale;
        float actionClipWidth = renderWidth - 8.0f * scale * visualScale;
        float actionX = actionClipX + Math.max(0.0f, (actionClipWidth - actionWidth) / 2.0f);

        renderGlassControl(renderX, renderY, renderWidth, renderHeight, 4.0f * scale * visualScale, alpha);
        renderScrollingText(graphics, "button:value", action, actionX, renderY + 2.5f * scale * visualScale, textSize, color(255, 255, 255, 245, alpha), actionClipX, renderY, actionClipWidth, renderHeight, controlX, controlY, controlWidth, controlHeight);
        float iconSize = 14.5f;
        renderGuiIcon("U", x + 3.5f * scale, controlY - 3.0f * scale, iconSize, scale);
        renderOptionLabel(graphics, "button", x + 15.5f * scale, controlY + 1.5f * scale, 7.2f, controlX - 4.0f * scale, y, height);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0 && event.button() != 1) {
            return false;
        }
        if (hovered(event.x(), event.y(), controlX, controlY, controlWidth, controlHeight)) {
            scaleAnimation.set(0.88);
            scaleAnimation.run(1.0, 0.22, Easings.CUBIC_OUT);
            setting.press();
            return true;
        }
        return false;
    }
}
