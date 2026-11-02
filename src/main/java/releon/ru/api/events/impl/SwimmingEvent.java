package releon.ru.api.events.impl;

import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.CancellableEvent;

public final class SwimmingEvent extends CancellableEvent {
    private Vec3 vector;

    public SwimmingEvent(Vec3 vector) {
        this.vector = vector;
    }

    public Vec3 getVector() {
        return vector;
    }

    public void setVector(Vec3 vector) {
        this.vector = vector;
    }
}
