package releon.ru.api.module.impl.combat.aura;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;

public class AngleConstructor {
    private final Angle angle;
    private final Vec3 vec3d;
    private final Entity entity;
    private final RotateConstructor angleSmooth;
    private final int ticksUntilReset;
    private final float resetThreshold;
    private final boolean moveCorrection;
    private final boolean freeCorrection;
    private boolean changeLook;

    public AngleConstructor(Angle angle, Vec3 vec3d, Entity entity, RotateConstructor angleSmooth, int ticksUntilReset, float resetThreshold, boolean moveCorrection, boolean freeCorrection) {
        this.angle = angle;
        this.vec3d = vec3d;
        this.entity = entity;
        this.angleSmooth = angleSmooth;
        this.ticksUntilReset = ticksUntilReset;
        this.resetThreshold = resetThreshold;
        this.moveCorrection = moveCorrection;
        this.freeCorrection = freeCorrection;
    }

    public Angle nextRotation(Angle fromAngle, boolean isResetting) {
        Minecraft client = Minecraft.getInstance();
        if (isResetting && client != null && client.player != null) {
            Vec2 rotationVector = client.player.getRotationVector();
            return angleSmooth.limitAngleChange(fromAngle, MathAngle.fromVec2f(rotationVector));
        }
        return angleSmooth.limitAngleChange(fromAngle, angle, vec3d, entity);
    }

    public Angle getAngle() {
        return angle;
    }

    public Vec3 getVec3d() {
        return vec3d;
    }

    public Entity getEntity() {
        return entity;
    }

    public int getTicksUntilReset() {
        return ticksUntilReset;
    }

    public float getResetThreshold() {
        return resetThreshold;
    }

    public boolean isMoveCorrection() {
        return moveCorrection;
    }

    public boolean isFreeCorrection() {
        return freeCorrection;
    }

    public boolean isChangeLook() {
        return changeLook;
    }

    public void setChangeLook(boolean changeLook) {
        this.changeLook = changeLook;
    }
}
