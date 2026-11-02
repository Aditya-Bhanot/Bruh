package releon.ru.utils.inventory.item;

import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import releon.ru.IMinecraft;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;

public class ItemToolkit implements IMinecraft {
    public static final ItemToolkit INSTANCE = new ItemToolkit();

    private boolean useItem;
    private boolean releaseItem = true;

    private ItemToolkit() {
    }

    public boolean isUseItem() {
        return useItem;
    }

    public void setUseItem(boolean useItem) {
        this.useItem = useItem;
    }

    public boolean isReleaseItem() {
        return releaseItem;
    }

    public void setReleaseItem(boolean releaseItem) {
        this.releaseItem = releaseItem;
    }

    @SubscribeEvent
    public void onPacket(PacketEvent event) {
        Object packet = event.getPacket();
        switch (packet) {
            case ServerboundPlayerActionPacket actionPacket
                    when actionPacket.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM -> releaseItem = true;
            case ServerboundClientCommandPacket statusPacket
                    when statusPacket.getAction() == ServerboundClientCommandPacket.Action.PERFORM_RESPAWN -> releaseItem = true;
            case ClientboundRespawnPacket ignored -> releaseItem = true;
            case ClientboundLoginPacket ignored -> releaseItem = true;
            default -> {
            }
        }
    }

    public void useHand(InteractionHand hand) {
        if (mc.player == null || mc.gameMode == null || hand == null) {
            return;
        }

        if (releaseItem) {
            PlayerInteractionHelper.interactItem(hand);
            releaseItem = false;
        }
        useItem = true;
    }
}
