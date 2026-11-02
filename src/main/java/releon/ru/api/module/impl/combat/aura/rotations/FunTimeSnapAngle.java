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
import releon.ru.api.module.impl.combat.aura.util.MathUtils;

public class FunTimeSnapAngle extends RotateConstructor {
    public static final FunTimeSnapAngle INSTANCE = new FunTimeSnapAngle();
    private long smoothbackShakeStartMs = -1L;

    public FunTimeSnapAngle() {
        super("FunTimeSnap");
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        AuraModule aura = AuraModule.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (aura == null || client.player == null) {
            return currentAngle;
        }
        StrikeManager attackHandler = aura.getAttackHandlerRaw();
        if (aura.isEnabled() && AuraModule.target != null && entity != null && attackHandler != null && attackHandler.canAttack(aura.getConfig(), 1)) {
            smoothbackShakeStartMs = -1L;
            return step(currentAngle, targetAngle, 130.0F, 130.0F, 0.85F, 0.0F, 0.0F);
        }
        Angle playerViewAngle = new Angle(client.player.getYRot(), client.player.getXRot());
        Angle delta = MathAngle.calculateDelta(currentAngle, playerViewAngle);
        float difference = Math.max((float) Math.hypot(Math.abs(delta.getYaw()), Math.abs(delta.getPitch())), 1.0E-4F);
        float yaw = (float) (MathUtils.getRandom(4, 15) * Math.sin(System.currentTimeMillis() / 95D));
        float pitch = (float) (MathUtils.getRandom(6, 7) * Math.cos(System.currentTimeMillis() / 45D));
        if (!aura.isEnabled() || AuraModule.target == null) {
            if (smoothbackShakeStartMs < 0L) {
                smoothbackShakeStartMs = System.currentTimeMillis();
            }
            float shakeFade = 1.0F - Mth.clamp((System.currentTimeMillis() - smoothbackShakeStartMs) / 1000F, 0.0F, 1.0F);
            yaw *= shakeFade;
            pitch *= shakeFade;
        } else {
            smoothbackShakeStartMs = -1L;
        }
        float speedYaw = Math.abs(delta.getYaw() / difference) * (attackHandler != null && !attackHandler.getAttackTimer().finished(535) ? 0.0F : 45.0F);
        float speedPitch = Math.abs(delta.getPitch() / difference) * (attackHandler != null && !attackHandler.getAttackTimer().finished(535) ? 0.0F : 45.0F);

        return new Angle(
                Mth.lerp(0.85F, currentAngle.getYaw(), currentAngle.getYaw() + Mth.clamp(delta.getYaw(), -speedYaw, speedYaw) + yaw),
                Mth.lerp(0.85F, currentAngle.getPitch(), currentAngle.getPitch() + Mth.clamp(delta.getPitch(), -speedPitch, speedPitch) + pitch)
        );
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }

    private Angle step(Angle currentAngle, Angle targetAngle, float yawBudget, float pitchBudget, float blend, float jitterYaw, float jitterPitch) {
        Angle delta = MathAngle.calculateDelta(currentAngle, targetAngle);
        float difference = Math.max((float) Math.hypot(Math.abs(delta.getYaw()), Math.abs(delta.getPitch())), 1.0E-4F);
        float straightLineYaw = Math.abs(delta.getYaw() / difference) * yawBudget;
        float straightLinePitch = Math.abs(delta.getPitch() / difference) * pitchBudget;
        return new Angle(
                Mth.lerp(blend, currentAngle.getYaw(), currentAngle.getYaw() + Mth.clamp(delta.getYaw(), -straightLineYaw, straightLineYaw) + jitterYaw),
                Mth.lerp(blend, currentAngle.getPitch(), currentAngle.getPitch() + Mth.clamp(delta.getPitch(), -straightLinePitch, straightLinePitch) + jitterPitch)
        );
    }
}
