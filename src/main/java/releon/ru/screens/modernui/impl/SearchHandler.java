package releon.ru.screens.modernui.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.impl.misc.ClientSounds;
import releon.ru.utils.math.MathUtils;
import releon.ru.utils.render.animation.modernfx.Decelerate;
import releon.ru.utils.render.animation.modernfx.Direction;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.utils.sounds.SoundManager;

public final class SearchHandler {
    private static final float TEXT_SIZE = 7.0F;

    private final Decelerate focusAnimation = MathUtils.createAnimation(180, Direction.BACKWARDS);

    private boolean active;
    private String text = "";
    private int cursor;
    private float cursorBlink;
    private float focus;
    private float textScroll;
    private float targetTextScroll;
    private long lastUpdateMs = System.currentTimeMillis();

    public void setSearchActive(boolean active) {
        this.active = active;
        cursor = Math.max(0, Math.min(cursor, text.length()));
    }

    public void updateAnimations() {
        long now = System.currentTimeMillis();
        float delta = Math.min((now - lastUpdateMs) / 1000.0F, 0.1F);
        lastUpdateMs = now;

        focusAnimation.setDirection(active ? Direction.FORWARDS : Direction.BACKWARDS);
        focus = MathUtils.clamp(focusAnimation.getOutput().floatValue(), 0.0F, 1.0F);

        if (active) {
            cursorBlink += delta * 2.0F;
            if (cursorBlink > 1.0F) {
                cursorBlink -= 1.0F;
            }
        } else {
            targetTextScroll = 0.0F;
        }
        textScroll += (targetTextScroll - textScroll) * MathUtils.clamp(delta * 12.0F, 0.0F, 1.0F);
    }

    public boolean handleSearchChar(CharacterEvent event) {
        if (!active || !event.isAllowedChatCharacter()) {
            return false;
        }
        insert(event.codepointAsString());
        return true;
    }

    public boolean handleSearchKey(KeyEvent event) {
        if (!active) {
            return false;
        }

        boolean control = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (control && event.key() == GLFW.GLFW_KEY_V) {
            paste();
            return true;
        }
        if (control && event.key() == GLFW.GLFW_KEY_X) {
            clear();
            return true;
        }

        switch (event.key()) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursor > 0) {
                    text = text.substring(0, cursor - 1) + text.substring(cursor);
                    cursor--;
                    playTypingSound();
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < text.length()) {
                    text = text.substring(0, cursor) + text.substring(cursor + 1);
                    playTypingSound();
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                cursor = Math.max(0, cursor - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                cursor = Math.min(text.length(), cursor + 1);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                cursor = 0;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                cursor = text.length();
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                setSearchActive(false);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                return true;
            }
        }
        return false;
    }

    public void updateSearchTextScroll(float visibleWidth, float textWidth) {
        if (!active || textWidth <= visibleWidth) {
            targetTextScroll = 0.0F;
            return;
        }

        String beforeCursor = text.substring(0, Math.max(0, Math.min(cursor, text.length())));
        float cursorX = Render2D.textWidth(FontType.SEMIBOLD, beforeCursor, TEXT_SIZE);
        if (cursorX - targetTextScroll > visibleWidth - 2.0F) {
            targetTextScroll = cursorX - visibleWidth + 2.0F;
        } else if (cursorX - targetTextScroll < 0.0F) {
            targetTextScroll = cursorX;
        }
        targetTextScroll = Math.max(0.0F, Math.min(targetTextScroll, Math.max(0.0F, textWidth - visibleWidth)));
    }

    public boolean isSearchActive() {
        return active;
    }

    public String getSearchText() {
        return text;
    }

    public int getSearchCursorPosition() {
        return cursor;
    }

    public float getSearchCursorBlink() {
        return cursorBlink;
    }

    public float getSearchFocusAnimation() {
        return focus;
    }

    public float getSearchTextScrollOffset() {
        return textScroll;
    }

    private void insert(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String cleaned = value.replace("\n", "").replace("\r", "").replace("\t", "");
        if (cleaned.isEmpty()) {
            return;
        }
        text = text.substring(0, cursor) + cleaned + text.substring(cursor);
        cursor += cleaned.length();
        playTypingSound();
    }

    private void paste() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return;
        }
        insert(GLFW.glfwGetClipboardString(minecraft.getWindow().handle()));
    }

    private void clear() {
        if (text.isEmpty()) {
            return;
        }
        text = "";
        cursor = 0;
        textScroll = 0.0F;
        targetTextScroll = 0.0F;
        playTypingSound();
    }

    private void playTypingSound() {
        if (ClientSounds.allowSearchTypingSound()) {
            SoundManager.playSoundDirect(SoundManager.SEARCH_TYPING, 1.0F, 1.0F);
        }
    }
}
