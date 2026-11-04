package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.PlayerTravelEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.MaceTarget;
import releon.ru.api.module.impl.combat.MaceTarget.ActionManager;
import releon.ru.api.module.impl.combat.MaceTarget.ActionManager.ActionPriority;
import releon.ru.api.module.impl.combat.MaceTarget.CombatStateManager;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * High-Speed Split-Priority AutoElytra Controller with Dynamic 200ms Re-Equip Delay:
 * - Priority 1 (Highest): AUTO_ELYTRA_UNEQUIP (Pre-emptive 2-Tick Pipeline & Zero-Delay Handoff)
 *     Trigger: Target distance < 4.5m and v_y < -0.1 while wearing an Elytra.
 *     Tick 0: Physical hotbar selector immediately changes to the Chestplate slot.
 *     Tick 1: Right-clicks to swap the Chestplate onto the body, restores the weapon slot, and immediately
 *             hands control to AutoMace with zero dead frames.
 * - Priority 3 (Lowest): AUTO_ELYTRA_EQUIP (Dynamic Re-Equip After 200ms Default)
 *     Trigger: Automatically re-equips and makes the player wear the Elytra once the dynamically adjustable
 *              "Reequip Delay (ms)" (default 200ms) has elapsed after unequipping or landing a Mace strike.
 *     Action: Executes a legit 2-tick hotbar right-click swap (or 3-tick inventory relay if in main inventory).
 */
public final class ElytraTarget extends Module {
    private static ElytraTarget instance;
    public static boolean shouldElytraTarget;

    // 1. Split-Priority Mutex Thresholds
    private static final double PRIORITY_1_UNEQUIP_TARGET_DIST = 4.5D;
    private static final double PRIORITY_1_UNEQUIP_MAX_VEL_Y = -0.1D;
    private static final double PRIORITY_3_EQUIP_WIND_CHARGE_VEL_Y = 0.6D;

    private static final float MIN_LEGAL_FALL_DISTANCE = 0.25F;
    private static final double MAX_LEGAL_GLIDE_VELOCITY_Y = -0.08D;

    private static final List<Item> CHESTPLATES = List.of(
            Items.NETHERITE_CHESTPLATE,
            Items.DIAMOND_CHESTPLATE,
            Items.CHAINMAIL_CHESTPLATE,
            Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE,
            Items.LEATHER_CHESTPLATE
    );

    private final NumberSetting reequipDelayMs = register(new NumberSetting(
            "Reequip Delay (ms)", "Dynamic delay in milliseconds before re-equipping and wearing the elytra (default 200ms).", 200.0D, 0.0D, 1000.0D, 1.0D));
    private final BooleanSetting autoReequip = register(new BooleanSetting(
            "Auto Reequip", "Automatically wear the elytra after Reequip Delay (ms) elapses.", true));
    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization", "Enable randomized delay variance for elytra re-equip cooldowns.", false));
    private final BooleanSetting automaceSync = register(new BooleanSetting(
            "Automace Sync", "Synchronize elytra unequip/equip priorities with AutoMace.", true));
    private final BooleanSetting disableOnGround = register(new BooleanSetting(
            "Disable on Ground", "Swap to chestplate and stay in chestplate while touching the ground.", false));
    private final NumberSetting macePreHitRange = register(new NumberSetting(
            "Mace Pre-Hit Range", "Pre-impact distance threshold (< 4.5m default) to unequip elytra before a mace hit.", 2.5D, 0.5D, 10.0D, 0.1D));
    private final NumberSetting minDropDist = register(new NumberSetting(
            "Min Drop Dist", "Minimum drop distance required for chestplate drop and smash preparation.", 1.5D, 0.0D, 20.0D, 0.1D));

    public enum CombatFlightState {
        STATE_1_FLIGHT,
        STATE_2_SMASH_WINDOW,
        STATE_3_RECOVERY
    }

    private final StopWatch swapCommitmentTimer = new StopWatch();
    private final StopWatch glideWindowTimer = new StopWatch();

    private CombatFlightState flightState = CombatFlightState.STATE_1_FLIGHT;
    private int lastElytraEquipCompletedTick = -1;
    private int lastChestplateUnequipTick = -10;
    private boolean jumpPulseToggle = false;
    private boolean forceCancelFlightInput = false;

