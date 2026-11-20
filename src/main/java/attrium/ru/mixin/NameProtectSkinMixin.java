package attrium.ru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.module.impl.misc.NameProtectModule;

/**
 * Overrides the local player's skin/cape with the default Steve PlayerSkin when
 * NameProtect -> Hide Skin is enabled, using CallbackInfoReturnable<Object> for
 * full 1.21.9-1.21.11 PlayerSkin package compatibility.
 */
@Mixin(AbstractClientPlayer.class)
public class NameProtectSkinMixin {

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true, require = 0)
    private void attrium$hideLocalPlayerSkin(CallbackInfoReturnable<Object> cir) {
        if (!NameProtectModule.isHideSkinActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || (Object) this != mc.player) {
            return;
        }
        Object defaultSkin = NameProtectModule.getDefaultSteveSkin();
        if (defaultSkin != null) {
            cir.setReturnValue(defaultSkin);
        }
    }
}
