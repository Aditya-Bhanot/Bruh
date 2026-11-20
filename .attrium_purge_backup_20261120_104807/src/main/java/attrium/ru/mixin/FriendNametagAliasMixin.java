package attrium.ru.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.module.impl.misc.NameProtectModule;
import attrium.ru.manager.FriendManager;

/**
 * Overhead Nametag NameProtect & Friend Aliasing Mixin (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Hooks concrete Player#getDisplayName() at RETURN (consumed by EntityRenderer nametag state
 * extraction) and masks the local player's IGN + FriendManager allies via NameProtectModule's
 * bounded LRU cache with zero per-frame String allocations.
 */
@Mixin(Player.class)
public class FriendNametagAliasMixin {

    @Inject(
            method = "getDisplayName",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private void attrium$protectNametag(CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        Component masked = NameProtectModule.isActive()
                ? NameProtectModule.protectComponent(original)
                : FriendManager.applyConditionalFriendAlias(original);
        if (masked != original) {
            cir.setReturnValue(masked);
        }
    }
}
