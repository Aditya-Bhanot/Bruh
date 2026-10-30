package releon.ru.mixin;

import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.module.impl.visual.Ambience;
import releon.ru.api.module.impl.visual.NoRender;

@Mixin(LightTexture.class)
public class LightmapRenderStateExtractorMixin {
    @Redirect(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 1), require = 0)
    private float releon$getBrightness(Double value) {
        float baseValue = value.floatValue();
        Ambience ambience = Ambience.getInstance();
        if (ambience != null && ambience.isEnabled()) {
            float brightness = ambience.getBrightnessValue();
            if (brightness >= 0.0f) {
                return Math.max(baseValue, brightness * 10.0f);
            }
            return Math.max(baseValue * (1.0f + brightness), 0.08f);
        }
        return baseValue;
    }

    @Inject(method = "calculateDarknessScale", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$removeDarkness(CallbackInfoReturnable<Float> cir) {
        if (NoRender.isActive("Darkness")) {
            cir.setReturnValue(0.0F);
        }
    }
}
