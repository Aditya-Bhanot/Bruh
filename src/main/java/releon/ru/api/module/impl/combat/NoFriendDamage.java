package releon.ru.api.module.impl.combat;

import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InteractEntityEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.utils.repository.friend.FriendUtils;

public class NoFriendDamage extends Module {
    public NoFriendDamage() {
        super("No Friend Damage", "Blocks attacks on friends.", ModuleCategory.COMBAT);
    }

    @SubscribeEvent
    private void onInteract(InteractEntityEvent event) {
        if (FriendUtils.isFriend(event.getEntity())) {
            event.cancel();
        }
    }
}
