package releon.ru.api.module.impl.combat.aura.back;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;

public class BackAngle extends RotateConstructor {
    private final float speed;

    public BackAngle(float speed) {
        super("UseBack");
        this.speed = Mth.clamp(speed, 0.05F, 1.0F);
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        Angle delta = MathAngle.calculateDelta(currentAngle, targetAngle);
        float yawStep = delta.getYaw() * speed;
        float pitchStep = delta.getPitch() * speed;
        if (Math.abs(yawStep) < 0.01F) {
            yawStep = delta.getYaw();
        }
        if (Math.abs(pitchStep) < 0.01F) {
            pitchStep = delta.getPitch();
        }
        return new Angle(currentAngle.getYaw() + yawStep, currentAngle.getPitch() + pitchStep);
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }
}
