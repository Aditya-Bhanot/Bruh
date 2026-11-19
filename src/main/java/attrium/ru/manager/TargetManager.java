package attrium.ru.manager;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import attrium.ru.api.module.impl.combat.AntiBot;
import attrium.ru.utils.repository.friend.FriendUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Centralized Once-Per-Tick Shared TargetManager, Static Memory Pool & Same-Tick Raycast Cache
 * with Stage-1 `FriendManager.isFriend(entity)` UUID Filtering (Minecraft 1.21.11 Official Mappings).
 */
public final class TargetManager {
    public static final int MAX_ENTITIES = 512;
    public static final int MAX_LIVING = 256;
    public static final int MAX_PLAYERS = 128;
    public static final int MAX_CRYSTALS = 128;

    private static final Entity[] VALID_ENTITIES = new Entity[MAX_ENTITIES];
    private static final LivingEntity[] VALID_LIVING = new LivingEntity[MAX_LIVING];
    private static final Player[] ENEMY_PLAYERS = new Player[MAX_PLAYERS];
    private static final Player[] ALL_PLAYERS = new Player[MAX_PLAYERS];
    private static final EndCrystal[] END_CRYSTALS = new EndCrystal[MAX_CRYSTALS];

    private static int validEntityCount = 0;
    private static int validLivingCount = 0;
    private static int enemyPlayerCount = 0;
    private static int allPlayerCount = 0;
    private static int endCrystalCount = 0;

    private static final List<LivingEntity> SHARED_LIVING_LIST = new ArrayList<>(MAX_LIVING);
    private static final List<Player> SHARED_ENEMY_PLAYERS_LIST = new ArrayList<>(MAX_PLAYERS);
    private static final List<EndCrystal> SHARED_CRYSTALS_LIST = new ArrayList<>(MAX_CRYSTALS);

    private static final List<LivingEntity> UNMODIFIABLE_LIVING_VIEW = Collections.unmodifiableList(SHARED_LIVING_LIST);
    private static final List<Player> UNMODIFIABLE_ENEMY_VIEW = Collections.unmodifiableList(SHARED_ENEMY_PLAYERS_LIST);
    private static final List<EndCrystal> UNMODIFIABLE_CRYSTALS_VIEW = Collections.unmodifiableList(SHARED_CRYSTALS_LIST);

    public static final BlockPos.MutableBlockPos SCRATCH_POS_1 = new BlockPos.MutableBlockPos();
    public static final BlockPos.MutableBlockPos SCRATCH_POS_2 = new BlockPos.MutableBlockPos();
    public static final BlockPos.MutableBlockPos SCRATCH_POS_3 = new BlockPos.MutableBlockPos();
    public static final BlockPos.MutableBlockPos SCRATCH_POS_4 = new BlockPos.MutableBlockPos();

    private static final int RAYCAST_TABLE_SIZE = 256;
    private static final int RAYCAST_TABLE_MASK = RAYCAST_TABLE_SIZE - 1;
    private static final long[] BLOCK_RAY_KEYS = new long[RAYCAST_TABLE_SIZE];
    private static final int[] BLOCK_RAY_TICKS = new int[RAYCAST_TABLE_SIZE];
    private static final BlockHitResult[] BLOCK_RAY_RESULTS = new BlockHitResult[RAYCAST_TABLE_SIZE];

    private static final int[] ENTITY_LOS_IDS = new int[RAYCAST_TABLE_SIZE];
    private static final int[] ENTITY_LOS_TICKS = new int[RAYCAST_TABLE_SIZE];
    private static final boolean[] ENTITY_LOS_RESULTS = new boolean[RAYCAST_TABLE_SIZE];

    private static long lastScannedGameTime = -100L;
    private static int lastScannedPlayerTick = -100;
    private static int currentRaycastTickStamp = 1;

    private TargetManager() {
    }

