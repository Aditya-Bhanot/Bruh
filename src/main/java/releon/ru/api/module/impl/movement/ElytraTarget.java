package releon.ru.api.module.impl.movement;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AntiBot;
import releon.ru.api.module.impl.combat.MaceTarget;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.concurrent.ThreadLocalRandom;

public final class ElytraTarget extends Module {
    private static ElytraTarget instance;
    public static boolean shouldElytraTarget;

    // 1. Timing & Randomization
    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization", "Enable randomized delay variance for elytra swaps.", true));
    private final NumberSetting minRandomMs = register(new NumberSetting(
            "Min Random (ms)", "Minimum random delay in milliseconds.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting maxRandomMs = register(new NumberSetting(
            "Max Random (ms)", "Maximum random delay in milliseconds.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting reequipDelayMs = register(new NumberSetting(
            "Reequip Delay (ms)", "Delay in milliseconds before re-equipping the elytra after dropping.", 217.0D, 0.0D, 1000.0D, 1.0D));

    // 2. State Conditions & Integration
    private final BooleanSetting automaceSync = register(new BooleanSetting(
            "Automace Sync", "Synchronize elytra drop and re-equip timing with AutoMace.", true));
    private final BooleanSetting inAir = register(new BooleanSetting(
            "In Air", "Automatically equip elytra whenever airborne outside drop range.", false));
    private final BooleanSetting onPearl = register(new BooleanSetting(
            "On Pearl", "Automatically equip elytra when throwing an ender pearl.", false));
    private final BooleanSetting disableOnGround = register(new BooleanSetting(
            "Disable on Ground", "Swap to chestplate and reset flight drop state when touching the ground.", true));
    private final BooleanSetting clickSim = register(new BooleanSetting(
            "ClickSim", "Simulate client click input during armor swaps.", true));

    // 3. Spatial & Distance Thresholds
    private final NumberSetting heightDiff = register(new NumberSetting(
            "Height Diff", "Required vertical height difference above target to initiate chestplate drop.", 7.0D, 0.0D, 50.0D, 0.1D));
    private final NumberSetting heightRange = register(new NumberSetting(
            "Height Range", "Maximum target distance within which height drop triggers.", 8.4D, 1.0D, 30.0D, 0.1D));
    private final NumberSetting macePreHitRange = register(new NumberSetting(
            "Mace Pre-Hit Range", "Pre-impact distance threshold to unequip elytra into chestplate before a mace hit.", 2.5D, 0.5D, 10.0D, 0.1D));
    private final NumberSetting minDropDist = register(new NumberSetting(
            "Min Drop Dist", "Minimum drop distance required for chestplate drop and smash preparation.", 1.5D, 0.0D, 20.0D, 0.1D));

    // Legit Swap State Machine (Stops movement input on the swap tick to bypass InventoryMove checks)
    private enum SwapAction {
        NONE,
        UNEQUIP_ELYTRA_TO_CHEST,
        REEQUIP_ELYTRA
    }

    private final StopWatch reequipTimer = new StopWatch();
    private final StopWatch swapCooldownTimer = new StopWatch();

    private SwapAction pendingSwapAction = SwapAction.NONE;
    private int movementLockTicks = 0;
    private boolean droppedForSmash = false;
    private boolean maceAttackConfirmed = false;
    private boolean pearlEquipPending = false;
    private double scheduledReequipDelayMs = 217.0D;
    private double dropStartY = Double.NaN;
    private double airbornePeakY = Double.NaN;
    private LivingEntity currentTarget = null;

    public ElytraTarget() {
        super("AutoElytra", "Advanced automatic elytra drop and re-equip combat controller.", ModuleCategory.MOVEMENT);
        instance = this;
    }

    public static ElytraTarget getInstance() {
        return instance;
    }

    public static boolean isTargetingActive() {
        return instance != null && instance.isEnabled() && shouldElytraTarget;
    }

    @Override
    protected void onEnable() {
        shouldElytraTarget = true;
        resetInternalState();
    }

    @Override
    protected void onDisable() {
        shouldElytraTarget = false;
        resetInternalState();
    }

