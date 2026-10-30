package releon.ru.api.module.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.screens.clickgui.ClickGui;

public final class AutoSprint extends Module {
    public AutoSprint() {
        super("Auto Sprint", "Keeps sprint enabled while moving.", ModuleCategory.MOVEMENT);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.options == null || client.getWindow() == null) {
            return;
        }

        if (client.screen != null) {
            boolean movingInClickGui = client.screen instanceof ClickGui
                    && (isPressed(client, client.options.keyUp)
                    || isPressed(client, client.options.keyDown)
                    || isPressed(client, client.options.keyLeft)
                    || isPressed(client, client.options.keyRight));
            client.options.keySprint.setDown(movingInClickGui);
            if (client.player.isSprinting() != movingInClickGui) {
                client.player.setSprinting(movingInClickGui);
            }
            return;
        }

        boolean moving = isPressed(client, client.options.keyUp)
                || isPressed(client, client.options.keyDown)
                || isPressed(client, client.options.keyLeft)
                || isPressed(client, client.options.keyRight);
        client.options.keySprint.setDown(moving);
        if (!moving && client.screen != null && client.player.isSprinting()) {
            client.player.setSprinting(false);
        }
    }

    private boolean isPressed(Minecraft client, KeyMapping key) {
        InputConstants.Key inputKey = InputConstants.getKey(key.saveString());
        long handle = client.getWindow().handle();
        return switch (inputKey.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(client.getWindow(), inputKey.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(handle, inputKey.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }
}
