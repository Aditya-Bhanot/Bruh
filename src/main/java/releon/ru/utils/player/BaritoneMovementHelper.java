package releon.ru.utils.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

public final class BaritoneMovementHelper {
    private BaritoneMovementHelper() {
    }

    public static boolean isBaritoneActive() {
        return isBaritoneActive(Minecraft.getInstance().player);
    }

    public static boolean isBaritoneActive(LocalPlayer player) {
        return isMovementControlled(player);
    }

    public static boolean isMovementControlled(LocalPlayer player) {
        if (player == null || player.input == null) {
            return false;
        }
        String inputName = player.input.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return inputName.contains("baritone") || inputName.contains("playermovementinput");
    }
}
