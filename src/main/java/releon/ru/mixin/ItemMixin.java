package releon.ru.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import releon.ru.api.module.impl.combat.aura.AngleConnection;

@Mixin(Item.class)
public abstract class ItemMixin {
    @Redirect(method = "getPlayerPOVHitResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getXRot()F"), require = 0)
    private static float releon$itemPitch(Player player) {
        AngleConnection controller = AngleConnection.INSTANCE;
        return controller.shouldApplyPacketRotation() ? controller.getPacketPitch() : player.getXRot();
    }

    @Redirect(method = "getPlayerPOVHitResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getYRot()F"), require = 0)
    private static float releon$itemYaw(Player player) {
        AngleConnection controller = AngleConnection.INSTANCE;
        return controller.shouldApplyPacketRotation() ? controller.getPacketYaw() : player.getYRot();
    }
}
