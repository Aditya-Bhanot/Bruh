package attrium.ru.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.module.impl.visual.Ambience;

/**
 * Client-only weather override mixin for Ambience (Clear, Rain, Thunder).
 * Strictly guarded by `instanceof ClientLevel` so singleplayer server weather is untouched.
 */
@Mixin(Level.class)
public class AmbienceWeatherMixin {

    @Inject(method = "getRainLevel", at = @At("RETURN"), cancellable = true, require = 0)
    private void attrium$overrideClientRainLevel(float delta, CallbackInfoReturnable<Float> cir) {
        if (!(((Object) this) instanceof ClientLevel) || !Ambience.shouldOverrideWeather()) {
            return;
        }
        cir.setReturnValue(Ambience.getOverriddenRainLevel(cir.getReturnValue()));
    }

    @Inject(method = "getThunderLevel", at = @At("RETURN"), cancellable = true, require = 0)
    private void attrium$overrideClientThunderLevel(float delta, CallbackInfoReturnable<Float> cir) {
        if (!(((Object) this) instanceof ClientLevel) || !Ambience.shouldOverrideWeather()) {
            return;
        }
        cir.setReturnValue(Ambience.getOverriddenThunderLevel(cir.getReturnValue()));
    }
}
