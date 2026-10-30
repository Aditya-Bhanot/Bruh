package releon.ru.screens.modernui.settings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.api.settings.impl.StringSetting;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

final class SettingCardContext {
    NumberSetting draggingSlider;
    ColorSetting activeColor;
    ColorSetting draggingColor;
    int draggingColorPart;
    BindSetting listeningBind;
    StringSetting focusedText;

    float moduleClipX;
    float moduleClipY;
    float moduleClipWidth;
    float moduleClipHeight;
    private float visualAlpha = 1.0F;

    void renderClippedText(GuiGraphics graphics, FontType font, String text, float x, float y, float width, float size, int color) {
        Render2D.pushScissor(graphics, x, y - 2.0F, width, size + 6.0F);
        Render2D.text(font, text == null ? "" : text, x, y, size, multiplyAlpha(color, visualAlpha));
        Render2D.popScissor(graphics);
    }

    List<String> wrapText(FontType font, String text, float size, float width, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank() || width <= 0.0F || maxLines <= 0) {
            return lines;
        }

        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        int index = 0;
        while (index < words.length && lines.size() < maxLines) {
            String word = words[index++];
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (Render2D.textWidth(font, candidate, size) <= width) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }

            if (!line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
                index--;
                continue;
            }

            lines.add(trimToWidth(font, word, size, width));
        }

        if (!line.isEmpty() && lines.size() < maxLines) {
            lines.add(line.toString());
        }

        return lines;
    }

    void renderWrappedText(GuiGraphics graphics, FontType font, List<String> lines, float x, float y, float width, float size, float lineHeight, int color) {
        for (int i = 0; i < lines.size(); i++) {
            renderClippedText(graphics, font, lines.get(i), x, y + lineHeight * i, width, size, color);
        }
    }

    private String trimToWidth(FontType font, String text, float size, float width) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = builder + String.valueOf(text.charAt(i));
            if (Render2D.textWidth(font, candidate, size) > width && !builder.isEmpty()) {
                break;
            }
            builder.append(text.charAt(i));
        }
        return builder.toString();
    }

    int withAlpha(Color color, float alpha) {
        return new Color(
                color.getRed(),
                color.getGreen(),
                color.getBlue(),
                clampInt(Math.round(color.getAlpha() * clamp(alpha, 0.0F, 1.0F) * visualAlpha))
        ).getRGB();
    }

    int color(int red, int green, int blue, int alpha) {
        return new Color(red, green, blue, clampInt(Math.round(alpha * visualAlpha))).getRGB();
    }

    int multiplyAlpha(int color, float alpha) {
        return new Color(
                red(color),
                green(color),
                blue(color),
                clampInt(Math.round(((color >>> 24) & 0xFF) * clamp(alpha, 0.0F, 1.0F)))
        ).getRGB();
    }

    void setVisualAlpha(float visualAlpha) {
        this.visualAlpha = clamp(visualAlpha, 0.0F, 1.0F);
    }

    float visualAlpha() {
        return visualAlpha;
    }

    float lerp(float current, float target, float speed) {
        if (Math.abs(target - current) < 0.001F) {
            return target;
        }
        return current + (target - current) * clamp(speed, 0.0F, 1.0F);
    }

    float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    boolean inside(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    String getClipboard() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return "";
        }
        String clipboard = GLFW.glfwGetClipboardString(minecraft.getWindow().handle());
        return clipboard == null ? "" : clipboard;
    }

    void setClipboard(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return;
        }
        GLFW.glfwSetClipboardString(minecraft.getWindow().handle(), text == null ? "" : text);
    }

    int clampInt(int value) {
        return Math.max(0, Math.min(255, value));
    }

    static int red(int color) {
        return (color >>> 16) & 0xFF;
    }

    static int green(int color) {
        return (color >>> 8) & 0xFF;
    }

    static int blue(int color) {
        return color & 0xFF;
    }
}