    // Event-driven / Lazy Armor Slot Cache (Updated only when inventory changes and hand is idle)
    private int cachedElytraHotbarSlot = -1;
    private int cachedChestplateHotbarSlot = -1;
    private int lastArmorInventoryHash = 0;

    private long strikeTime = 0L;
    private long lastUnequipCompletedTimeMs = 0L;
    private long lastChestplateObservedTimeMs = 0L;
    private double rolledRandomOffsetMs = 0.0D;
    private boolean migratedLegacy217Default = false;
    private boolean postStrikeReequipPending = false;
    private long lastWindChargeOrBlastTimeMs = 0L;
    private boolean wasWindChargeOnCooldown = false;
    private boolean windChargeAscentDetected = false;
    private boolean equipAfterWindChargeApexPending = false;
    private double prevTickVelY = 0.0D;
    private double airbornePeakY = Double.NaN;
    private int lastProcessedMovementTick = -1;
    private int lastSeenPlayerId = -1;
    private int lastSeenTickCount = -1;
    private LivingEntity cachedTarget = null;

    public ElytraTarget() {
        super("AutoElytra", "High-speed 2-tick unequip & dynamic 200ms re-equip Elytra controller.", ModuleCategory.MOVEMENT);
        instance = this;
    }

    public static ElytraTarget getInstance() {
        return instance;
    }

    public static boolean isTargetingActive() {
        return instance != null && instance.isEnabled() && shouldElytraTarget;
    }

    public double getDynamicReequipDelayMs() {
        return Math.max(0.0D, reequipDelayMs.getValue() + (randomization.getValue() ? rolledRandomOffsetMs : 0.0D));
    }

    public boolean isSwapLocked() {
        return ActionManager.getInstance().isSwapInProgress() || CombatStateManager.isElytraAscentLocked();
    }

