package releon.ru.api.events.impl;

import net.minecraft.client.gui.screens.Screen;
import releon.ru.api.events.CancellableEvent;

public final class CloseScreenEvent extends CancellableEvent {
    private final Screen screen;

    public CloseScreenEvent(Screen screen) {
        this.screen = screen;
    }

    public Screen getScreen() {
        return screen;
    }
}