    /**
     * Stage-1 Target Pipeline Guard:
     * Discards whitelisted friends by UUID (`FriendManager.isFriend(entity)`) at the absolute top
     * before any distance, yaw, or raycast math is calculated.
     */
    public static boolean isValidTarget(Entity entity) {
        if (FriendManager.isFriend(entity)) {
            return false;
        }
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || entity == mc.player) {
            return false;
        }
        if (entity instanceof LivingEntity living) {
            if (living.getHealth() <= 0.0F || living.isDeadOrDying()) {
                return false;
            }
            if (living instanceof Player player) {
                if (player.isSpectator() || AntiBot.shouldIgnore(player)) {
                    return false;
                }
                if (FriendUtils.isFriend(player.getName().getString())
                        || (mc.player != null && mc.player.isAlliedTo(player))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Scans and filters world entities strictly once per client tick.
     */
    public static void updateOncePerTick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            clear();
            return;
        }

        long gameTime = mc.level.getGameTime();
        int playerTick = mc.player.tickCount;
        if (gameTime == lastScannedGameTime && playerTick == lastScannedPlayerTick) {
            return;
        }
        lastScannedGameTime = gameTime;
        lastScannedPlayerTick = playerTick;
        currentRaycastTickStamp++;
        if (currentRaycastTickStamp <= 0) {
            currentRaycastTickStamp = 1;
        }

        final double px = mc.player.getX();
        final double py = mc.player.getY();
        final double pz = mc.player.getZ();
        final double maxScanRangeSq = 128.0D * 128.0D;

        int eCount = 0;
        int lCount = 0;
        int enemyCount = 0;
        int pCount = 0;
        int cCount = 0;

        SHARED_LIVING_LIST.clear();
        SHARED_ENEMY_PLAYERS_LIST.clear();
        SHARED_CRYSTALS_LIST.clear();

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == null || entity == mc.player || !entity.isAlive()) {
                continue;
            }

            if (entity instanceof Player onlinePlayer) {
                FriendManager.reconcileOnlinePlayerProfile(onlinePlayer);
            }

            double dx = entity.getX() - px;
            double dy = entity.getY() - py;
            double dz = entity.getZ() - pz;
            if ((dx * dx + dy * dy + dz * dz) > maxScanRangeSq) {
                continue;
            }

            if (eCount < MAX_ENTITIES) {
                VALID_ENTITIES[eCount++] = entity;
            }

            if (entity instanceof EndCrystal crystal) {
                if (cCount < MAX_CRYSTALS) {
                    END_CRYSTALS[cCount++] = crystal;
                    SHARED_CRYSTALS_LIST.add(crystal);
                }
                continue;
            }

            if (entity instanceof LivingEntity living && !(entity instanceof ArmorStand)) {
                if (living.getHealth() <= 0.0F) {
                    continue;
                }
                if (living instanceof Player player) {
                    if (player.isSpectator() || AntiBot.shouldIgnore(player)) {
                        continue;
                    }
                    if (pCount < MAX_PLAYERS) {
                        ALL_PLAYERS[pCount++] = player;
                    }
                    // Stage 1 UUID Friend Check before adding to enemy target arrays
                    boolean isFriend = FriendManager.isFriend(player)
                            || FriendUtils.isFriend(player.getName().getString())
                            || mc.player.isAlliedTo(player);
                    if (!isFriend && enemyCount < MAX_PLAYERS) {
                        ENEMY_PLAYERS[enemyCount++] = player;
                        SHARED_ENEMY_PLAYERS_LIST.add(player);
                    }
                }
                if (lCount < MAX_LIVING) {
                    VALID_LIVING[lCount++] = living;
                    SHARED_LIVING_LIST.add(living);
                }
            }
        }

        for (int i = eCount; i < validEntityCount; i++) VALID_ENTITIES[i] = null;
        for (int i = lCount; i < validLivingCount; i++) VALID_LIVING[i] = null;
        for (int i = enemyCount; i < enemyPlayerCount; i++) ENEMY_PLAYERS[i] = null;
        for (int i = pCount; i < allPlayerCount; i++) ALL_PLAYERS[i] = null;
        for (int i = cCount; i < endCrystalCount; i++) END_CRYSTALS[i] = null;

