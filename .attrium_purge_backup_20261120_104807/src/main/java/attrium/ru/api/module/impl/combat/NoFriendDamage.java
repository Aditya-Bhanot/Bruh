package attrium.ru.api.module.impl.combat;

import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.InteractEntityEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.utils.repository.friend.FriendUtils;

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