    /**
     * Called by AutoMace (MaceTarget) before executing any Mace attack to ensure the Elytra
     * is legit-unequipped first. Returns true only when the Elytra is completely unequipped
     * and the player is out of fall-flying state.
     */
    public boolean ensureUnequippedBeforeMaceAttack(Minecraft client, LivingEntity target) {
        if (client.player == null) {
            return false;
        }
        if (!InventoryUtils.hasElytra() && !client.player.isFallFlying()) {
            return pendingSwapAction == SwapAction.NONE;
        }
        // Queue or execute legit Elytra -> Chestplate swap immediately before attack
        if (pendingSwapAction != SwapAction.UNEQUIP_ELYTRA_TO_CHEST) {
            queueLegitUnequipForSmash(client, target);
        }
        return false;
    }

    /**
     * Called by AutoMace (MaceTarget) the moment the Mace smash or StunSlam attack lands.
     * Arms the Reequip Delay timer so AutoElytra smoothly re-equips the Elytra after the hit.
     */
    public void notifyMaceAttackLanded() {
        maceAttackConfirmed = true;
        if (!droppedForSmash) {
            droppedForSmash = true;
        }
        scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
        reequipTimer.reset();
    }

    public boolean isSyncEnabled() {
        return isEnabled() && automaceSync.getValue();
    }

    @SubscribeEvent
    public void onInput(InputEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        // Legit Inventory Swap Protection: halt movement inputs during the exact tick an armor swap executes
        if (pendingSwapAction != SwapAction.NONE || movementLockTicks > 0) {
            event.setDirectionalLow(false, false, false, false);
            event.setJumping(false);
            event.setSprinting(false);
            client.player.setSprinting(false);

            if (pendingSwapAction != SwapAction.NONE) {
                executePendingLegitSwap(client);
            }
            if (movementLockTicks > 0) {
                movementLockTicks--;
            }
            return;
        }

        // Assist mid-air Elytra deployment right after re-equipping when airborne
        if (!droppedForSmash
                && InventoryUtils.hasElytra()
                && !client.player.onGround()
                && !client.player.isFallFlying()
                && !client.player.isInWater()
                && !client.player.isInLava()
                && !swapCooldownTimer.finished(180.0D)) {
            event.setJumping(client.player.tickCount % 2 == 0);
        }
    }

    @SubscribeEvent
    public void onRotationUpdate(RotationUpdateEvent event) {
        if (event.getPhase() != EventPhase.PRE) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            resetInternalState();
            return;
        }

        updateAirborneTracking(client);

        // Execute any queued legit swap if InputEvent did not already flush it
        if (pendingSwapAction != SwapAction.NONE) {
            executePendingLegitSwap(client);
            return;
        }

        // 1. Disable on Ground logic (Strictly executed only when enabled)
        if (disableOnGround.getValue() && client.player.onGround()) {
            if (InventoryUtils.hasElytra()) {
                queueLegitSwap(SwapAction.UNEQUIP_ELYTRA_TO_CHEST);
            }
            droppedForSmash = false;
            maceAttackConfirmed = false;
            pearlEquipPending = false;
            dropStartY = Double.NaN;
            return;
        }

        // 2. On Pearl logic (Strictly skipped when Disabled)
        if (onPearl.getValue() && pearlEquipPending) {
            pearlEquipPending = false;
            if (!InventoryUtils.hasElytra()) {
                queueLegitSwap(SwapAction.REEQUIP_ELYTRA);
                return;
            }
        }

        // 3. Automace Sync gate (Strictly skipped when Disabled)
        if (automaceSync.getValue()) {
            MaceTarget autoMace = MaceTarget.getInstance();
            if (autoMace != null && autoMace.isEnabled()) {
                LivingEntity maceTarget = autoMace.getActiveTarget();
                if (maceTarget != null && maceTarget.isAlive()) {
                    currentTarget = maceTarget;
                } else {
                    currentTarget = findCombatTarget(client);
                }
            } else {
                currentTarget = findCombatTarget(client);
            }
        } else {
            currentTarget = findCombatTarget(client);
        }

        // 4. Handle Re-Equip Phase after dropping to Chestplate for a Mace smash
        if (droppedForSmash) {
            if (shouldReequipElytraNow(client)) {
                queueLegitSwap(SwapAction.REEQUIP_ELYTRA);
                droppedForSmash = false;
                maceAttackConfirmed = false;
                dropStartY = Double.NaN;
            }
            return;
        }

