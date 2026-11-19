package attrium.ru.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.module.impl.visual.ESP;

/**
 * Native Spectral Arrow Shader Glow Hijack Mixin (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * 1. Activation Hook (isCurrentlyGlowing):
 *    Forces valid ESP targets from TargetManager to activate Minecraft's native entity_outline
 *    shader when ESP is enabled and Mode is set to "Glow" (excluding mc.player).
 * 2. Color & Intensity Hook (getTeamColor):
 *    Overrides the entity outline color with the RGB integer from ESP Color scaled by
 *    Glow Intensity (0.1 - 1.0).
 */
@Mixin(Entity.class)
public class EspEntityGlowMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void attrium$forceEspNativeGlow(CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (ESP.shouldRenderNativeGlowOutline(self)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void attrium$overrideEspNativeGlowColor(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        if (ESP.shouldRenderNativeGlowOutline(self)) {
            int customRgb = ESP.getNativeGlowOutlineColorRGB(self);
            if (customRgb >= 0) {
                cir.setReturnValue(customRgb);
            }
        }
    }
}
