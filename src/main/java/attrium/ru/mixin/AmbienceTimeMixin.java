package attrium.ru.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.module.impl.visual.Ambience;

/**
 * Client-only world time override mixin for Ambience (Preset, Custom, and live Cycle timelapse).
 */
@Mixin(ClientLevel.ClientLevelData.class)
public class AmbienceTimeMixin {

    @Inject(method = "getDayTime", at = @At("RETURN"), cancellable = true, require = 0)
    private void attrium$overrideClientDayTime(CallbackInfoReturnable<Long> cir) {
        if (!Ambience.shouldOverrideTime()) {
            return;
        }
        long original = cir.getReturnValue();
        long overridden = Ambience.getOverriddenDayTime(original);
        if (overridden != original) {
            cir.setReturnValue(overridden);
        }
    }
}
