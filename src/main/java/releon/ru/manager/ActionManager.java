package releon.ru.manager;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AntiBot;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Centralized Tick-Deterministic ActionManager & Split-Priority Mutex
 *
 * Split-Priority Hierarchy:
 * - Priority 0: CRITICAL (Survival AutoTotem / AutoHeal)
 * - Priority 1 (Highest Combat/Flight): AUTO_ELYTRA_UNEQUIP
 *     Triggered when target distance < 4.5m and v_y < -0.1 while wearing an Elytra.
 *     Overrides the AutoMace lock to immediately cancel flight state and execute the 2-tick chestplate swap.
 * - Priority 2 (Critical Strike): AUTO_MACE_STRIKE (and COMBAT alias)
 *     Triggered when target is in smash range AND Elytra is confirmed unequipped.
 *     Executes the weapon swap to cachedMaceSlot and sends the attack packet.
 * - Priority 3 (Lowest Flight): AUTO_ELYTRA_EQUIP (and MOVEMENT alias)
 *     Triggered on Wind Charge upward velocity (v_y > 0.6).
 *     Only executes if AutoMace is completely idle.
 * - Priority 4: UTILITY (Inventory managers / AutoArmor)
 */
public final class ActionManager {
    private static final ActionManager INSTANCE = new ActionManager();

    public enum ActionPriority {
        CRITICAL(0),
        AUTO_ELYTRA_UNEQUIP(1), // Priority 1 (Highest): Flight Cancel / Chestplate Swap (< 4.5m & v_y < -0.1)
        AUTO_MACE_STRIKE(2),    // Priority 2 (Critical): AutoMace Strike (after Elytra is unequipped)
        COMBAT(2),              // Alias for general combat modules
        AUTO_ELYTRA_EQUIP(3),   // Priority 3 (Lowest): Flight Deploy (v_y > 0.6 when AutoMace is idle)
        MOVEMENT(3),            // Alias for general movement modules
        UTILITY(4);             // Lowest utility priority

        private final int rank;

        ActionPriority(int rank) {
            this.rank = rank;
        }

        public int getRank() {
            return rank;
        }

        public boolean outranks(ActionPriority other) {
            return other == null || this.rank < other.rank;
        }

        public boolean outranksOrEquals(ActionPriority other) {
            return other == null || this.rank <= other.rank;
        }
    }

    private enum ConsolidatedSwapStage {
        IDLE,
        WAIT_TO_INTERACT,
        WAIT_TO_RESTORE
    }

    private static final int MIN_TICKS_PER_SWAP_STAGE = 1;
    private static final long MIN_STAGE_MS = 50L;
    private static final double GLOBAL_MIN_CPS = 10.0D;
    private static final double GLOBAL_MAX_CPS = 14.0D;
    private static final long CRITICAL_LOCK_FAILSAFE_MS = 450L;
    private static final long GENERAL_LOCK_FAILSAFE_MS = 550L;

    // Mutex & State Locking
    private volatile ActionPriority activeLockPriority = null;
    private volatile String activeLockOwnerName = "";
    private volatile long lockAcquiredAtMs = 0L;

    // Consolidated 2-Tick Swap State Machine
    private ConsolidatedSwapStage swapStage = ConsolidatedSwapStage.IDLE;
    private ActionPriority queuedSwapPriority = null;
    private int targetHotbarSlot = -1;
    private int restoreHotbarSlot = -1;
    private int stageStartTick = -1;
    private long stageStartTimeMs = 0L;
    private boolean simulateUseClick = false;
    private Runnable onSlotReadyCallback = null;

    // Global Outgoing Interaction / CPS Throttle
    private int lastInteractionTick = -1;
    private long lastInteractionTimeMs = 0L;
    private long currentMinInteractionIntervalMs = 80L;

    // Once-Per-Tick Pre-Filtered Valid Targets Cache
    private int cachedTargetsTick = -1;
    private final List<LivingEntity> validTargetsMutable = new ArrayList<>(32);
    private List<LivingEntity> validTargetsView = Collections.emptyList();

    private ActionManager() {
    }

    public static ActionManager getInstance() {
        return INSTANCE;
    }

    public synchronized void ensureTickSynchronized(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            resetAll();
            return;
        }

        checkLockTimeouts();
        detectCriticalSurvivalLock(client);

