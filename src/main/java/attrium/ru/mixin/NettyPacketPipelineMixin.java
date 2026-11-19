package attrium.ru.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import attrium.ru.manager.PacketQueueManager;

/**
 * Step 4: Low-Level Netty Outbound Connection Hook (`NettyPacketPipelineMixin`)
 * (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Hooks `Connection#send(Packet<?>)` at `HEAD` so every outbound packet passes through
 * `PacketQueueManager` for vanilla sequencing enforcement, duplicate C03/slot suppression,
 * and optional Blink/FakeLag ring buffering.
 */
@Mixin(Connection.class)
public class NettyPacketPipelineMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void attrium$onSendPacket(Packet<?> packet, CallbackInfo ci) {
        Connection self = (Connection) (Object) this;
        if (PacketQueueManager.getInstance().interceptOutboundPacket(self, packet)) {
            ci.cancel();
        }
    }
}
