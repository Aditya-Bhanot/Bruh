package releon.ru.api.module.impl.combat.aura.impl;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;

public class LinearConstructor extends RotateConstructor {
    public static final LinearConstructor INSTANCE = new LinearConstructor();

    public LinearConstructor() {
        super("Linear");
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        Angle angleDelta = MathAngle.calculateDelta(currentAngle, targetAngle);
        float yawDelta = angleDelta.getYaw();
        float pitchDelta = angleDelta.getPitch();
        float rotationDifference = (float) Math.hypot(yawDelta, pitchDelta);
        if (rotationDifference < 1.0E-4F) {
            rotationDifference = 1.0E-4F;
        }
        float straightLineYaw = Math.abs(yawDelta / rotationDifference) * 360.0F;
        float straightLinePitch = Math.abs(pitchDelta / rotationDifference) * 360.0F;
        return new Angle(
                currentAngle.getYaw() + Math.min(Math.max(yawDelta, -straightLineYaw), straightLineYaw),
                currentAngle.getPitch() + Math.min(Math.max(pitchDelta, -straightLinePitch), straightLinePitch)
        );
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }
}
