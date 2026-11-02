package releon.ru.api.events.impl;

import net.minecraft.client.gui.GuiGraphics;
import releon.ru.api.events.Event;

public record DrawEvent(GuiGraphics graphics, float partialTicks, Layer layer) implements Event {
    public DrawEvent(GuiGraphics graphics, float partialTicks) {
        this(graphics, partialTicks, Layer.GAME);
    }

    public GuiGraphics getGraphics() {
        return graphics;
    }

    public float getPartialTicks() {
        return partialTicks;
    }

    public Layer getLayer() {
        return layer;
    }

    public boolean isScreenBackgroundLayer() {
        return layer == Layer.SCREEN_BACKGROUND;
    }

    public enum Layer {
        GAME,
        SCREEN_BACKGROUND,
        CHAT_OVERLAY
    }
}
