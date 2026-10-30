package releon.ru.api.module.impl.visual.particles;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

final class ParticleMath {
    private ParticleMath() {
    }

    static Vec3 randomVec(Random random, Vec3 at, float minDistance, float maxDistance) {
        float min = Math.min(minDistance, maxDistance);
        float max = Math.max(minDistance, maxDistance);
        float yaw = randomFloat(random, -180.0F, 180.0F);
        float pitch = randomFloat(random, -90.0F, 90.0F);
        float distance = min == max ? min : randomFloat(random, min, max);
        double[] offset = vec3FromAngles(yaw, pitch, distance);
        return at.add(offset[0], offset[1], offset[2]);
    }

    static float[] vecNeeded(Vec3 to, Vec3 from) {
        return vecNeeded(to.x, to.y, to.z, from.x, from.y, from.z);
    }

    static float[] vecNeeded(double toX, double toY, double toZ, double fromX, double fromY, double fromZ) {
        return vecNeeded(toX, toY, toZ, fromX, fromY, fromZ, new float[2]);
    }

    static float[] vecNeeded(Vec3 to, Vec3 from, float[] out) {
        return vecNeeded(to.x, to.y, to.z, from.x, from.y, from.z, out);
    }

    static float[] vecNeeded(double toX, double toY, double toZ, double fromX, double fromY, double fromZ, float[] out) {
        double diffX = toX - fromX;
        double diffY = toY - fromY;
        double diffZ = toZ - fromZ;
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float[] result = out == null || out.length < 2 ? new float[2] : out;
        result[0] = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0F;
        result[1] = (float) Math.toDegrees(-Math.atan2(diffY, diffXZ));
        return result;
    }

    static float stepRotationSpeed(float rotate, float rotateTo, float speed360) {
        float diff = Mth.wrapDegrees(rotateTo - rotate);
        diff = Math.copySign(Math.min(Math.abs(diff), speed360), diff);
        return Mth.wrapDegrees(rotate + diff);
    }

    static float[] yawPitchFromDelta(double motionX, double motionY, double motionZ) {
        return yawPitchFromDelta(motionX, motionY, motionZ, new float[2]);
    }

    static float[] yawPitchFromDelta(double motionX, double motionY, double motionZ, float[] out) {
        float yaw = 0.0F;
        float pitch = 0.0F;
        double xzLength = Math.sqrt(motionX * motionX + motionZ * motionZ);
        if (xzLength > 1.0E-6D) {
            yaw = (float) Math.toDegrees(Math.atan2(motionZ, motionX)) - 90.0F;
            yaw = Mth.wrapDegrees(yaw);
        }
        if (xzLength > 1.0E-6D || Math.abs(motionY) > 1.0E-6D) {
            pitch = (float) Math.toDegrees(-Math.atan2(motionY, xzLength));
            pitch = Mth.clamp(pitch, -90.0F, 90.0F);
        }
        float[] result = out == null || out.length < 2 ? new float[2] : out;
        result[0] = yaw;
        result[1] = pitch;
        return result;
    }

    static double[] vec3FromAngles(float yaw, float pitch, double length) {
        return vec3FromAngles(yaw, pitch, length, new double[3]);
    }

    static double[] vec3FromAngles(float yaw, float pitch, double length, double[] out) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double x = Math.cos(pitchRadians) * Math.sin(yawRadians);
        double y = Math.sin(pitchRadians);
        double z = Math.cos(pitchRadians) * Math.cos(yawRadians);
        double[] result = out == null || out.length < 3 ? new double[3] : out;
        result[0] = x * -length;
        result[1] = y * -length;
        result[2] = z * length;
        return result;
    }

    static double interpolate(double previous, double current, float partialTicks) {
        return current + (previous - current) * partialTicks;
    }

    static float interpolate(float previous, float current, float partialTicks) {
        return current + (previous - current) * partialTicks;
    }

    static float randomFloat(Random random, float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    static float cubicOut(float value) {
        return 1.0F - (float) Math.pow(1.0F - value, 3.0D);
    }

    static float sineOut(float value) {
        return (float) Math.sin(value * Math.PI / 2.0D);
    }

    static float quadInOut(float value) {
        return value < 0.5F ? 2.0F * value * value : 1.0F - (float) Math.pow(-2.0F * value + 2.0F, 2.0D) / 2.0F;
    }

    static float quintOut(float value) {
        return 1.0F - (float) Math.pow(1.0F - value, 5.0D);
    }

    static float valWave01(float value) {
        return (value > 0.5F ? 1.0F - value : value) * 2.0F;
    }
}
