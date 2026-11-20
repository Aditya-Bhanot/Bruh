package attrium.ru.manager;
import attrium.ru.manager.FriendManager;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Live-Field Bridge to {@link TargetManager}.
 *
 * Delegates all target arrays to {@link TargetManager} (scanned once per tick) and reads
 * player/entity positions (getX/Y/Z) and rotations (getYRot/getXRot) directly from live
 * entity fields to prevent aim desync.
 */
public final class EntityTargetScanner {
    public static double playerX;
    public static double playerY;
    public static double playerZ;
    public static double eyeX;
    public static double eyeY;
    public static double eyeZ;
    public static float playerYaw;
    public static float playerPitch;
    public static double lookX;
    public static double lookY;
    public static double lookZ;
    public static double minCosFov = -0.25D;

    private EntityTargetScanner() {
    }

    public static void updatePlayerMathCache(Minecraft mc) {
        if (mc == null || mc.player == null) {
            return;
        }
        // Read directly from live entity fields at exact moment of aim evaluation
        playerX = mc.player.getX();
        playerY = mc.player.getY();
        playerZ = mc.player.getZ();
        eyeX = mc.player.getX();
        eyeY = mc.player.getEyeY();
        eyeZ = mc.player.getZ();
        playerYaw = mc.player.getYRot();
        playerPitch = mc.player.getXRot();

        double radYaw = Math.toRadians(-playerYaw);
        double radPitch = Math.toRadians(-playerPitch);
        double cosPitch = Math.cos(radPitch);
        lookX = Math.sin(radYaw) * cosPitch;
        lookY = Math.sin(radPitch);
        lookZ = Math.cos(radYaw) * cosPitch;

        if (mc.options != null) {
            double fovDeg = Mth.clamp(mc.options.fov().get(), 30.0D, 170.0D);
            minCosFov = Math.cos(Math.toRadians(Math.min(115.0D, fovDeg * 0.78D)));
        }
    }

    public static void ensureUpdated(Minecraft mc, boolean forceTickRefresh) {
        if (mc == null || mc.player == null || mc.level == null) {
            TargetManager.clear();
            return;
        }
        updatePlayerMathCache(mc);
        TargetManager.updateOncePerTick(mc);
    }

    public static void clear() {
        TargetManager.clear();
    }

    public static Entity[] getValidEntities() {
        return TargetManager.getValidEntitiesArray();
    }

    public static int getValidEntityCount() {
        return TargetManager.getValidEntityCount();
    }

    public static LivingEntity[] getValidLiving() {
        return TargetManager.getValidLivingArray();
    }

    public static int getValidLivingCount() {
        return TargetManager.getValidLivingCount();
    }

    public static Player[] getValidPlayers() {
        return TargetManager.getEnemyPlayersArray();
    }

    public static int getValidPlayerCount() {
        return TargetManager.getEnemyPlayerCount();
    }

    public static EndCrystal[] getValidCrystals() {
        return TargetManager.getEndCrystalsArray();
    }

    public static int getValidCrystalCount() {
        return TargetManager.getEndCrystalCount();
    }

    public static double squaredDistanceToEye(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            double dx = x - mc.player.getX();
            double dy = y - mc.player.getEyeY();
            double dz = z - mc.player.getZ();
            return dx * dx + dy * dy + dz * dz;
        }
        double dx = x - eyeX;
        double dy = y - eyeY;
        double dz = z - eyeZ;
        return dx * dx + dy * dy + dz * dz;
    }

    public static double squaredDistanceToPlayer(Entity entity) {
        if (entity == null) {
            return Double.MAX_VALUE;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            double dx = entity.getX() - mc.player.getX();
            double dy = entity.getY() - mc.player.getY();
            double dz = entity.getZ() - mc.player.getZ();
            return dx * dx + dy * dy + dz * dz;
        }
        double dx = entity.getX() - playerX;
        double dy = entity.getY() - playerY;
        double dz = entity.getZ() - playerZ;
        return dx * dx + dy * dy + dz * dz;
    }

    public static boolean isBoxInFrustum(AABB box) {
        updatePlayerMathCache(Minecraft.getInstance());
        double cx = (box.minX + box.maxX) * 0.5D - eyeX;
        double cy = (box.minY + box.maxY) * 0.5D - eyeY;
        double cz = (box.minZ + box.maxZ) * 0.5D - eyeZ;

        double distSq = cx * cx + cy * cy + cz * cz;
        if (distSq < 2.25D) {
            return true;
        }

        double invDist = 1.0D / Math.sqrt(distSq);
        double dot = (cx * lookX + cy * lookY + cz * lookZ) * invDist;
        double boxRadius = Math.max(box.getXsize(), box.getYsize()) * 0.65D;
        return (dot + boxRadius * invDist) >= minCosFov;
    }
}
