package releon.ru.api.drag.impl;

import com.adl.nativeprotect.User;
import releon.ru.api.drag.core.ElementComponent;
import releon.ru.api.drag.core.ElementManager;
import releon.ru.api.drag.core.ElementScreen;
import releon.ru.api.drag.core.HudElement;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static releon.ru.IMinecraft.mc;

public final class Watermark implements HudElement {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final float ICON_BOX_SIZE = 20.0f;
    private static final float ROW_HEIGHT = 20.0f;
    private static final float ROW_GAP = 2.0f;
    private static final float TOP_MIN_WIDTH = 160.0f;
    private static final float BOTTOM_MIN_WIDTH = 154.0f;
    private static final float TEXT_SIZE = 6.0f;
    private static final float TOP_TEXT_OFFSET = 25.0f;
    private static final float TOP_DOT_OFFSET = 19.0f;
    private static final float TOP_SEGMENT_GAP = 8.0f;
    private static final float BOTTOM_TEXT_OFFSET = 24.0f;
    private static final float BOTTOM_DOT_OFFSET = 18.0f;
    private static final float BOTTOM_SEGMENT_GAP = 10.0f;
    private static final float SIZE_ANIMATION_SECONDS = 0.22f;

    private final ElementComponent drag = ElementManager.getInstance()
            .register("hud.watermark", "Watermark", 10.0f, 10.0f)
            .minimumSize(112.0f, 28.0f);
    private final SmoothAnimation topWidthAnimation = new SmoothAnimation();
    private final SmoothAnimation bottomWidthAnimation = new SmoothAnimation();
    private final SmoothAnimation totalWidthAnimation = new SmoothAnimation();

    public Watermark() {
        topWidthAnimation.set(TOP_MIN_WIDTH);
        bottomWidthAnimation.set(BOTTOM_MIN_WIDTH);
        totalWidthAnimation.set(182.0f);
    }

    public String elementName() {
        return "Watermark";
    }

    public void setHudVisible(boolean visible) {
        drag.visible(visible);
    }

    @Override
    public void render() {
        WatermarkState state = logics();
        renderWatermark(state);
    }

    private WatermarkState logics() {
        int ping = 0;
        if (mc.player != null && mc.getConnection() != null && mc.getConnection().getPlayerInfo(mc.player.getUUID()) != null) {
            ping = Math.max(0, mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency());
        }

        String username = resolveUsername();
        String serverText = "mc.reallyworld.ru";
        if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null && !mc.getCurrentServer().ip.isBlank()) {
            serverText = mc.getCurrentServer().ip;
        }
        String pingText = "Ping " + ping;
        String tpsText = "Tps 20";
        String fpsText = "Fps " + mc.getFps();
        String timeText = LocalTime.now().format(TIME_FORMATTER);

        float topWidth = Math.max(TOP_MIN_WIDTH,
                topSegmentWidth(username) + topSegmentWidth(fpsText) + topSegmentWidth(timeText) - TOP_SEGMENT_GAP + 2.0f);
        float serverTextWidth = Render2D.textWidth(FontType.BOLD, serverText, 6f);
        float pingTextWidth = Render2D.textWidth(FontType.BOLD, pingText, 6f);
        float tpsTextWidth = Render2D.textWidth(FontType.BOLD, tpsText, 6f);
        float pingIconX = bottomSegmentWidth(serverTextWidth);
        float tpsIconX = pingIconX + bottomSegmentWidth(pingTextWidth);
        float bottomWidth = Math.max(BOTTOM_MIN_WIDTH, tpsIconX + BOTTOM_TEXT_OFFSET + tpsTextWidth + 2.0f);
        float animatedTopWidth = animate(topWidthAnimation, topWidth);
        float animatedBottomWidth = animate(bottomWidthAnimation, bottomWidth);
        float totalWidth = Math.max(ICON_BOX_SIZE + ROW_GAP + topWidth, bottomWidth);
        float animatedTotalWidth = animate(totalWidthAnimation, totalWidth);
        float height = ROW_HEIGHT * 2.0f + ROW_GAP;
        drag.size(animatedTotalWidth, height);
        drag.clamp(ElementScreen.current());

        float x = drag.x();
        float y = drag.y();

        float radius = 4;

