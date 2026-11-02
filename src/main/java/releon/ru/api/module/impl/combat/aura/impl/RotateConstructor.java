package releon.ru.api.module.impl.combat.aura.impl;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.aura.Angle;

public abstract class RotateConstructor {
    private final String name;

    protected RotateConstructor(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle) {
        return limitAngleChange(currentAngle, targetAngle, null, null);
    }

    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d) {
        return limitAngleChange(currentAngle, targetAngle, vec3d, null);
    }

    public abstract Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity);

    public abstract Vec3 randomValue();
}
