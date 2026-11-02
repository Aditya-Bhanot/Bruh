package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TextFactoryEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.StringSetting;

public final class NameProtect extends Module {
    private final StringSetting nameSetting = register(new StringSetting("Name", "Replacement nickname.", "releon-dlc.tech", 32));
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
