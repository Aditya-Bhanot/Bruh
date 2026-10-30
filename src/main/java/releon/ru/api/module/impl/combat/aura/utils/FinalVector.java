package releon.ru.api.module.impl.combat.aura.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class FinalVector {
    private FinalVector() {
    }

    public static Vec3 expensiveUpgradePoint(Entity entity) {
        return FromVector.hitbox(entity, 1, 1, 1, 4);
    }

    public static Vec3 mincedPoint(Entity entity) {
        return FromVector.getBestPoint(net.minecraft.client.Minecraft.getInstance().player.getEyePosition(), entity);
    }

    public static Vec3 celestialPoint(Entity entity) {
        return FromVector.closest(entity);
    }

    public static Vec3 randomPoint(Entity entity) {
        return FromVector.custom(entity, 75, 1000);
    }

    public static Vec3 expensivePoint(Entity entity) {
        return FromVector.brain(entity, 2, 3);
    }
}
