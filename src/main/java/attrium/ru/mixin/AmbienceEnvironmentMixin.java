package attrium.ru.mixin;

import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.module.impl.visual.Ambience;

/**
 * Native Minecraft 1.21.11 EnvironmentAttributeProbe Mixin for Ambience.
 *
 * Intercepts client-camera visual environment attributes (sky, fog, horizon glow,
 * clouds, cloud height, water fog, star brightness/angle, sky light tint & floor)
 * with zero per-frame allocations and zero server/gameplay side effects.
 */
@Mixin(EnvironmentAttributeProbe.class)
public class AmbienceEnvironmentMixin {

    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true, require = 0)
    private <Value> void attrium$applyAmbienceEnvironment(
            EnvironmentAttribute<Value> attribute,
            float partialTicks,
            CallbackInfoReturnable<Value> cir
    ) {
        if (!Ambience.isActive()) {
            return;
        }
        Value original = cir.getReturnValue();
        Value modified = Ambience.modifyEnvironmentAttribute(attribute, original);
        if (modified != original) {
            cir.setReturnValue(modified);
        }
    }
}
