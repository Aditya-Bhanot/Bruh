package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.ClickSlotEvent;
import releon.ru.api.events.impl.HandledScreenEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.InventoryTask;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;

public final class ItemScroller extends Module {
    private final StopWatch stopWatch = new StopWatch();
    private final NumberSetting scrollDelay = register(new NumberSetting("Scroll Delay", "Delay between item scroll clicks.", 50.0, 0.0, 200.0, 1.0));

    public ItemScroller() {
        super("ItemScroller", "Item Scroller", ModuleCategory.PLAYER);
    }

    @SubscribeEvent
    private void onHandledScreen(HandledScreenEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        Slot hoverSlot = event.getSlotHover();
        ClickType actionType = PlayerInteractionHelper.isKey(client.options.keyDrop)
                ? ClickType.THROW
                : PlayerInteractionHelper.isKey(client.options.keyAttack) ? ClickType.QUICK_MOVE : null;

        if (PlayerInteractionHelper.isKey(client.options.keyShift)
                && !PlayerInteractionHelper.isKey(client.options.keySprint)
                && hoverSlot != null
                && hoverSlot.hasItem()
                && actionType != null
                && stopWatch.every(scrollDelay.getValue())) {
            int slotId = InventoryTask.getMenuSlotId(hoverSlot);
            if (slotId != -1) {
                client.gameMode.handleInventoryMouseClick(client.player.containerMenu.containerId, slotId, actionType == ClickType.THROW ? 1 : 0, actionType, client.player);
            }
        }
    }

    @SubscribeEvent
    private void onClickSlot(ClickSlotEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.gameMode == null) {
            return;
        }

        int slotId = event.getSlotId();
        if (slotId < 0 || slotId >= client.player.containerMenu.slots.size()) {
            return;
        }

        Slot slot = client.player.containerMenu.getSlot(slotId);
        if (!slot.hasItem()) {
            return;
        }

        Item item = slot.getItem().getItem();
        if (item != null
                && PlayerInteractionHelper.isKey(client.options.keyShift)
                && PlayerInteractionHelper.isKey(client.options.keySprint)
                && stopWatch.every(50.0)) {
            InventoryTask.slots()
                    .filter(scrolledSlot -> scrolledSlot.hasItem()
                            && scrolledSlot.getItem().getItem().equals(item)
                            && scrolledSlot.container.equals(slot.container))
                    .forEach(scrolledSlot -> {
                        int scrolledSlotId = InventoryTask.getMenuSlotId(scrolledSlot);
                        if (scrolledSlotId != -1) {
                            client.gameMode.handleInventoryMouseClick(client.player.containerMenu.containerId, scrolledSlotId, 1, event.getActionType(), client.player);
                        }
                    });
        }
    }
}
