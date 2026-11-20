package attrium.ru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Permanent World Entity Culling Mixin (Minecraft 1.21.11 Official Mappings — Sodium Compatible).
 *
 * Early-exits entity rendering for dead entities, distant ground items (> 24m), distant
 * armor stands (> 16m), and distant non-player ambient entities (> 48m) using direct
 * field reads (getX/Y/Z) with zero object allocations.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityCullingMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void onShouldRender(
            E entity,
            Frustum frustum,
            double camX,
            double camY,
            double camZ,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (entity == null) {
            cir.setReturnValue(false);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || entity == mc.player) {
            return;
        }

        // Read live entity coordinates directly from fields
        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (entity instanceof ArmorStand) {
            if (distSq > 256.0D) { // > 16 blocks
                cir.setReturnValue(false);
            }
            return;
        }

        if (entity instanceof ItemEntity) {
            if (distSq > 576.0D) { // > 24 blocks
                cir.setReturnValue(false);
            }
            return;
        }

        if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
            if (living.isDeadOrDying() || distSq > 2304.0D) { // > 48 blocks
                cir.setReturnValue(false);
            }
            return;
        }

        if (!(entity instanceof Player) && !(entity instanceof EndCrystal) && distSq > 3025.0D) { // > 55 blocks
            cir.setReturnValue(false);
        }
    }
}
