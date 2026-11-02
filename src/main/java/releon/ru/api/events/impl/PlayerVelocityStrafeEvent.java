package releon.ru.api.events.impl;

import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.Event;

public final class PlayerVelocityStrafeEvent implements Event {
    private final Vec3 movementInput;
    private final float speed;
    private final float yaw;
    private Vec3 velocity;

    public PlayerVelocityStrafeEvent(Vec3 movementInput, float speed, float yaw, Vec3 velocity) {
        this.movementInput = movementInput;
        this.speed = speed;
        this.yaw = yaw;
        this.velocity = velocity;
    }

    public Vec3 getMovementInput() {
        return movementInput;
    }

    public float getSpeed() {
        return speed;
    }

    public float getYaw() {
        return yaw;
    }

    public Vec3 getVelocity() {
        return velocity;
    }

    public void setVelocity(Vec3 velocity) {
        this.velocity = velocity;
    }
}
