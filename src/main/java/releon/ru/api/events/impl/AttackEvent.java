package releon.ru.api.events.impl;

import net.minecraft.world.entity.Entity;
import releon.ru.api.events.CancellableEvent;

public final class AttackEvent extends CancellableEvent {
    private final Entity target;

    public AttackEvent(Entity target) {
        this.target = target;
    }

    public Entity getTarget() {
        return target;
    }
}
