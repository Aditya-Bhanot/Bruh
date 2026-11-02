package releon.ru.utils.repository.way;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import releon.ru.api.module.impl.visual.esp.EspGeometry;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WayRenderer {
    private final Style style = new Style();
    private final SmoothAnimation bobAnimation = new SmoothAnimation();
    private final List<RenderEntry> entries = new ArrayList<>();
    private final Map<WayKey, DistanceState> distanceStates = new HashMap<>();
    private boolean bobDown;
    private boolean bobStarted;
    private float frameBobOffset;
    private long frameTimeMillis;

    public Style style() {
        return style;
    }

    public void beginFrame() {
        entries.clear();
        frameTimeMillis = System.currentTimeMillis();
        pruneDistanceStates();
        frameBobOffset = bobOffset();
    }

    public void queue(Minecraft mc, Way way, EspGeometry.ScreenPoint screen, double distance) {
        if (mc == null || mc.getWindow() == null || way == null || screen == null) {
            return;
        }
        if (!Double.isFinite(screen.x()) || !Double.isFinite(screen.y()) || !Double.isFinite(screen.z()) || screen.z() < 0.0D) {
            return;
        }

        DistanceState distanceState = distanceStates.computeIfAbsent(WayKey.of(way), key -> new DistanceState());
        int displayDistance = distanceState.update(distance, frameTimeMillis, style);
        String nameText = way.name();
        String separatorText = " - ";
        String distanceText = formatDistance(displayDistance);
        float nameWidth = Render2D.textWidth(FontType.SEMIBOLD, nameText, style.textSize);
        float separatorWidth = Render2D.textWidth(FontType.SEMIBOLD, separatorText, style.textSize);
        float distanceWidth = Render2D.textWidth(FontType.SEMIBOLD, distanceText, style.textSize);
        float distanceSlotWidth = distanceState.stableSlotWidth(Math.max(distanceWidth, minDistanceSlotWidth()));
        float width = nameWidth + separatorWidth + distanceSlotWidth + style.paddingX * 2.0F;
        float height = style.height;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float rawCenterX = (float) screen.x();
        float rawCenterY = (float) screen.y();
        float rawX = rawCenterX - width * 0.5F;
        float rawY = rawCenterY - height * 0.5F;
        if (isOutsideScreen(rawX, rawY, width, height, screenWidth, screenHeight)) {
            return;
        }

        ScreenCenter smoothedCenter = distanceState.updateScreen(rawCenterX, rawCenterY, frameTimeMillis, style);
        float centerX = smoothedCenter.x();
        float centerY = smoothedCenter.y();
        float x = centerX - width * 0.5F;
        float y = centerY - height * 0.5F;
        if (style.snapLabelToPixel) {
            x = Math.round(x);
            y = Math.round(y);
            centerX = x + width * 0.5F;
        }

        float radius = safeRadius(width, height, style.radius);
        float accentRadius = safeRadius(style.accentSize, style.accentSize, style.accentRadius);
        float accentY = y + height + style.accentOffsetY + frameBobOffset;

        entries.add(new RenderEntry(
                nameText,
                separatorText,
                distanceText,
                x,
                y,
                centerX,
                width,
                height,
                radius,
                accentRadius,
                accentY,
                nameWidth,
                separatorWidth,
                distanceWidth,
                distanceSlotWidth
        ));
    }

    public void renderQueued(GuiGraphics graphics) {
        if (graphics == null || entries.isEmpty()) {
            return;
        }

        for (RenderEntry entry : entries) {
            renderBackground(entry);
        }
        graphics.nextStratum();
        Render2D.beginFrame(graphics);
        for (RenderEntry entry : entries) {
            renderText(entry);
        }
        graphics.nextStratum();
        Render2D.beginFrame(graphics);
    }

    private void renderBackground(RenderEntry entry) {
        float x = entry.x();
        float y = entry.y();
        float width = entry.width();
        float height = entry.height();
        float radius = entry.radius();
        Render2D.blur(x, y, width, height, radius, style.blurRadius, 1.0F, style.blurColor);
        Render2D.rect(x, y, width, height, radius, style.backgroundColor);
        Render2D.outline(x, y, width, height, radius, style.outlineWidth, style.outlineColor);
        Render2D.rect(
                entry.centerX() + style.accentOffsetX,
                entry.accentY(),
                style.accentSize,
                style.accentSize,
                entry.accentRadius(),
                style.accentColor
        );
    }

    private void renderText(RenderEntry entry) {
        float textX = entry.x() + style.paddingX;
        float textY = entry.y() + style.textOffsetY;
        if (style.snapTextToPixel) {
            textX = Math.round(textX);
            textY = Math.round(textY);
        }

        float separatorX = textX + entry.nameWidth();
        float distanceX = textX + entry.nameWidth() + entry.separatorWidth();
        if (style.snapTextToPixel) {
            separatorX = Math.round(separatorX);
            distanceX = Math.round(distanceX);
        }

        int passes = Math.max(1, style.textStrengthPasses);
        for (int i = 0; i < passes; i++) {
            Render2D.text(FontType.SEMIBOLD, entry.nameText(), textX, textY, style.textSize, style.textColor);
            Render2D.text(FontType.SEMIBOLD, entry.separatorText(), separatorX, textY, style.textSize, style.textColor);
            Render2D.text(FontType.SEMIBOLD, entry.distanceText(), distanceX, textY, style.textSize, style.textColor);
        }
    }

    private String formatDistance(int distance) {
        return String.format(Locale.ROOT, "%dm", Math.max(0, distance));
    }

    private float minDistanceSlotWidth() {
        int digits = Math.max(1, style.minDistanceIntegerDigits);
        return Render2D.textWidth(FontType.SEMIBOLD, "8".repeat(digits) + "m", style.textSize);
    }

    private float bobOffset() {
        if (!style.bobEnabled || style.bobAmplitude <= 0.0F) {
            bobStarted = false;
            bobAnimation.set(0.0);
            return 0.0F;
        }

        if (!bobStarted) {
            bobStarted = true;
            bobDown = true;
            bobAnimation.set(-1.0);
            bobAnimation.run(1.0, style.bobDuration, Easings.EXPO_IN_OUT);
        } else if (!bobAnimation.isAlive()) {
            bobDown = !bobDown;
            bobAnimation.run(bobDown ? 1.0 : -1.0, style.bobDuration, Easings.EXPO_IN_OUT);
        }

        bobAnimation.update();
        return bobAnimation.get() * style.bobAmplitude;
    }

    private boolean isOutsideScreen(float x, float y, float width, float height, int screenWidth, int screenHeight) {
        float minY = y;
        float maxY = y + height + style.accentOffsetY + style.accentSize + Math.abs(style.bobAmplitude);
        float margin = style.offscreenMargin;
        return x + width < -margin
                || x > screenWidth + margin
                || maxY < -margin
                || minY > screenHeight + margin;
    }

    private float safeRadius(float width, float height, float radius) {
        return Mth.clamp(radius, 0.0F, Math.max(0.0F, Math.min(width, height) * 0.5F));
    }

    private void pruneDistanceStates() {
        Iterator<DistanceState> iterator = distanceStates.values().iterator();
        while (iterator.hasNext()) {
            DistanceState state = iterator.next();
            if (state.lastSeenMillis > 0L && frameTimeMillis - state.lastSeenMillis > style.distanceStateLifetimeMillis) {
                iterator.remove();
            }
        }
    }

    public static final class Style {
        public float textSize = 7.0F;
        public float paddingX = 5.0F;
        public float height = 12.0F;
        public float screenPadding = 4.0F;
        public float radius = 2.0F;
        public float blurRadius = 8.0F;
        public float outlineWidth = 0.4F;
        public float textOffsetY = 1.5F;
        public float accentOffsetX = -2.0F;
        public float accentOffsetY = 3.0F;
        public float accentSize = 6.0F;
        public float accentRadius = 12.0F;
        public float offscreenMargin = 4.0F;
        public float bobAmplitude = 3.0F;
        public double bobDuration = 1.15D;
        public double screenSmoothingSpeed = 32.0D;
        public float screenSnapDistance = 85.0F;
        public long screenResyncMillis = 120L;
        public double distanceHysteresis = 0.12D;
        public int distanceSnapBlocks = 8;
        public int minDistanceIntegerDigits = 5;
        public long distanceStateLifetimeMillis = 10000L;
        public boolean bobEnabled = true;
        public boolean snapLabelToPixel = true;
        public boolean snapTextToPixel = true;
        public int textStrengthPasses = 2;

        public int backgroundColor = ColorUtil.rgba(13, 13, 13, 150);
        public int outlineColor = ColorUtil.rgba(128, 128, 128, 90);
        public int accentColor = ColorUtil.rgba(127, 242, 255, 230);
        public int textColor = ColorUtil.rgba(255, 255, 255, 255);
        public int blurColor = ColorUtil.rgba(255, 255, 255, 255);
    }

    private record RenderEntry(
            String nameText,
            String separatorText,
            String distanceText,
            float x,
            float y,
            float centerX,
            float width,
            float height,
            float radius,
            float accentRadius,
            float accentY,
            float nameWidth,
            float separatorWidth,
            float distanceWidth,
            float distanceSlotWidth
    ) {
    }

    private record WayKey(String name, BlockPos pos, String server) {
        private static WayKey of(Way way) {
            return new WayKey(way.name(), way.pos(), way.server());
        }
    }

    private record ScreenCenter(float x, float y) {
    }

    private static final class DistanceState {
        private int displayDistance;
        private float distanceSlotWidth;
        private float screenX;
        private float screenY;
        private long lastScreenUpdateMillis;
        private long lastSeenMillis;
        private boolean initialized;
        private boolean screenInitialized;

        private int update(double targetDistance, long now, Style style) {
            lastSeenMillis = now;
            int target = Math.max(0, (int) Math.floor(targetDistance));
            if (!initialized) {
                initialized = true;
                displayDistance = target;
                return target;
            }

            if (Math.abs(target - displayDistance) >= style.distanceSnapBlocks) {
                displayDistance = target;
            } else if (target > displayDistance && targetDistance >= displayDistance + 1.0D + style.distanceHysteresis) {
                displayDistance = target;
            } else if (target < displayDistance && targetDistance <= displayDistance - style.distanceHysteresis) {
                displayDistance = target;
            }
            return displayDistance;
        }

        private float stableSlotWidth(float wantedWidth) {
            distanceSlotWidth = Math.max(distanceSlotWidth, wantedWidth);
            return distanceSlotWidth;
        }

        private ScreenCenter updateScreen(float targetX, float targetY, long now, Style style) {
            if (!screenInitialized || !Float.isFinite(screenX) || !Float.isFinite(screenY)) {
                return snapScreen(targetX, targetY, now);
            }

            long elapsedMillis = Math.max(0L, now - lastScreenUpdateMillis);
            float dx = targetX - screenX;
            float dy = targetY - screenY;
            float distance = Mth.sqrt(dx * dx + dy * dy);
            if (elapsedMillis > style.screenResyncMillis || distance >= style.screenSnapDistance) {
                return snapScreen(targetX, targetY, now);
            }

            lastScreenUpdateMillis = now;
            double deltaSeconds = Math.min(elapsedMillis / 1000.0D, 0.08D);
            float factor = (float) (1.0D - Math.exp(-Math.max(1.0D, style.screenSmoothingSpeed) * deltaSeconds));
            screenX += dx * factor;
            screenY += dy * factor;
            return new ScreenCenter(screenX, screenY);
        }

        private ScreenCenter snapScreen(float targetX, float targetY, long now) {
            screenInitialized = true;
            screenX = targetX;
            screenY = targetY;
            lastScreenUpdateMillis = now;
            return new ScreenCenter(screenX, screenY);
        }
    }
}
