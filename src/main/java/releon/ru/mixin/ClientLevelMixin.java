package releon.ru.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.module.impl.visual.Ambience;
import releon.ru.api.module.impl.visual.NoRender;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Shadow
    @Final
    private ClientLevel.ClientLevelData clientLevelData;

    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$tickTime(CallbackInfo ci) {
        Ambience ambience = Ambience.getInstance();
        if (ambience == null || !ambience.isEnabled()) {
            return;
        }
        ClientLevel level = (ClientLevel) (Object) this;
        long time = ambience.getInternalTime();
        this.clientLevelData.setDayTime(time);
        ambience.syncWeather(level, clientLevelData);
        ci.cancel();
    }

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$addParticle(ParticleOptions parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ, CallbackInfo ci) {
        if (releon$shouldCancelHitParticle(parameters)) {
            ci.cancel();
        }
    }

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$addParticle(ParticleOptions parameters, boolean force, boolean canSpawnOnMinimal, double x, double y, double z, double velocityX, double velocityY, double velocityZ, CallbackInfo ci) {
        if (releon$shouldCancelHitParticle(parameters)) {
            ci.cancel();
        }
    }

    private static boolean releon$shouldCancelHitParticle(ParticleOptions parameters) {
        if (parameters == null || !NoRender.isActive("Hit Particles")) {
            return false;
        }
        return parameters.getType() == ParticleTypes.DAMAGE_INDICATOR
                || parameters.getType() == ParticleTypes.CRIT
                || parameters.getType() == ParticleTypes.ENCHANTED_HIT
                || parameters.getType() == ParticleTypes.SWEEP_ATTACK;
    }
}
