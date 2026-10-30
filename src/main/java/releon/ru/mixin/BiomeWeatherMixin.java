package releon.ru.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.module.impl.visual.Ambience;

@Mixin(Biome.class)
public abstract class BiomeWeatherMixin {
    @Inject(method = "hasPrecipitation", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$hasPrecipitation(CallbackInfoReturnable<Boolean> cir) {
        Ambience ambience = Ambience.getInstance();
        Biome.Precipitation precipitation = ambience != null ? ambience.getForcedPrecipitation() : null;
        if (precipitation != null) {
            cir.setReturnValue(precipitation != Biome.Precipitation.NONE);
        }
    }

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$getPrecipitationAt(BlockPos pos, int seaLevel, CallbackInfoReturnable<Biome.Precipitation> cir) {
        Ambience ambience = Ambience.getInstance();
        Biome.Precipitation precipitation = ambience != null ? ambience.getForcedPrecipitation() : null;
        if (precipitation != null) {
            cir.setReturnValue(precipitation);
        }
    }

    @Inject(method = "coldEnoughToSnow", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$coldEnoughToSnow(BlockPos pos, int seaLevel, CallbackInfoReturnable<Boolean> cir) {
        Ambience ambience = Ambience.getInstance();
        Biome.Precipitation precipitation = ambience != null ? ambience.getForcedPrecipitation() : null;
        if (precipitation == Biome.Precipitation.SNOW) {
            cir.setReturnValue(true);
        } else if (precipitation == Biome.Precipitation.RAIN || precipitation == Biome.Precipitation.NONE) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "warmEnoughToRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$warmEnoughToRain(BlockPos pos, int seaLevel, CallbackInfoReturnable<Boolean> cir) {
        Ambience ambience = Ambience.getInstance();
        Biome.Precipitation precipitation = ambience != null ? ambience.getForcedPrecipitation() : null;
        if (precipitation == Biome.Precipitation.SNOW) {
            cir.setReturnValue(false);
        } else if (precipitation == Biome.Precipitation.RAIN) {
            cir.setReturnValue(true);
        }
    }
}
