package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.attack.StrikeManager;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;

public class SnapAngle extends RotateConstructor {
    public SnapAngle() {
        super("Snap");
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        AuraModule aura = AuraModule.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (aura == null || client.player == null) {
            return currentAngle;
        }
        StrikeManager attackHandler = aura.getAttackHandlerRaw();
        if (entity != null && attackHandler != null && attackHandler.canAttack(aura.getConfig(), 1)) {
            return step(currentAngle, targetAngle, aura.getSpeedValue().getFloat() * 1.5F);
        }
        Angle playerViewAngle = new Angle(client.player.getYRot(), client.player.getXRot());
        float speed = attackHandler != null && !attackHandler.getAttackTimer().finished(aura.getTimeOnTick().getValue()) ? 0.0F : aura.getSpeedValue().getFloat() / 1.5F;
        return step(currentAngle, playerViewAngle, speed);
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }

    private Angle step(Angle currentAngle, Angle targetAngle, float speed) {
        Angle delta = MathAngle.calculateDelta(currentAngle, targetAngle);
        float yawDelta = delta.getYaw();
        float pitchDelta = delta.getPitch();
        float difference = (float) Math.hypot(Math.abs(yawDelta), Math.abs(pitchDelta));
        if (difference < 0.0001F) {
            difference = 0.0001F;
        }
        float straightLineYaw = Math.abs(yawDelta / difference) * speed;
        float straightLinePitch = Math.abs(pitchDelta / difference) * speed;
        return new Angle(
                currentAngle.getYaw() + Math.min(Math.max(yawDelta, -straightLineYaw), straightLineYaw),
                currentAngle.getPitch() + Math.min(Math.max(pitchDelta, -straightLinePitch), straightLinePitch)
        );
    }
}
