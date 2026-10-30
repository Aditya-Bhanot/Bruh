package releon.ru.api.module.impl.misc;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;

import java.util.UUID;

public final class ServerRPSpoofer extends Module {
    private final StopWatch counter = new StopWatch();
    private ResourcePackAction currentAction = ResourcePackAction.WAIT;
    private UUID resourcePackId;
    private ClientCommonPacketListenerImpl resourcePackListener;

    public ServerRPSpoofer() {
        super("Server RP Spoof", "Spoofs server resource pack acceptance.", ModuleCategory.MISC);
    }

    @Override
    protected void onDisable() {
        reset();
    }

    public boolean handleResourcePackPush(ClientCommonPacketListenerImpl listener, ClientboundResourcePackPushPacket packet) {
        if (!isEnabled() || listener == null || packet == null) {
            return false;
        }

        resourcePackListener = listener;
        resourcePackId = packet.id();
        currentAction = ResourcePackAction.ACCEPT;
        processResourcePackAction();
        return true;
    }

    @SubscribeEvent(receiveCancelled = true)
    private void onTick(TickEvent event) {
        processResourcePackAction();
    }

    private void processResourcePackAction() {
        ClientCommonPacketListenerImpl connection = resourcePackListener;
        if (connection == null) {
            ClientPacketListener playConnection = mc.getConnection();
            if (playConnection != null) {
                connection = playConnection;
            }
        }
        if (connection == null || resourcePackId == null) {
            return;
        }
        if (currentAction == ResourcePackAction.ACCEPT) {
            connection.send(new ServerboundResourcePackPacket(resourcePackId, ServerboundResourcePackPacket.Action.ACCEPTED));
            currentAction = ResourcePackAction.SEND;
            counter.reset();
            return;
        }
        if (currentAction == ResourcePackAction.SEND && counter.finished(300L)) {
            connection.send(new ServerboundResourcePackPacket(resourcePackId, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
            reset();
        }
    }

    private void reset() {
        currentAction = ResourcePackAction.WAIT;
        resourcePackId = null;
        resourcePackListener = null;
    }

    private enum ResourcePackAction {
        ACCEPT,
        SEND,
        WAIT
    }
}
