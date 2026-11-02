package releon.ru.utils.render.ui;

import releon.ru.utils.render.ui.blur.BlurBuilder;
import releon.ru.utils.render.ui.blur.BlurFramebuffer;
import releon.ru.utils.render.ui.blur.BuiltBlur;
import releon.ru.utils.render.ui.effecticon.BuiltEffectIcon;
import releon.ru.utils.render.ui.effecticon.EffectIconRenderer;
import releon.ru.utils.render.ui.font.BuiltText;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.utils.render.ui.font.TextRenderer;
import releon.ru.utils.render.ui.glass.BuiltGlass;
import releon.ru.utils.render.ui.glass.GlassRenderer;
import releon.ru.utils.render.ui.image.BuiltImage;
import releon.ru.utils.render.ui.image.ImageRenderer;
import releon.ru.utils.render.ui.msdf.MsdfIconRenderer;
import releon.ru.utils.render.ui.arc.ArcOutlineRenderer;
import releon.ru.utils.render.ui.arc.ArcRenderer;
import releon.ru.utils.render.ui.arc.BuiltArc;
import releon.ru.utils.render.ui.arc.BuiltArcOutline;
import releon.ru.utils.render.ui.outline.outline360.BuiltOutline360;
import releon.ru.utils.render.ui.outline.outline360.Outline360Range;
import releon.ru.utils.render.ui.outline.outline360.Outline360Renderer;
import releon.ru.utils.render.ui.outline.outlinedefault.BuiltOutline;
import releon.ru.utils.render.ui.outline.outlinedefault.DefaultOutlineRenderer;
import releon.ru.utils.render.ui.outline.outlineglass.BuiltGlassOutline;
import releon.ru.utils.render.ui.outline.outlineglass.GlassOutlineRenderer;
import releon.ru.utils.render.ui.rectangle.rectdefault.BuiltRectangle;
import releon.ru.utils.render.ui.rectangle.rectdefault.DefaultRectangleRenderer;
import releon.ru.utils.render.ui.rectangle.recthalficon.BuiltHalfIconRectangle;
import releon.ru.utils.render.ui.rectangle.recthalficon.HalfIconRectangleRenderer;
import releon.ru.utils.render.ui.rectangle.recthalftone.BuiltHalftoneRectangle;
import releon.ru.utils.render.ui.rectangle.recthalftone.HalftoneRectangleRenderer;
import releon.ru.utils.render.ui.ripple.BuiltRipple;
import releon.ru.utils.render.ui.ripple.RippleRenderer;
import releon.ru.utils.render.ui.zippy.BuiltZippy;
import releon.ru.utils.render.ui.zippy.ZippyRenderer;
import net.minecraft.core.Holder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ScissorUtil;

