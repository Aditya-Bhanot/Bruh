package releon.ru.api.events.impl;

import net.minecraft.world.inventory.ClickType;
import releon.ru.api.events.CancellableEvent;

public final class ClickSlotEvent extends CancellableEvent {
    private final int windowId;
    private final int slotId;
    private final int button;
    private final ClickType actionType;

    public ClickSlotEvent(int windowId, int slotId, int button, ClickType actionType) {
        this.windowId = windowId;
        this.slotId = slotId;
        this.button = button;
        this.actionType = actionType;
    }

    public int getWindowId() {
        return windowId;
    }

    public int getSlotId() {
        return slotId;
    }

    public int getButton() {
        return button;
    }

    public ClickType getActionType() {
        return actionType;
    }
}
