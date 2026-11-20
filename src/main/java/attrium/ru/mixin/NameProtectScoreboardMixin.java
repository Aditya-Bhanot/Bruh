package attrium.ru.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.module.impl.misc.NameProtectModule;

/**
 * Masks usernames, friend IGNs, and server IPs/domains in scoreboard sidebar team lines
 * (PlayerTeam#formatNameForTeam) when NameProtect is enabled.
 */
@Mixin(PlayerTeam.class)
public class NameProtectScoreboardMixin {

    @Inject(method = "formatNameForTeam", at = @At("RETURN"), cancellable = true, require = 0)
    private static void attrium$protectScoreboardLine(
            Team team, Component name, CallbackInfoReturnable<MutableComponent> cir) {
        if (!NameProtectModule.isActive()) {
            return;
        }
        MutableComponent original = cir.getReturnValue();
        if (original != null) {
            Component masked = NameProtectModule.protectScoreboardComponent(original);
            if (masked != original) {
                cir.setReturnValue(masked instanceof MutableComponent mc ? mc : masked.copy());
            }
        }
    }
}