        validEntityCount = eCount;
        validLivingCount = lCount;
        enemyPlayerCount = enemyCount;
        allPlayerCount = pCount;
        endCrystalCount = cCount;
    }

    public static void clear() {
        for (int i = 0; i < validEntityCount; i++) VALID_ENTITIES[i] = null;
        for (int i = 0; i < validLivingCount; i++) VALID_LIVING[i] = null;
        for (int i = enemyPlayerCount - 1; i >= 0; i--) ENEMY_PLAYERS[i] = null;
        for (int i = 0; i < allPlayerCount; i++) ALL_PLAYERS[i] = null;
        for (int i = 0; i < endCrystalCount; i++) END_CRYSTALS[i] = null;

        validEntityCount = 0;
        validLivingCount = 0;
        enemyPlayerCount = 0;
        allPlayerCount = 0;
        endCrystalCount = 0;

        SHARED_LIVING_LIST.clear();
        SHARED_ENEMY_PLAYERS_LIST.clear();
        SHARED_CRYSTALS_LIST.clear();
        lastScannedGameTime = -100L;
        lastScannedPlayerTick = -100;
    }

    public static BlockHitResult raycastBlockFaceSameTick(
            Minecraft mc,
            BlockPos targetPos,
            Direction face
    ) {
        if (mc == null || mc.player == null || mc.level == null || targetPos == null || face == null) {
            return null;
        }
        updateOncePerTick(mc);

        long key = (targetPos.asLong() * 31L) ^ (face.ordinal() + 1L);
        int slot = ((int) (key ^ (key >>> 32))) & RAYCAST_TABLE_MASK;

        if (BLOCK_RAY_TICKS[slot] == currentRaycastTickStamp && BLOCK_RAY_KEYS[slot] == key) {
            return BLOCK_RAY_RESULTS[slot];
        }

        Vec3 eyePos = mc.player.getEyePosition(1.0F);
        Vec3 faceCenter = new Vec3(
                targetPos.getX() + 0.5D + face.getStepX() * 0.48D,
                targetPos.getY() + 0.5D + face.getStepY() * 0.48D,
                targetPos.getZ() + 0.5D + face.getStepZ() * 0.48D
        );
        BlockHitResult result = mc.level.clip(new ClipContext(
                eyePos,
                faceCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mc.player
        ));

        BLOCK_RAY_KEYS[slot] = key;
        BLOCK_RAY_TICKS[slot] = currentRaycastTickStamp;
        BLOCK_RAY_RESULTS[slot] = result;
        return result;
    }

    public static boolean hasLineOfSightToEntitySameTick(Minecraft mc, Entity entity) {
        if (mc == null || mc.player == null || mc.level == null || entity == null) {
            return false;
        }
        updateOncePerTick(mc);

        int entityId = entity.getId();
        int slot = (entityId * 0x9E3779B9) & RAYCAST_TABLE_MASK;

        if (ENTITY_LOS_TICKS[slot] == currentRaycastTickStamp && ENTITY_LOS_IDS[slot] == entityId) {
            return ENTITY_LOS_RESULTS[slot];
        }

        Vec3 eyePos = mc.player.getEyePosition(1.0F);
        AABB box = entity.getBoundingBox();
        double cx = (box.minX + box.maxX) * 0.5D;
        double cy = (box.minY + box.maxY) * 0.5D;
        double cz = (box.minZ + box.maxZ) * 0.5D;

        boolean visible = false;
        double[] sampleY = new double[]{
                cy,
                box.minY + box.getYsize() * 0.25D,
                box.minY + box.getYsize() * 0.80D
        };
        for (int i = 0; i < 3; i++) {
            BlockHitResult hit = mc.level.clip(new ClipContext(
                    eyePos,
                    new Vec3(cx, sampleY[i], cz),
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    mc.player
            ));
            if (hit.getType() == HitResult.Type.MISS) {
                visible = true;
                break;
            }
        }

        ENTITY_LOS_IDS[slot] = entityId;
        ENTITY_LOS_TICKS[slot] = currentRaycastTickStamp;
        ENTITY_LOS_RESULTS[slot] = visible;
        return visible;
    }

    public static List<LivingEntity> getSharedLivingTargets() {
        return UNMODIFIABLE_LIVING_VIEW;
    }

    public static List<Player> getSharedEnemyPlayers() {
        return UNMODIFIABLE_ENEMY_VIEW;
    }

    public static List<EndCrystal> getSharedEndCrystals() {
        return UNMODIFIABLE_CRYSTALS_VIEW;
    }

    public static Entity[] getValidEntitiesArray() {
        return VALID_ENTITIES;
    }

    public static int getValidEntityCount() {
        return validEntityCount;
    }

    public static LivingEntity[] getValidLivingArray() {
        return VALID_LIVING;
    }

    public static int getValidLivingCount() {
        return validLivingCount;
    }

    public static Player[] getEnemyPlayersArray() {
        return ENEMY_PLAYERS;
    }

    public static int getEnemyPlayerCount() {
        return enemyPlayerCount;
    }

    public static Player[] getAllPlayersArray() {
        return ALL_PLAYERS;
    }

    public static int getAllPlayerCount() {
        return allPlayerCount;
    }

    public static EndCrystal[] getEndCrystalsArray() {
        return END_CRYSTALS;
    }

    public static int getEndCrystalCount() {
        return endCrystalCount;
    }
}
