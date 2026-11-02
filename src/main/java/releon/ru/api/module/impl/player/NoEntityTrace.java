package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;

public final class NoEntityTrace extends Module {
    private static NoEntityTrace instance;
    private final BooleanSetting noSword = register(new BooleanSetting("Disable With Sword", "Do not ignore entities while holding sword.", true));

    public NoEntityTrace() {
        super("No Entity Trace", "Ignores entities while raycasting blocks.", ModuleCategory.PLAYER);
        instance = this;
    }

    public static NoEntityTrace getInstance() {
        return instance;
    }

    public boolean shouldIgnoreEntityTrace() {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || client.player == null) {
            return false;
        }
        if (!noSword.getValue()) {
            return true;
        }
        ItemStack stack = client.player.getMainHandItem();
        String key = stack.getItem().getDescriptionId().toLowerCase(java.util.Locale.ROOT);
        return !key.contains("sword");
    }
}
