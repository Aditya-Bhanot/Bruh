package attrium.ru;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import attrium.ru.manager.Manager;
import attrium.ru.utils.render.ui.Render2D;
import attrium.ru.utils.sounds.SoundManager;

public class Engine implements ModInitializer, ClientModInitializer {

    private final Manager manager = new Manager();

    @Override
    public void onInitialize() {
        SoundManager.init();
        Render2D.init();
    }

    @Override
    public void onInitializeClient() {
        manager.initClient();
    }
}
