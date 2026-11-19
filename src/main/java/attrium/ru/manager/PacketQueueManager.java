package attrium.ru.manager;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Lightweight Outbound Packet Pass-Through (`PacketQueueManager`).
 * Never cancels or drops vanilla movement (`C03`), slot (`C09`), or interaction packets.
 */
public final class PacketQueueManager {
    private static final PacketQueueManager INSTANCE = new PacketQueueManager();

    private boolean bufferingEnabled = false;
    private boolean enforceSwingBeforeAttack = false;

    private PacketQueueManager() {
    }

    public static PacketQueueManager getInstance() {
        return INSTANCE;
    }

    public boolean isBufferingEnabled() {
        return false;
    }

    public int getBufferedPacketCount() {
        return 0;
    }

    public void setEnforceSwingBeforeAttack(boolean enforce) {
        this.enforceSwingBeforeAttack = enforce;
    }

    public void enableBuffering(int maxHoldTicks) {
        this.bufferingEnabled = false;
    }

    public void disableAndFlush(Minecraft mc) {
        this.bufferingEnabled = false;
    }

    public void onClientTick(Minecraft mc) {
    }

    public boolean interceptOutboundPacket(Connection connection, Packet<?> packet) {
        if (packet == null) {
            return false;
        }
        RotationManager rotMgr = RotationManager.getInstance();
        if (packet instanceof ServerboundMovePlayerPacket movePacket && rotMgr.isSpoofingActive()) {
            if (movePacket.hasRotation()) {
                RotationManager.injectSpoofedRotationIntoMovePacket(
                        movePacket,
                        rotMgr.getServerSpoofedYaw(),
                        rotMgr.getServerSpoofedPitch()
                );
            }
        }
        return false;
    }

    public void flushAll(Connection connection) {
    }

    public void clearAndReset() {
        this.bufferingEnabled = false;
    }
}
