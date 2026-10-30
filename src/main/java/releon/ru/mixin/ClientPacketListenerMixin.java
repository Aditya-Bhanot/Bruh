package releon.ru.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.events.impl.ChatEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.manager.Manager;
import releon.ru.utils.network.Network;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void releon$onSendChat(String message, CallbackInfo ci) {
        ChatEvent event = Manager.postEvent(new ChatEvent(message));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void releon$onMovePlayer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetTime", at = @At("HEAD"), cancellable = true)
    private void releon$onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        Network.handleTimePacket();
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$onSetEntityMotion(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void releon$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerCombatKill", at = @At("HEAD"), cancellable = true)
    private void releon$onPlayerCombatKill(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"), cancellable = true)
    private void releon$onTakeItemEntity(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true)
    private void releon$onContainerSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"), cancellable = true)
    private void releon$onChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true)
    private void releon$onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleGameEvent", at = @At("HEAD"), cancellable = true)
    private void releon$onGameEvent(ClientboundGameEventPacket packet, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleEntityEvent", at = @At("HEAD"), cancellable = true)
    private void releon$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        PacketEvent event = Manager.postEvent(new PacketEvent(PacketEvent.Type.RECEIVE, packet));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }
}