        return new WatermarkState(x, y, radius, animatedTopWidth, animatedBottomWidth, pingIconX, tpsIconX, username, serverText, pingText, tpsText, fpsText, timeText);
    }

    private void renderWatermark(WatermarkState state) {
        int background = ColorUtil.rgba(0, 0, 0, 255);
        int glow = ColorUtil.rgba(247, 133, 255, 130);
        int purple = ColorUtil.rgba(247, 133, 255, 255);
        int white = ColorUtil.rgba(255, 255, 255, 255);

        HudRenderCompat.background(state.x, state.y, ICON_BOX_SIZE, ROW_HEIGHT, state.radius, 15.0f, 1.2f, background);
        HudRenderCompat.glow("releon:textures/particles/ghost-glow.png", state.x - 1.0f, state.y - 1.0f, 22.0f, 22.0f, 0.0f, glow);
        Render2D.text(FontType.MAINMENUSCREEN, "f", state.x + 3.0f, state.y + 4.0f, 14.0f, purple);
        HudRenderCompat.background(state.x + ICON_BOX_SIZE + ROW_GAP, state.y, state.topWidth, ROW_HEIGHT, state.radius, 15.0f, 1.2f, background);
        HudRenderCompat.background(state.x, state.y + ROW_HEIGHT + ROW_GAP, state.bottomWidth, ROW_HEIGHT, state.radius, 15.0f, 1.2f, background);

        float topX = state.x + ICON_BOX_SIZE + ROW_GAP;
        topX += renderTopItem(topX, state.y, "t", 9.0f, 6.5f, state.username, purple, white, glow);
        topX += renderTopItem(topX, state.y, "b", 9.0f, 6.5f, state.fpsText, purple, white, glow);
        renderTopItem(topX, state.y, "h", 9.0f, 6.5f, state.timeText, purple, white, glow);

        float bottomY = state.y + ROW_HEIGHT + ROW_GAP;
        renderBottomItem(state.x, bottomY, "x", 9.0f, 6.0f, state.serverText, purple, white, glow);
        renderBottomItem(state.x + state.pingIconX, bottomY, "y", 8.5f, 6.0f, state.pingText, purple, white, glow);
        renderBottomItem(state.x + state.tpsIconX, bottomY, "z", 7.0f, 7.0f, state.tpsText, purple, white, glow);
    }

    private float renderTopItem(float x, float y, String icon, float iconSize, float iconYOffset, String text, int iconColor, int textColor, int glowColor) {
        HudRenderCompat.glow("releon:textures/particles/ghost-glow.png", x - 1.0f, y, 22.0f, 22.0f, 0.0f, glowColor);
        Render2D.text(FontType.MAINMENUSCREEN, icon, x + 6.5f, y + iconYOffset, iconSize, iconColor);
        Render2D.rect(x + TOP_DOT_OFFSET, y + 9.0f, 4.0f, 4.0f, 4.0f, textColor);
        Render2D.text(FontType.BOLD, text, x + TOP_TEXT_OFFSET, y + 7.0f, TEXT_SIZE, textColor);
        return topSegmentWidth(text);
    }

    private void renderBottomItem(float x, float y, String icon, float iconSize, float iconYOffset, String text, int iconColor, int textColor, int glowColor) {
        HudRenderCompat.glow("releon:textures/particles/ghost-glow.png", x - 1.5f, y - 1.0f, 22.0f, 22.0f, 0.0f, glowColor);
        Render2D.text(FontType.MAINMENUSCREEN, icon, x + 6.5f, y + iconYOffset, iconSize, iconColor);
        Render2D.rect(x + BOTTOM_DOT_OFFSET, y + 8.5f, 4.0f, 4.0f, 4.0f, textColor);
        Render2D.text(FontType.BOLD, text, x + BOTTOM_TEXT_OFFSET, y + 6.0f, TEXT_SIZE, textColor);
    }

    private float topSegmentWidth(String text) {
        return TOP_TEXT_OFFSET + Render2D.textWidth(FontType.BOLD, text, TEXT_SIZE) + TOP_SEGMENT_GAP;
    }

    private float bottomSegmentWidth(float textWidth) {
        return BOTTOM_TEXT_OFFSET + textWidth + BOTTOM_SEGMENT_GAP;
    }

    private float animate(SmoothAnimation animation, float target) {
        animation.run(target, SIZE_ANIMATION_SECONDS, Easings.CUBIC_OUT, true);
        animation.update();
        return animation.get();
    }

    private String resolveUsername() {
        String username = "";
        try {
            username = User.getInstance().profile("username");
        } catch (Throwable ignored) {
        }
        if (isBlank(username) && mc.getUser() != null) {
            username = mc.getUser().getName();
        }
        if (isBlank(username) && mc.player != null && mc.player.getGameProfile() != null) {
            username = mc.player.getGameProfile().name();
        }
        return isBlank(username) ? "User" : username.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record WatermarkState(
            float x,
            float y,
            float radius,
            float topWidth,
            float bottomWidth,
            float pingIconX,
            float tpsIconX,
            String username,
            String serverText,
            String pingText,
            String tpsText,
            String fpsText,
            String timeText
    ) {
    }
}
