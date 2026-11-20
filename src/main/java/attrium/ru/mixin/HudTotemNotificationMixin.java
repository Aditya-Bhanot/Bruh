package attrium.ru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import attrium.ru.manager.HudManager;

/**
 * Dispatches an animated Gold Star HUD Notification toast whenever a player pops a Totem of Undying
 * (entity event status byte 35).
 */
@Mixin(ClientPacketListener.class)
public class HudTotemNotificationMixin {

    @Inject(method = "handleEntityEvent", at = @At("TAIL"), require = 0)
    private void attrium$onTotemPopNotification(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        if (packet == null || packet.getEventId() != 35) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        Entity entity = packet.getEntity(mc.level);
        if (entity instanceof Player player) {
            String name = player.getName().getString();
            HudManager.pushNotification(name, "Popped a Totem!", HudManager.TYPE_ALERT);
        }
    }
}
