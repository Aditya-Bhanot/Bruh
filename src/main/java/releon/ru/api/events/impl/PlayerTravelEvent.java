package releon.ru.api.events.impl;

import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.CancellableEvent;

public final class PlayerTravelEvent extends CancellableEvent {
    private Vec3 motion;
    private final boolean pre;

    public PlayerTravelEvent(Vec3 motion, boolean pre) {
        this.motion = motion;
        this.pre = pre;
    }

    public Vec3 getMotion() {
        return motion;
    }

    public void setMotion(Vec3 motion) {
        this.motion = motion;
    }

    public boolean isPre() {
        return pre;
    }

    public boolean isPost() {
        return !pre;
    }
}
