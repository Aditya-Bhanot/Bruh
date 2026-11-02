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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Phase 2: Centralized Tick-Deterministic ActionManager & Global Priority Mutex
 *
 * Responsibilities:
 * 1. Strict 4-Tier Priority Hierarchy:
 *    - CRITICAL (1): AutoTotem / AutoAnchor / AutoHeal / Offhand survival recovery (overrides everything).
 *    - COMBAT   (2): AutoMace / KillAura / CrystalAura (damage dealing).
 *    - MOVEMENT (3): AutoElytra / Velocity / Speed (positioning & flight).
 *    - UTILITY  (4): Inventory managers / AutoArmor.
 * 2. Consolidated 2-Tick Hardware-Simulated Hotbar Swaps:
 *    - Evaluates competing swap requests in the same tick, drops lower-priority requests,
 *      and executes a single deterministic swap sequence.
 * 3. Global CPS & Outgoing Interaction Packet Throttle:
 *    - Caps interactions across ALL active modules to <= 1 per tick and 10-14 CPS (72ms-100ms minimum interval).
 * 4. Once-Per-Tick Pre-Filtered Entity Target Cache:
 *    - Scans mc.level.entitiesForRendering() at most ONCE per client tick and shares the unmodifiable
 *      Valid Targets list across Combat, Movement, ESP, and TargetHUD modules.
 */
public final class ActionManager {
    private static final ActionManager INSTANCE = new ActionManager();

    public enum ActionPriority {
        CRITICAL(1), // 1. Survival: AutoTotem / AutoAnchor / AutoHeal
        COMBAT(2),   // 2. Damage: AutoMace / KillAura / CrystalAura
        MOVEMENT(3), // 3. Positioning: AutoElytra / Velocity / Speed
        UTILITY(4);  // 4. Inventory: AutoArmor / ChestStealer / Cleanup

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

    private static final int MIN_TICKS_PER_SWAP_STAGE = 2;
    private static final long MIN_STAGE_MS = 100L;
    private static final double GLOBAL_MIN_CPS = 10.0D;
    private static final double GLOBAL_MAX_CPS = 14.0D;
    private static final long CRITICAL_LOCK_FAILSAFE_MS = 450L;
    private static final long GENERAL_LOCK_FAILSAFE_MS = 650L;

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
    private long currentMinInteractionIntervalMs = 82L;

    // Once-Per-Tick Pre-Filtered Valid Targets Cache
    private int cachedTargetsTick = -1;
    private final List<LivingEntity> validTargetsMutable = new ArrayList<>(32);
    private List<LivingEntity> validTargetsView = Collections.emptyList();

    private ActionManager() {
    }

    public static ActionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Called once per client tick to advance consolidated swaps, expire stale locks,
     * and refresh the shared pre-filtered Valid Targets list.
     */
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

    /**
     * Automatically detects CRITICAL survival actions (offhand Totem swap recovery, critical low health
     * Golden Apple consumption) and locks out lower-priority COMBAT/MOVEMENT slot swaps.
     */
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
     * Attempts to claim the global action mutex for the given priority level.
     * Higher priority (lower rank number) preempts lower priority locks.
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

    /**
     * Global CPS & Outgoing Interaction Packet Throttle:
     * Ensures the client never dispatches more than 1 interaction/attack packet in the same tick
     * and strictly obeys the global 10-14 CPS human limit across all active modules.
     */
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
     * Submits a consolidated hotbar swap request to the global ActionManager:
     * If two modules request a hotbar swap in the same tick, ActionManager evaluates priority,
     * drops the lower-priority request, and executes a single deterministic hardware-simulated swap.
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

        // If a swap is already running for a higher or equal priority, drop lower priority requests
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

        // Step 1: Physically select the target hotbar slot on the client
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

        if (client.player.swinging || client.player.attackAnim > 0.0F || client.player.hurtTime > 0) {
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

                if (onSlotReadyCallback != null && canDispatchInteraction(client, queuedSwapPriority)) {
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

    /**
     * 2. Event Bus Pre-Filtering (Single Once-Per-Tick "Valid Targets" List):
     * Builds the filtered list of valid living combat targets ONCE per tick so ESP, TargetHUD,
     * AutoMace, AutoElytra, and KillAura never redundantly iterate mc.level.entitiesForRendering().
     */
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

    /**
     * Queries the pre-filtered once-per-tick Valid Targets cache for the closest visible target.
     */
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
     * Phase 3: Strict 3.0m Vanilla Hitbox Raycast & Line-of-Sight Verification:
     * Guarantees no combat module (AutoMace, KillAura, etc.) can send an InteractEntity attack packet
     * unless the player's actual look ray intersects the target's bounding box within <= 3.0m
     * with zero solid blocks obstructing the ray (zero Wallhack Hits).
     */
    public static boolean verifyStrict3mAttackRaycast(Minecraft client, LivingEntity target, double requestedReach) {
        if (client == null || client.player == null || client.level == null || target == null || !target.isAlive()) {
            return false;
        }
        double legalReach = Math.min(3.0D, Math.max(1.0D, requestedReach));
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 lookVec = client.player.getViewVector(1.0F);
        Vec3 rayEnd = eyePos.add(lookVec.scale(legalReach));

        AABB targetBox = target.getBoundingBox().inflate(target.getPickRadius());
        if (targetBox.contains(eyePos)) {
            return true;
        }

         java.util.Optional<Vec3> hitOpt = targetBox.clip(eyePos, rayEnd);
        if (hitOpt.isEmpty()) {
            return false;
        }

        Vec3 hitPoint = hitOpt.get();
        if (eyePos.distanceToSqr(hitPoint) > (legalReach * legalReach)) {
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