package releon.ru.api.module.impl.misc;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;

import java.util.Locale;

public final class DeathCoords extends Module {
    public DeathCoords() {
        super("Death Coords", "Prints death coordinates to chat.", ModuleCategory.MISC);
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isReceive() || mc.player == null || mc.level == null) {
            return;
        }
        if (!(event.getPacket() instanceof ClientboundPlayerCombatKillPacket)) {
            return;
        }

        String coords = String.format(Locale.ROOT, "%.0f %.0f %.0f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Component text = Component.literal(coords).withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(coords)));
        mc.player.displayClientMessage(text, false);
    }
}
