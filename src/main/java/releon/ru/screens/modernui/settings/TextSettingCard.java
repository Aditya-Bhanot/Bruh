package releon.ru.screens.modernui.settings;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.impl.StringSetting;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;

final class TextSettingCard extends SettingCardComponent<StringSetting> {
    TextSettingCard(StringSetting setting) {
        super(setting);
    }

    @Override
    int order() {
        return 6;
    }

    @Override
    float height(SettingCardContext context) {
        return 18.0F + descriptionOffset(context, 62.0F);
    }

    @Override
    void render(GuiGraphics graphics, SettingCardContext context, float cardX, float y, int mouseX, int mouseY) {
        markRow(cardX, y, height(context));
        float descriptionOffset = descriptionOffset(context, 62.0F);
        float boxX = cardX + 87.0F;
        float boxY = y - 2.0F + descriptionOffset;
        float boxWidth = 52.5f;
        float boxHeight = 12.5f;
        state.box(boxX, boxY, boxWidth, boxHeight);
        if (context.focusedText != setting) {
            state.text = setting.getValue();
            state.cursor = Math.min(state.cursor, state.text.length());
        }

        renderNameAndDescription(graphics, context, cardX + 10.0F, y + 4, 62.0F, 62.0F);

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

        Render2D.text(FontType.MAINMENUSCREEN, "D", boxX - 10.0F, boxY + 3F, 8.0F, context.color(128, 128, 128, 255));

        Render2D.pushScissor(graphics, boxX + 4.0F, boxY, boxWidth - 8.0F, boxHeight);

        String text = state.text == null || state.text.isEmpty() ? "Text..." : state.text;
        int alpha = state.text == null || state.text.isEmpty() ? 165 : 225;

        Render2D.text(FontType.SEMIBOLD, text, boxX + 4.0F, boxY + 3.5F, 5.0F, context.color(225, 225, 225, alpha));

        if (context.focusedText == setting && !state.selectedAll && (System.currentTimeMillis() / 420L) % 2L == 0L) {
            float cursorX = boxX + 4.0F + Render2D.textWidth(FontType.SEMIBOLD, state.text.substring(0, Math.min(state.cursor, state.text.length())), 5.0F) + 1.0F;
            Render2D.rect(cursorX - 1, boxY + 2.5F, 0.65F, 7.0F, 0.0F, context.color(230, 230, 230, 220));
        }
        Render2D.popScissor(graphics);
    }

    @Override
    boolean mouseClicked(MouseButtonEvent event, boolean doubled, SettingCardContext context) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !context.inside(event.x(), event.y(), state.boxX, state.boxY, state.boxWidth, state.boxHeight)) {
            return false;
        }
        context.focusedText = setting;
        state.text = setting.getValue();
        state.cursor = state.text.length();
        state.selectedAll = doubled;
        context.activeColor = null;
        return true;
    }

    @Override
    boolean keyPressed(KeyEvent event, SettingCardContext context) {
        if (context.focusedText != setting) {
            return false;
        }
        if (state.text == null) {
            state.text = setting.getValue();
        }

        boolean control = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (control && event.key() == GLFW.GLFW_KEY_A) {
            state.selectedAll = true;
            state.cursor = state.text.length();
            return true;
        }
        if (control && event.key() == GLFW.GLFW_KEY_C) {
            context.setClipboard(state.text);
            return true;
        }
        if (control && event.key() == GLFW.GLFW_KEY_V) {
            insertText(context.getClipboard());
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            if (state.selectedAll) {
                state.text = "";
                state.cursor = 0;
                state.selectedAll = false;
            } else if (state.cursor > 0) {
                state.text = state.text.substring(0, state.cursor - 1) + state.text.substring(state.cursor);
                state.cursor--;
            }
            setting.setValue(state.text);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_DELETE) {
            if (state.selectedAll) {
                state.text = "";
                state.cursor = 0;
                state.selectedAll = false;
            } else if (state.cursor < state.text.length()) {
                state.text = state.text.substring(0, state.cursor) + state.text.substring(state.cursor + 1);
            }
            setting.setValue(state.text);
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_LEFT) {
            state.cursor = Math.max(0, state.cursor - 1);
            state.selectedAll = false;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_RIGHT) {
            state.cursor = Math.min(state.text.length(), state.cursor + 1);
            state.selectedAll = false;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER || event.key() == GLFW.GLFW_KEY_ESCAPE) {
            context.focusedText = null;
            state.selectedAll = false;
            return true;
        }
        return true;
    }

    @Override
    boolean charTyped(CharacterEvent event, SettingCardContext context) {
        if (context.focusedText != setting || !event.isAllowedChatCharacter()) {
            return false;
        }
        insertText(event.codepointAsString());
        return true;
    }

    private void insertText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (state.text == null) {
            state.text = setting.getValue();
        }
        String cleaned = text.replace("\r", "").replace("\n", " ");
        if (state.selectedAll) {
            state.text = cleaned;
            state.selectedAll = false;
        } else {
            state.text = state.text.substring(0, state.cursor) + cleaned + state.text.substring(state.cursor);
        }
        state.cursor = Math.min(state.text.length(), state.cursor + cleaned.length());
        setting.setValue(state.text);
        state.text = setting.getValue();
        state.cursor = Math.min(state.cursor, state.text.length());
    }
}
