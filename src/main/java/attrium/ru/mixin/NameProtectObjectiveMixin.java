package attrium.ru.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.module.impl.misc.NameProtectModule;

/**
 * Masks usernames, friend IGNs, and server IPs/domains in scoreboard sidebar objective
 * headers (Objective#getDisplayName) when NameProtect is enabled.
 */
@Mixin(Objective.class)
public class NameProtectObjectiveMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, require = 0)
    private void attrium$protectScoreboardTitle(CallbackInfoReturnable<Component> cir) {
        if (!NameProtectModule.isActive()) {
            return;
        }
        Component original = cir.getReturnValue();
        if (original != null) {
            Component masked = NameProtectModule.protectScoreboardComponent(original);
            if (masked != original) {
                cir.setReturnValue(masked);
            }
        }
    }
}
