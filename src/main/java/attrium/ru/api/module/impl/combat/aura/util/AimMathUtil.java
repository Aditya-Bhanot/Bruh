package attrium.ru.api.module.impl.combat.aura.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Extended AimMathUtil supporting:
 * 1. Bounded 3D Bounding-Box & Block-Face Randomization (Zero Static Center Locking)
 * 2. Cubic Bezier Multi-Tick Cursor Drift Interpolation
 * 3. Proportional-Derivative (PD) Kinematic Smoothing & GCD Sensitivity Quantization
 * 4. Strict Bounding-Box Raycast Intersection Verification
 */
public final class AimMathUtil {
    private AimMathUtil() {
    }

    public static double randomDouble(double min, double max) {
        if (max <= min) {
            return min;
        }
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    public static int randomInt(int min, int maxInclusive) {
        if (maxInclusive <= min) {
            return min;
        }
        return ThreadLocalRandom.current().nextInt(min, maxInclusive + 1);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static boolean rollChance(double percentage) {
        if (percentage >= 100.0D) {
            return true;
        }
        if (percentage <= 0.0D) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble(0.0D, 100.0D) <= percentage;
    }

    /**
     * Generates a randomized 3D coordinate strictly inside the shrunk inner safe core of a bounding box,
     * explicitly avoiding the exact geometric center (0.5, 0.5, 0.5).
     */
    public static Vec3 generateRandomBoxPoint(AABB box, double marginRatio, double extraJitterDegrees) {
        double mx = box.getXsize() * clamp(marginRatio, 0.12D, 0.42D);
        double my = box.getYsize() * clamp(marginRatio, 0.12D, 0.42D);
        double mz = box.getZsize() * clamp(marginRatio, 0.12D, 0.42D);

        double minX = box.minX + mx;
        double maxX = box.maxX - mx;
        double minY = box.minY + my;
        double maxY = box.maxY - my;
        double minZ = box.minZ + mz;
        double maxZ = box.maxZ - mz;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double rx = Mth.lerp(0.5D + clamp(rng.nextGaussian() * 0.28D, -0.48D, 0.48D), minX, maxX);
        double ry = Mth.lerp(0.5D + clamp(rng.nextGaussian() * 0.28D, -0.48D, 0.48D), minY, maxY);
        double rz = Mth.lerp(0.5D + clamp(rng.nextGaussian() * 0.28D, -0.48D, 0.48D), minZ, maxZ);

        Vec3 center = box.getCenter();
        if (Math.abs(rx - center.x) < 0.025D) {
            rx += (rng.nextBoolean() ? 0.045D : -0.045D);
        }
        if (Math.abs(ry - center.y) < 0.025D) {
            ry += (rng.nextBoolean() ? 0.045D : -0.045D);
        }
        if (Math.abs(rz - center.z) < 0.025D) {
            rz += (rng.nextBoolean() ? 0.045D : -0.045D);
        }

        if (extraJitterDegrees > 0.0D) {
            double spatialJitter = clamp(extraJitterDegrees * 0.012D, 0.0D, 0.14D);
            rx += randomDouble(-spatialJitter, spatialJitter);
            ry += randomDouble(-spatialJitter, spatialJitter);
            rz += randomDouble(-spatialJitter, spatialJitter);
        }

        return new Vec3(
                clamp(rx, box.minX + 0.08D, box.maxX - 0.08D),
                clamp(ry, box.minY + 0.08D, box.maxY - 0.08D),
                clamp(rz, box.minZ + 0.08D, box.maxZ - 0.08D)
        );
    }

    /**
     * Generates a randomized 3D hit vector on the specified placement face of a block.
     */
    public static Vec3 generateRandomFacePoint(BlockPos pos, Direction face, double extraJitter) {
        AABB blockBox = new AABB(pos);
        Vec3 base = generateRandomBoxPoint(blockBox, 0.20D, extraJitter);
        return switch (face) {
            case UP -> new Vec3(base.x, pos.getY() + randomDouble(0.92D, 0.99D), base.z);
            case DOWN -> new Vec3(base.x, pos.getY() + randomDouble(0.01D, 0.08D), base.z);
            case NORTH -> new Vec3(base.x, base.y, pos.getZ() + randomDouble(0.01D, 0.08D));
            case SOUTH -> new Vec3(base.x, base.y, pos.getZ() + randomDouble(0.92D, 0.99D));
            case WEST -> new Vec3(pos.getX() + randomDouble(0.01D, 0.08D), base.y, base.z);
            case EAST -> new Vec3(pos.getX() + randomDouble(0.92D, 0.99D), base.y, base.z);
        };
    }

    /**
     * Evaluates a 3D cubic Bezier curve between p0 and p3 with control points p1 and p2.
     */
    public static Vec3 cubicBezierVec3(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double u = clamp(t, 0.0D, 1.0D);
        double inv = 1.0D - u;
        double b0 = inv * inv * inv;
        double b1 = 3.0D * inv * inv * u;
        double b2 = 3.0D * inv * u * u;
        double b3 = u * u * u;
        return new Vec3(
                b0 * p0.x + b1 * p1.x + b2 * p2.x + b3 * p3.x,
                b0 * p0.y + b1 * p1.y + b2 * p2.y + b3 * p3.y,
                b0 * p0.z + b1 * p1.z + b2 * p2.z + b3 * p3.z
        );
    }

    /**
     * Computes GCD-quantized yaw and pitch angles from eyePos to targetVec.
     */
    public static float[] computeAnglesToPoint(Vec3 eyePos, Vec3 targetVec, float currentYaw, float currentPitch) {
        double dx = targetVec.x - eyePos.x;
        double dy = targetVec.y - eyePos.y;
        double dz = targetVec.z - eyePos.z;
        double horiz = Math.hypot(dx, dz);
        if (horiz < 1.0E-5D && Math.abs(dy) < 1.0E-5D) {
            return new float[]{currentYaw, currentPitch};
        }
        float rawYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float rawPitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        float yawDelta = Mth.wrapDegrees(rawYaw - currentYaw);
        float pitchDelta = rawPitch - currentPitch;

        double gcd = MathUtils.computeGcd();
        if (gcd > 0.0D) {
            float g = (float) gcd;
            yawDelta = Math.round(yawDelta / g) * g;
            pitchDelta = Math.round(pitchDelta / g) * g;
        }
        return new float[]{
                currentYaw + yawDelta,
                Mth.clamp(currentPitch + pitchDelta, -89.5F, 89.5F)
        };
    }

    /**
     * Verifies whether a ray cast from eyePos along (yaw, pitch) intersects box within maxRange.
     */
    public static boolean doesRayIntersectBox(Vec3 eyePos, float yaw, float pitch, AABB box, double maxRange) {
        if (box.contains(eyePos)) {
            return true;
        }
        Vec3 lookDir = Vec3.directionFromRotation(pitch, yaw);
        Vec3 rayEnd = eyePos.add(lookDir.scale(maxRange));
        Optional<Vec3> hit = box.clip(eyePos, rayEnd);
        return hit.isPresent() && eyePos.distanceToSqr(hit.get()) <= (maxRange * maxRange);
    }
}
