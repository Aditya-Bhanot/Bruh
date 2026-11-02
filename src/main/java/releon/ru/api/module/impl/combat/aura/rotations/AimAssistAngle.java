package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;

import java.util.concurrent.ThreadLocalRandom;

public final class AimAssistAngle extends RotateConstructor {
    public static final AimAssistAngle INSTANCE = new AimAssistAngle();

    private Entity lastEntity;
    private long targetAcquiredAtMs;
    private double previousYawStep;
    private double previousPitchStep;
    private double yawVelocity;
    private double pitchVelocity;
    private double yawWavePhase;
    private double pitchWavePhase;
    private double yawWaveSpeed;
    private double pitchWaveSpeed;
    private double speedMultiplier;

    private AimAssistAngle() {
        super("AimAssist");
        randomizeProfile();
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        if (currentAngle == null || targetAngle == null) {
            return currentAngle;
        }

        long now = System.currentTimeMillis();
        if (entity != lastEntity) {
            lastEntity = entity;
            targetAcquiredAtMs = now;
            resetMotion();
        }

        Angle delta = MathAngle.calculateDelta(currentAngle, targetAngle);
        double yawDelta = delta.getYaw();
        double pitchDelta = delta.getPitch();
        double deltaLength = Math.sqrt(yawDelta * yawDelta + pitchDelta * pitchDelta);

        if (deltaLength < 0.05D) {
            yawVelocity *= 0.35D;
            pitchVelocity *= 0.35D;
            return currentAngle;
        }

        AuraModule aura = AuraModule.getInstance();
        double speed = aura != null ? aura.getAimAssistSpeed().getValue() : 16.0D;
        double smoothnessValue = aura != null ? aura.getAimAssistSmoothness().getValue() : 6.0D;
        double smoothness = Mth.clamp((smoothnessValue - 1.0D) / 9.0D, 0.0D, 1.0D);
        double distanceResponse = Mth.clamp(deltaLength / 35.0D, 0.0D, 1.0D);
        double responseScale = 1.0D - smoothness * 0.18D + distanceResponse * 0.18D;
        double microScale = 1.0D - smoothness * 0.85D;
        double warmup = Mth.clamp((now - targetAcquiredAtMs) / 180.0D, 0.0D, 1.0D);
        double tickScale = 2.65D;
        double stiffness = (0.012D + speed * 0.0026D) * (0.45D + warmup * 0.55D) * responseScale * tickScale;
        double damping = 0.74D - smoothness * 0.035D;
        double maxYawSpeed = (0.55D + speed * 0.50D) * (1.0D + distanceResponse * 0.55D - smoothness * 0.04D) * tickScale;
        double maxPitchSpeed = (0.36D + speed * 0.36D) * (1.0D + distanceResponse * 0.42D - smoothness * 0.04D) * tickScale;

        if (deltaLength > 25.0D) {
            double boost = Mth.clamp((deltaLength - 25.0D) / 55.0D, 0.0D, 1.0D);
            stiffness *= 1.0D + boost * 0.55D;
            maxYawSpeed *= 1.0D + boost * 0.35D;
            maxPitchSpeed *= 1.0D + boost * 0.25D;
        }

        yawVelocity += yawDelta * stiffness * speedMultiplier;
        pitchVelocity += pitchDelta * stiffness * speedMultiplier;
        yawVelocity *= damping;
        pitchVelocity *= damping;

        yawVelocity = Mth.clamp(yawVelocity, -maxYawSpeed, maxYawSpeed);
        pitchVelocity = Mth.clamp(pitchVelocity, -maxPitchSpeed, maxPitchSpeed);

        if (Math.abs(yawDelta) < 1.2D) {
            yawVelocity *= 0.55D;
        }
        if (Math.abs(pitchDelta) < 1.2D) {
            pitchVelocity *= 0.55D;
        }

        yawWavePhase += yawWaveSpeed * 0.45D;
        pitchWavePhase += pitchWaveSpeed * 0.45D;

        double yawStep = yawVelocity + Math.sin(yawWavePhase) * 0.012D * microScale;
        double pitchStep = pitchVelocity + Math.cos(pitchWavePhase) * 0.008D * microScale;
        double stepResponse = 0.62D - smoothness * 0.22D + distanceResponse * 0.22D;

        yawStep = smoothStep(previousYawStep, yawStep, stepResponse);
        pitchStep = smoothStep(previousPitchStep, pitchStep, stepResponse);

        double stepLimitScale = 1.0D - smoothness * 0.10D + distanceResponse * 0.30D;
        double yawStepLimit = (0.2D + speed * 0.22D) * stepLimitScale * tickScale;
        double pitchStepLimit = (0.1D + speed * 0.16D) * stepLimitScale * tickScale;

        yawStep = clampStep(yawStep, previousYawStep, yawStepLimit);
        pitchStep = clampStep(pitchStep, previousPitchStep, pitchStepLimit);
        yawStep = clampToRemaining(yawStep, yawDelta);
        pitchStep = clampToRemaining(pitchStep, pitchDelta);

        previousYawStep = yawStep;
        previousPitchStep = pitchStep;

        if (Math.abs(yawStep) < 0.003D && Math.abs(pitchStep) < 0.003D) {
            return currentAngle;
        }

        return new Angle(
                currentAngle.getYaw() + (float) yawStep,
                currentAngle.getPitch() + (float) pitchStep
        );
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }

    private void resetMotion() {
        previousYawStep = 0.0D;
        previousPitchStep = 0.0D;
        yawVelocity = 0.0D;
        pitchVelocity = 0.0D;
        randomizeProfile();
    }

    private void randomizeProfile() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        yawWavePhase = random.nextDouble(Math.PI * 2.0D);
        pitchWavePhase = random.nextDouble(Math.PI * 2.0D);
        yawWaveSpeed = random.nextDouble(0.08D, 0.25D);
        pitchWaveSpeed = random.nextDouble(0.06D, 0.2D);
        speedMultiplier = random.nextDouble(0.75D, 1.25D);
    }

    private double clampStep(double value, double previousValue, double maxDelta) {
        double delta = value - previousValue;
        if (Math.abs(delta) > maxDelta) {
            return previousValue + Math.signum(delta) * maxDelta;
        }
        return value;
    }

    private double smoothStep(double previousValue, double targetValue, double response) {
        double clampedResponse = Mth.clamp(response, 0.05D, 1.0D);
        return previousValue + (targetValue - previousValue) * clampedResponse;
    }

    private double clampToRemaining(double step, double remaining) {
        if (Math.abs(step) > Math.abs(remaining)) {
            return remaining;
        }
        return step;
    }
}
