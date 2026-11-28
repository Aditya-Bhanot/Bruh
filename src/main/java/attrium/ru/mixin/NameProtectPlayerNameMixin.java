package attrium.ru.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.module.impl.misc.NameProtectModule;

/**
 * Masks local player and friend names in Player#getDisplayName() (overhead nametags & HUD queries).
 */
@Mixin(Player.class)
public class NameProtectPlayerNameMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, require = 0)
    private void attrium$protectPlayerDisplayName(CallbackInfoReturnable<Component> cir) {
        if (!NameProtectModule.isActive()) {
            return;
        }
        Component original = cir.getReturnValue();
        if (original != null) {
            Component masked = NameProtectModule.protectComponent(original);
            if (masked != original) {
                cir.setReturnValue(masked);
            }
        }
    }
}
