package releon.ru.api.module.impl.visual.frageffect;

public final class FragEffectEasing {
    private FragEffectEasing() {
    }

    public static float quintOut(float value) {
        float t = 1.0F - Math.clamp(value, 0.0F, 1.0F);
        return 1.0F - t * t * t * t * t;
    }

    public static float quartInOut(float value) {
        float t = Math.clamp(value, 0.0F, 1.0F);
        return t < 0.5F ? 8.0F * t * t * t * t : 1.0F - (float) Math.pow(-2.0F * t + 2.0F, 4.0F) / 2.0F;
    }

    public static float sineOut(float value) {
        return (float) Math.sin(Math.clamp(value, 0.0F, 1.0F) * Math.PI / 2.0D);
    }

    public static float sineInOut(float value) {
        return -(float) (Math.cos(Math.PI * Math.clamp(value, 0.0F, 1.0F)) - 1.0D) / 2.0F;
    }

    public static float expoIn(float value) {
        float t = Math.clamp(value, 0.0F, 1.0F);
        return t <= 0.0F ? 0.0F : (float) Math.pow(2.0D, 10.0F * t - 10.0F);
    }

    public static float expoInOut(float value) {
        float t = Math.clamp(value, 0.0F, 1.0F);
        if (t <= 0.0F) {
            return 0.0F;
        }
        if (t >= 1.0F) {
            return 1.0F;
        }
        return t < 0.5F ? (float) Math.pow(2.0D, 20.0F * t - 10.0F) / 2.0F : (2.0F - (float) Math.pow(2.0D, -20.0F * t + 10.0F)) / 2.0F;
    }

    public static float backInOut(float value) {
        float c1 = 1.70158F;
        float c2 = c1 * 1.525F;
        float t = Math.clamp(value, 0.0F, 1.0F);
        if (t < 0.5F) {
            float x = 2.0F * t;
            return x * x * ((c2 + 1.0F) * x - c2) / 2.0F;
        }
        float x = 2.0F * t - 2.0F;
        return (x * x * ((c2 + 1.0F) * x + c2) + 2.0F) / 2.0F;
    }
}
