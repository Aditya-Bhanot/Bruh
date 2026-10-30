package releon.ru.utils.math;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.utils.render.animation.modernfx.Decelerate;
import releon.ru.utils.render.animation.modernfx.Direction;

import java.util.concurrent.ThreadLocalRandom;

public final class MathUtils {
    public static final double PI2 = Math.PI * 2.0;

    private MathUtils() {
    }

    public static int getRandom(int min, int max) {
        if (min == max) {
            return min;
        }
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public static float getRandom(float min, float max) {
        return (float) getRandom((double) min, (double) max);
    }

    public static double getRandom(double min, double max) {
        if (min == max) {
            return min;
        }
        if (min > max) {
            double tmp = min;
            min = max;
            max = tmp;
        }
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    public static double computeGcd() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.options == null) {
            return 0.0;
        }
        double factor = client.options.sensitivity().get() * 0.6D + 0.2D;
        return factor * factor * factor * 1.2D;
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    public static boolean inside(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    public static Decelerate createAnimation(int ms, Direction direction) {
        Decelerate animation = new Decelerate();
        animation.setMs(ms);
        animation.setValue(1.0);
        animation.setDirection(direction);
        animation.counter.setTime(System.currentTimeMillis() - ms - 1L);
        return animation;
    }

    public static String shortBind(KeyBind bind) {
        if (bind == null || !bind.isBound()) {
            return "...";
        }
        return bind.getDisplayName()
                .replace("MOUSE ", "M")
                .replace("L SHIFT", "LSH")
                .replace("R SHIFT", "RSH")
                .replace("L CTRL", "LCT")
                .replace("R CTRL", "RCT")
                .replace("SPACE", "SPC");
    }

    public static float interpolate(float prev, float to, float value) {
        return prev + (to - prev) * value;
    }

    public static double interpolate(double prev, double to, double value) {
        return prev + (to - prev) * value;
    }

    public static float interpolate(float prev, float to) {
        return Mth.lerp(tickDelta(), prev, to);
    }

    public static double interpolate(double prev, double to) {
        return Mth.lerp(tickDelta(), prev, to);
    }

    public static Vec3 interpolate(Vec3 previous, Vec3 current) {
        if (previous == null || current == null) {
            return Vec3.ZERO;
        }
        return new Vec3(
                interpolate(previous.x, current.x),
                interpolate(previous.y, current.y),
                interpolate(previous.z, current.z)
        );
    }

    public static Vec3 interpolate(Entity entity) {
        if (entity == null) {
            return Vec3.ZERO;
        }
        return new Vec3(
                interpolate(entity.xo, entity.getX()),
                interpolate(entity.yo, entity.getY()),
                interpolate(entity.zo, entity.getZ())
        );
    }

    private static float tickDelta() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getDeltaTracker() == null) {
            return 1.0f;
        }
        return client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }
}
