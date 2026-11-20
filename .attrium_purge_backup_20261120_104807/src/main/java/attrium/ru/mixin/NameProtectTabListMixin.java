package attrium.ru.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.module.impl.misc.NameProtectModule;

/**
 * Masks local player and FriendManager usernames in the Tab List (PlayerTabOverlay#getNameForDisplay)
 * when NameProtect is enabled, backed by NameProtectModule's bounded LRU cache.
 */
@Mixin(PlayerTabOverlay.class)
public class NameProtectTabListMixin {

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true, require = 0)
    private void attrium$protectTabListName(PlayerInfo playerInfo, CallbackInfoReturnable<Component> cir) {
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
