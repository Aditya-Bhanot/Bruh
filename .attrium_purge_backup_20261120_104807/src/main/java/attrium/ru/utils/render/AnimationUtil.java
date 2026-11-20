package attrium.ru.utils.render;

import net.minecraft.util.Mth;

/**
 * Framerate-independent primitive float animation & color interpolation utility
 * for Attrium ClickGUI and Glassmorphic HUD Suite (zero heap allocations).
 */
public final class AnimationUtil {
    public static final int ACCENT_AQUA_CYAN = 0xFF00E5FF;
    public static final int ACCENT_PURE_WHITE = 0xFFFFFFFF;
    public static final int ACCENT_CYBER_BLUE = 0xFF38BDF8;
    public static final int ACCENT_GRADIENT_END = 0xFF6366F1;

    public static final String[] ACCENT_THEME_NAMES = new String[]{
            "Aqua Cyan", "Pure White", "Cyber Blue", "Dynamic Gradient"
    };

    private AnimationUtil() {
    }

    /**
     * Clamps frame delta time to prevent animation skips during lag spikes.
     */
    public static float clampDelta(float deltaTime) {
        if (Float.isNaN(deltaTime) || Float.isInfinite(deltaTime) || deltaTime <= 0.0F) {
            return 0.016F;
        }
        return Math.min(deltaTime, 0.05F);
    }

    /**
     * Exponential-decay framerate-independent lerp.
     */
    public static float lerp(float current, float target, float speed, float deltaTime) {
        float dt = clampDelta(deltaTime);
        float factor = 1.0F - (float) Math.exp(-speed * dt);
        float next = current + (target - current) * factor;
        if (Math.abs(target - next) < 0.0005F) {
            return target;
        }
        return next;
    }

    /**
     * Crisp ease-out cubic curve for slide-ins and pop-ups (t in [0, 1]).
     */
    public static float easeOutCubic(float t) {
        float c = Mth.clamp(t, 0.0F, 1.0F);
        float inv = 1.0F - c;
        return 1.0F - inv * inv * inv;
    }

    /**
     * Smooth ease-in-out cubic curve (t in [0, 1]).
     */
    public static float easeInOutCubic(float t) {
        float c = Mth.clamp(t, 0.0F, 1.0F);
        return c < 0.5F
                ? 4.0F * c * c * c
                : 1.0F - (float) Math.pow(-2.0F * c + 2.0F, 3.0D) * 0.5F;
    }

    /**
     * Zero-allocation ARGB linear color interpolation.
     */
    public static int lerpColor(int argbA, int argbB, float t) {
        float c = Mth.clamp(t, 0.0F, 1.0F);
        int aA = (argbA >>> 24) & 0xFF;
        int rA = (argbA >>> 16) & 0xFF;
        int gA = (argbA >>> 8) & 0xFF;
        int bA = argbA & 0xFF;

        int aB = (argbB >>> 24) & 0xFF;
        int rB = (argbB >>> 16) & 0xFF;
        int gB = (argbB >>> 8) & 0xFF;
        int bB = argbB & 0xFF;

        int a = Math.round(aA + (aB - aA) * c);
        int r = Math.round(rA + (rB - rA) * c);
        int g = Math.round(gA + (gB - gA) * c);
        int b = Math.round(bA + (bB - bA) * c);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Multiplies the alpha channel of an ARGB color by alphaFactor (0.0 - 1.0).
     */
    public static int withAlpha(int argb, float alphaFactor) {
        int baseA = (argb >>> 24) & 0xFF;
        int outA = Mth.clamp(Math.round(baseA * Mth.clamp(alphaFactor, 0.0F, 1.0F)), 0, 255);
        return (outA << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Replaces the alpha channel of an RGB/ARGB color with alphaByte (0 - 255).
     */
    public static int withAlphaByte(int argb, int alphaByte) {
        return (Mth.clamp(alphaByte, 0, 255) << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Resolves the active HUD accent color for a given theme index and animation phase.
     */
    public static int resolveAccentColor(int themeIndex, float animSeconds, float phaseOffset) {
        return switch (themeIndex) {
            case 1 -> ACCENT_PURE_WHITE;
            case 2 -> ACCENT_CYBER_BLUE;
            case 3 -> {
                float wave = 0.5F + 0.5F * (float) Math.sin(animSeconds * 2.1F + phaseOffset);
                yield lerpColor(ACCENT_AQUA_CYAN, ACCENT_GRADIENT_END, wave);
            }
            default -> ACCENT_AQUA_CYAN;
        };
    }

    /**
     * Secondary gradient endpoint for dual-tone bars (e.g., TargetHUD health gradient).
     */
    public static int resolveAccentSecondary(int themeIndex, float animSeconds, float phaseOffset) {
        return switch (themeIndex) {
            case 1 -> 0xFFCBD5E1;
            case 2 -> 0xFF0284C7;
            case 3 -> {
                float wave = 0.5F + 0.5F * (float) Math.sin(animSeconds * 2.1F + phaseOffset + 1.2F);
                yield lerpColor(ACCENT_CYBER_BLUE, 0xFFA855F7, wave);
            }
            default -> ACCENT_CYBER_BLUE;
        };
    }
}
