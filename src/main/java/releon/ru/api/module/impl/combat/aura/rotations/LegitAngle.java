package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.util.MathUtils;

public final class LegitAngle extends RotateConstructor {
    public LegitAngle() {
        super("Legit");
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        Minecraft client = Minecraft.getInstance();
        AuraModule aura = AuraModule.getInstance();
        if (client.player == null || aura == null) {
            return currentAngle;
        }

        if (entity != null && aura.isLegitPitchCorrecting()) {
            targetAngle = MathAngle.calculateAngle(calculateLegitPitchPoint(entity));
        }

        Angle angleDelta = MathAngle.calculateDelta(currentAngle, targetAngle);
        float yawDelta = angleDelta.getYaw();
        float pitchDelta = angleDelta.getPitch();
        float rotationDifference = (float) Math.hypot(Math.abs(yawDelta), Math.abs(pitchDelta));
        if (rotationDifference < 0.0001F) {
            rotationDifference = 0.0001F;
        }

        float straightLineYaw;
        float straightLinePitch;
        if (entity != null) {
            float speed = aura.getLegitSpeed().getFloat();
            float minYawSpeed = speed / 4.0F;
            float maxYawSpeed = speed / 2.0F;
            float minPitchSpeed = speed / 24.0F;
            float maxPitchSpeed = speed / 12.0F;
            float angleDiff = Math.abs(yawDelta) + Math.abs(pitchDelta);
            float factor = Mth.clamp(1.0F - angleDiff / rotationDifference + 5.0F, 0.0F, 1.0F);
            straightLineYaw = Mth.lerp(factor, minYawSpeed, maxYawSpeed);
            straightLinePitch = Mth.lerp(factor, minPitchSpeed, maxPitchSpeed);
        } else {
            straightLineYaw = 15.0F;
            straightLinePitch = 5.0F;
        }

        float yaw = currentAngle.getYaw() + Mth.clamp(yawDelta, -straightLineYaw, straightLineYaw);
        if (aura.isLegitPitchCorrecting()) {
            float pitch = currentAngle.getPitch() + Mth.clamp(pitchDelta, -straightLinePitch, straightLinePitch);
            return new Angle(yaw, pitch);
        }

        return new Angle(yaw, client.player.getXRot() + MathUtils.getRandom(-1.0F, 1.0F));
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }

    private Vec3 calculateLegitPitchPoint(Entity entity) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return entity.position();
        }

        double distance = client.player.position().distanceTo(entity.position());
        double normalizedDistance = Mth.clamp(distance / 2.0D, 0.0D, 1.0D);
        double targetHeight = 0.2D + (0.8D - 0.2D) * normalizedDistance;
        return new Vec3(
                entity.getX(),
                entity.getY() + entity.getBbHeight() * targetHeight,
                entity.getZ()
        );
    }
}
