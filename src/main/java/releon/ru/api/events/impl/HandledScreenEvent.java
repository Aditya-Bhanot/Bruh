package releon.ru.api.events.impl;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.Slot;
import releon.ru.api.events.Event;

public final class HandledScreenEvent implements Event {
    private final GuiGraphics graphics;
    private final Slot slotHover;
    private final int imageWidth;
    private final int imageHeight;

    public HandledScreenEvent(Slot slotHover, int imageWidth, int imageHeight) {
        this(null, slotHover, imageWidth, imageHeight);
    }

    public HandledScreenEvent(GuiGraphics graphics, Slot slotHover, int imageWidth, int imageHeight) {
        this.graphics = graphics;
        this.slotHover = slotHover;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    public GuiGraphics getGraphics() {
        return graphics;
    }

    public Slot getSlotHover() {
        return slotHover;
    }

    public int getImageWidth() {
        return imageWidth;
    }

    public int getImageHeight() {
        return imageHeight;
    }
}
