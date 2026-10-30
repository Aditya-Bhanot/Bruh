package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DeathScreenEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;

public final class AutoRespawn extends Module {
    private final ModeSetting mode = register(new ModeSetting("Mode", "Respawn mode.", "Default", "Default"));

    public AutoRespawn() {
        super("Auto Respawn", "Automatically respawns after death.", ModuleCategory.PLAYER);
    }

    @SubscribeEvent
    private void onDeathScreen(DeathScreenEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (mode.is("Default") && client.player != null) {
            client.player.respawn();
            client.setScreen(null);
        }
    }
}
