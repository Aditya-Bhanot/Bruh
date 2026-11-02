package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.attack.StrikeManager;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;

import java.security.SecureRandom;

public class FTAngle extends RotateConstructor {
    public static final FTAngle INSTANCE = new FTAngle();

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long LOOK_DELAY_MS = 3_500L;
    private static final int AIR_MISS_INTERVAL = 31;
    private static final long AIR_MISS_LOOK_MS = 250L;
    private static final long AIR_MISS_SWING_MS = 238L;

    private int lastAirMissSwingCount = -1;

    public FTAngle() {
        super("FunTime");
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        AuraModule aura = AuraModule.getInstance();
        StrikeManager attackHandler = aura == null ? null : aura.getAttackHandlerRaw();
        if (attackHandler == null) {
            return FunTimeSnapAngle.INSTANCE.limitAngleChange(currentAngle, targetAngle, vec3d, entity);
        }

        StopWatch attackTimer = attackHandler.getAttackTimer();
        int count = attackHandler.getCount();
        Angle angleDelta = MathAngle.calculateDelta(currentAngle, targetAngle);
        float yawDelta = angleDelta.getYaw();
        float pitchDelta = angleDelta.getPitch();
        float rotationDifference = (float) Math.hypot(Math.abs(yawDelta), Math.abs(pitchDelta));
        if (rotationDifference < 1.0E-4F) {
            rotationDifference = 1.0E-4F;
        }

        long elapsed = attackTimer.elapsedTime();
        boolean airMiss = count > 0 && count % AIR_MISS_INTERVAL == 0 && elapsed < AIR_MISS_LOOK_MS;
        if (airMiss) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && elapsed >= AIR_MISS_SWING_MS && lastAirMissSwingCount != count) {
                client.player.swing(InteractionHand.MAIN_HAND);
                lastAirMissSwingCount = count;
            }
            float missYaw = currentAngle.getYaw() + Mth.clamp(yawDelta, -22.0F, 22.0F);
            return new Angle(missYaw, -85.0F);
        }

        if (entity != null) {
            return applyRecordedTracking(currentAngle, yawDelta, pitchDelta, rotationDifference, entity, aura, attackHandler, attackTimer);
        }

        return applyRecordedDelay(currentAngle, yawDelta, pitchDelta, rotationDifference, attackTimer, count);
    }

    @Override
    public Vec3 randomValue() {
        return new Vec3(0.05, 0.1, 0.02);
    }

    private Angle applyRecordedTracking(
            Angle currentAngle,
            float yawDelta,
            float pitchDelta,
            float rotationDifference,
            Entity entity,
            AuraModule aura,
            StrikeManager attackHandler,
            StopWatch attackTimer
    ) {
        Minecraft client = Minecraft.getInstance();
        int count = attackHandler.getCount();
        boolean attackZone = aura != null && client.player != null && client.player.distanceTo(entity) <= aura.attackDistance();
        boolean attackNow = aura != null && attackHandler.canAttack(aura.getConfig(), 0);
        boolean attackSoon = aura != null && attackHandler.canAttack(aura.getConfig(), 1);
        boolean recentAttack = !attackTimer.finished(180);

        float yawBudget = rand(18.0F, 28.0F);
        float pitchBudget = rand(2.8F, 6.2F);

        if (attackSoon) {
            yawBudget = Math.max(yawBudget, rand(34.0F, 52.0F));
            pitchBudget = Math.max(pitchBudget, rand(4.2F, 7.8F));
        }

        if (recentAttack) {
            yawBudget = Math.max(yawBudget, rand(44.0F, 72.0F));
            pitchBudget = Math.max(pitchBudget, rand(5.4F, 10.0F));
        }

        if (Math.abs(yawDelta) > 40.0F) {
            yawBudget += rand(10.0F, 18.0F);
        }
        if (Math.abs(yawDelta) > 75.0F) {
            yawBudget += rand(12.0F, 24.0F);
        }
        if (Math.abs(pitchDelta) > 20.0F) {
            pitchBudget += rand(1.4F, 3.2F);
        }
        if (Math.abs(pitchDelta) > 35.0F) {
            pitchBudget += rand(1.6F, 3.8F);
        }

        float moveYaw = Mth.clamp(yawDelta, -axisBudget(yawDelta, rotationDifference, yawBudget), axisBudget(yawDelta, rotationDifference, yawBudget));
        float movePitch = Mth.clamp(pitchDelta, -axisBudget(pitchDelta, rotationDifference, pitchBudget), axisBudget(pitchDelta, rotationDifference, pitchBudget));

        float blend = attackNow
                ? 1.0F
                : attackSoon
                ? rand(0.88F, 0.97F)
                : recentAttack
                ? rand(0.74F, 0.88F)
                : rand(0.56F, 0.74F);

        if (attackZone && !attackSoon && !recentAttack) {
            blend = Math.max(blend, rand(0.68F, 0.82F));
        }

        float shakeScale = attackZone ? 1.25F : 0.9F;
        if (attackSoon) {
            shakeScale = Math.max(shakeScale, 1.4F);
        }
        if (recentAttack) {
            shakeScale = Math.max(shakeScale, 1.55F);
        }
        float shakeYaw = trackShakeYaw(attackTimer.elapsedTime(), count, shakeScale, Math.abs(yawDelta));
        float shakePitch = trackShakePitch(attackTimer.elapsedTime(), count, shakeScale, Math.abs(pitchDelta));

        if (Math.abs(yawDelta) < 4.0F) {
            shakeYaw *= 0.35F;
        }
        if (Math.abs(pitchDelta) < 2.5F) {
            shakePitch *= 0.25F;
        }

        float newYaw = Mth.lerp(blend, currentAngle.getYaw(), currentAngle.getYaw() + moveYaw) + shakeYaw;
        float newPitch = Mth.lerp(blend, currentAngle.getPitch(), currentAngle.getPitch() + movePitch) + shakePitch;
        return new Angle(newYaw, Mth.clamp(newPitch, -90.0F, 90.0F));
    }

    private Angle applyRecordedDelay(
            Angle currentAngle,
            float yawDelta,
            float pitchDelta,
            float rotationDifference,
            StopWatch attackTimer,
            int count
    ) {
        long elapsed = attackTimer.elapsedTime();
        Angle oscillation = switch (count % 4) {
            case 0 -> new Angle(
                    (float) Math.cos(elapsed / 40.0F + (count % 6)),
                    (float) Math.sin(elapsed / 40.0F + (count % 6))
            );
            case 1 -> new Angle(
                    (float) Math.sin(elapsed / 40.0F + (count % 6)),
                    (float) Math.cos(elapsed / 40.0F + (count % 6))
            );
            case 2 -> new Angle(
                    (float) Math.sin(elapsed / 40.0F + (count % 6)),
                    (float) -Math.cos(elapsed / 40.0F + (count % 6))
            );
            default -> new Angle(
                    (float) -Math.cos(elapsed / 40.0F + (count % 6)),
                    (float) Math.sin(elapsed / 40.0F + (count % 6))
            );
        };

        float holdProgress = Mth.clamp(elapsed / (float) LOOK_DELAY_MS, 0.0F, 1.0F);
        float holdScale = attackTimer.finished(LOOK_DELAY_MS) ? 0.0F : 1.0F - holdProgress * 0.55F;

        float idleYaw = holdScale > 0.0F ? rand(12.0F, 22.0F) * oscillation.getYaw() * holdScale : 0.0F;
        float pitchWave = rand(0.35F, 1.35F) * (float) Math.cos((double) System.currentTimeMillis() / 420.0 + count);
        float idlePitch = holdScale > 0.0F
                ? (rand(2.2F, 5.8F) * oscillation.getPitch() + pitchWave) * holdScale
                : 0.0F;

        float yawBudget = !attackTimer.finished(180)
                ? rand(0.0F, 3.5F)
                : !attackTimer.finished(600)
                ? rand(4.0F, 10.0F)
                : attackTimer.finished(LOOK_DELAY_MS)
                ? rand(12.0F, 28.0F)
                : rand(6.0F, 14.0F);

        float pitchBudget = !attackTimer.finished(180)
                ? rand(0.0F, 1.0F)
                : !attackTimer.finished(600)
                ? rand(1.2F, 3.0F)
                : attackTimer.finished(LOOK_DELAY_MS)
                ? rand(3.0F, 6.8F)
                : rand(1.5F, 4.2F);

        float moveYaw = Mth.clamp(yawDelta, -axisBudget(yawDelta, rotationDifference, yawBudget), axisBudget(yawDelta, rotationDifference, yawBudget));
        float movePitch = Mth.clamp(pitchDelta, -axisBudget(pitchDelta, rotationDifference, pitchBudget), axisBudget(pitchDelta, rotationDifference, pitchBudget));

        float returnBlend = !attackTimer.finished(180)
                ? 0.0F
                : !attackTimer.finished(600)
                ? rand(0.08F, 0.22F)
                : attackTimer.finished(LOOK_DELAY_MS)
                ? rand(0.54F, 0.78F)
                : rand(0.20F, 0.42F);

        float newYaw = Mth.lerp(returnBlend, currentAngle.getYaw(), currentAngle.getYaw() + moveYaw) + idleYaw;
        float newPitch = Mth.lerp(returnBlend, currentAngle.getPitch(), currentAngle.getPitch() + movePitch) + idlePitch;
        return new Angle(newYaw, Mth.clamp(newPitch, -90.0F, 90.0F));
    }

    private float trackShakeYaw(long elapsed, int count, float scale, float absYawDelta) {
        float shake = (float) Math.sin(elapsed / 38.0F + count * 0.37F) * rand(0.45F, 1.25F)
                + (float) Math.cos(elapsed / 71.0F + count * 0.18F) * rand(0.18F, 0.55F);

        if (chance(absYawDelta > 24.0F ? 0.22F : 0.08F)) {
            shake += rand(-1.55F, 1.55F);
        }

        return shake * scale;
    }

    private float trackShakePitch(long elapsed, int count, float scale, float absPitchDelta) {
        float shake = (float) Math.sin(elapsed / 52.0F + count * 0.21F) * rand(0.10F, 0.42F)
                + (float) Math.cos(elapsed / 93.0F + count * 0.11F) * rand(0.08F, 0.28F);

        if (chance(absPitchDelta > 8.0F ? 0.18F : 0.06F)) {
            shake += rand(-0.55F, 0.55F);
        }

        return shake * scale;
    }

    private float axisBudget(float axisDelta, float rotationDifference, float budget) {
        return Math.abs(axisDelta / rotationDifference) * budget;
    }

    private boolean chance(float probability) {
        return RANDOM.nextFloat() < probability;
    }

    private float rand(float min, float max) {
        return Mth.lerp(RANDOM.nextFloat(), min, max);
    }
}
