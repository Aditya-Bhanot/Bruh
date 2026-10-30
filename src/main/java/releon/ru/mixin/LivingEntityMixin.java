package releon.ru.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.events.impl.JumpEvent;
import releon.ru.api.events.impl.SwingDurationEvent;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConstructor;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.manager.Manager;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Shadow
    protected abstract double getEffectiveGravity();

    @ModifyExpressionValue(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getYRot()F"), require = 0)
    private float releon$jumpYaw(float original) {
        if ((Object) this == Minecraft.getInstance().player && AngleConnection.INSTANCE.isMoveCorrectionActive()) {
            Angle angle = AngleConnection.INSTANCE.getMoveRotation();
            if (angle != null) {
                return angle.getYaw();
            }
        }
        return original;
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$jumpEvent(CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer player) {
            JumpEvent event = Manager.postEvent(new JumpEvent(player));
            if (event.isCancelled()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "getCurrentSwingDuration", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$swingDuration(CallbackInfoReturnable<Integer> cir) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || (Object) this != player) {
            return;
        }

        SwingDurationEvent event = Manager.postEvent(new SwingDurationEvent());
        if (!event.isCancelled()) {
            return;
        }

        float animation = event.getAnimation();
        if (MobEffectUtil.hasDigSpeed(player)) {
            animation *= 6 - (1 + MobEffectUtil.getDigSpeedAmplification(player));
        } else {
            animation *= player.hasEffect(MobEffects.MINING_FATIGUE)
                    ? 6 + (1 + player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) * 2
                    : 6;
        }
        cir.setReturnValue(Math.max(1, (int) animation));
    }

    @Inject(method = "updateFallFlyingMovement", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$fallFlyingMovement(Vec3 oldVelocity, CallbackInfoReturnable<Vec3> cir) {
        if ((Object) this != Minecraft.getInstance().player) {
            return;
        }

        AngleConnection rotationManager = AngleConnection.INSTANCE;
        Angle rotation = rotationManager.getRotation();
        AngleConstructor plan = rotationManager.getCurrentRotationPlan();
        if (rotation == null || plan == null || !plan.isMoveCorrection() || plan.isChangeLook()) {
            return;
        }

        Vec3 look = rotation.toVector();
        float pitchRadians = rotation.getPitch() * Mth.DEG_TO_RAD;
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double velocityHorizontal = oldVelocity.horizontalDistance();
        double gravity = getEffectiveGravity();
        double pitchCosSq = Mth.square(Math.cos(pitchRadians));
        oldVelocity = oldVelocity.add(0.0D, gravity * (-1.0D + pitchCosSq * 0.75D), 0.0D);
        if (oldVelocity.y < 0.0D && lookHorizontal > 0.0D) {
            double fallPull = oldVelocity.y * -0.1D * pitchCosSq;
            oldVelocity = oldVelocity.add(look.x * fallPull / lookHorizontal, fallPull, look.z * fallPull / lookHorizontal);
        }
        if (pitchRadians < 0.0F && lookHorizontal > 0.0D) {
            double lift = velocityHorizontal * -Mth.sin(pitchRadians) * 0.04D;
            oldVelocity = oldVelocity.add(-look.x * lift / lookHorizontal, lift * 3.2D, -look.z * lift / lookHorizontal);
        }
        if (lookHorizontal > 0.0D) {
            oldVelocity = oldVelocity.add((look.x / lookHorizontal * velocityHorizontal - oldVelocity.x) * 0.1D, 0.0D, (look.z / lookHorizontal * velocityHorizontal - oldVelocity.z) * 0.1D);
        }
        cir.setReturnValue(oldVelocity.multiply(0.99F, 0.98F, 0.99F));
        cir.cancel();
    }
}