    /**
     * Returns true once the Chestplate is equipped OR on the exact tick the 2-tick unequip pipeline finished,
     * enabling a zero-delay handoff to AutoMace without waiting for extra confirmation frames.
     */
    public boolean isReadyForZeroDelayMaceHandoff() {
        return mc.player != null && !mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    @Override
    protected void onEnable() {
        shouldElytraTarget = true;
        migrateLegacyDefaultIfNeeded();
        resetInternalState();
        refreshArmorSlotCacheOnInventoryChange();
    }

    @Override
    protected void onDisable() {
        shouldElytraTarget = false;
        ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
        ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
        resetInternalState();
    }

    private void migrateLegacyDefaultIfNeeded() {
        if (!migratedLegacy217Default) {
            migratedLegacy217Default = true;
            if (Math.abs(reequipDelayMs.getValue() - 217.0D) < 0.01D) {
                reequipDelayMs.setValue(200.0D);
            }
        }
    }

    /**
     * Pre-emptive Slot Pre-selection (Tick 0) & Optimized 2-Tick Unequip Pipeline:
     * Called the instant AutoMace calculates target distance < 4.5m and v_y < -0.1.
     * Immediately selects the Chestplate slot on Tick 0 and swaps + restores on Tick 1.
     */
    public boolean ensureUnequippedBeforeMaceAttack(LivingEntity target) {
        if (!isEnabled() || mc.player == null || mc.screen != null) {
            return false;
        }
        if (target != null && isWhitelistedFriend(target)) {
            return false;
        }
        if (isReadyForZeroDelayMaceHandoff()) {
            forceCancelFlightInput = false;
            return true;
        }
        if (ActionManager.getInstance().isSwapInProgress()) {
            return false;
        }
        if (requestFast2TickChestplateUnequip()) {
            flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
        }
        return false;
    }

    public void enterState3RecoveryLock() {
        flightState = CombatFlightState.STATE_3_RECOVERY;
        forceCancelFlightInput = false;
        if (mc.player != null) {
            airbornePeakY = mc.player.getY();
        }
        long now = System.currentTimeMillis();
        strikeTime = now;
        lastUnequipCompletedTimeMs = now;
        lastChestplateObservedTimeMs = now;
        rolledRandomOffsetMs = computeSingleRandomOffsetMs();
        postStrikeReequipPending = true;
        swapCommitmentTimer.reset();
    }

    public void notifyMaceAttackLanded() {
        enterState3RecoveryLock();
    }

    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        if (!isEnabled() || mc.player == null || mc.level == null) {
            return;
        }
        lastProcessedMovementTick = mc.player.tickCount;
        runAutoElytraCycle();
    }

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        if (!isEnabled()) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
            ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
            resetInternalState();
            return;
        }
        if (lastProcessedMovementTick != mc.player.tickCount) {
            runAutoElytraCycle();
        }
    }

    private void runAutoElytraCycle() {
        migrateLegacyDefaultIfNeeded();

        if (mc.player != null) {
            int pid = mc.player.getId();
            int t = mc.player.tickCount;
            if (pid != lastSeenPlayerId || t < lastSeenTickCount) {
                lastSeenPlayerId = pid;
                lastSeenTickCount = t;
                lastChestplateUnequipTick = -100;
                lastElytraEquipCompletedTick = -100;
                lastProcessedMovementTick = -100;
                lastArmorInventoryHash = 0;
                forceCancelFlightInput = false;
            } else {
                lastSeenTickCount = t;
            }
        }
        ActionManager actionManager = ActionManager.getInstance();
        actionManager.ensureTickSynchronized(mc);
        updateWindChargeAndPhysicsTracking();
        refreshArmorSlotCacheOnInventoryChange();

        if (mc.screen != null) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean wearingElytra = InventoryUtils.isElytraEquipped();

        if (!wearingElytra) {
            forceCancelFlightInput = false;
            actionManager.releaseLock(ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
            if (lastChestplateObservedTimeMs == 0L) {
                lastChestplateObservedTimeMs = now;
                rolledRandomOffsetMs = computeSingleRandomOffsetMs();
            }
        } else {
            lastChestplateObservedTimeMs = 0L;
            postStrikeReequipPending = false;
        }

        if (actionManager.isSwapInProgress()) {
            return;
        }

        MaceTarget autoMace = MaceTarget.getInstance();
        boolean maceActive = autoMace != null && autoMace.isEnabled();
        LivingEntity maceTarget = maceActive ? autoMace.getActiveTarget() : null;

        if (maceTarget != null && maceTarget.isAlive() && !isWhitelistedFriend(maceTarget)) {
            cachedTarget = maceTarget;
        } else {
            double searchRange = macePreHitRange.getValue() + 6.0D;
            cachedTarget = actionManager.getNearestVisibleTarget(mc, searchRange, true);
        }

        double velY = mc.player.getDeltaMovement().y;

        // =========================================================================================
        // PRIORITY 1 (HIGHEST): High-Speed 2-Tick AutoElytra_UNEQUIP
        // Trigger: Target distance < 4.5m and v_y < -0.1 while wearing Elytra.
        // Tick 0: Immediately selects Chestplate slot.
        // Tick 1: Swaps Chestplate onto body, restores weapon slot, and hands control to AutoMace.
        // =========================================================================================
        boolean inPriority1SmashTrajectory = !mc.player.onGround()
                && !CombatStateManager.isInPostReequipGraceWindow()
                && (CombatStateManager.isRequiresUnequip()
                || (velY < PRIORITY_1_UNEQUIP_MAX_VEL_Y
                && cachedTarget != null
                && shouldTriggerPriority1Unequip(cachedTarget)));

        if (wearingElytra && inPriority1SmashTrajectory) {
            if (requestFast2TickChestplateUnequip()) {
                flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
                return;
            }
        }

        // Ground Handling: Only stay in Chestplate on ground when "Disable on Ground" is explicitly enabled.
        // When "Disable on Ground" is false (default), NEVER cancel postStrikeReequipPending so the player
        // automatically wears the Elytra once the 200ms Reequip Delay elapses!
        if (mc.player.onGround()) {
            forceCancelFlightInput = false;
            equipAfterWindChargeApexPending = false;
            if (disableOnGround.getValue()) {
                if (wearingElytra
                        && canStartPriority3Equip()
                        && (mc.player.tickCount < lastChestplateUnequipTick || (mc.player.tickCount - lastChestplateUnequipTick) > 6)) {
                    requestFast2TickChestplateUnequip();
                }
                flightState = CombatFlightState.STATE_1_FLIGHT;
                return;
            }
        }

        // =========================================================================================
        // PRIORITY 3 (LOWEST): Dynamic Re-Equip After 200ms ("Reequip Delay (ms)")
        // Reads reequipDelayMs.getValue() dynamically every tick. Once 200ms (or user-selected ms)
        // has elapsed since unequipping or attacking with AutoMace, automatically equips the Elytra
        // via the legit 2-tick hotbar right-click swap!
        // =========================================================================================
        boolean autoMaceCompletelyIdle = !maceActive || !autoMace.isMidSlamSequence();
        long lastMaceStrikeMs = CombatStateManager.getLastMaceStrikeTimeMs();
        long effectiveUnequipMs = Math.max(lastUnequipCompletedTimeMs, CombatStateManager.getLastUnequipCompletedTimeMs());
        long referenceTimeMs = Math.max(
                Math.max(strikeTime, lastMaceStrikeMs),
                Math.max(effectiveUnequipMs, lastChestplateObservedTimeMs)
        );
        double guiReequipDelay = getDynamicReequipDelayMs();
        boolean reequipDelayElapsed = referenceTimeMs == 0L || (now - referenceTimeMs) >= guiReequipDelay;

        // Only pause re-equip before the 200ms Reequip Delay has elapsed; once Reequip Delay (ms) elapses, re-equip immediately!
        boolean waitingForImminentMaceStrike = false;

        if (autoMaceCompletelyIdle
                && reequipDelayElapsed
                && !waitingForImminentMaceStrike
                && !actionManager.isBlockedByHigherPriority(ActionPriority.AUTO_ELYTRA_EQUIP)
                && canStartPriority3Equip()) {
            if (!wearingElytra && (postStrikeReequipPending || equipAfterWindChargeApexPending || autoReequip.getValue())) {
                if (requestPriority3ElytraEquip()) {
                    postStrikeReequipPending = false;
                    equipAfterWindChargeApexPending = false;
                    flightState = CombatFlightState.STATE_1_FLIGHT;
                }
            } else if (wearingElytra) {
                postStrikeReequipPending = false;
                equipAfterWindChargeApexPending = false;
                flightState = CombatFlightState.STATE_1_FLIGHT;
            }
        }
    }

    /**
     * Event-Driven / Change-Driven Armor Slot Cache with Active Hand Protection:
     * Never runs while the player is using an item, swinging, or while ActionManager is locked.
     * Updates cachedElytraHotbarSlot and cachedChestplateHotbarSlot ONLY when hotbar contents change.
     */
    private void refreshArmorSlotCacheOnInventoryChange() {
        if (mc.player == null
                || mc.player.isUsingItem()
                || mc.player.swinging
                || mc.player.attackAnim > 0.0F
                || ActionManager.getInstance().isLocked()) {
            return;
        }

        int currentHash = 1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            currentHash = 31 * currentHash + (s.isEmpty() ? 0 : System.identityHashCode(s.getItem()));
        }
        if (currentHash == lastArmorInventoryHash) {
            return;
        }
        lastArmorInventoryHash = currentHash;

        cachedElytraHotbarSlot = -1;
        cachedChestplateHotbarSlot = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.ELYTRA) {
                cachedElytraHotbarSlot = i;
                break;
            }
        }

        outer:
        for (Item chestItem : CHESTPLATES) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getItem() == chestItem) {
                    cachedChestplateHotbarSlot = i;
                    break outer;
                }
            }
        }
    }

    /**
     * Optimized 2-Tick High-Speed Unequip Pipeline:
     * - Tick 0: Immediately selects the Chestplate slot on the physical hotbar.
     * - Tick 1: Right-clicks to swap the Chestplate onto the body, immediately restores the previous weapon
     *           slot (or pre-selects AutoMace's cachedMaceSlot for zero-delay handoff!), and releases the lock.
     */
    private boolean requestFast2TickChestplateUnequip() {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (mc.player.isUsingItem()) {
            return false;
        }
        if (mc.player.tickCount >= lastChestplateUnequipTick && (mc.player.tickCount - lastChestplateUnequipTick) <= 2) {
            return false;
        }

        int chestplateSlot = getValidChestplateSlot();
        if (chestplateSlot == -1) {
            return false;
        }

        forceCancelFlightInput = true;
        int currentSelected = mc.player.getInventory().getSelectedSlot();

        MaceTarget autoMace = MaceTarget.getInstance();
        int handoffRestoreSlot = currentSelected;
        if (autoMace != null && autoMace.isEnabled() && !mc.player.onGround()) {
            int maceSlot = autoMace.getPreferredMaceHotbarSlot();
            if (maceSlot >= 0 && maceSlot <= 8) {
                handoffRestoreSlot = maceSlot;
            }
        }

        boolean accepted = ActionManager.getInstance().submitFastTwoTickUnequip(
                mc,
                chestplateSlot,
                handoffRestoreSlot,
                () -> {
                    if (mc.player != null) {
                        lastChestplateUnequipTick = mc.player.tickCount;
                        CombatStateManager.markUnequipCompleted(mc.player.tickCount);
                    } else {
                        CombatStateManager.setRequiresUnequip(false);
                    }
                    long completedNow = System.currentTimeMillis();
                    lastUnequipCompletedTimeMs = completedNow;
                    lastChestplateObservedTimeMs = completedNow;
                    strikeTime = completedNow;
                    rolledRandomOffsetMs = computeSingleRandomOffsetMs();
                    postStrikeReequipPending = true;
                    swapCommitmentTimer.reset();
                }
        );

        if (accepted) {
            swapCommitmentTimer.reset();
        }
        return accepted;
    }

    /**
     * Legit 2-Tick Hotbar Re-Equip Pipeline (with 3-Tick Inventory Relay fallback if Elytra is in 9..35):
     * - Tick 0: Selects the Elytra slot on the hotbar.
     * - Tick 1: Simulates right-click (KeyMapping.click + useItem + swing) to wear the Elytra and restores weapon slot.
     */
    private boolean requestPriority3ElytraEquip() {
        if (mc.player == null || mc.screen != null || isManualItemUseActive()) {
            return false;
        }
        int elytraSlot = getValidElytraSlot();
        int originalSlot = mc.player.getInventory().getSelectedSlot();
        boolean accepted;

        if (elytraSlot >= 0 && elytraSlot <= 8) {
            accepted = ActionManager.getInstance().submitFastTwoTickArmorSwap(
                    mc,
                    ActionPriority.AUTO_ELYTRA_EQUIP,
                    "AutoElytra_EQUIP",
                    elytraSlot,
                    originalSlot,
                    () -> {
                        if (mc.player != null) {
                            lastElytraEquipCompletedTick = mc.player.tickCount;
                            airbornePeakY = mc.player.getY();
                        }
                        CombatStateManager.markElytraReequipped();
                        lastChestplateObservedTimeMs = 0L;
                        postStrikeReequipPending = false;
                        glideWindowTimer.reset();
                    }
            );
        } else {
            accepted = ActionManager.executeLegitArmorSwapFromSlot(-1);
        }

        if (accepted) {
            forceCancelFlightInput = false;
            swapCommitmentTimer.reset();
            lastElytraEquipCompletedTick = mc.player.tickCount + 1;
            glideWindowTimer.reset();
            jumpPulseToggle = false;
        }
        return accepted;
    }

    private int getValidElytraSlot() {
        if (mc.player == null) {
            return -1;
        }
        if (cachedElytraHotbarSlot >= 0 && cachedElytraHotbarSlot <= 8) {
            ItemStack s = mc.player.getInventory().getItem(cachedElytraHotbarSlot);
            if (!s.isEmpty() && s.getItem() == Items.ELYTRA) {
                return cachedElytraHotbarSlot;
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.ELYTRA) {
                cachedElytraHotbarSlot = i;
                return i;
            }
        }
        return -1;
    }

    private int getValidChestplateSlot() {
        if (mc.player == null) {
            return -1;
        }
        if (cachedChestplateHotbarSlot >= 0 && cachedChestplateHotbarSlot <= 8) {
            ItemStack s = mc.player.getInventory().getItem(cachedChestplateHotbarSlot);
            if (!s.isEmpty() && CHESTPLATES.contains(s.getItem())) {
                return cachedChestplateHotbarSlot;
            }
        }
        for (Item chestItem : CHESTPLATES) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getItem() == chestItem) {
                    cachedChestplateHotbarSlot = i;
                    return i;
                }
            }
        }
        return -1;
    }

    private void updateWindChargeAndPhysicsTracking() {
        if (mc.player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean windCooling = mc.player.getCooldowns().isOnCooldown(Items.WIND_CHARGE.getDefaultInstance());
        if (windCooling && !wasWindChargeOnCooldown) {
            lastWindChargeOrBlastTimeMs = now;
        }
        wasWindChargeOnCooldown = windCooling;

        double currentY = mc.player.getY();
        double velY = mc.player.getDeltaMovement().y;

        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()) {
            airbornePeakY = currentY;
            windChargeAscentDetected = false;
            equipAfterWindChargeApexPending = false;
            jumpPulseToggle = false;
        } else {
            if (Double.isNaN(airbornePeakY) || currentY > airbornePeakY) {
                airbornePeakY = currentY;
            }
            if (velY > PRIORITY_3_EQUIP_WIND_CHARGE_VEL_Y) {
                if (!windChargeAscentDetected || (velY - prevTickVelY) > 0.25D) {
                    lastWindChargeOrBlastTimeMs = now;
                }
                windChargeAscentDetected = true;
                equipAfterWindChargeApexPending = true;
            }
        }

        prevTickVelY = velY;
    }

    private boolean isLegalVanillaFallFlyingState() {
        if (mc.player == null) {
            return false;
        }
        if (mc.player.onGround() || mc.player.isFallFlying() || mc.player.isInWater() || mc.player.isInLava()) {
            return false;
        }
        if (mc.player.hasEffect(MobEffects.LEVITATION) || mc.player.getAbilities().flying) {
            return false;
        }
        if (mc.player.hurtTime > 0) {
            return false;
        }
        if (mc.player.getDeltaMovement().y > MAX_LEGAL_GLIDE_VELOCITY_Y) {
            return false;
        }
        if (mc.player.fallDistance < MIN_LEGAL_FALL_DISTANCE) {
            return false;
        }
        double guiReequipDelay = getDynamicReequipDelayMs();
        long effectiveStrikeTime = Math.max(strikeTime, CombatStateManager.getLastMaceStrikeTimeMs());
        if (effectiveStrikeTime > 0L && (System.currentTimeMillis() - effectiveStrikeTime) < guiReequipDelay) {
            return false;
        }
        return (System.currentTimeMillis() - lastWindChargeOrBlastTimeMs) >= guiReequipDelay;
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        if (!isEnabled() || mc.player == null || mc.screen != null) {
            jumpPulseToggle = false;
            return;
        }

        if (forceCancelFlightInput) {
            event.setJumping(false);
            jumpPulseToggle = false;
            return;
        }

        if (flightState == CombatFlightState.STATE_1_FLIGHT
                && !ActionManager.getInstance().isSwapInProgress()
                && InventoryUtils.isElytraEquipped()
                && InventoryUtils.isElytraUsable()
                && (mc.player.tickCount < lastElytraEquipCompletedTick - 20 || mc.player.tickCount > lastElytraEquipCompletedTick)
                && isLegalVanillaFallFlyingState()
                && (!glideWindowTimer.finished(450.0D) || windChargeAscentDetected)) {
            jumpPulseToggle = !jumpPulseToggle;
            event.setJumping(jumpPulseToggle);
            if (mc.player.isFallFlying()) {
                windChargeAscentDetected = false;
            }
        } else {
            jumpPulseToggle = false;
        }
    }

    private boolean canStartPriority3Equip() {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (ActionManager.getInstance().isBlockedByHigherPriority(ActionPriority.AUTO_ELYTRA_EQUIP)
                || ActionManager.getInstance().isSwapInProgress()) {
            return false;
        }
        if (isManualItemUseActive()) {
            return false;
        }
        double guiReequipDelay = getDynamicReequipDelayMs();
        long referenceTimeMs = Math.max(
                Math.max(strikeTime, CombatStateManager.getLastMaceStrikeTimeMs()),
                Math.max(lastUnequipCompletedTimeMs, lastChestplateObservedTimeMs)
        );
        if (referenceTimeMs > 0L && (System.currentTimeMillis() - referenceTimeMs) < guiReequipDelay) {
            return false;
        }
        return swapCommitmentTimer.finished(guiReequipDelay);
    }

    private boolean isManualItemUseActive() {
        if (mc.player == null || !mc.player.isUsingItem()) {
            return false;
        }
        if (mc.player.getTicksUsingItem() <= 2) {
            return false;
        }
        ItemStack activeUse = mc.player.getUseItem();
        return activeUse.has(DataComponents.FOOD)
                || activeUse.getItem() == Items.GOLDEN_APPLE
                || activeUse.getItem() == Items.ENCHANTED_GOLDEN_APPLE;
    }

    public static boolean isWhitelistedFriend(Entity entity) {
        return ActionManager.isWhitelistedFriend(Minecraft.getInstance(), entity);
    }

    private boolean shouldTriggerPriority1Unequip(LivingEntity target) {
        if (mc.player == null || target == null || isWhitelistedFriend(target)) {
            return false;
        }
        if (getValidChestplateSlot() == -1 || !ActionManager.hasLineOfSight(mc, target)) {
            return false;
        }

        Vec3 playerVel = mc.player.getDeltaMovement();
        if (playerVel.y >= PRIORITY_1_UNEQUIP_MAX_VEL_Y) {
            return false;
        }

        double eyeX = mc.player.getX();
        double eyeY = mc.player.getEyeY();
        double eyeZ = mc.player.getZ();
        AABB box = target.getBoundingBox();

        double dx = eyeX - Mth.clamp(eyeX, box.minX, box.maxX);
        double dy = eyeY - Mth.clamp(eyeY, box.minY, box.maxY);
        double dz = eyeZ - Mth.clamp(eyeZ, box.minZ, box.maxZ);
        double eyeToBoxDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double verticalAltitudeOverTarget = mc.player.getY() - target.getY();
        boolean hasSmashHeight = getEffectiveFallDistance() >= minDropDist.getValue()
                || verticalAltitudeOverTarget >= minDropDist.getValue();
        if (!hasSmashHeight) {
            return false;
        }

        double unequipTriggerRadius = Math.max(PRIORITY_1_UNEQUIP_TARGET_DIST, macePreHitRange.getValue());
        return eyeToBoxDist < unequipTriggerRadius;
    }

    private double computeSingleRandomOffsetMs() {
        if (!randomization.getValue()) {
            return 0.0D;
        }
        return ThreadLocalRandom.current().nextDouble(5.0D, 35.0D);
    }

    private double getEffectiveFallDistance() {
        if (mc.player == null) {
            return 0.0D;
        }
        double vanillaFall = mc.player.fallDistance;
        double trackedFall = Double.isNaN(airbornePeakY) ? 0.0D : Math.max(0.0D, airbornePeakY - mc.player.getY());
        return Math.max(vanillaFall, trackedFall);
    }

    private void resetInternalState() {
        flightState = CombatFlightState.STATE_1_FLIGHT;
        lastElytraEquipCompletedTick = -1;
        lastChestplateUnequipTick = -10;
        jumpPulseToggle = false;
        forceCancelFlightInput = false;
        cachedElytraHotbarSlot = -1;
        cachedChestplateHotbarSlot = -1;
        lastArmorInventoryHash = 0;
        strikeTime = 0L;
        lastUnequipCompletedTimeMs = 0L;
        lastChestplateObservedTimeMs = 0L;
        rolledRandomOffsetMs = 0.0D;
        postStrikeReequipPending = false;
        lastWindChargeOrBlastTimeMs = 0L;
        wasWindChargeOnCooldown = false;
        windChargeAscentDetected = false;
        equipAfterWindChargeApexPending = false;
        prevTickVelY = 0.0D;
        airbornePeakY = Double.NaN;
        lastProcessedMovementTick = -1;
        cachedTarget = null;
    }

    public float getRange() {
        return macePreHitRange.getFloat() + 4.0F;
    }

    public float getForward() {
        return macePreHitRange.getFloat();
    }
}
