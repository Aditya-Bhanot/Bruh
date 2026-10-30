package releon.ru.api.module.impl.combat.aura.target;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.attack.StrikerConstructor;

import java.util.Objects;
import java.util.function.Predicate;

public final class RaycastAngle {
    private RaycastAngle() {
    }

    public static BlockHitResult raycast(double range, Angle angle, boolean includeFluids) {
        Minecraft client = Minecraft.getInstance();
        return raycast(Objects.requireNonNull(client.player).getEyePosition(1.0F), range, angle, includeFluids);
    }

    public static BlockHitResult raycast(Vec3 vec, double range, Angle angle, boolean includeFluids) {
        Minecraft client = Minecraft.getInstance();
        Entity entity = client.getCameraEntity();
        if (entity == null || client.level == null) {
            return null;
        }
        Vec3 rotationVec = angle.toVector();
        Vec3 end = vec.add(rotationVec.x * range, rotationVec.y * range, rotationVec.z * range);
        ClipContext.Fluid fluidHandling = includeFluids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE;
        return client.level.clip(new ClipContext(vec, end, ClipContext.Block.OUTLINE, fluidHandling, entity));
    }

    public static BlockHitResult raycast(Vec3 start, Vec3 end, ClipContext.Block shapeType) {
        Minecraft client = Minecraft.getInstance();
        return raycast(start, end, shapeType, ClipContext.Fluid.NONE, client.player);
    }

    public static BlockHitResult raycast(Vec3 start, Vec3 end, ClipContext.Block shapeType, ClipContext.Fluid fluidHandling, Entity entity) {
        Minecraft client = Minecraft.getInstance();
        Level level = client.level;
        return level == null ? null : level.clip(new ClipContext(start, end, shapeType, fluidHandling, entity));
    }

    public static EntityHitResult raytraceEntity(double range, Angle angle, Predicate<Entity> filter) {
        Minecraft client = Minecraft.getInstance();
        Entity entity = client.player;
        if (entity == null) {
            return null;
        }
        Vec3 cameraVec = entity.getEyePosition(1.0F);
        Vec3 rotationVec = angle.toVector();
        Vec3 end = cameraVec.add(rotationVec.x * range, rotationVec.y * range, rotationVec.z * range);
        AABB box = entity.getBoundingBox().expandTowards(rotationVec.scale(range)).inflate(1.0, 1.0, 1.0);
        return ProjectileUtil.getEntityHitResult(entity, cameraVec, end, box, e -> !e.isSpectator() && filter.test(e), range * range);
    }

    public static boolean rayTrace(StrikerConstructor.AttackPerpetratorConfigurable config) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && config.getTarget() != null && client.player.isFallFlying() && config.getTarget().isFallFlying()) {
            return true;
        }
        return rayTrace(AngleConnection.INSTANCE.getRotation().toVector(), config.getMaximumRange() - 0.25F, config.getBox());
    }

    public static boolean rayTrace(double range, AABB box) {
        return rayTrace(AngleConnection.INSTANCE.getRotation().toVector(), range - 0.25F, box);
    }

    public static boolean rayTrace(Vec3 clientVec, double range, AABB box) {
        Minecraft client = Minecraft.getInstance();
        Vec3 cameraVec = Objects.requireNonNull(client.player).getEyePosition();
        return box != null && (box.contains(cameraVec) || box.clip(cameraVec, cameraVec.add(clientVec.scale(range))).isPresent());
    }
}