        // 5. Evaluate Spatial & Distance Thresholds to legit-unequip Elytra -> Chestplate above target
        if (InventoryUtils.hasElytra() && !client.player.onGround() && currentTarget != null) {
            if (!automaceSync.getValue() || isHoldingOrHasMace(client)) {
                if (shouldDropToChestplateForSmash(client, currentTarget)) {
                    queueLegitUnequipForSmash(client, currentTarget);
                    return;
                }
            }
        }

        // 6. In Air logic (Strictly skipped when Disabled)
        if (inAir.getValue() && !client.player.onGround() && !InventoryUtils.hasElytra() && !droppedForSmash) {
            double currentFall = getEffectiveFallDistance(client);
            if (currentFall >= minDropDist.getValue()
                    && (currentTarget == null || !shouldDropToChestplateForSmash(client, currentTarget))) {
                queueLegitSwap(SwapAction.REEQUIP_ELYTRA);
            }
        }
    }

    @SubscribeEvent
    public void onPacket(PacketEvent event) {
        if (!onPearl.getValue() || !event.isSend()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (event.getPacket() instanceof ServerboundUseItemPacket usePacket) {
            InteractionHand hand = usePacket.getHand();
            ItemStack usedStack = client.player.getItemInHand(hand);
            if (!usedStack.isEmpty() && usedStack.getItem() == Items.ENDER_PEARL) {
                pearlEquipPending = true;
            }
        }
    }

    private void queueLegitUnequipForSmash(Minecraft client, LivingEntity target) {
        droppedForSmash = true;
        maceAttackConfirmed = false;
        dropStartY = client.player.getY();
        scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
        reequipTimer.reset();
        queueLegitSwap(SwapAction.UNEQUIP_ELYTRA_TO_CHEST);
    }

    private void queueLegitSwap(SwapAction action) {
        this.pendingSwapAction = action;
        this.movementLockTicks = 1;
    }

    private void executePendingLegitSwap(Minecraft client) {
        SwapAction action = this.pendingSwapAction;
        this.pendingSwapAction = SwapAction.NONE;

        if (client.player == null) {
            return;
        }

        if (action == SwapAction.UNEQUIP_ELYTRA_TO_CHEST) {
            equipChestplateOrUnequipElytraLegit(client);
        } else if (action == SwapAction.REEQUIP_ELYTRA) {
            equipElytraAndStartGlideLegit(client);
        }
    }

    /**
     * High-Precision Kinematic Drop Evaluator:
     * Calculates exact 3D eye-to-hitbox distance, horizontal trajectory convergence, and vertical descent speed
     * so the Elytra is unequipped with enough vertical clearance to accumulate Min Drop Dist (1.5+ blocks)
     * in free-fall right as the player enters AutoMace's Swing Range (3.0) / Mace Pre-Hit Range (2.5).
     */
    private boolean shouldDropToChestplateForSmash(Minecraft client, LivingEntity target) {
        if (!hasLineOfSight(client, target)) {
            return false;
        }

        Vec3 playerPos = client.player.position();
        Vec3 playerEye = client.player.getEyePosition(1.0F);
        Vec3 playerVel = client.player.getDeltaMovement();
        Vec3 targetVel = target.getDeltaMovement();

        AABB targetBox = target.getBoundingBox();
        Vec3 closestPoint = clampToBox(playerEye, targetBox);
        double eyeToBoxDist = playerEye.distanceTo(closestPoint);

        double verticalAltitudeOverTarget = playerPos.y - target.getY();
        double verticalGapToTop = playerPos.y - targetBox.maxY;
        double descentSpeed = Math.max(0.08D, -playerVel.y);
        double effectiveFall = getEffectiveFallDistance(client);

        // Verify the player either already has Min Drop Dist (1.5) or has vertical room to drop Min Drop Dist before impact
        boolean canAccumulateMinDrop = effectiveFall >= minDropDist.getValue()
                || verticalGapToTop >= minDropDist.getValue() * 0.85D
                || verticalAltitudeOverTarget >= minDropDist.getValue() + 0.5D;
        if (!canAccumulateMinDrop) {
            return false;
        }

        // Predict horizontal convergence over the next 2 ticks so we only unequip when actually on an intercept course
        Vec3 relativeHorizontalVel = new Vec3(playerVel.x - targetVel.x, 0.0D, playerVel.z - targetVel.z);
        double currentHorizontalDist = Math.hypot(playerPos.x - target.getX(), playerPos.z - target.getZ());
        Vec3 projectedPos = playerPos.add(relativeHorizontalVel.scale(1.5D));
        double projectedHorizontalDist = Math.hypot(projectedPos.x - target.getX(), projectedPos.z - target.getZ());
        double minHorizontalIntercept = Math.min(currentHorizontalDist, projectedHorizontalDist);

        // Trigger 1: Pre-Hit Range (2.5) + free-fall lead distance so the Elytra comes off BEFORE the Mace swing tick
        double leadDistanceForFreeFall = client.player.isFallFlying()
                ? Math.max(minDropDist.getValue(), descentSpeed * 2.2D)
                : descentSpeed * 1.2D;
        double preHitTriggerDist = macePreHitRange.getValue() + leadDistanceForFreeFall;

        if (eyeToBoxDist <= preHitTriggerDist && minHorizontalIntercept <= macePreHitRange.getValue() + 1.25D) {
            return true;
        }

        // Trigger 2: Height Diff (7.0) within Height Range (8.4) — high-altitude vertical drop directly above target
        if (eyeToBoxDist <= heightRange.getValue()
                && minHorizontalIntercept <= Math.min(heightRange.getValue() * 0.55D, 3.8D)
                && verticalAltitudeOverTarget >= heightDiff.getValue()) {
            return true;
        }

        return false;
    }

    private boolean shouldReequipElytraNow(Minecraft client) {
        if (client.player == null || client.player.onGround()) {
            return false;
        }
        if (!reequipTimer.finished(scheduledReequipDelayMs)) {
            return false;
        }

        // 1. Immediate re-equip after a confirmed Mace smash or Wind Burst upward bounce
        if (maceAttackConfirmed || client.player.getDeltaMovement().y > 0.02D) {
            return true;
        }

        // 2. If Automace Sync is active and we are still falling toward a valid target within strike range, wait for the hit
        if (automaceSync.getValue() && currentTarget != null && currentTarget.isAlive()) {
            double eyeDist = client.player.getEyePosition(1.0F).distanceTo(clampToBox(client.player.getEyePosition(1.0F), currentTarget.getBoundingBox()));
            boolean stillAboveTarget = client.player.getY() >= currentTarget.getY() - 0.5D;
            if (stillAboveTarget && eyeDist <= heightRange.getValue() && client.player.getDeltaMovement().y < 0.0D) {
                return false;
            }
        }

        // 3. Fallback: re-equip once Min Drop Dist has been traversed and target window has passed
        double droppedDistance = Double.isNaN(dropStartY)
                ? getEffectiveFallDistance(client)
                : Math.max(getEffectiveFallDistance(client), dropStartY - client.player.getY());
        return droppedDistance >= minDropDist.getValue();
    }

    private double computeEffectiveReequipDelayMs() {
        double baseDelay = reequipDelayMs.getValue();
        if (!randomization.getValue()) {
            return baseDelay;
        }
        double minRand = Math.min(minRandomMs.getValue(), maxRandomMs.getValue());
        double maxRand = Math.max(minRandomMs.getValue(), maxRandomMs.getValue());
        if (maxRand <= 0.0D || Double.compare(minRand, maxRand) == 0) {
            return baseDelay + minRand;
        }
        return baseDelay + ThreadLocalRandom.current().nextDouble(minRand, maxRand);
    }

    /**
     * Legit Elytra Unequip:
     * Swaps equipped Elytra (armor slot 6) with the best Chestplate in inventory, or if no Chestplate
     * is present, quick-moves the Elytra into a free inventory slot and stops fall-flying.
     */
    private boolean equipChestplateOrUnequipElytraLegit(Minecraft client) {
        if (!InventoryUtils.hasElytra()) {
            return true;
        }

        if (clickSim.getValue() && client.options != null) {
            KeyMapping.click(client.options.keyAttack.getDefaultKey());
        }

        int chestSlot = InventoryUtils.findChestArmorSlot();
        if (chestSlot != -1) {
            InventoryUtils.swap(InventoryUtils.wrapSlot(chestSlot), 6);
            InventoryUtils.closeScreen();
        } else {
            // Fallback: unequip Elytra from chest slot (6) into an empty inventory slot
            InventoryUtils.click(6, 0, ClickType.QUICK_MOVE);
            InventoryUtils.closeScreen();
        }

        if (client.player != null && client.player.isFallFlying()) {
            client.player.stopFallFlying();
        }
        swapCooldownTimer.reset();
        return !InventoryUtils.hasElytra();
    }

    private boolean equipElytraAndStartGlideLegit(Minecraft client) {
        if (InventoryUtils.hasElytra()) {
            startFallFlyingIfNeeded(client);
            return true;
        }
        int elytraSlot = InventoryUtils.findElytraSlot();
        if (elytraSlot == -1) {
            return false;
        }
        if (clickSim.getValue() && client.options != null) {
            KeyMapping.click(client.options.keyAttack.getDefaultKey());
        }
        InventoryUtils.swap(InventoryUtils.wrapSlot(elytraSlot), 6);
        InventoryUtils.closeScreen();
        swapCooldownTimer.reset();
        startFallFlyingIfNeeded(client);
        return true;
    }

    private void startFallFlyingIfNeeded(Minecraft client) {
        if (client.player == null || client.player.onGround() || client.player.isFallFlying()) {
            return;
        }
        client.player.startFallFlying();
        if (client.getConnection() != null) {
            client.getConnection().send(new ServerboundPlayerCommandPacket(
                    client.player,
                    ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            ));
        }
    }

    private boolean hasLineOfSight(Minecraft client, LivingEntity target) {
        if (client.player == null || client.level == null || target == null) {
            return false;
        }
        if (client.player.hasLineOfSight(target)) {
            return true;
        }
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        BlockHitResult hit = client.level.clip(new ClipContext(
                eyePos,
                targetCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                client.player
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private LivingEntity findCombatTarget(Minecraft client) {
        double maxSearchRange = Math.max(heightRange.getValue(), macePreHitRange.getValue()) + 5.0D;
        Vec3 eyePos = client.player.getEyePosition(1.0F);

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

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
                if (AntiBot.shouldIgnore(player) || FriendUtils.isFriend(player.getName().getString())) {
                    continue;
                }
            } else if (!(living instanceof Mob) && !(living instanceof Animal)) {
                continue;
            }

            double dist = eyePos.distanceTo(clampToBox(eyePos, living.getBoundingBox()));
            if (dist > maxSearchRange || !hasLineOfSight(client, living)) {
                continue;
            }

            double horizontalDist = Math.hypot(client.player.getX() - living.getX(), client.player.getZ() - living.getZ());
            double score = dist * 0.65D + horizontalDist * 0.35D;
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    private boolean isHoldingOrHasMace(Minecraft client) {
        if (client.player == null) {
            return false;
        }
        if (client.player.getMainHandItem().getItem() == Items.MACE) {
            return true;
        }
        return InventoryUtils.findHotbarItem(Items.MACE) != -1;
    }

    private void updateAirborneTracking(Minecraft client) {
        if (client.player.onGround() || client.player.isInWater() || client.player.isInLava()) {
            airbornePeakY = client.player.getY();
        } else {
            double y = client.player.getY();
            if (Double.isNaN(airbornePeakY) || y > airbornePeakY) {
                airbornePeakY = y;
            }
        }
    }

    private double getEffectiveFallDistance(Minecraft client) {
        if (client.player == null) {
            return 0.0D;
        }
        double vanillaFall = client.player.fallDistance;
        double trackedFall = Double.isNaN(airbornePeakY) ? 0.0D : Math.max(0.0D, airbornePeakY - client.player.getY());
        return Math.max(vanillaFall, trackedFall);
    }

    private Vec3 clampToBox(Vec3 point, AABB box) {
        return new Vec3(
                Mth.clamp(point.x, box.minX, box.maxX),
                Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ)
        );
    }

    private void resetInternalState() {
        pendingSwapAction = SwapAction.NONE;
        movementLockTicks = 0;
        droppedForSmash = false;
        maceAttackConfirmed = false;
        pearlEquipPending = false;
        scheduledReequipDelayMs = reequipDelayMs.getValue();
        dropStartY = Double.NaN;
        airbornePeakY = Double.NaN;
        currentTarget = null;
    }

    // Preserved public methods for external compatibility (e.g., AuraModule)
    public float getRange() {
        return heightRange.getFloat();
    }

    public float getForward() {
        return macePreHitRange.getFloat();
    }
}