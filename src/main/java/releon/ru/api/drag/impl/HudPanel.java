package releon.ru.api.drag.impl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import releon.ru.api.drag.core.ElementComponent;
import releon.ru.api.drag.core.ElementManager;
import releon.ru.api.drag.core.ElementScreen;
import releon.ru.api.drag.core.HudElement;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public abstract class HudPanel implements HudElement {
    protected static final FontType TITLE_FONT = FontType.BOLD;
    protected static final FontType TEXT_FONT = FontType.BOLD;
    protected static final int TEXT_COLOR = ColorUtil.rgba(255, 255, 255, 245);
    protected static final int MUTED_COLOR = ColorUtil.rgba(183, 190, 202, 215);
    protected static final int ACCENT_COLOR = ColorUtil.rgba(127, 242, 255, 230);
    private static final float CONTENT_ANIM = 0.24F;
    private static final String[] ASCII_CHARS = new String[128];

    static {
        for (int i = 0; i < ASCII_CHARS.length; i++) {
            ASCII_CHARS[i] = Character.toString((char) i);
        }
    }

    protected final Minecraft mc = Minecraft.getInstance();
    protected final ElementComponent drag;
    private final String elementName;
    private float animatedWidth;
    private float animatedHeight;
    private long lastFrameMs = System.currentTimeMillis();
    private final SmoothAnimation contentAnimation = new SmoothAnimation();
    private boolean enabled = true;

    protected HudPanel(String id, String title, float defaultX, float defaultY, float width, float height) {
        this.elementName = title;
        this.drag = ElementManager.getInstance()
                .register("hud." + id, title, defaultX, defaultY)
                .minimumSize(12.0F, 12.0F);
        this.animatedWidth = width;
        this.animatedHeight = height;
    }

    public String elementName() {
        return elementName;
    }

    public void setHudVisible(boolean visible) {
        enabled = visible;
        drag.visible(visible);
    }

    protected boolean selected() {
        return enabled;
    }

    protected void contentVisible(boolean visible) {
        drag.visible(enabled && visible);
    }

    protected float contentAlpha(boolean targetVisible) {
        contentAnimation.update();
        contentAnimation.run(targetVisible ? 1.0 : 0.0, CONTENT_ANIM, targetVisible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);

        float alpha = contentAnimation.get();
        contentVisible(targetVisible || alpha > 0.01F || contentAnimation.isAlive());
        return alpha;
    }

    protected boolean editPreview() {
        return mc.screen instanceof ChatScreen;
    }

    protected void size(float targetWidth, float targetHeight) {
        float delta = deltaSeconds();
        animatedWidth = smooth(animatedWidth, targetWidth, delta, 8.0F);
        animatedHeight = smooth(animatedHeight, targetHeight, delta, 8.0F);
        if (Math.abs(animatedWidth - targetWidth) < 0.2F) {
            animatedWidth = targetWidth;
        }
        if (Math.abs(animatedHeight - targetHeight) < 0.2F) {
            animatedHeight = targetHeight;
        }
        drag.size((float) Math.ceil(animatedWidth), (float) Math.ceil(animatedHeight));
        drag.clamp(ElementScreen.current());
    }

    protected String trimToWidth(String text, FontType font, float size, float width) {
        if (text == null) {
            return "";
        }
        if (Render2D.textWidth(font, text, size) <= width) {
            return text;
        }
        String suffix = "..";
        if (Render2D.textWidth(font, suffix, size) > width) {
            return suffix;
        }

        int low = 0;
        int high = text.length();
        int best = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            String candidate = text.substring(0, mid) + suffix;
            if (Render2D.textWidth(font, candidate, size) <= width) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best <= 0 ? suffix : text.substring(0, best) + suffix;
    }

    protected String formatDurationTicks(int ticks) {
        if (ticks < 0) {
            return "inf";
        }
        int totalSeconds = Math.max(0, ticks / 20);
        return totalSeconds / 60 + ":" + twoDigits(totalSeconds % 60);
    }

    protected static String twoDigits(int value) {
        int safe = Math.max(0, Math.min(99, value));
        return ASCII_CHARS['0' + safe / 10] + ASCII_CHARS['0' + safe % 10];
    }

    protected static String timerText(int ticks) {
        if (ticks < 0) {
            return "**:**";
        }
        int totalSeconds = Math.max(0, ticks / 20);
        int minutes = Math.min(99, totalSeconds / 60);
        int seconds = totalSeconds % 60;
        return twoDigits(minutes) + ":" + twoDigits(seconds);
    }

    protected static String charText(char c) {
        return c < ASCII_CHARS.length ? ASCII_CHARS[c] : Character.toString(c);
    }

    protected String shortBind(KeyBind bind) {
        if (bind == null || !bind.isBound()) {
            return "None";
        }
        return bind.getDisplayName()
                .replace("MOUSE ", "M")
                .replace("L SHIFT", "LSH")
                .replace("R SHIFT", "RSH")
                .replace("L CTRL", "LCT")
                .replace("R CTRL", "RCT")
                .replace("SPACE", "SPC");
    }

    protected static float smooth(float current, float target, float deltaSeconds, float speed) {
        float factor = (float) (1.0D - Math.pow(0.001D, Math.max(0.0F, deltaSeconds) * speed));
        return current + (target - current) * factor;
    }

    protected static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float deltaSeconds() {
        long now = System.currentTimeMillis();
        float delta = Math.min(0.1F, Math.max(0.0F, (now - lastFrameMs) / 1000.0F));
        lastFrameMs = now;
        return delta;
    }
}
