package releon.ru.api.events.impl;

import net.minecraft.world.item.ItemStack;
import releon.ru.api.events.Event;

public final class HeldItemUpdateEvent implements Event {
    private ItemStack mainHand;
    private ItemStack offHand;

    public HeldItemUpdateEvent(ItemStack mainHand, ItemStack offHand) {
        this.mainHand = mainHand;
        this.offHand = offHand;
    }

    public ItemStack getMainHand() {
        return mainHand;
    }

    public void setMainHand(ItemStack mainHand) {
        this.mainHand = mainHand;
    }

    public ItemStack getOffHand() {
        return offHand;
    }

    public void setOffHand(ItemStack offHand) {
        this.offHand = offHand;
    }
}
