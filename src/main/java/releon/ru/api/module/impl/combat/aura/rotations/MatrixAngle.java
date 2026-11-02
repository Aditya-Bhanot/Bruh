package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.attack.StrikeManager;
import releon.ru.api.module.impl.combat.aura.attack.StrikerConstructor;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.target.RaycastAngle;
import releon.ru.api.module.impl.combat.aura.util.MathUtils;

public class MatrixAngle extends RotateConstructor {
    public MatrixAngle() {
        super("Matrix");
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        AuraModule aura = AuraModule.getInstance();
        if (aura == null) {
            return currentAngle;
        }
        StrikeManager attackHandler = aura.getAttackHandlerRaw();
        if (attackHandler == null) {
            return currentAngle;
        }
        StrikerConstructor.AttackPerpetratorConfigurable config = aura.getConfig();
        Angle angleDelta = MathAngle.calculateDelta(currentAngle, targetAngle);
        float yawDelta = angleDelta.getYaw();
        float pitchDelta = angleDelta.getPitch();
        float rotationDifference = (float) Math.hypot(Math.abs(yawDelta), Math.abs(pitchDelta));
        if (rotationDifference < 0.0001F) {
            rotationDifference = 0.0001F;
        }
        boolean shouldAttack = entity != null && attackHandler.canAttack(config, 2);
        boolean shouldAttack2 = entity != null && attackHandler.canAttack(config, 0);
        boolean shouldAttack3 = entity != null && attackHandler.canAttack(config, 5);
        boolean rayTrace = entity != null && RaycastAngle.rayTrace(aura.attackDistance(), config.getBox());
        float straightLineYaw = Math.abs(yawDelta / rotationDifference) * (shouldAttack2 ? 100 : MathUtils.getRandom(25, 50));
        float straightLinePitch = Math.abs(pitchDelta / rotationDifference) * (shouldAttack2 ? 100 : MathUtils.getRandom(5, 25));
        if (!attackHandler.getAttackTimer().finished(MathUtils.getRandom(0, 300))) {
            straightLinePitch = -4;
            straightLineYaw = -4;
        }
        float jitterY = shouldAttack3 ? (!rayTrace && shouldAttack ? 0 : MathUtils.getRandom(3, -3)) : 0;
        float jitterX = shouldAttack3 ? (!rayTrace && shouldAttack ? 0 : MathUtils.getRandom(3, -3)) : 0;
        return new Angle(
                currentAngle.getYaw() + Math.min(Math.max(yawDelta, -straightLineYaw), straightLineYaw) + jitterX,
                currentAngle.getPitch() + Math.min(Math.max(pitchDelta, -straightLinePitch), straightLinePitch) + jitterY
        );
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }
}
