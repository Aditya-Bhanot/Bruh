package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
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

public class SpookyTimeNewAngle extends RotateConstructor {
    public static final SpookyTimeNewAngle INSTANCE = new SpookyTimeNewAngle();
    private long smoothbackShakeStartMs = -1L;

    public SpookyTimeNewAngle() {
        super("SpookyTime");
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        AuraModule aura = AuraModule.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (aura == null || client.player == null) {
            return currentAngle;
        }
        StrikeManager attackHandler = aura.getAttackHandlerRaw();
        if (aura.isEnabled() && AuraModule.target != null && entity != null && attackHandler != null) {
            smoothbackShakeStartMs = -1L;
            StrikerConstructor.AttackPerpetratorConfigurable config = aura.getConfig();
            Angle delta = MathAngle.calculateDelta(currentAngle, targetAngle);
            float difference = Math.max((float) Math.hypot(Math.abs(delta.getYaw()), Math.abs(delta.getPitch())), 0.0001F);
            boolean shouldAttack = attackHandler.canAttack(config, 2);
            boolean shouldAttack2 = attackHandler.canAttack(config, 0);
            boolean shouldAttack3 = attackHandler.canAttack(config, 5);
            boolean airborne = !client.player.onGround();
            boolean rayTrace = RaycastAngle.rayTrace(aura.attackDistance(), config.getBox());
            float yawBudget = shouldAttack2 ? 30.0F : airborne ? MathUtils.getRandom(13, 35) : MathUtils.getRandom(15, 33);
            float pitchBudget = shouldAttack2 ? 50.0F : airborne ? MathUtils.getRandom(12, 25) : MathUtils.getRandom(13, 21);
            float straightLineYaw = Math.abs(delta.getYaw() / difference) * yawBudget;
            float straightLinePitch = Math.abs(delta.getPitch() / difference) * pitchBudget;
            float jitterY = shouldAttack3 ? (!rayTrace && shouldAttack ? 0 : MathUtils.getRandom(0, 0)) : 0;
            float jitterX = shouldAttack3 ? (!rayTrace && shouldAttack ? 0 : MathUtils.getRandom(0, 0)) : 0;
            return new Angle(
                    currentAngle.getYaw() + Math.min(Math.max(delta.getYaw(), -straightLineYaw), straightLineYaw) + jitterX,
                    currentAngle.getPitch() + Math.min(Math.max(delta.getPitch(), -straightLinePitch), straightLinePitch) + jitterY
            );
        }
        Angle playerViewAngle = new Angle(client.player.getYRot(), client.player.getXRot());
        Angle delta = MathAngle.calculateDelta(currentAngle, playerViewAngle);
        float difference = Math.max((float) Math.hypot(Math.abs(delta.getYaw()), Math.abs(delta.getPitch())), 1.0E-4F);
        float yaw = 0.0F;
        float pitch = 0.0F;
        if (!aura.isEnabled() || AuraModule.target == null) {
            if (smoothbackShakeStartMs < 0L) {
                smoothbackShakeStartMs = System.currentTimeMillis();
            }
            float shakeFade = 1.0F - Mth.clamp((System.currentTimeMillis() - smoothbackShakeStartMs) / 4000.0F, 0.0F, 1.0F);
            yaw *= shakeFade;
            pitch *= shakeFade;
        } else {
            smoothbackShakeStartMs = -1L;
        }
        float straightLineYaw = Math.abs(delta.getYaw() / difference) * (attackHandler != null && !attackHandler.getAttackTimer().finished(750) ? 0.0F : 35.0F);
        float straightLinePitch = Math.abs(delta.getPitch() / difference) * (attackHandler != null && !attackHandler.getAttackTimer().finished(750) ? 0.0F : 35.0F);
        return new Angle(
                Mth.lerp(1F, currentAngle.getYaw(), currentAngle.getYaw() + Mth.clamp(delta.getYaw(), -straightLineYaw, straightLineYaw) + yaw),
                Mth.lerp(1F, currentAngle.getPitch(), currentAngle.getPitch() + Mth.clamp(delta.getPitch(), -straightLinePitch, straightLinePitch) + pitch)
        );
    }

    @Override
    public Vec3 randomValue() {
        return Vec3.ZERO;
    }
}
