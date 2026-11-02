package releon.ru.api.events.impl;

import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.Event;

public final class CameraPositionEvent implements Event {
    private Vec3 pos;

    public CameraPositionEvent(Vec3 pos) {
        this.pos = pos;
    }

    public Vec3 getPos() {
        return pos;
    }

    public void setPos(Vec3 pos) {
        this.pos = pos;
    }
}
