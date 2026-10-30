package releon.ru.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import io.netty.channel.ChannelFutureListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.manager.Manager;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void releon$onSendPacket(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.SEND, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }
}
