package releon.ru.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.events.impl.CameraPositionEvent;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.player.FreeLook;
import releon.ru.manager.Manager;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @ModifyExpressionValue(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float releon$freeLookYaw(float original) {
        Angle angle = FreeLook.getActiveAngle();
        if (angle != null) {
            return angle.getYaw();
        }

        Angle legitAngle = releon$getLegitAuraCameraAngle();
        return legitAngle != null ? legitAngle.getYaw() : original;
    }

    @ModifyExpressionValue(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float releon$freeLookPitch(float original) {
        Angle angle = FreeLook.getActiveAngle();
        if (angle != null) {
            return angle.getPitch();
        }

        Angle legitAngle = releon$getLegitAuraCameraAngle();
        return legitAngle != null ? legitAngle.getPitch() : original;
    }

    @Unique
    private Angle releon$getLegitAuraCameraAngle() {
        AuraModule aura = AuraModule.getInstance();
        if (aura == null || !aura.isEnabled() || !aura.isLegitMode()) {
            return null;
        }
        return AngleConnection.INSTANCE.getCurrentAngle();
    }

    @Inject(method = "setPosition(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$cameraPositionHook(Vec3 pos, CallbackInfo ci) {
        CameraPositionEvent event = Manager.postEvent(new CameraPositionEvent(pos));
        Vec3 nextPos = event.getPos();
        if (nextPos == null || !Double.isFinite(nextPos.x) || !Double.isFinite(nextPos.y) || !Double.isFinite(nextPos.z)) {
            return;
        }
        if (nextPos.distanceToSqr(pos) <= 1.0E-8D) {
            return;
        }
        setPosition(nextPos.x, nextPos.y, nextPos.z);
        ci.cancel();
    }

}
