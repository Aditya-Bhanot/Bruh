package attrium.ru.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import attrium.ru.manager.FreelookManager;

/**
 * 100% Legit Freelook Entity Turn Mixin (Minecraft 1.21.11 Official Mojang Mappings).
 * Routes LocalPlayer mouse deltas purely to FreelookManager's client camera angles
 * without altering real player yaw/pitch, movement, or server look packets.
 */
@Mixin(Entity.class)
public class FreelookEntityMixin {

    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void attrium$redirectLocalPlayerTurnToFreelook(double yRot, double xRot, CallbackInfo ci) {
        if (((Object) this) instanceof LocalPlayer && FreelookManager.isFreelooking()) {
            FreelookManager.onMouseTurn(yRot, xRot);
            ci.cancel();
        }
    }
}
