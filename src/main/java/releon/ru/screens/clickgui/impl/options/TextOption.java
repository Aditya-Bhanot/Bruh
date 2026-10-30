package releon.ru.screens.clickgui.impl.options;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.impl.StringSetting;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public class TextOption extends ClickGuiOption {
    private final SmoothAnimation scrollAnimation = new SmoothAnimation();
    private final SmoothAnimation outlineAnimation = new SmoothAnimation();
    private final StringSetting setting;
    private String value;
    private boolean editing;
    private boolean selectedAll;
    private int cursor;
    private String cachedCursorValue;
    private int cachedCursor = -1;
    private float cachedCursorWidth;
    private float controlX;
    private float controlY;
    private float controlWidth;
    private float controlHeight;

    public TextOption(StringSetting setting) {
        super(setting.getName());
        this.setting = setting;
        this.value = setting.getValue();
        this.cursor = value.length();
        scrollAnimation.set(0.0);
        outlineAnimation.set(0.0);
    }

    @Override
    protected void renderControl(GuiGraphics graphics, float x, float y, float width, float height, float scale, float alpha) {
        if (!editing) {
            value = setting.getValue();
            cursor = Math.min(cursor, value.length());
        }
        controlWidth = 50.0f * scale;
        controlHeight = 13.0f * scale;
        controlX = x + width - controlWidth - 5.0f * scale;
        controlY = y + 5.5f * scale;
        float textSize = 6.2f;
        float textX = controlX + 5.0f * scale;
        float textY = controlY + 3f * scale;
        float available = controlWidth - 10.0f * scale;
        float valueWidth = textWidth(value, textSize);
        float targetScroll = valueWidth > available ? available - valueWidth - 1.0f * scale : 0.0f;
        scrollAnimation.run(targetScroll, 0.20, Easings.CUBIC_OUT, true);
        scrollAnimation.update();
        outlineAnimation.run(editing ? 1.0 : 0.0, 0.18, Easings.CUBIC_OUT, true);
        outlineAnimation.update();

        renderGlassControl(controlX, controlY, controlWidth, controlHeight, 4.0f * scale, alpha);
        if (outlineAnimation.get() > 0.01f) {
            Render2D.outline(controlX, controlY, controlWidth, controlHeight, 4.0f * scale, 1.0f * scale, color(155, 162, 176, Math.round(165.0f * outlineAnimation.get()), alpha));
        }

        if (editing) {
            Render2D.pushScissor(graphics, controlX + 4.0f * scale, controlY, available + 2.0f * scale, controlHeight);
            if (selectedAll && !value.isEmpty()) {
                Render2D.rect(textX + scrollAnimation.get() - 1.0f * scale, controlY + 3.0f * scale, valueWidth + 5.0f * scale, 8.0f * scale, 1.0f * scale, color(155, 162, 176, 85, alpha));
            }
            Render2D.text(FontType.SEMIBOLD, value, textX + scrollAnimation.get(), textY, textSize, color(255, 255, 255, 235, alpha));
            if (!selectedAll && (System.currentTimeMillis() / 420L) % 2L == 0L) {
                float cursorX = textX + scrollAnimation.get() + cursorWidth(textSize);
                Render2D.rect(cursorX + 2, controlY + 3.5f * scale, 1.0f * scale, 7.0f * scale, 0f * scale, color(24, 24, 27, 235, alpha));
            }
            Render2D.popScissor(graphics);
        } else {
            renderScrollingText(graphics, "text:value", value, textX, textY, textSize, color(255, 255, 255, 235, alpha), controlX + 4.0f * scale, controlY, available + 2.0f * scale, controlHeight, controlX, controlY, controlWidth, controlHeight);
        }
        float iconSize = 12.5f;
        renderGuiIcon("S", x + 3.5f * scale, controlY - 0.5f * scale, iconSize, scale);
        renderOptionLabel(graphics, "text", x + 15.5f * scale, controlY + 1.5f * scale, 7.2f, controlX - 4.0f * scale, y, height);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event.button() != 0) {
            return false;
        }
        if (hovered(event.x(), event.y(), controlX, controlY, controlWidth, controlHeight)) {
            editing = true;
            selectedAll = false;
            cursor = value.length();
            return true;
        }
        editing = false;
        selectedAll = false;
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!editing) {
            return false;
        }

        int key = event.key();
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && key == GLFW.GLFW_KEY_A) {
            selectedAll = true;
            cursor = value.length();
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_C) {
            setClipboard(value);
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_V) {
            insertText(getClipboard());
            return true;
        }

        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (selectedAll) {
                value = "";
                cursor = 0;
                selectedAll = false;
            } else if (cursor > 0) {
                value = value.substring(0, cursor - 1) + value.substring(cursor);
                cursor--;
            }
            syncSetting();
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE) {
            if (selectedAll) {
                value = "";
                cursor = 0;
                selectedAll = false;
            } else if (cursor < value.length()) {
                value = value.substring(0, cursor) + value.substring(cursor + 1);
            }
            syncSetting();
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            cursor = Math.max(0, cursor - 1);
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_RIGHT) {
            cursor = Math.min(value.length(), cursor + 1);
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_HOME) {
            cursor = 0;
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_END) {
            cursor = value.length();
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_ESCAPE) {
            editing = false;
            selectedAll = false;
            return true;
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!editing || !event.isAllowedChatCharacter()) {
            return false;
        }
        insertText(event.codepointAsString());
        return true;
    }

    private void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String cleaned = text.replace("\r", "").replace("\n", " ");
        if (selectedAll) {
            value = cleaned;
            cursor = value.length();
            selectedAll = false;
            syncSetting();
            return;
        }
        value = value.substring(0, cursor) + cleaned + value.substring(cursor);
        cursor += cleaned.length();
        syncSetting();
    }

    private String getClipboard() {
        Minecraft client = Minecraft.getInstance();
        String clipboard = GLFW.glfwGetClipboardString(client.getWindow().handle());
        return clipboard == null ? "" : clipboard;
    }

    private void setClipboard(String text) {
        Minecraft client = Minecraft.getInstance();
        GLFW.glfwSetClipboardString(client.getWindow().handle(), text);
    }

    private void syncSetting() {
        setting.setValue(value);
        value = setting.getValue();
        cursor = Math.min(cursor, value.length());
        invalidateCursorWidth();
    }

    private float cursorWidth(float textSize) {
        int safeCursor = Math.min(cursor, value.length());
        if (cachedCursorValue != value || cachedCursor != safeCursor) {
            cachedCursorValue = value;
            cachedCursor = safeCursor;
            cachedCursorWidth = textWidth(value.substring(0, safeCursor), textSize);
        }
        return cachedCursorWidth;
    }

    private void invalidateCursorWidth() {
        cachedCursorValue = null;
        cachedCursor = -1;
        cachedCursorWidth = 0.0f;
    }
}