        if (cachedTargetsTick != client.player.tickCount) {
            cachedTargetsTick = client.player.tickCount;
            rebuildValidTargetsCacheOncePerTick(client);
            advanceConsolidatedSwapMachine(client);
        }
    }

    private void detectCriticalSurvivalLock(Minecraft client) {
        if (client.player == null) {
            return;
        }
        boolean criticalHealthEating = (client.player.getHealth() + client.player.getAbsorptionAmount() <= 8.0F)
                && client.player.isUsingItem()
                && (client.player.getUseItem().has(DataComponents.FOOD)
                || client.player.getUseItem().getItem() == Items.GOLDEN_APPLE
                || client.player.getUseItem().getItem() == Items.ENCHANTED_GOLDEN_APPLE);

        if (criticalHealthEating) {
            acquireLock(ActionPriority.CRITICAL, "SurvivalAutoHeal");
        } else if (activeLockPriority == ActionPriority.CRITICAL && "SurvivalAutoHeal".equals(activeLockOwnerName)) {
            releaseLock(ActionPriority.CRITICAL, "SurvivalAutoHeal");
        }
    }

    private void checkLockTimeouts() {
        if (activeLockPriority == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - lockAcquiredAtMs;
        long limit = activeLockPriority == ActionPriority.CRITICAL ? CRITICAL_LOCK_FAILSAFE_MS : GENERAL_LOCK_FAILSAFE_MS;
        if (elapsed > limit && swapStage == ConsolidatedSwapStage.IDLE) {
            activeLockPriority = null;
            activeLockOwnerName = "";
        }
    }

    /**
     * Claims the global action mutex if the requested priority outranks or equals the active lock.
     * Priority 1 (AUTO_ELYTRA_UNEQUIP) automatically preempts Priority 2 (AUTO_MACE_STRIKE).
     */
    public synchronized boolean acquireLock(ActionPriority priority, String ownerName) {
        checkLockTimeouts();
        if (activeLockPriority != null && !priority.outranksOrEquals(activeLockPriority)) {
            return false;
        }
        if (activeLockPriority != null && priority.outranks(activeLockPriority) && swapStage != ConsolidatedSwapStage.IDLE) {
            abortActiveConsolidatedSwap(Minecraft.getInstance());
        }
        activeLockPriority = priority;
        activeLockOwnerName = ownerName != null ? ownerName : priority.name();
        lockAcquiredAtMs = System.currentTimeMillis();
        return true;
    }

    public synchronized void releaseLock(ActionPriority priority, String ownerName) {
        if (activeLockPriority == priority
                && (ownerName == null || ownerName.isEmpty() || ownerName.equals(activeLockOwnerName))) {
            if (swapStage == ConsolidatedSwapStage.IDLE) {
                activeLockPriority = null;
                activeLockOwnerName = "";
            }
        }
    }

    public boolean isBlockedByHigherPriority(ActionPriority requesterPriority) {
        checkLockTimeouts();
        if (activeLockPriority == null) {
            return false;
        }
        return activeLockPriority.outranks(requesterPriority);
    }

    public ActionPriority getActiveLockPriority() {
        checkLockTimeouts();
        return activeLockPriority;
    }

    public boolean isSwapInProgress() {
        return swapStage != ConsolidatedSwapStage.IDLE;
    }

    public synchronized boolean canDispatchInteraction(Minecraft client, ActionPriority priority) {
        if (client == null || client.player == null) {
            return false;
        }
        if (isBlockedByHigherPriority(priority)) {
            return false;
        }
        if (client.player.tickCount == lastInteractionTick) {
            return false;
        }
        long now = System.currentTimeMillis();
        return (now - lastInteractionTimeMs) >= currentMinInteractionIntervalMs;
    }

    public synchronized void recordInteractionDispatched(Minecraft client) {
        if (client != null && client.player != null) {
            lastInteractionTick = client.player.tickCount;
        }
        lastInteractionTimeMs = System.currentTimeMillis();
        double cps = ThreadLocalRandom.current().nextDouble(GLOBAL_MIN_CPS, GLOBAL_MAX_CPS);
        currentMinInteractionIntervalMs = Math.round(1000.0D / cps);
    }

    /**
     * Executes a deterministic 2-tick hardware-simulated hotbar swap governed by ActionPriority.
     * Priority 1 (AUTO_ELYTRA_UNEQUIP) overrides Priority 2 (AUTO_MACE_STRIKE) and Priority 3 (AUTO_ELYTRA_EQUIP).
     */
    public synchronized boolean submitConsolidatedSwap(
            Minecraft client,
            ActionPriority priority,
            String ownerName,
            int targetSlot,
            int restoreSlot,
            boolean rightClickUseOnEquip,
            boolean clickSim,
            Runnable onSlotReady
    ) {
        if (client == null || client.player == null || targetSlot < 0 || targetSlot > 8) {
            return false;
        }
        ensureTickSynchronized(client);

        if (isBlockedByHigherPriority(priority)) {
            return false;
        }

        if (swapStage != ConsolidatedSwapStage.IDLE && queuedSwapPriority != null) {
            if (!priority.outranks(queuedSwapPriority)) {
                return false;
            }
            abortActiveConsolidatedSwap(client);
        }

        acquireLock(priority, ownerName);
        this.queuedSwapPriority = priority;
        this.targetHotbarSlot = targetSlot;
        this.restoreHotbarSlot = restoreSlot;
        this.simulateUseClick = clickSim && rightClickUseOnEquip;
        this.onSlotReadyCallback = onSlotReady != null ? onSlotReady : (rightClickUseOnEquip ? () -> {
            if (client.gameMode != null && client.player != null) {
                if (this.simulateUseClick && client.options != null) {
                    KeyMapping.click(client.options.keyUse.getDefaultKey());
                }
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                client.player.swing(InteractionHand.MAIN_HAND);
            }
        } : null);

        // Tick 1: Physically select the target hotbar slot on the client
        if (client.player.getInventory().getSelectedSlot() != targetSlot) {
            client.player.getInventory().setSelectedSlot(targetSlot);
        }
        this.stageStartTick = client.player.tickCount;
        this.stageStartTimeMs = System.currentTimeMillis();
        this.swapStage = ConsolidatedSwapStage.WAIT_TO_INTERACT;
        return true;
    }

    private void advanceConsolidatedSwapMachine(Minecraft client) {
        if (swapStage == ConsolidatedSwapStage.IDLE || client.player == null) {
            return;
        }

        if (client.player.swinging || client.player.attackAnim > 0.0F) {
            return;
        }

        long now = System.currentTimeMillis();
        int ticksElapsed = client.player.tickCount - stageStartTick;
        long msElapsed = now - stageStartTimeMs;

        switch (swapStage) {
            case WAIT_TO_INTERACT -> {
                if (ticksElapsed < MIN_TICKS_PER_SWAP_STAGE || msElapsed < MIN_STAGE_MS) {
                    return;
                }
                if (targetHotbarSlot >= 0 && targetHotbarSlot <= 8
                        && client.player.getInventory().getSelectedSlot() != targetHotbarSlot) {
                    client.player.getInventory().setSelectedSlot(targetHotbarSlot);
                    stageStartTick = client.player.tickCount;
                    stageStartTimeMs = now;
                    return;
                }

                if (onSlotReadyCallback != null) {
                    onSlotReadyCallback.run();
                    recordInteractionDispatched(client);
                }

                if (restoreHotbarSlot >= 0 && restoreHotbarSlot <= 8) {
                    stageStartTick = client.player.tickCount;
                    stageStartTimeMs = now;
                    swapStage = ConsolidatedSwapStage.WAIT_TO_RESTORE;
                } else {
                    completeConsolidatedSwap();
                }
            }

            case WAIT_TO_RESTORE -> {
                if (ticksElapsed < MIN_TICKS_PER_SWAP_STAGE || msElapsed < MIN_STAGE_MS) {
                    return;
                }
                if (restoreHotbarSlot >= 0 && restoreHotbarSlot <= 8
                        && client.player.getInventory().getSelectedSlot() != restoreHotbarSlot) {
                    client.player.getInventory().setSelectedSlot(restoreHotbarSlot);
                }
                completeConsolidatedSwap();
            }

            default -> completeConsolidatedSwap();
        }
    }

    private void abortActiveConsolidatedSwap(Minecraft client) {
        if (client != null && client.player != null && restoreHotbarSlot >= 0 && restoreHotbarSlot <= 8
                && client.player.getInventory().getSelectedSlot() != restoreHotbarSlot) {
            client.player.getInventory().setSelectedSlot(restoreHotbarSlot);
        }
        completeConsolidatedSwap();
    }

    private void completeConsolidatedSwap() {
        ActionPriority finishedPriority = queuedSwapPriority;
        swapStage = ConsolidatedSwapStage.IDLE;
        queuedSwapPriority = null;
        targetHotbarSlot = -1;
        restoreHotbarSlot = -1;
        stageStartTick = -1;
        onSlotReadyCallback = null;
        if (finishedPriority != null && activeLockPriority == finishedPriority) {
            activeLockPriority = null;
            activeLockOwnerName = "";
        }
    }

    private void rebuildValidTargetsCacheOncePerTick(Minecraft client) {
        validTargetsMutable.clear();
        if (client.player == null || client.level == null) {
            validTargetsView = Collections.emptyList();
            return;
        }

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living == client.player || !living.isAlive() || living.getHealth() <= 0.0F) {
                continue;
            }
            if (living instanceof ArmorStand) {
                continue;
            }
            if (living instanceof Player player) {
                if (AntiBot.shouldIgnore(player)) {
                    continue;
                }
            } else if (!(living instanceof Mob) && !(living instanceof Animal)) {
                continue;
            }
            validTargetsMutable.add(living);
        }

        validTargetsView = Collections.unmodifiableList(validTargetsMutable);
    }

    public List<LivingEntity> getValidTargets(Minecraft client) {
        ensureTickSynchronized(client);
        return validTargetsView;
    }

    public LivingEntity getNearestVisibleTarget(Minecraft client, double maxRange, boolean ignoreFriends) {
        ensureTickSynchronized(client);
        if (client == null || client.player == null || validTargetsView.isEmpty()) {
            return null;
        }

        double maxRangeSqr = maxRange * maxRange;
        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();

        LivingEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;

        for (int i = 0, size = validTargetsView.size(); i < size; i++) {
            LivingEntity candidate = validTargetsView.get(i);
            if (!candidate.isAlive()) {
                continue;
            }
            if (ignoreFriends && isWhitelistedFriend(client, candidate)) {
                continue;
            }

            AABB box = candidate.getBoundingBox();
            double dx = eyeX - Mth.clamp(eyeX, box.minX, box.maxX);
            double dy = eyeY - Mth.clamp(eyeY, box.minY, box.maxY);
            double dz = eyeZ - Mth.clamp(eyeZ, box.minZ, box.maxZ);
            double distSqr = dx * dx + dy * dy + dz * dz;

            if (distSqr > maxRangeSqr || distSqr >= bestDistSqr) {
                continue;
            }
            if (!hasLineOfSight(client, candidate)) {
                continue;
            }

            bestDistSqr = distSqr;
            best = candidate;
        }

        return best;
    }

    public static boolean isWhitelistedFriend(Minecraft client, Entity entity) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        if (FriendUtils.isFriend(player.getName().getString())) {
            return true;
        }
        return client != null && client.player != null && client.player.isAlliedTo(player);
    }

    public static boolean hasLineOfSight(Minecraft client, LivingEntity target) {
        if (client == null || client.player == null || client.level == null || target == null) {
            return false;
        }
        if (client.player.hasLineOfSight(target)) {
            return true;
        }
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 center = target.getBoundingBox().getCenter();
        BlockHitResult hit = client.level.clip(new ClipContext(
                eyePos,
                center,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                client.player
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * Strict 3.0m Vanilla Hitbox Raycast & Line-of-Sight Verification
     * (Supports both camera look vector and Silent rotation vector).
     */
    public static boolean verifyStrict3mAttackRaycast(Minecraft client, LivingEntity target, double requestedReach, Vec3 lookDirection) {
        if (client == null || client.player == null || client.level == null || target == null || !target.isAlive()) {
            return false;
        }
        double legalReach = Math.min(3.0D, Math.max(1.0D, requestedReach));
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 dir = lookDirection != null ? lookDirection : client.player.getViewVector(1.0F);
        Vec3 rayEnd = eyePos.add(dir.scale(legalReach + 0.15D));

        AABB targetBox = target.getBoundingBox().inflate(target.getPickRadius() + 0.05D);
        if (targetBox.contains(eyePos)) {
            return true;
        }

        Optional<Vec3> hitOpt = targetBox.clip(eyePos, rayEnd);
        if (hitOpt.isEmpty()) {
            return false;
        }

        Vec3 hitPoint = hitOpt.get();
        if (eyePos.distanceToSqr(hitPoint) > ((legalReach + 0.15D) * (legalReach + 0.15D))) {
            return false;
        }

        BlockHitResult blockHit = client.level.clip(new ClipContext(
                eyePos,
                hitPoint,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                client.player
        ));
        return blockHit.getType() == HitResult.Type.MISS;
    }

    public static boolean verifyStrict3mAttackRaycast(Minecraft client, LivingEntity target, double requestedReach) {
        return verifyStrict3mAttackRaycast(client, target, requestedReach, null);
    }

    public synchronized void resetAll() {
        activeLockPriority = null;
        activeLockOwnerName = "";
        lockAcquiredAtMs = 0L;
        swapStage = ConsolidatedSwapStage.IDLE;
        queuedSwapPriority = null;
        targetHotbarSlot = -1;
        restoreHotbarSlot = -1;
        stageStartTick = -1;
        onSlotReadyCallback = null;
        cachedTargetsTick = -1;
        validTargetsMutable.clear();
        validTargetsView = Collections.emptyList();
    }
}