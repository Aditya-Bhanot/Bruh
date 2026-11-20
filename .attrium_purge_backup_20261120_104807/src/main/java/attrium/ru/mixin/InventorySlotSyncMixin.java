package attrium.ru.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import attrium.ru.manager.GlobalInventoryCache;

/**
 * Packet-Driven Inventory Cache Synchronizer (Minecraft 1.21.11 Official Mappings).
 *
 * Hooks {@link ClientboundContainerSetSlotPacket} (ScreenHandlerSlotUpdateS2CPacket) and
 * {@link ClientboundContainerSetContentPacket} (InventoryS2CPacket) so {@link GlobalInventoryCache}
 * updates immediately whenever a slot changes without scanning on every tick.
 */
@Mixin(ClientPacketListener.class)
public class InventorySlotSyncMixin {

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void onHandleContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        GlobalInventoryCache.markDirtyFromPacket();
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void onHandleContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        GlobalInventoryCache.markDirtyFromPacket();
    }
}
