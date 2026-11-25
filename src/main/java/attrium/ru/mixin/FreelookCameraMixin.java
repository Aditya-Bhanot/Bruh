package attrium.ru.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import attrium.ru.manager.FreelookManager;

/**
 * 100% Legit Freelook Camera Mixin (Minecraft 1.21.11 Official Mojang Mappings).
 * Overrides Camera#setRotation(FF)V inside Camera#setup so vanilla third-person
 * collision (getMaxZoom) remains completely intact.
 */
@Mixin(Camera.class)
public class FreelookCameraMixin {

    @ModifyArgs(
            method = "setup",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"),
            require = 0
    )
    private void attrium$applyFreelookCameraRotation(Args args) {
        if (FreelookManager.isFreelooking()) {
            args.set(0, FreelookManager.getCameraYaw());
            args.set(1, FreelookManager.getCameraPitch());
        }
    }
}
