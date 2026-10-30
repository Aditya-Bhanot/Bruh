package releon.ru.utils.render.ui.rectangle.recthalficon;

import net.minecraft.client.gui.GuiGraphics;

public record BuiltHalfIconRectangle(
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
        float smoothness,
        String fontName,
        String icon,
        float iconSize,
        int iconColor,
        float spacingX,
        float spacingY,
        float paddingX,
        float paddingY,
        float jitterX,
        float jitterY,
        float rotationMaxDegrees,
        long seed
) {
    public static final float DEFAULT_SMOOTHNESS = 1.0f;
    public static final float DEFAULT_ICON_SIZE = 8.0f;
    public static final float DEFAULT_SPACING = 14.0f;
    public static final float DEFAULT_PADDING = 2.0f;

    public BuiltHalfIconRectangle(
            float x,
            float y,
            float width,
            float height,
            float radius,
            int color,
            String fontName,
            String icon,
            float iconSize,
            int iconColor,
            float spacingX,
            float spacingY,
            float rotationMaxDegrees
    ) {
        this(
                x,
                y,
                width,
                height,
                radius,
                radius,
                radius,
                radius,
                color,
                color,
                color,
                color,
                DEFAULT_SMOOTHNESS,
                fontName,
                icon,
                iconSize,
                iconColor,
                spacingX,
                spacingY,
                DEFAULT_PADDING,
                DEFAULT_PADDING,
                0.0f,
                0.0f,
                rotationMaxDegrees,
                0L
        );
    }

    public BuiltHalfIconRectangle withBackground(int color) {
        return new BuiltHalfIconRectangle(
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
                smoothness,
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
        );
    }

    public BuiltHalfIconRectangle withIcon(int iconColor, float iconSize, float spacingX, float spacingY) {
        return new BuiltHalfIconRectangle(
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
                smoothness,
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
        );
    }

    public BuiltHalfIconRectangle withPadding(float paddingX, float paddingY) {
        return new BuiltHalfIconRectangle(
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
                smoothness,
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
        );
    }

    public BuiltHalfIconRectangle withRandomization(float rotationMaxDegrees, float jitterX, float jitterY, long seed) {
        return new BuiltHalfIconRectangle(
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
                smoothness,
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
        );
    }

    public void render(GuiGraphics graphics) {
        HalfIconRectangleRenderer.getInstance().draw(graphics, this);
    }

    public boolean visible() {
        return backgroundVisible() || iconsVisible();
    }

    public boolean backgroundVisible() {
        return width > 0.0f
                && height > 0.0f
                && (((colorTopLeft | colorTopRight | colorBottomRight | colorBottomLeft) >>> 24) & 0xFF) > 0;
    }

    public boolean iconsVisible() {
        return width > 0.0f
                && height > 0.0f
                && fontName != null
                && icon != null
                && !icon.isEmpty()
                && iconSize > 0.0f
                && (iconColor >>> 24) != 0;
    }
}
