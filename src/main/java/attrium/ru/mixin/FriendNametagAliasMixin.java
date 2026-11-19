package attrium.ru.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.manager.FriendManager;

/**
 * Conditional Nametag Friend Aliasing Mixin (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Hooks concrete `Player#getDisplayName()` at RETURN (used by `EntityRenderer` nametag state
 * extraction) and replaces friend usernames with their configured alias ONLY when
 * `NameProtect` / `Hide Nametag` is enabled, backed by `FriendManager`'s bounded cache
 * so zero per-frame String allocations occur.
 */
@Mixin(Player.class)
public class FriendNametagAliasMixin {

    @Inject(
            method = "getDisplayName",
            at = @At("RETURN"),
            cancellable = true,
            require = 0
    )
    private void attrium$aliasFriendInNametag(CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        if (original != null) {
            Component aliased = FriendManager.applyConditionalFriendAlias(original);
            if (aliased != original) {
                cir.setReturnValue(aliased);
            }
        }
    }
}
