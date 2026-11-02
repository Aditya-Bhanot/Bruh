package releon.ru.utils.render.color;

public final class ColorUtil {
    public static final int WHITE = rgba(255, 255, 255, 255);
    public static final int TRANSPARENT = rgba(0, 0, 0, 0);

    private ColorUtil() {
    }

    public static int rgba(int red, int green, int blue, int alpha) {
        return (clamp(alpha) << 24)
                | (clamp(red) << 16)
                | (clamp(green) << 8)
                | clamp(blue);
    }

    public static int rgba(int red, int green, int blue, int alpha, float alphaMultiplier) {
        return rgba(red, green, blue, Math.round(alpha * alphaMultiplier));
    }

    public static int getAlpha(int color) {
        return (color >>> 24) & 0xFF;
    }

    public static int getRed(int color) {
        return (color >>> 16) & 0xFF;
    }

    public static int getGreen(int color) {
        return (color >>> 8) & 0xFF;
    }

    public static int getBlue(int color) {
        return color & 0xFF;
    }

    public static int withAlpha(int color, int alpha) {
        return rgba(getRed(color), getGreen(color), getBlue(color), alpha);
    }

    public static int multAlpha(int color, float alphaMultiplier) {
        return withAlpha(color, Math.round(getAlpha(color) * clamp01(alphaMultiplier)));
    }

    public static int multRed(int color, float multiplier) {
        float safeMultiplier = Math.max(0.0001f, multiplier);
        return rgba(
                getRed(color),
                Math.round(Math.min(255.0f, getGreen(color) / safeMultiplier)),
                Math.round(Math.min(255.0f, getBlue(color) / safeMultiplier)),
                getAlpha(color)
        );
    }

    public static int scaleAlpha(int color, float alphaMultiplier) {
        return multAlpha(color, alphaMultiplier);
    }

    public static int lerpColor(int first, int second, float factor) {
        float t = clamp01(factor);
        return rgba(
                Math.round(getRed(first) + (getRed(second) - getRed(first)) * t),
                Math.round(getGreen(first) + (getGreen(second) - getGreen(first)) * t),
                Math.round(getBlue(first) + (getBlue(second) - getBlue(first)) * t),
                Math.round(getAlpha(first) + (getAlpha(second) - getAlpha(first)) * t)
        );
    }

    public static int interpolateColor(int first, int second, float factor) {
        return lerpColor(first, second, factor);
    }

    public static int tintForDamage(int color, float hurtProgress) {
        return lerpColor(color, rgba(255, 60, 60, getAlpha(color)), clamp01(hurtProgress) * 0.45f);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
