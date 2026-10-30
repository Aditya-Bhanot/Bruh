package releon.ru.api.events.impl;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import releon.ru.api.events.Event;

public final class ItemRendererEvent implements Event {
    private AbstractClientPlayer player;
    private ItemStack stack;
    private InteractionHand hand;

    public ItemRendererEvent(AbstractClientPlayer player, ItemStack stack, InteractionHand hand) {
        this.player = player;
        this.stack = stack;
        this.hand = hand;
    }

    public AbstractClientPlayer getPlayer() {
        return player;
    }

    public void setPlayer(AbstractClientPlayer player) {
        this.player = player;
    }

    public ItemStack getStack() {
        return stack;
    }

    public void setStack(ItemStack stack) {
        this.stack = stack;
    }

    public InteractionHand getHand() {
        return hand;
    }

    public void setHand(InteractionHand hand) {
        this.hand = hand;
    }
}
