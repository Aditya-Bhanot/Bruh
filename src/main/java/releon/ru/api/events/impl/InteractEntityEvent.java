package releon.ru.api.events.impl;

import net.minecraft.world.entity.Entity;
import releon.ru.api.events.CancellableEvent;

public final class InteractEntityEvent extends CancellableEvent {
    private final Entity entity;

    public InteractEntityEvent(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() {
        return entity;
    }
}
