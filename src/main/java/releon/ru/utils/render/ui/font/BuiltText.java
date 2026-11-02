package releon.ru.utils.render.ui.font;

public record BuiltText(
        String fontName,
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
        float rotationOriginY,
        boolean fadeLeft,
        boolean fadeRight,
        float fadeLeftX,
        float fadeRightX,
        float fadeWidth,
        float fadeLeftStrength,
        float fadeRightStrength
) {
    public BuiltText(String fontName, String text, float x, float y, float size, int color) {
        this(fontName, text, x, y, size, color, color, color, color);
    }

    public BuiltText(String fontName, String text, float x, float y, float size, int colorTopLeft, int colorTopRight, int colorBottomRight, int colorBottomLeft) {
        this(fontName, text, x, y, size, colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft, 0.0f, x, y, false, false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    public boolean visible() {
        return text != null
                && !text.isEmpty()
                && size > 0.0f
                && (((colorTopLeft | colorTopRight | colorBottomRight | colorBottomLeft) >>> 24) & 0xFF) > 0;
    }

    public boolean hasHorizontalFade() {
        return (fadeLeftStrength > 0.001f || fadeRightStrength > 0.001f) && fadeWidth > 0.0f && fadeRightX > fadeLeftX;
    }

    public BuiltText withHorizontalFade(float leftX, float rightX, float width, boolean left, boolean right) {
        return withHorizontalFade(leftX, rightX, width, left ? 1.0f : 0.0f, right ? 1.0f : 0.0f);
    }

    public BuiltText withHorizontalFade(float leftX, float rightX, float width, float leftStrength, float rightStrength) {
        return new BuiltText(
                fontName,
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
                leftStrength > 0.001f,
                rightStrength > 0.001f,
                leftX,
                rightX,
                width,
                clamp01(leftStrength),
                clamp01(rightStrength)
        );
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