import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public final class Render2D {
    @FunctionalInterface
    public interface PointProjector {
        ProjectedPoint project(float x, float y);
    }

    public record ProjectedPoint(float x, float y) {
    }

    public record ProjectedRect(float x, float y, float width, float height) {
    }

    private static final PointProjector IDENTITY_PROJECTOR = ProjectedPoint::new;
    private static final Deque<Matrix4f> PROJECTION_OVERRIDES = new ArrayDeque<>();
    private static final Deque<PointProjector> POINT_PROJECTORS = new ArrayDeque<>();

    private Render2D() {
    }

    public static void init() {
    }

    public static int getFixedScaledWidth() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(minecraft.getWindow().getWidth() / Render2DCoordinateSpace.designGuiScale()));
    }

    public static int getFixedScaledHeight() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(minecraft.getWindow().getHeight() / Render2DCoordinateSpace.designGuiScale()));
    }

    public static float guiToFixed(float value) {
        float scale = Render2DCoordinateSpace.guiIndependentScale();
        if (Math.abs(scale) <= 0.0001f) {
            return value;
        }
        return value / scale;
    }

    public static double guiToFixed(double value) {
        float scale = Render2DCoordinateSpace.guiIndependentScale();
        if (Math.abs(scale) <= 0.0001f) {
            return value;
        }
        return value / scale;
    }

    public static void withProjection(Matrix4f projection, PointProjector projector, Runnable block) {
        PROJECTION_OVERRIDES.addLast(new Matrix4f(projection));
        POINT_PROJECTORS.addLast(projector == null ? IDENTITY_PROJECTOR : projector);
        try {
            block.run();
        } finally {
            PROJECTION_OVERRIDES.removeLast();
            POINT_PROJECTORS.removeLast();
        }
    }

    public static PointProjector getCurrentProjector() {
        return POINT_PROJECTORS.peekLast();
    }

    public static boolean hasProjectionOverride() {
        return !PROJECTION_OVERRIDES.isEmpty();
    }

    public static ProjectedRect projectRect(float x, float y, float width, float height) {
        PointProjector projector = getCurrentProjector();
        if (projector == null) {
            return new ProjectedRect(x, y, width, height);
        }

        ProjectedPoint[] points = {
                projector.project(x, y),
                projector.project(x + width, y),
                projector.project(x + width, y + height),
                projector.project(x, y + height)
        };

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        boolean hasPoint = false;

        for (ProjectedPoint point : points) {
            if (point == null) {
                continue;
            }
            hasPoint = true;
            minX = Math.min(minX, point.x());
            minY = Math.min(minY, point.y());
            maxX = Math.max(maxX, point.x());
            maxY = Math.max(maxY, point.y());
        }

        if (!hasPoint) {
            return null;
        }
        return new ProjectedRect(minX, minY, Math.max(0.0f, maxX - minX), Math.max(0.0f, maxY - minY));
    }

    public static void beginFrame(GuiGraphics graphics) {
        blur().beginFrame(graphics);
        glass().beginFrame(graphics);
        glassOutline().beginFrame(graphics);
        rectangle().beginFrame(graphics);
        halfIconRectangle().beginFrame(graphics);
        halftoneRectangle().beginFrame(graphics);
        zippy().beginFrame(graphics);
        arc().beginFrame(graphics);
        arcOutline().beginFrame(graphics);
        outline().beginFrame(graphics);
        outline360().beginFrame(graphics);
        text().beginFrame(graphics);
        msdfIcon().beginFrame(graphics);
        image().beginFrame(graphics);
        effectIcon().beginFrame(graphics);
        ripple().beginFrame(graphics);
    }

    public static void flush() {
        blur().flush();
        glass().flush();
        glassOutline().flush();
        rectangle().flush();
        halfIconRectangle().flush();
        halftoneRectangle().flush();
        zippy().flush();
        arc().flush();
        arcOutline().flush();
        outline().flush();
        outline360().flush();
        text().flush();
        msdfIcon().flush();
        image().flush();
        effectIcon().flush();
        ripple().flush();
    }

    public static void blur(float x, float y, float width, float height, float radius) {
        blur(x, y, width, height, radius, 16.0f, 1.0f, ColorUtil.WHITE);
    }

    public static void blur(float x, float y, float width, float height, float radius, float blurRadius) {
        blur(x, y, width, height, radius, blurRadius, 1.0f, ColorUtil.WHITE);
    }

    public static void blur(float x, float y, float width, float height, float radius, float blurRadius, float smoothness, int color) {
        imageBarrier();
        blur().enqueue(new BuiltBlur(x, y, width, height, radius, smoothness, blurRadius).withColor(color));
    }

    public static void blur(
            float x,
            float y,
            float width,
            float height,
            float radius,
            float blurRadius,
            float smoothness,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft
    ) {
        imageBarrier();
        blur().enqueue(new BuiltBlur(x, y, width, height, radius, smoothness, blurRadius)
                .withColors(colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft));
    }

    public static void blur(GuiGraphics graphics, float x, float y, float width, float height, float radius, float blurRadius) {
        beginFrame(graphics);
        blur(x, y, width, height, radius, blurRadius);
        flush();
    }

    public static void blur(BuiltBlur blur) {
        imageBarrier();
        Render2D.blur().enqueue(blur);
    }

    public static BlurBuilder blurBuilder() {
        return new BlurBuilder();
    }

    public static void glass(
            float x,
            float y,
            float width,
            float height,
            float[] radius,
            int color,
            float globalAlpha,
            float fresnelPower,
            int fresnelColor,
            float baseAlpha,
            boolean fresnelInvert,
            float fresnelMix,
            float distortStrength,
            float squirt,
            float z
    ) {
        imageBarrier();
        glass().enqueue(new BuiltGlass(
                x,
                y,
                width,
                height,
                radius,
                color,
                globalAlpha,
                fresnelPower,
                fresnelColor,
                baseAlpha,
                fresnelInvert,
                fresnelMix,
                distortStrength,
                squirt,
                z
        ));
    }

    public static void glass(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            int color,
            float globalAlpha,
            float fresnelPower,
            int fresnelColor,
            float baseAlpha,
            boolean fresnelInvert,
            float fresnelMix,
            float distortStrength,
            float squirt,
            float z
    ) {
        imageBarrier();
        glass().enqueue(new BuiltGlass(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                color,
                globalAlpha,
                fresnelPower,
                fresnelColor,
                baseAlpha,
                fresnelInvert,
                fresnelMix,
                distortStrength,
                squirt,
                z
        ));
    }

    public static void glass(
            float x,
            float y,
            float width,
            float height,
            float radius,
            int color,
            float globalAlpha,
            float fresnelPower,
            int fresnelColor,
            float baseAlpha,
            boolean fresnelInvert,
            float fresnelMix,
            float distortStrength,
            float squirt,
            float z
    ) {
        imageBarrier();
        glass().enqueue(new BuiltGlass(
                x,
                y,
                width,
                height,
                radius,
                radius,
                radius,
                radius,
                color,
                globalAlpha,
                fresnelPower,
                fresnelColor,
                baseAlpha,
                fresnelInvert,
                fresnelMix,
                distortStrength,
                squirt,
                z
        ));
    }

    public static void glass(BuiltGlass glass) {
        imageBarrier();
        Render2D.glass().enqueue(glass);
    }

    public static void liquidGlass(float x, float y, float width, float height, float squirt, float power, float radius, int color) {
        glass(
                x,
                y,
                width,
                height,
                radius * squirt / 2.0f,
                radius * squirt / 2.0f,
                radius * squirt / 2.0f,
                radius * squirt / 2.0f,
                color,
                ((color >>> 24) & 0xFF) / 255.0f,
                height == 240.0f ? 100.0f : 50.0f,
                color | 0xFF000000,
                1.0f,
                true,
                0.0f,
                power,
                squirt,
                0.0f
        );
    }

    public static void glassOutline(
            float x,
            float y,
            float width,
            float height,
            float[] radius,
            float thickness,
            int color,
            float globalAlpha,
            float fresnelPower,
            int fresnelColor,
            float baseAlpha,
            boolean fresnelInvert,
            float fresnelMix,
            float distortStrength,
            float squirt,
            float z
    ) {
        imageBarrier();
        glassOutline().enqueue(new BuiltGlassOutline(
                x,
                y,
                width,
                height,
                radius,
                thickness,
                color,
                globalAlpha,
                fresnelPower,
                fresnelColor,
                baseAlpha,
                fresnelInvert,
                fresnelMix,
                distortStrength,
                squirt,
                z
        ));
    }

    public static void glassOutline(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            float thickness,
            int color,
            float globalAlpha,
            float fresnelPower,
            int fresnelColor,
            float baseAlpha,
            boolean fresnelInvert,
            float fresnelMix,
            float distortStrength,
            float squirt,
            float z
    ) {
        imageBarrier();
        glassOutline().enqueue(new BuiltGlassOutline(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                thickness,
                color,
                globalAlpha,
                fresnelPower,
                fresnelColor,
                baseAlpha,
                fresnelInvert,
                fresnelMix,
                distortStrength,
                squirt,
                BuiltGlassOutline.DEFAULT_SMOOTHNESS,
                z
        ));
    }

    public static void glassOutline(
            float x,
            float y,
            float width,
            float height,
            float radius,
            float thickness,
            int color,
            float globalAlpha,
            float fresnelPower,
            int fresnelColor,
            float baseAlpha,
            boolean fresnelInvert,
            float fresnelMix,
            float distortStrength,
            float squirt,
            float z
    ) {
        imageBarrier();
        glassOutline().enqueue(new BuiltGlassOutline(
                x,
                y,
                width,
                height,
                radius,
                radius,
                radius,
                radius,
                thickness,
                color,
                globalAlpha,
                fresnelPower,
                fresnelColor,
                baseAlpha,
                fresnelInvert,
                fresnelMix,
                distortStrength,
                squirt,
                BuiltGlassOutline.DEFAULT_SMOOTHNESS,
                z
        ));
    }

    public static void glassOutline(BuiltGlassOutline outline) {
        imageBarrier();
        Render2D.glassOutline().enqueue(outline);
    }

    public static void liquidGlassOutline(float x, float y, float width, float height, float squirt, float power, float radius, float thickness, int color) {
        glassOutline(
                x,
                y,
                width,
                height,
                radius * squirt / 2.0f,
                radius * squirt / 2.0f,
                radius * squirt / 2.0f,
                radius * squirt / 2.0f,
                thickness,
                color,
                ((color >>> 24) & 0xFF) / 255.0f,
                height == 240.0f ? 100.0f : 50.0f,
                color | 0xFF000000,
                1.0f,
                true,
                0.0f,
                power,
                squirt,
                0.0f
        );
    }

    public static void rect(float x, float y, float width, float height, int color) {
        rect(x, y, width, height, 0.0f, color);
    }

    public static void rect(float x, float y, float width, float height, float radius, int color) {
        imageBarrier();
        rectangle().enqueue(new BuiltRectangle(x, y, width, height, radius, color));
    }

    public static void rect(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            int color
    ) {
        imageBarrier();
        rectangle().enqueue(new BuiltRectangle(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                color
        ));
    }

    public static void rect(
            float x,
            float y,
            float width,
            float height,
            float radius,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft
    ) {
        rect(x, y, width, height, radius, radius, radius, radius, colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft);
    }

    public static void rect(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft
    ) {
        imageBarrier();
        rectangle().enqueue(new BuiltRectangle(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                colorTopLeft,
                colorTopRight,
                colorBottomRight,
                colorBottomLeft,
                BuiltRectangle.DEFAULT_SMOOTHNESS
        ));
    }

    public static void rect(GuiGraphics graphics, float x, float y, float width, float height, float radius, int color) {
        beginFrame(graphics);
        rect(x, y, width, height, radius, color);
        flush();
    }

    public static void rect(BuiltRectangle rectangle) {
        Render2D.rectangle().enqueue(rectangle);
    }

    public static void arc(float x, float y, float size, float thickness, float degree, float rotation, int color) {
        imageBarrier();
        arc().enqueue(new BuiltArc(x, y, size, thickness, degree, rotation, color));
    }

    public static void arc(float x, float y, float size, float thickness, float degree, float rotation, int... colors) {
        imageBarrier();
        arc().enqueue(new BuiltArc(x, y, size, thickness, degree, rotation, colors));
    }

    public static void arc(BuiltArc arc) {
        imageBarrier();
        Render2D.arc().enqueue(arc);
    }

    public static void arcOutline(float x, float y, float size, float arcThickness, float degree,
                                  float rotation, float outlineThickness, int fillColor, int outlineColor) {
        imageBarrier();
        arcOutline().enqueue(new BuiltArcOutline(
                x,
                y,
                size,
                arcThickness,
                degree,
                rotation,
                outlineThickness,
                fillColor,
                outlineColor
        ));
    }

    public static void arcOutline(BuiltArcOutline outline) {
        imageBarrier();
        Render2D.arcOutline().enqueue(outline);
    }

    public static void halftoneRect(
            float x,
            float y,
            float width,
            float height,
            float radius,
            int color,
            int dotColor,
            float dotSize,
            float dotSpacing
    ) {
        imageBarrier();
        halftoneRectangle().enqueue(new BuiltHalftoneRectangle(x, y, width, height, radius, color, dotColor, dotSize, dotSpacing));
    }

    public static void halftoneRect(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            int color,
            int dotColor,
            float dotSize,
            float dotSpacing
    ) {
        imageBarrier();
        halftoneRectangle().enqueue(new BuiltHalftoneRectangle(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                color,
                dotColor,
                dotSize,
                dotSpacing
        ));
    }

    public static void halftoneRect(
            float x,
            float y,
            float width,
            float height,
            float radius,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft,
            int dotColor,
            float dotSize,
            float dotSpacing
    ) {
        halftoneRect(
                x,
                y,
                width,
                height,
                radius,
                radius,
                radius,
                radius,
                colorTopLeft,
                colorTopRight,
                colorBottomRight,
                colorBottomLeft,
                dotColor,
                dotSize,
                dotSpacing
        );
    }

    public static void halftoneRect(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft,
            int dotColor,
            float dotSize,
            float dotSpacing
    ) {
        imageBarrier();
        halftoneRectangle().enqueue(new BuiltHalftoneRectangle(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                colorTopLeft,
                colorTopRight,
                colorBottomRight,
                colorBottomLeft,
                BuiltHalftoneRectangle.DEFAULT_SMOOTHNESS,
                dotColor,
                dotSize,
                dotSpacing
        ));
    }

    public static void halftoneRect(BuiltHalftoneRectangle rectangle) {
        imageBarrier();
        Render2D.halftoneRectangle().enqueue(rectangle);
    }

    public static void halfIconRect(
            float x,
            float y,
            float width,
            float height,
            float radius,
            int color,
            int iconColor,
            String fontName,
            String icon,
            float iconSize,
            float spacingX,
            float spacingY,
            float rotationMaxDegrees
    ) {
        halfIconRect(new BuiltHalfIconRectangle(
                x,
                y,
                width,
                height,
                radius,
                color,
                fontName,
                icon,
                iconSize,
                iconColor,
                spacingX,
                spacingY,
                rotationMaxDegrees
        ));
    }

    public static void halfIconRect(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            int color,
            int iconColor,
            String fontName,
            String icon,
            float iconSize,
            float spacingX,
            float spacingY,
            float paddingX,
            float paddingY,
            float jitterX,
            float jitterY,
            float rotationMaxDegrees,
            long seed
    ) {
        halfIconRect(new BuiltHalfIconRectangle(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                color,
                color,
                color,
                color,
                BuiltHalfIconRectangle.DEFAULT_SMOOTHNESS,
                fontName,
                icon,
                iconSize,
                iconColor,
                spacingX,
                spacingY,
                paddingX,
                paddingY,
                jitterX,
                jitterY,
                rotationMaxDegrees,
                seed
        ));
    }

    public static void halfIconRect(BuiltHalfIconRectangle rectangle) {
        imageBarrier();
        if (rectangle != null && rectangle.backgroundVisible()) {
            rect(
                    rectangle.x(),
                    rectangle.y(),
                    rectangle.width(),
                    rectangle.height(),
                    rectangle.radiusTopLeft(),
                    rectangle.radiusTopRight(),
                    rectangle.radiusBottomRight(),
                    rectangle.radiusBottomLeft(),
                    rectangle.colorTopLeft(),
                    rectangle.colorTopRight(),
                    rectangle.colorBottomRight(),
                    rectangle.colorBottomLeft()
            );
        }
        if (rectangle != null && rectangle.iconsVisible()) {
            halfIconRectangle().enqueue(rectangle);
        }
    }

    public static void zippy(float x, float y, float width, float height, float radius, int color) {
        imageBarrier();
        zippy().enqueue(new BuiltZippy(x, y, width, height, radius, color));
    }

    public static void zippy(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            int color
    ) {
        imageBarrier();
        zippy().enqueue(new BuiltZippy(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                color
        ));
    }

    public static void zippy(BuiltZippy zippy) {
        imageBarrier();
        Render2D.zippy().enqueue(zippy);
    }

    public static void rectHalftone(
            float x,
            float y,
            float width,
            float height,
            float radius,
            int color,
            int dotColor,
            float dotSize,
            float dotSpacing
    ) {
        halftoneRect(x, y, width, height, radius, color, dotColor, dotSize, dotSpacing);
    }

    public static void outline(float x, float y, float width, float height, float radius, float thickness, int color) {
        imageBarrier();
        outline().enqueue(new BuiltOutline(x, y, width, height, radius, thickness, color));
    }

    public static void outline(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            float thickness,
            int color
    ) {
        imageBarrier();
        outline().enqueue(new BuiltOutline(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                thickness,
                color
        ));
    }

    public static void outline(
            float x,
            float y,
            float width,
            float height,
            float radius,
            float thickness,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft
    ) {
        outline(x, y, width, height, radius, radius, radius, radius, thickness, colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft);
    }

    public static void outline(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            float thickness,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft
    ) {
        imageBarrier();
        outline().enqueue(new BuiltOutline(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                thickness,
                colorTopLeft,
                colorTopRight,
                colorBottomRight,
                colorBottomLeft,
                BuiltOutline.DEFAULT_SMOOTHNESS
        ));
    }

    public static void outline(GuiGraphics graphics, float x, float y, float width, float height, float radius, float thickness, int color) {
        beginFrame(graphics);
        outline(x, y, width, height, radius, thickness, color);
        flush();
    }

    public static void outline(BuiltOutline outline) {
        imageBarrier();
        Render2D.outline().enqueue(outline);
    }

    public static Outline360Range outline360Range(float startDegrees, float endDegrees, int color) {
        return Outline360Range.of(startDegrees, endDegrees, color);
    }

    public static void outline360(
            float x,
            float y,
            float width,
            float height,
            float radius,
            float thickness,
            int defaultColor,
            Outline360Range... ranges
    ) {
        imageBarrier();
        outline360(x, y, width, height, radius, thickness, defaultColor, Arrays.asList(ranges));
    }

    public static void outline360(
            float x,
            float y,
            float width,
            float height,
            float radius,
            float thickness,
            int defaultColor,
            List<Outline360Range> ranges
    ) {
        imageBarrier();
        outline360().enqueue(new BuiltOutline360(x, y, width, height, radius, thickness, defaultColor, ranges));
    }

    public static void outline360(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            float thickness,
            int defaultColor,
            Outline360Range... ranges
    ) {
        imageBarrier();
        outline360(x, y, width, height, radiusTopLeft, radiusTopRight, radiusBottomRight, radiusBottomLeft, thickness, defaultColor, Arrays.asList(ranges));
    }

    public static void outline360(
            float x,
            float y,
            float width,
            float height,
            float radiusTopLeft,
            float radiusTopRight,
            float radiusBottomRight,
            float radiusBottomLeft,
            float thickness,
            int defaultColor,
            List<Outline360Range> ranges
    ) {
        imageBarrier();
        outline360().enqueue(new BuiltOutline360(
                x,
                y,
                width,
                height,
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                thickness,
                defaultColor,
                ranges
        ));
    }

    public static void outline360(BuiltOutline360 outline) {
        imageBarrier();
        Render2D.outline360().enqueue(outline);
    }

    public static void text(String nameFont, String text, float x, float y, float size, int color) {
        imageBarrier();
        Render2D.text().enqueue(new BuiltText(nameFont, text, x, y, size, color));
    }

    public static void text(FontType font, String text, float x, float y, float size, int color) {
        if (canUseMsdf(font, text)) {
            imageBarrier();
            Render2D.msdfIcon().draw(font, text, x, y, size, color);
            return;
        }
        text(fontName(font), text, x, y, size, color);
        if (font == FontType.BOLD) {
            text(fontName(font), text, x + boldOffset(size), y, size, color);
        }
    }

    public static void textFade(String nameFont, String text, float x, float y, float size, int color, float fadeLeftX, float fadeRightX, float fadeWidth, boolean fadeLeft, boolean fadeRight) {
        imageBarrier();
        Render2D.text().enqueue(new BuiltText(nameFont, text, x, y, size, color).withHorizontalFade(fadeLeftX, fadeRightX, fadeWidth, fadeLeft, fadeRight));
    }

    public static void textFade(FontType font, String text, float x, float y, float size, int color, float fadeLeftX, float fadeRightX, float fadeWidth, boolean fadeLeft, boolean fadeRight) {
        if (canUseMsdf(font, text)) {
            imageBarrier();
            Render2D.msdfIcon().draw(font, text, x, y, size, color, color, color, color, 0.0f, x, y, fadeLeft, fadeRight, fadeLeftX, fadeRightX, fadeWidth, fadeLeft ? 1.0f : 0.0f, fadeRight ? 1.0f : 0.0f);
            return;
        }
        textFade(fontName(font), text, x, y, size, color, fadeLeftX, fadeRightX, fadeWidth, fadeLeft, fadeRight);
        if (font == FontType.BOLD) {
            textFade(fontName(font), text, x + boldOffset(size), y, size, color, fadeLeftX, fadeRightX, fadeWidth, fadeLeft, fadeRight);
        }
    }

    public static void textFade(String nameFont, String text, float x, float y, float size, int color, float fadeLeftX, float fadeRightX, float fadeWidth, float fadeLeftStrength, float fadeRightStrength) {
        imageBarrier();
        Render2D.text().enqueue(new BuiltText(nameFont, text, x, y, size, color).withHorizontalFade(fadeLeftX, fadeRightX, fadeWidth, fadeLeftStrength, fadeRightStrength));
    }

    public static void textFade(FontType font, String text, float x, float y, float size, int color, float fadeLeftX, float fadeRightX, float fadeWidth, float fadeLeftStrength, float fadeRightStrength) {
        if (canUseMsdf(font, text)) {
            imageBarrier();
            Render2D.msdfIcon().draw(font, text, x, y, size, color, color, color, color, 0.0f, x, y, fadeLeftStrength > 0.001f, fadeRightStrength > 0.001f, fadeLeftX, fadeRightX, fadeWidth, fadeLeftStrength, fadeRightStrength);
            return;
        }
        textFade(fontName(font), text, x, y, size, color, fadeLeftX, fadeRightX, fadeWidth, fadeLeftStrength, fadeRightStrength);
        if (font == FontType.BOLD) {
            textFade(fontName(font), text, x + boldOffset(size), y, size, color, fadeLeftX, fadeRightX, fadeWidth, fadeLeftStrength, fadeRightStrength);
        }
    }

    public static void text(String nameFont, String text, float x, float y, float size, int color, float rotationDegrees, float rotationOriginX, float rotationOriginY) {
        imageBarrier();
        Render2D.text().enqueue(new BuiltText(nameFont, text, x, y, size, color, color, color, color, rotationDegrees, rotationOriginX, rotationOriginY, false, false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
    }

    public static void text(FontType font, String text, float x, float y, float size, int color, float rotationDegrees, float rotationOriginX, float rotationOriginY) {
        if (canUseMsdf(font, text)) {
            imageBarrier();
            Render2D.msdfIcon().draw(font, text, x, y, size, color, color, color, color, rotationDegrees, rotationOriginX, rotationOriginY, false, false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            return;
        }
        text(fontName(font), text, x, y, size, color, rotationDegrees, rotationOriginX, rotationOriginY);
        if (font == FontType.BOLD) {
            text(fontName(font), text, x + boldOffset(size), y, size, color, rotationDegrees, rotationOriginX, rotationOriginY);
        }
    }

    public static void text(
            String nameFont,
            String text,
            float x,
            float y,
            float size,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft
    ) {
        imageBarrier();
        Render2D.text().enqueue(new BuiltText(
                nameFont,
                text,
                x,
                y,
                size,
                colorTopLeft,
                colorTopRight,
                colorBottomRight,
                colorBottomLeft
        ));
    }

    public static void text(
            FontType font,
            String text,
            float x,
            float y,
            float size,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft
    ) {
        if (canUseMsdf(font, text)) {
            imageBarrier();
            Render2D.msdfIcon().draw(font, text, x, y, size, colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft, 0.0f, x, y, false, false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            return;
        }
        text(fontName(font), text, x, y, size, colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft);
        if (font == FontType.BOLD) {
            text(fontName(font), text, x + boldOffset(size), y, size, colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft);
        }
    }

    public static void ripple(
            float x,
            float y,
            float width,
            float height,
            float radius,
            float centerX,
            float centerY,
            float progress,
            int sourceColor,
            int targetColor
    ) {
        ripple(x, y, width, height, radius, 0.5f, centerX, centerY, progress, 25.0f, sourceColor, targetColor);
    }

    public static void ripple(
            float x,
            float y,
            float width,
            float height,
            float radius,
            float smoothness,
            float centerX,
            float centerY,
            float progress,
            float rippleSmoothness,
            int sourceColor,
            int targetColor
    ) {
        ripple().enqueue(new BuiltRipple(
                x, y, width, height,
                radius, radius, radius, radius,
                smoothness,
                centerX, centerY,
                progress, rippleSmoothness,
                sourceColor, targetColor,
                false
        ));
    }

    public static void rippleIcon(
            float x,
            float y,
            float width,
            float height,
            float radius,
            float centerX,
            float centerY,
            float progress,
            int sourceColor,
            int targetColor
    ) {
        ripple().enqueue(new BuiltRipple(
                x, y, width, height,
                radius, radius, radius, radius,
                0.5f,
                centerX, centerY,
                progress, 25.0f,
                sourceColor, targetColor,
                true
        ));
    }

    public static void text(
            String nameFont,
            String text,
            float x,
            float y,
            float size,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft,
            float rotationDegrees,
            float rotationOriginX,
            float rotationOriginY
    ) {
        imageBarrier();
        Render2D.text().enqueue(new BuiltText(
                nameFont,
                text,
                x,
                y,
                size,
                colorTopLeft,
                colorTopRight,
                colorBottomRight,
                colorBottomLeft,
                rotationDegrees,
                rotationOriginX,
                rotationOriginY,
                false,
                false,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f
        ));
    }

    public static void text(
            FontType font,
            String text,
            float x,
            float y,
            float size,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft,
            float rotationDegrees,
            float rotationOriginX,
            float rotationOriginY
    ) {
        if (canUseMsdf(font, text)) {
            imageBarrier();
            Render2D.msdfIcon().draw(font, text, x, y, size, colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft, rotationDegrees, rotationOriginX, rotationOriginY, false, false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            return;
        }
        text(fontName(font), text, x, y, size, colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft, rotationDegrees, rotationOriginX, rotationOriginY);
        if (font == FontType.BOLD) {
            text(fontName(font), text, x + boldOffset(size), y, size, colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft, rotationDegrees, rotationOriginX, rotationOriginY);
        }
    }

    public static void text(GuiGraphics graphics, String nameFont, String text, float x, float y, float size, int color) {
        beginFrame(graphics);
        text(nameFont, text, x, y, size, color);
        flush();
    }

    public static void text(GuiGraphics graphics, FontType font, String text, float x, float y, float size, int color) {
        beginFrame(graphics);
        text(font, text, x, y, size, color);
        flush();
    }

    public static void text(BuiltText text) {
        imageBarrier();
        Render2D.text().enqueue(text);
    }

    public static void image(String texture, float x, float y, float size, float radius) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, size, radius));
    }

    public static void image(String texture, float x, float y, float size, float radius, int color) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, size, radius, color));
    }

    public static void image(String texture, float x, float y, float size, float radius, int... colors) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, size, radius).withColors(colors));
    }

    public static void image(String texture, float x, float y, float width, float height, float radius, int color) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, width, height, radius, color));
    }

    public static void image(String texture, float x, float y, float width, float height, float radius, int... colors) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, width, height, radius).withColors(colors));
    }

    public static void imageUv(String texture, float x, float y, float width, float height, float radius, float u0, float v0, float u1, float v1, int color) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, width, height, radius, color).withUv(u0, v0, u1, v1));
    }

    public static void imageUv(String texture, float x, float y, float width, float height, float radius, float u0, float v0, float u1, float v1, int... colors) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, width, height, radius).withUv(u0, v0, u1, v1).withColors(colors));
    }

    public static void imageUvNearest(String texture, float x, float y, float width, float height, float radius, float smoothness, float u0, float v0, float u1, float v1, int color) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, width, height, radius, color)
                .withSmoothness(smoothness)
                .withNearestFilter()
                .withUv(u0, v0, u1, v1));
    }

    public static void imageUvNearest(String texture, float x, float y, float width, float height, float radius, float smoothness, float u0, float v0, float u1, float v1, int... colors) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, width, height, radius)
                .withSmoothness(smoothness)
                .withNearestFilter()
                .withUv(u0, v0, u1, v1)
                .withColors(colors));
    }

    public static void image(String texture, float x, float y, float size, float radius, float rotationDegrees, float originX, float originY, int color) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, size, radius, color).withRotation(rotationDegrees, originX, originY));
    }

    public static void image(String texture, float x, float y, float size, float radius, float rotationDegrees, float originX, float originY, int... colors) {
        Render2D.image().enqueue(new BuiltImage(texture, x, y, size, radius).withColors(colors).withRotation(rotationDegrees, originX, originY));
    }

    public static void effectIcon(Holder<MobEffect> effect, float x, float y, float size) {
        effectIcon().enqueue(new BuiltEffectIcon(effect, x, y, size));
    }

    public static void effectIcon(Holder<MobEffect> effect, float x, float y, float size, int color) {
        effectIcon().enqueue(new BuiltEffectIcon(effect, x, y, size, color));
    }

    public static void effectIcon(MobEffectInstance effect, float x, float y, float size) {
        if (effect != null && effect.showIcon()) {
            effectIcon().enqueue(new BuiltEffectIcon(effect, x, y, size));
        }
    }

    public static void effectIcon(MobEffectInstance effect, float x, float y, float size, int color) {
        if (effect != null && effect.showIcon()) {
            effectIcon().enqueue(new BuiltEffectIcon(effect, x, y, size, color));
        }
    }

    public static float textWidth(String nameFont, String text, float size) {
        return Render2D.text().width(nameFont, text, size);
    }

    public static float textWidth(FontType font, String text, float size) {
        if (canUseMsdf(font, text)) {
            return Render2D.msdfIcon().width(font, text, size);
        }
        return textWidth(fontName(font), text, size) + (font == FontType.BOLD ? boldOffset(size) : 0.0f);
    }

    public static void warmupText(String nameFont, String text, float size) {
        Render2D.text().glyphLayout(nameFont, text, size);
    }

    public static void warmupText(FontType font, String text, float size) {
        if (canUseMsdf(font, text)) {
            Render2D.msdfIcon().width(font, text, size);
            return;
        }
        warmupText(fontName(font), text, size);
    }

    public static void pushScissor(GuiGraphics graphics, float x, float y, float width, float height) {
        if (graphics == null) {
            return;
        }

        float scissorX = x;
        float scissorY = y;
        float scissorWidth = width;
        float scissorHeight = height;
        if (hasProjectionOverride()) {
            ProjectedRect projected = projectRect(x, y, width, height);
            if (projected == null) {
                projected = new ProjectedRect(0.0f, 0.0f, 0.0f, 0.0f);
            }
            scissorX = projected.x();
            scissorY = projected.y();
            scissorWidth = projected.width();
            scissorHeight = projected.height();
        }

        int left = Render2DCoordinateSpace.toGuiInt(scissorX);
        int top = Render2DCoordinateSpace.toGuiInt(scissorY);
        int right = Render2DCoordinateSpace.toGuiInt(scissorX + scissorWidth);
        int bottom = Render2DCoordinateSpace.toGuiInt(scissorY + scissorHeight);
        ScissorUtil.push(left, top, right, bottom);
        graphics.enableScissor(
                left,
                top,
                right,
                bottom
        );
    }

    public static void popScissor(GuiGraphics graphics) {
        if (graphics != null) {
            graphics.disableScissor();
            ScissorUtil.pop();
        }
    }

    public static void close() {
        BlurFramebuffer.closeInstance();
        GlassRenderer.closeInstance();
        GlassOutlineRenderer.closeInstance();
        DefaultRectangleRenderer.closeInstance();
        HalfIconRectangleRenderer.closeInstance();
        HalftoneRectangleRenderer.closeInstance();
        ZippyRenderer.closeInstance();
        ArcRenderer.closeInstance();
        ArcOutlineRenderer.closeInstance();
        DefaultOutlineRenderer.closeInstance();
        Outline360Renderer.closeInstance();
        TextRenderer.closeInstance();
        MsdfIconRenderer.closeInstance();
        ImageRenderer.closeInstance();
        EffectIconRenderer.closeInstance();
        RippleRenderer.closeInstance();
    }

    private static BlurFramebuffer blur() {
        return BlurFramebuffer.getInstance();
    }

    private static GlassRenderer glass() {
        return GlassRenderer.getInstance();
    }

    private static GlassOutlineRenderer glassOutline() {
        return GlassOutlineRenderer.getInstance();
    }

    private static DefaultRectangleRenderer rectangle() {
        return DefaultRectangleRenderer.getInstance();
    }

    private static HalfIconRectangleRenderer halfIconRectangle() {
        return HalfIconRectangleRenderer.getInstance();
    }

    private static HalftoneRectangleRenderer halftoneRectangle() {
        return HalftoneRectangleRenderer.getInstance();
    }

    private static ZippyRenderer zippy() {
        return ZippyRenderer.getInstance();
    }

    private static ArcRenderer arc() {
        return ArcRenderer.getInstance();
    }

    private static ArcOutlineRenderer arcOutline() {
        return ArcOutlineRenderer.getInstance();
    }

    private static DefaultOutlineRenderer outline() {
        return DefaultOutlineRenderer.getInstance();
    }

    private static Outline360Renderer outline360() {
        return Outline360Renderer.getInstance();
    }

    private static TextRenderer text() {
        return TextRenderer.getInstance();
    }

    private static MsdfIconRenderer msdfIcon() {
        return MsdfIconRenderer.getInstance();
    }

    private static ImageRenderer image() {
        return ImageRenderer.getInstance();
    }

    private static EffectIconRenderer effectIcon() {
        return EffectIconRenderer.getInstance();
    }

    private static RippleRenderer ripple() {
        return RippleRenderer.getInstance();
    }

    private static void imageBarrier() {
        ImageRenderer.getInstance().barrier();
        EffectIconRenderer.getInstance().barrier();
    }

    private static String fontName(FontType font) {
        return font == null ? FontType.DEFAULT.fontName() : font.fontName();
    }

    private static boolean canUseMsdf(FontType font, String text) {
        return font != null && font.msdf() && Render2D.msdfIcon().canRender(font, text);
    }

    private static float boldOffset(float size) {
        return Math.max(0.16f, Math.min(0.42f, size * 0.035f));
    }
}
