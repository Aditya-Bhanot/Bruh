package attrium.ru.manager;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

/**
 * Direct Pass-Through Slot Helper (No Spoofing, No Same-Tick Auto-Revert Desync).
 * Uses the exact AutoMace direct `getInventory().getSelectedSlot()` / `setSelectedSlot()` mechanic.
 */
public final class SlotManager {
    private static final SlotManager INSTANCE = new SlotManager();

    private SlotManager() {
    }

    public static SlotManager getInstance() {
        return INSTANCE;
    }

    public int getServerSlot() {
        return getVisualSlot();
    }

    public int getVisualSlot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && mc.player.getInventory() != null) {
            return mc.player.getInventory().getSelectedSlot();
        }
        return 0;
    }

    public boolean isSpoofingActive() {
        return false;
    }

    public boolean requestServerSlot(int targetSlot, int holdTicks) {
        if (targetSlot < 0 || targetSlot > 8) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.player.getInventory() == null) {
            return false;
        }
        if (mc.player.getInventory().getSelectedSlot() != targetSlot) {
            mc.player.getInventory().setSelectedSlot(targetSlot);
        }
        return true;
    }

    public void sendWrappedPacketWithSlot(
            int targetSlot,
            Packet<?> interactionPacket,
            boolean restoreToVisualSlotImmediately
    ) {
        if (interactionPacket == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.player.connection == null) {
            return;
        }
        if (targetSlot >= 0 && targetSlot <= 8 && mc.player.getInventory().getSelectedSlot() != targetSlot) {
            mc.player.getInventory().setSelectedSlot(targetSlot);
        }
        mc.player.connection.send(interactionPacket);
    }

    public void executeWithSpoofedSlot(
            int targetSlot,
            boolean silentOnly,
            boolean restoreAfter,
            Runnable interactionAction
    ) {
        if (interactionAction == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && targetSlot >= 0 && targetSlot <= 8) {
            if (mc.player.getInventory().getSelectedSlot() != targetSlot) {
                mc.player.getInventory().setSelectedSlot(targetSlot);
            }
        }
        interactionAction.run();
    }

    public void syncToVisualSlot() {
    }

    public void onClientTick(Minecraft mc) {
    }

    public boolean shouldSuppressOutgoingSlotPacket(ServerboundSetCarriedItemPacket packet) {
        return false;
    }

    public void onIncomingSetCarriedItemSlot(int slot) {
    }

    public void resetState() {
    }
}
