package releon.ru.api.events.impl;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.Screen;
import releon.ru.api.events.Event;

public record KeyEvent(Screen screen, InputConstants.Type type, int key, int action) implements Event {
}
