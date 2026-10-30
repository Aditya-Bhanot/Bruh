package releon.ru.utils.inventory.bind;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.impl.HotBarScrollEvent;
import releon.ru.api.settings.bind.InputType;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;

public final class InventoryBindHelper {
    public static final int SCROLL_UP_BIND = 1000;
    public static final int SCROLL_DOWN_BIND = 1001;
    public static final int MIDDLE_MOUSE_BIND = 1002;

    private InventoryBindHelper() {
    }

    public static boolean isHeld(Minecraft mc, BindSetting bind) {
        if (mc == null || mc.getWindow() == null || bind == null) {
            return false;
        }
        KeyBind keyBind = bind.getValue();
        if (keyBind == null || !keyBind.isBound()) {
            return false;
        }
        if (keyBind.getCode() == MIDDLE_MOUSE_BIND) {
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        }
        return keyBind.isDown(mc.getWindow().handle());
    }

    public static boolean matchesScroll(HotBarScrollEvent event, BindSetting bind) {
        if (event == null || bind == null) {
            return false;
        }
        KeyBind keyBind = bind.getValue();
        if (keyBind == null || keyBind.getType() != InputType.NONE) {
            return false;
        }
        if (keyBind.getCode() == SCROLL_UP_BIND && event.getVertical() > 0) {
            return true;
        }
        return keyBind.getCode() == SCROLL_DOWN_BIND && event.getVertical() < 0;
    }
}
