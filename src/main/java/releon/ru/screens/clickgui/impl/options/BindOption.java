package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public class BindOption extends ClickGuiOption {
    private final BindSetting setting;
    private String key;
    private boolean listening;
    private float boxX;
    private float boxY;
    private float boxWidth;
    private float boxHeight;

    public BindOption(BindSetting setting) {
        super(setting.getName());
        this.setting = setting;
        this.key = setting.getValue().getDisplayName();
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        if (!listening) {
            key = setting.getValue().getDisplayName();
        }
        String text = listening ? "..." : key;
        float textSize = 6.0f;
        float textWidth = textWidth(text, textSize);
        boxWidth = Math.max(15.0f * scale, Math.min(39.0f * scale, textWidth + 10.0f * scale));
        boxHeight = 13.0f * scale;
        boxX = x + width - boxWidth - 6.0f * scale;
        boxY = y + 5.5f * scale;

        renderGlassControl(boxX + 1, boxY + 1, boxWidth, boxHeight, 4.0f * scale, alpha);
        float keyClipX = boxX + 4.0f * scale;
        float keyClipWidth = boxWidth - 8.0f * scale;
        float keyTextX = keyClipX + Math.max(0.0f, (keyClipWidth - textWidth) / 2.0f);
        renderScrollingText(graphics, "bind:value", text, keyTextX, boxY + 3.5f * scale, textSize, color(255, 255, 255, 235, alpha), keyClipX, boxY, keyClipWidth, boxHeight, boxX, boxY, boxWidth, boxHeight);
        float iconSize = 8.0f;
        renderGuiIcon("L", x + 3.5f * scale, boxY + 3.5f * scale, iconSize, scale);
        renderOptionLabel(graphics, "bind", x + 15.5f * scale, boxY + 2.0f * scale, 7.2f, boxX - 4.0f * scale, y, height);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (listening) {
            KeyBind bind = KeyBind.mouse(event.button());
            key = bind.getDisplayName();
            setting.setValue(bind);
            listening = false;
            return true;
        }
        if (event.button() == 0 && hovered(event.x(), event.y(), boxX, boxY, boxWidth, boxHeight)) {
            listening = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!listening) {
            return false;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            setting.setValue(KeyBind.NONE);
        } else {
            setting.setValue(KeyBind.keyboard(event.key()));
        }
        key = setting.getValue().getDisplayName();
        listening = false;
        return true;
    }
}
