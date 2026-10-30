package releon.ru.api.module.impl.combat.aura.target;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class Vector {
    private Vector() {
    }

    public static Vec3 hitbox(Entity entity, float x, float y, float z, float width) {
        Minecraft client = Minecraft.getInstance();
        double wHalf = entity.getBbWidth() / width;
        double yExpand = Mth.clamp(entity.getEyeY() - entity.getY(), 0, entity.getBbHeight());
        double xExpand = Mth.clamp(client.player.getX() - entity.getX(), -wHalf, wHalf);
        double zExpand = Mth.clamp(client.player.getZ() - entity.getZ(), -wHalf, wHalf);
        return new Vec3(entity.getX() + xExpand / x, entity.getY() + yExpand / y, entity.getZ() + zExpand / z);
    }
}
