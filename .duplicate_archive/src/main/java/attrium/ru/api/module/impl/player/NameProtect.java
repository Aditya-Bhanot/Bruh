package attrium.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.TextFactoryEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.StringSetting;

public final class NameProtect extends Module {
    private final StringSetting nameSetting = register(new StringSetting("Name", "Replacement nickname.", "attrium-dlc.tech", 32));
    private final BooleanSetting friendsSetting = register(new BooleanSetting("Friends", "Hide friend nicknames when friend repository is available.", true));

    public NameProtect() {
        super("Name Protect", "Hides your nickname in rendered text.", ModuleCategory.PLAYER);
    }

    @SubscribeEvent
    private void onTextFactory(TextFactoryEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.getUser() != null) {
            event.replaceText(client.getUser().getName(), nameSetting.getValue());
        }
    }
}
