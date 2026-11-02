package releon.ru.api.module.impl.combat.aura.util;

import net.minecraft.client.Minecraft;

import java.util.concurrent.ThreadLocalRandom;

public final class MathUtils {
    private MathUtils() {
    }

    public static float getRandom(float min, float max) {
        if (min == max) {
            return min;
        }
        if (max < min) {
            float tmp = min;
            min = max;
            max = tmp;
        }
        return (float) ThreadLocalRandom.current().nextDouble(min, max);
    }

    public static double computeGcd() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return 0.0;
        }
        double sensitivity = client.options.sensitivity().get();
        double factor = sensitivity * 0.6D + 0.2D;
        return factor * factor * factor * 1.2D;
    }
}
