package releon.ru.api.drag.core;

import net.minecraft.client.input.MouseButtonEvent;

public interface HudElement {
    void render();

    default boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return false;
    }
}
