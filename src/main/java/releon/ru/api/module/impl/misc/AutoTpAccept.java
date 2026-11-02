package releon.ru.api.module.impl.misc;

import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.Locale;

public final class AutoTpAccept extends Module {
    private static final String[] TELEPORT_MESSAGES = {
            "has requested teleport",
            "просит телепортироваться",
            "хочет телепортироваться к вам",
            "просит к вам телепортироваться"
    };

    private final BooleanSetting friendsOnly = register(new BooleanSetting("Only Friends", "Accept teleport requests only from friends.", false));
    private boolean canAccept;

    public AutoTpAccept() {
        super("Auto Accept", "Automatically accepts teleport requests.", ModuleCategory.MISC);
    }

    @Override
    protected void onEnable() {
        canAccept = false;
    }

    @Override
    protected void onDisable() {
        canAccept = false;
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isReceive() || !(event.getPacket() instanceof ClientboundSystemChatPacket packet)) {
            return;
        }
        if (isOverlay(packet)) {
            return;
        }

        String message = packet.content().getString();
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (!isTeleportMessage(lower)) {
            return;
        }

        canAccept = !friendsOnly.getValue() || containsFriendName(lower);
    }

    @SubscribeEvent
    private void onTick(TickEvent event) {
        if (mc.player == null || mc.player.connection == null || !canAccept) {
            return;
        }

        mc.player.connection.sendCommand("tpaccept");
        canAccept = false;
    }

    private boolean isTeleportMessage(String message) {
        for (String pattern : TELEPORT_MESSAGES) {
            if (message.contains(pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsFriendName(String message) {
        for (String friend : FriendUtils.friends()) {
            if (friend != null && message.contains(friend.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isOverlay(ClientboundSystemChatPacket packet) {
        try {
            return packet.overlay();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
