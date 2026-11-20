package attrium.ru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Permanent World Particle Culling Mixin (Minecraft 1.21.11 Official Mappings).
 *
 * Prevents particle vertex buffer bloat and GC stutters during Crystal / Mace / Elytra PvP
 * by culling particles spawned more than 24 blocks away from the player using direct
 * primitive squared-distance checks.
 */
@Mixin(ParticleEngine.class)
public class ParticleCullingMixin {

    private static final double MAX_PARTICLE_DISTANCE_SQ = 24.0D * 24.0D;

    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void onCreateParticle(
            ParticleOptions options,
            double x,
            double y,
            double z,
            double xSpeed,
            double ySpeed,
            double zSpeed,
            CallbackInfoReturnable<Particle> cir
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }

        // Read live player coordinates directly from fields
        double dx = x - mc.player.getX();
        double dy = y - mc.player.getY();
        double dz = z - mc.player.getZ();
        if ((dx * dx + dy * dy + dz * dz) > MAX_PARTICLE_DISTANCE_SQ) {
            cir.setReturnValue(null);
        }
    }
}
