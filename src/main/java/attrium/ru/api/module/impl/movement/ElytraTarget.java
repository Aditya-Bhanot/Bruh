package attrium.ru.api.module.impl.movement;


import attrium.ru.api.module.impl.misc.ElytraHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.tags.ItemTags;
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
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.InputEvent;
import attrium.ru.api.events.impl.PlayerTravelEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.module.impl.combat.MaceTarget;
import attrium.ru.api.module.impl.combat.MaceTarget.ActionManager;
import attrium.ru.api.module.impl.combat.MaceTarget.ActionManager.ActionPriority;
import attrium.ru.api.module.impl.combat.MaceTarget.CombatStateManager;
import attrium.ru.api.module.impl.combat.aura.util.StopWatch;
import attrium.ru.api.settings.Setting;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.utils.inventory.lookup.InventoryUtils;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GrimAC-Safe Fast Legit Hotbar AutoElytra Controller (Mojang 1.21.11 Official Mappings):
 * - Default Settings:
 *   * Reequip Delay (ms): 200.0ms
 *   * Height Diff: 7.0 blocks
 *   * Height Range: 6.0 blocks
 *   * Mace Pre-Hit Range: 2.7 blocks
 *   * Min Drop Dist: 1.5 blocks
 * - Exact Trigger Logic (zero hidden overrides):
 *   1. Mace Pre-Hit Range (2.7m default): Unequips Elytra into Chestplate when within <= Mace Pre-Hit Range
 *      of the target (while gliding or falling >= Min Drop Dist [1.5m]).
 *   2. Height Drop (Height Diff 7.0m & Height Range 6.0m default): Unequips Elytra into Chestplate when
 *      vertical height above target >= Height Diff (7.0m), horizontal/3D distance <= Height Range (6.0m),
 *      and drop distance >= Min Drop Dist (1.5m) (or gliding).
 *   3. Reequip Delay (200ms default): Re-equips the Elytra dynamically after Reequip Delay (ms).
 */
public final class ElytraTarget extends Module {
    private static volatile long lastAutoArmorSwapMs = 0L;
    private static ElytraTarget instance;
    public static boolean shouldElytraTarget;

    private static final double PRIORITY_3_EQUIP_WIND_CHARGE_VEL_Y = 0.6D;
    private static final long WIND_CHARGE_SETTLE_DELAY_MS = 200L;
    private static final float MIN_LEGAL_FALL_DISTANCE = 0.25F;
    private static final double MAX_LEGAL_GLIDE_VELOCITY_Y = -0.08D;
    private static final int HOTBAR_SCAN_INTERVAL_TICKS = 4;

    private static final List<Item> CHESTPLATES = List.of(
            Items.NETHERITE_CHESTPLATE,
            Items.DIAMOND_CHESTPLATE,
            Items.CHAINMAIL_CHESTPLATE,
            Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE,
            Items.LEATHER_CHESTPLATE
    );

    private final BooleanSetting bindToggleKey = register(new BooleanSetting(
            "Toggle Bind: [NONE]",
            "Click this, then press any key or mouse button to bind AutoElytra ON/OFF toggle (ESC/BACKSPACE clears).",
            false));
    private final BooleanSetting dynamicInterceptBeta = register(new BooleanSetting(
            "Dynamic Intercept (Beta)",
            "Calculates exact 3D unequip distance from relative 3D closing speed, dive angle, and free-fall gravity (hides static distance sliders).",
            false));
    private final BooleanSetting flightCancelReequip = register(new BooleanSetting(
            "Flight Cancel Re-Equip",
            "Re-equips Elytra after cancelling glide so you stay ready to fly immediately after impact.",
            true));
    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization", "Enable randomized delay variance for elytra re-equip cooldowns.", false));
    private final NumberSetting minRandomMs = register(new NumberSetting(
            "Min Random (ms)", "Minimum random delay in milliseconds.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting maxRandomMs = register(new NumberSetting(
            "Max Random (ms)", "Maximum random delay in milliseconds.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting reequipDelayMs = register(new NumberSetting(
            "Reequip Delay (ms)", "Dynamic delay in milliseconds before re-equipping the elytra (250ms default).", 250.0D, 0.0D, 1000.0D, 1.0D));
    private final BooleanSetting automaceSync = register(new BooleanSetting(
            "Automace Sync", "Synchronize elytra unequip/equip priorities with AutoMace via ActionManager.", true));
    private final BooleanSetting disableOnGround = register(new BooleanSetting(
            "Disable on Ground", "Swap to chestplate and reset flight state when touching the ground.", true));
    private final BooleanSetting clickSim = register(new BooleanSetting(
            "ClickSim", "Simulate client click input during legit hotbar armor swaps.", true));
    private final NumberSetting heightDiff = register(new NumberSetting(
            "Height Diff", "Required vertical height difference above target to initiate chestplate drop (7.0 default).", 7.0D, 0.0D, 50.0D, 0.1D));
    private final NumberSetting heightRange = register(new NumberSetting(
            "Height Range", "Maximum target distance within which height drop triggers (6.0 default).", 6.0D, 1.0D, 30.0D, 0.1D));
    private final NumberSetting macePreHitRange = register(new NumberSetting(
            "Mace Pre-Hit Range", "Pre-impact distance threshold to unequip elytra before a mace hit (2.7 default).", 5.2D, 0.5D, 12.0D, 0.1D));
    private final NumberSetting minDropDist = register(new NumberSetting(
            "Min Drop Dist", "Minimum drop distance required for chestplate drop and smash preparation (1.5 default).", 1.5D, 0.0D, 20.0D, 0.1D));

    private final NumberSetting savedToggleKeyCode = register(new NumberSetting(
            "_aeToggleKeyCode",
            "Internal saved GLFW keycode for AutoElytra Toggle Bind.",
            -1.0D,
            -100.0D,
            400.0D,
            1.0D));

    public enum CombatFlightState {
        STATE_1_FLIGHT,
        STATE_2_SMASH_WINDOW,
        STATE_3_RECOVERY
    }

    private final StopWatch swapCommitmentTimer = new StopWatch();
    private final StopWatch glideWindowTimer = new StopWatch();

    private CombatFlightState flightState = CombatFlightState.STATE_1_FLIGHT;
    private int lastElytraEquipCompletedTick = -100;
    private long lastElytraEquippedTimeMs = 0L;
    private boolean wasWearingElytraLastTick = false;
    private int nextGlidePulseAllowedTick = 0;
    private boolean jumpPulseToggle = false;
    private boolean forceCancelFlightInput = false;
    private boolean migratedLegacyDefaultsOnce = false;

    private long lastWindChargeOrBlastTimeMs = 0L;
    private boolean wasWindChargeOnCooldown = false;
    private boolean windChargeAscentDetected = false;
    private boolean equipAfterWindChargeApexPending = false;
    private double prevTickVelY = 0.0D;
    private double airbornePeakY = Double.NaN;
    private int lastProcessedMovementTick = -100;
    private int lastObservedPlayerId = -1;
    private LivingEntity cachedTarget = null;

    private boolean betaFlightCancelSuppressGlide = false;
    private long betaFlightCancelUntilMs = 0L;
    private boolean betaPendingReequip = false;
    private int betaReequipAfterTick = -100;

    private static final long AUTO_GLIDE_IDLE_EQUIP_MS = 3500L;
    private static final long ELYTRA_HELPER_POST_REEQUIP_GUARD_MS = 500L;
    private boolean forcedGlideJumpDown = false;
    private boolean postHitReequipPending = false;
    private long postHitReequipAtMs = 0L;
    private long postHitReequipExpireMs = 0L;
    private long postHitReequipNextAttemptMs = 0L;
    private int postHitReequipAttempts = 0;
    private long groundedSinceMs = 0L;
    private long elytraEquippedNotGlidingSinceMs = 0L;
    private int trackedEnemyUpdatedTick = -100;
    private int trackedEnemyId = -1;
    private double trackedEnemyVx = 0.0D;
    private double trackedEnemyVy = 0.0D;
    private double trackedEnemyVz = 0.0D;
    private double lastEnemyX = Double.NaN;
    private double lastEnemyY = Double.NaN;
    private double lastEnemyZ = Double.NaN;

    private int cachedChestplateSlot = -1;
    private int cachedElytraSlot = -1;
    private int lastHotbarScanTick = -100;

    private boolean wasTogglePressed = false;
    private boolean waitingForToggleMouseRelease = false;
    private boolean wasListeningToggle = false;
    private int lastDisplayedToggleKey = Integer.MIN_VALUE;

    public ElytraTarget() {
        super("AutoElytra", "Deploys and manages Elytra flight automatically in midair", ModuleCategory.MOVEMENT);
        instance = this;
        savedToggleKeyCode.visibleWhen(() -> false);
        flightCancelReequip.visibleWhen(dynamicInterceptBeta::getValue);
        heightDiff.visibleWhen(() -> !dynamicInterceptBeta.getValue());
        heightRange.visibleWhen(() -> !dynamicInterceptBeta.getValue());
        macePreHitRange.visibleWhen(() -> !dynamicInterceptBeta.getValue());
        minDropDist.visibleWhen(() -> !dynamicInterceptBeta.getValue());
        bindToggleKey.visibleWhen(() -> {
            pollToggleBindCapture();
            return true;
        });
        refreshToggleBindLabel();

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            migrateLegacyConfigDefaultsIfNeeded();
            pollToggleBindCapture();
            pollModuleToggleBind(client);
            if (isEnabled() && client != null && client.player != null && client.level != null) {
                ActionManager.getInstance().onPreTickRender(client);
                int currentTick = ActionManager.getInstance().getClientTick();
                if (lastProcessedMovementTick != currentTick) {
                    lastProcessedMovementTick = currentTick;
                    runAutoElytraCycle();
                }
            }
        });
    }

    /**
     * Ensures that if modules.attrium previously stored old defaults (217ms, 8.4, 4.5/5.0, 1.6),
     * they are updated to the user's exact requested defaults (200ms, 7.0, 6.0, 2.7, 1.5) once after config load.
     */
    private void migrateLegacyConfigDefaultsIfNeeded() {
        if (migratedLegacyDefaultsOnce) {
            return;
        }
        migratedLegacyDefaultsOnce = true;

        if (Math.abs(reequipDelayMs.getValue() - 217.0D) < 0.01D) {
            reequipDelayMs.setValue(200.0D);
        }
        if (Math.abs(heightRange.getValue() - 8.4D) < 0.01D) {
            heightRange.setValue(6.0D);
        }
        if (Math.abs(macePreHitRange.getValue() - 4.5D) < 0.01D
                || Math.abs(macePreHitRange.getValue() - 5.0D) < 0.01D) {
            macePreHitRange.setValue(2.7D);
        }
        if (Math.abs(minDropDist.getValue() - 1.6D) < 0.01D) {
            minDropDist.setValue(2.0D);
        }
    }

    public static ElytraTarget getInstance() {
        return instance;
    }

    public static boolean isTargetingActive() {
        return instance != null && instance.isEnabled() && shouldElytraTarget;
    }

    public boolean isSwapLocked() {
        return ActionManager.getInstance().isSwapInProgress() || CombatStateManager.isElytraAscentLocked();
    }

    public static boolean isBetaDynamicInterceptEnabled() {
        return instance != null && instance.isEnabled() && instance.dynamicInterceptBeta.getValue();
    }

    public static boolean isBetaFlightCancelActive() {
        return instance != null
                && instance.isEnabled()
                && instance.dynamicInterceptBeta.getValue()
                && instance.betaFlightCancelSuppressGlide
                && System.currentTimeMillis() <= instance.betaFlightCancelUntilMs;
    }

    public boolean isReadyForMaceStrike() {
        if (!isEnabled() || mc.player == null) {
            return true;
        }
        if (dynamicInterceptBeta.getValue()) {
            return !mc.player.isFallFlying();
        }
        return !isWearingElytra();
    }

    @Override
    protected void onEnable() {
        migrateLegacyConfigDefaultsIfNeeded();
        shouldElytraTarget = true;
        resetInternalState();
        refreshToggleBindLabel();
    }

    @Override
    protected void onDisable() {
        shouldElytraTarget = false;
        releaseForcedGlideJump();
        ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
        ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
        resetInternalState();
        refreshToggleBindLabel();
    }

    public void syncTargetAndVelocity(LivingEntity target) {
        if (target != null && target.isAlive() && !isWhitelistedFriend(target)) {
            cachedTarget = target;
            updateTrackedEnemyVelocity(target);
        }
    }

    public boolean ensureUnequippedBeforeMaceAttack(LivingEntity target) {
        if (!isEnabled() || mc.player == null || mc.screen != null) {
            return false;
        }
        if (target != null && (isWhitelistedFriend(target) || !MaceTarget.isEntityAllowedByAutoMaceTargets(mc, target))) {
            return false;
        }
        if (target != null && target.isAlive()) {
            cachedTarget = target;
        }
        if (!isWearingElytra()) {
            forceCancelFlightInput = false;
            return true;
        }
        if (!shouldTriggerPriority1Unequip(target)) {
            return false;
        }
        if (ActionManager.getInstance().isSwapInProgress()) {
            return false;
        }
        if (requestPriority1ChestplateUnequip()) {
            flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
            onBetaUnequipAccepted();
        }
        return false;
    }

    public void enterState3RecoveryLock() {
        long now = System.currentTimeMillis();
        flightState = CombatFlightState.STATE_3_RECOVERY;
        forceCancelFlightInput = false;
        betaFlightCancelSuppressGlide = false;
        betaFlightCancelUntilMs = 0L;
        betaPendingReequip = false;
        glideWindowTimer.reset();

        // Schedule exact Reequip Delay (ms) once per hit (don't reset if Wind Burst bounce arrives 1-2 ticks later)
        if (!postHitReequipPending || now > postHitReequipExpireMs) {
            long delayMs = Math.max(0L, Math.round(computeDynamicReequipDelayMs()));
            postHitReequipPending = true;
            postHitReequipAtMs = now + delayMs;
            postHitReequipNextAttemptMs = postHitReequipAtMs;
            postHitReequipExpireMs = now + Math.max(2500L, delayMs + 2000L);
            postHitReequipAttempts = 0;
            swapCommitmentTimer.reset();
        }
    }

    public void notifyMaceAttackLanded() {
        enterState3RecoveryLock();
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        pollToggleBindCapture();
    }

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        // Handled exclusively in ClientTickEvents.START_CLIENT_TICK
    }

    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        // Handled exclusively in ClientTickEvents.START_CLIENT_TICK to prevent BadPackets/Post flags
    }

    private void checkWorldOrRespawnChange() {
        if (mc.player == null) {
            return;
        }
        int currentId = mc.player.getId();
        if (currentId != lastObservedPlayerId || mc.player.tickCount < lastProcessedMovementTick) {
            lastObservedPlayerId = currentId;
            lastProcessedMovementTick = mc.player.tickCount;
            lastElytraEquipCompletedTick = -100;
            lastHotbarScanTick = -100;
            cachedChestplateSlot = -1;
            cachedElytraSlot = -1;
            flightState = CombatFlightState.STATE_1_FLIGHT;
            forceCancelFlightInput = false;
            swapCommitmentTimer.reset();
            ActionManager.getInstance().resetAll();
        }
    }

    private void runAutoElytraCycle() {
        // Always release our 1-tick synthetic auto-glide jump press on the next tick first
        if (forcedGlideJumpDown) {
            releaseForcedGlideJump();
        }

        if (mc.player == null || mc.level == null) {
            ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
            ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
            resetInternalState();
            return;
        }

        checkWorldOrRespawnChange();

        if (!mc.player.isAlive() || mc.player.getHealth() <= 0.0F) {
            ActionManager.getInstance().resetAll();
            resetInternalState();
            return;
        }

        long now = System.currentTimeMillis();
        ActionManager actionManager = ActionManager.getInstance();
        actionManager.ensureTickSynchronized(mc);

        updateAirbornePeakAndWindChargeState();
        updateHotbarArmorCache(false);

        boolean wearingElytra = isWearingElytra();
        if (!wearingElytra || mc.player.isFallFlying()) {
            releaseForcedGlideJump();
        elytraEquippedNotGlidingSinceMs = 0L;
        postHitReequipPending = false;
        postHitReequipAtMs = 0L;
        postHitReequipExpireMs = 0L;
        postHitReequipNextAttemptMs = 0L;
        postHitReequipAttempts = 0;
        groundedSinceMs = 0L;
        } else if (elytraEquippedNotGlidingSinceMs == 0L) {
            elytraEquippedNotGlidingSinceMs = now;
        }

        if (wearingElytra && !wasWearingElytraLastTick) {
            lastElytraEquippedTimeMs = now;
            lastElytraEquipCompletedTick = actionManager.getClientTick() + 2;
            elytraEquippedNotGlidingSinceMs = mc.player.isFallFlying() ? 0L : now;
            forceCancelFlightInput = false;
            if (postHitReequipPending) {
                // Confirm Elytra equipped post-hit & extend 0.5s ElytraHelper swap guard
                postHitReequipPending = false;
                postHitReequipAttempts = 0;
                ElytraHelper.notifyAutoElytraReequipped(ELYTRA_HELPER_POST_REEQUIP_GUARD_MS);
            }
            if (!betaFlightCancelSuppressGlide) {
                glideWindowTimer.reset();
                if (flightState != CombatFlightState.STATE_1_FLIGHT) {
                    flightState = CombatFlightState.STATE_1_FLIGHT;
                }
            }
        }
        wasWearingElytraLastTick = wearingElytra;
        if (!wearingElytra) {
            forceCancelFlightInput = false;
        } else if (postHitReequipPending) {
            postHitReequipPending = false;
            postHitReequipAttempts = 0;
        }

        if (postHitReequipPending && now > postHitReequipExpireMs) {
            postHitReequipPending = false;
            postHitReequipAttempts = 0;
        }

        if (ElytraHelper.isManualSwapLockActive()) {
            return;
        }

        if (actionManager.isBlockedByHigherPriority(ActionPriority.AUTO_ELYTRA_UNEQUIP)) {
            forceCancelFlightInput = false;
            return;
        }

        // Ground landing handling (preserve postHitReequipPending across brief <= 350ms ground grazes)
        if (mc.player.onGround()) {
            if (groundedSinceMs == 0L) {
                groundedSinceMs = now;
            }
            forceCancelFlightInput = false;
            betaFlightCancelSuppressGlide = false;
            betaFlightCancelUntilMs = 0L;
            betaPendingReequip = false;

            if (postHitReequipPending && !wearingElytra && !disableOnGround.getValue()
                    && now >= postHitReequipAtMs && now >= postHitReequipNextAttemptMs) {
                attemptScheduledPostHitReequip(now, actionManager);
                return;
            }
            if (postHitReequipPending && disableOnGround.getValue() && (now - groundedSinceMs) > 350L) {
                postHitReequipPending = false;
                postHitReequipAttempts = 0;
            }

            if (disableOnGround.getValue() && wearingElytra && !actionManager.isSwapInProgress() && canStartNewArmorSwap()) {
                requestPriority1ChestplateUnequip();
            }
            if (flightState != CombatFlightState.STATE_1_FLIGHT && !CombatStateManager.isMaceSmashLocked() && !postHitReequipPending) {
                flightState = CombatFlightState.STATE_1_FLIGHT;
            }
            return;
        } else {
            groundedSinceMs = 0L;
        }

        // Resolve target synchronized with AutoMace Target Selection (Players, Mobs, Animals)
        MaceTarget autoMace = MaceTarget.getInstance();
        if (autoMace != null && autoMace.isEnabled() && autoMace.getTarget() != null
                && autoMace.getTarget().isAlive()
                && MaceTarget.isEntityAllowedByAutoMaceTargets(mc, autoMace.getTarget())) {
            cachedTarget = autoMace.getTarget();
        } else {
            double searchRange = Math.max(20.0D, Math.max(heightRange.getValue(), macePreHitRange.getValue()) + 10.0D);
            cachedTarget = actionManager.getNearestVisibleTarget(mc, searchRange, true);
        }

        updateTrackedEnemyVelocity(cachedTarget);
        expireBetaFlightCancelIfNeeded();

        // =========================================================================
        // PRIORITY 1 (HIGHEST): AUTO_ELYTRA_UNEQUIP
        // =========================================================================
        if (wearingElytra && shouldTriggerPriority1Unequip(cachedTarget)) {
            forceCancelFlightInput = true;
            if (!actionManager.isSwapInProgress()) {
                if (requestPriority1ChestplateUnequip()) {
                    flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
                    onBetaUnequipAccepted();
                }
            }
            return;
        }

        // =========================================================================
        // 3.5-SECOND IDLE AUTO-GLIDE PULSE (only after wearing Elytra >= 3.5s without gliding)
        // =========================================================================
        if (wearingElytra) {
            processAutoGlideJumpPulse(actionManager);
        }

        // =========================================================================
        // GUARANTEED POST-HIT RE-EQUIP (honors exact Reequip Delay (ms) with retries)
        // =========================================================================
        if (postHitReequipPending && !wearingElytra) {
            if (now >= postHitReequipAtMs && now >= postHitReequipNextAttemptMs) {
                attemptScheduledPostHitReequip(now, actionManager);
            }
            return;
        }

        if (automaceSync.getValue() && CombatStateManager.isMaceSmashLocked() && !wearingElytra) {
            flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
            return;
        }

        // =========================================================================
        // PRIORITY 3: AUTO_ELYTRA_EQUIP
        // =========================================================================
        switch (flightState) {
            case STATE_1_FLIGHT -> {
                forceCancelFlightInput = false;
                if (!wearingElytra) {
                    if (shouldEquipElytraInFlightState() && canStartNewArmorSwap()) {
                        requestPriority3ElytraEquip(false);
                    }
                }
            }

            case STATE_2_SMASH_WINDOW -> {
                if (wearingElytra && (!betaFlightCancelSuppressGlide || mc.player.isFallFlying())) {
                    forceCancelFlightInput = true;
                    if (!actionManager.isSwapInProgress()) {
                        requestPriority1ChestplateUnequip();
                    }
                    return;
                }

                forceCancelFlightInput = false;

                double velY = mc.player.getDeltaMovement().y;
                boolean stillDescendingOnTarget = velY < -0.05D
                        && cachedTarget != null
                        && cachedTarget.isAlive()
                        && getDistanceToBox(mc.player.getEyePosition(1.0F), cachedTarget.getBoundingBox()) <= Math.max(heightRange.getValue(), macePreHitRange.getValue()) + 3.0D;
                boolean maceStillStriking = (autoMace != null && autoMace.isEnabled() && autoMace.isMidSlamSequence())
                        || CombatStateManager.isMaceSmashLocked();

                // Detect upward bounce OR manual melee swing during smash window to start exact Reequip Delay
                if (velY > 0.08D || mc.player.swinging || mc.player.attackAnim > 0.0F) {
                    enterState3RecoveryLock();
                } else if (!stillDescendingOnTarget && !maceStillStriking && swapCommitmentTimer.finished(computeDynamicReequipDelayMs())) {
                    enterState3RecoveryLock();
                } else if (swapCommitmentTimer.finished(1200.0D)) {
                    enterState3RecoveryLock();
                }
            }

            case STATE_3_RECOVERY -> {
                forceCancelFlightInput = false;
                if (wearingElytra) {
                    flightState = CombatFlightState.STATE_1_FLIGHT;
                    return;
                }
                if (CombatStateManager.isMaceSmashLocked()) {
                    return;
                }
                if (canStartNewArmorSwap() && shouldEquipElytraInRecovery()) {
                    if (requestPriority3ElytraEquip(true)) {
                        flightState = CombatFlightState.STATE_1_FLIGHT;
                    }
                }
            }
        }
    }

    private void attemptScheduledPostHitReequip(long now, ActionManager actionManager) {
        if (mc.player == null || mc.screen != null || mc.player.isUsingItem()) {
            return;
        }
        if (CombatStateManager.isMaceSmashLocked()
                || actionManager.isSwapInProgress()
                || !actionManager.canMutateHotbarSlotThisTick()) {
            return;
        }
        if (requestPriority3ElytraEquip(true)) {
            postHitReequipAttempts++;
            if (postHitReequipAttempts >= 3) {
                postHitReequipPending = false;
                flightState = CombatFlightState.STATE_1_FLIGHT;
            } else {
                // Retry after 150ms only if server didn't confirm EquipmentSlot.CHEST flip
                postHitReequipNextAttemptMs = now + 150L;
            }
        }
    }

    private void updateHotbarArmorCache(boolean forceImmediate) {
        if (mc.player == null || mc.screen != null) {
            return;
        }
        int currentTick = mc.player.tickCount;
        if (!forceImmediate && (currentTick - lastHotbarScanTick) < HOTBAR_SCAN_INTERVAL_TICKS) {
            return;
        }
        lastHotbarScanTick = currentTick;

        int bestChestSlot = -1;
        int bestChestScore = -1;
        int foundElytraSlot = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.ELYTRA)) {
                if (foundElytraSlot == -1 && isElytraUsable(stack)) {
                    foundElytraSlot = i;
                }
                continue;
            }
            if (isChestplateStack(stack)) {
                int score = getChestplateTierScore(stack.getItem());
                if (score > bestChestScore) {
                    bestChestScore = score;
                    bestChestSlot = i;
                }
            }
        }

        cachedChestplateSlot = bestChestSlot;
        cachedElytraSlot = foundElytraSlot;
    }

    private boolean isChestplateStack(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.is(Items.ELYTRA)) {
            return false;
        }
        if (stack.is(ItemTags.CHEST_ARMOR)) {
            return true;
        }
        return CHESTPLATES.contains(stack.getItem());
    }

    private int getChestplateTierScore(Item item) {
        if (item == Items.NETHERITE_CHESTPLATE) return 6;
        if (item == Items.DIAMOND_CHESTPLATE) return 5;
        if (item == Items.IRON_CHESTPLATE) return 4;
        if (item == Items.CHAINMAIL_CHESTPLATE) return 3;
        if (item == Items.GOLDEN_CHESTPLATE) return 2;
        if (item == Items.LEATHER_CHESTPLATE) return 1;
        return 1;
    }

    private int getValidChestplateSlot() {
        if (mc.player == null) {
            return -1;
        }
        if (cachedChestplateSlot >= 0 && cachedChestplateSlot <= 8) {
            ItemStack stack = mc.player.getInventory().getItem(cachedChestplateSlot);
            if (isChestplateStack(stack)) {
                return cachedChestplateSlot;
            }
        }
        updateHotbarArmorCache(true);
        return cachedChestplateSlot;
    }

    private int getValidElytraSlot() {
        if (mc.player == null) {
            return -1;
        }
        if (cachedElytraSlot >= 0 && cachedElytraSlot <= 8) {
            ItemStack stack = mc.player.getInventory().getItem(cachedElytraSlot);
            if (!stack.isEmpty() && stack.is(Items.ELYTRA) && isElytraUsable(stack)) {
                return cachedElytraSlot;
            }
        }
        updateHotbarArmorCache(true);
        return cachedElytraSlot;
    }

    private boolean requestPriority1ChestplateUnequip() {
        if (mc.player == null || mc.screen != null || mc.player.isUsingItem()
                || !ActionManager.getInstance().canMutateHotbarSlotThisTick()) {
            return false;
        }
        int chestplateSlot = getValidChestplateSlot();
        int currentSlot = mc.player.getInventory().getSelectedSlot();

        int nextSlotAfterUnequip = currentSlot;
        MaceTarget autoMace = MaceTarget.getInstance();
        if (automaceSync.getValue() && autoMace != null && autoMace.isEnabled()
                && cachedTarget != null && !mc.player.onGround()) {
            autoMace.notifyElytraUnequippedForSmash(mc, currentSlot, cachedTarget);
            // Direct Tick-1 Chestplate -> Mace handoff on fast/close dives eliminates 1-2 ticks of swing latency
            nextSlotAfterUnequip = autoMace.resolveOptimalMaceSlotForHandoff(mc, cachedTarget, currentSlot);
        }

        boolean accepted;
        if (chestplateSlot != -1) {
            accepted = ActionManager.getInstance().submitFastTwoTickUnequip(
                    mc,
                    chestplateSlot,
                    nextSlotAfterUnequip,
                    clickSim.getValue()
            );
        } else {
            accepted = ActionManager.executeLegitArmorSwapFromSlot(-1);
        }

        if (accepted) {
            swapCommitmentTimer.reset();
        }
        return accepted;
    }

    private boolean requestPriority3ElytraEquip() {
        return requestPriority3ElytraEquip(false);
    }

    private boolean requestPriority3ElytraEquip(boolean isPostHitReequip) {
        if (ElytraHelper.isManualSwapLockActive()
                || (!isPostHitReequip && (System.currentTimeMillis() - lastAutoArmorSwapMs) < 120L)
                || !ActionManager.getInstance().canMutateHotbarSlotThisTick()) {
            return false;
        }
        if (mc.player == null || mc.screen != null || mc.player.isUsingItem()) {
            return false;
        }
        if (CombatStateManager.isMaceSmashLocked()) {
            return false;
        }
        int elytraSlot = getValidElytraSlot();
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        boolean accepted;
        if (elytraSlot != -1) {
            accepted = ActionManager.getInstance().submitFastTwoTickArmorSwap(
                    mc,
                    ActionPriority.AUTO_ELYTRA_EQUIP,
                    "AutoElytra_EQUIP",
                    elytraSlot,
                    currentSlot,
                    clickSim.getValue()
            );
        } else {
            accepted = ActionManager.executeLegitArmorSwapFromSlot(-1);
        }
        if (accepted) {
            long now = System.currentTimeMillis();
            lastAutoArmorSwapMs = now;
            forceCancelFlightInput = false;
            swapCommitmentTimer.reset();
            lastElytraEquippedTimeMs = now;
            // Start 3.5s non-gliding timer: re-equip Elytra WITHOUT auto-gliding until 3.5s have elapsed
            elytraEquippedNotGlidingSinceMs = now;
            lastElytraEquipCompletedTick = ActionManager.getInstance().getClientTick() + 3;
            glideWindowTimer.reset();
            jumpPulseToggle = false;
            equipAfterWindChargeApexPending = false;
            // Block ElytraHelper swaps for 0.5s so pressing ElytraHelper right after post-hit equip won't swap it back
            ElytraHelper.notifyAutoElytraReequipped(ELYTRA_HELPER_POST_REEQUIP_GUARD_MS);
        }
        return accepted;
    }

    @SubscribeEvent
    public void onInput(InputEvent event) {
        if (!isEnabled() || mc.player == null || mc.level == null || mc.screen != null) {
            jumpPulseToggle = false;
            return;
        }
        if (forceCancelFlightInput && mc.player.isFallFlying()) {
            event.setJumping(false);
            jumpPulseToggle = false;
        }
    }

    private void processAutoGlideJumpPulse(ActionManager actionManager) {
        if (mc == null || mc.player == null || mc.options == null || mc.screen != null) {
            releaseForcedGlideJump();
            return;
        }
        if (forceCancelFlightInput && mc.player.isFallFlying()) {
            releaseForcedGlideJump();
            return;
        }
        if (!shouldSendLegalGlideStartPulse()) {
            return;
        }
        int nowTick = actionManager.getClientTick();
        if (nowTick < nextGlidePulseAllowedTick) {
            return;
        }
        // If Spacebar is already held, release for 1 tick so vanilla LocalPlayer.aiStep() sees a clean rising edge
        if (mc.options.keyJump.isDown()) {
            mc.options.keyJump.setDown(false);
            forcedGlideJumpDown = true;
            return;
        }
        // Pulse vanilla keyJump inside START_CLIENT_TICK (exact vanilla packet order, zero Post / Elytra A-B)
        mc.options.keyJump.setDown(true);
        forcedGlideJumpDown = true;
        nextGlidePulseAllowedTick = nowTick + 5;
    }

    private void releaseForcedGlideJump() {
        if (forcedGlideJumpDown && mc != null && mc.options != null) {
            mc.options.keyJump.setDown(false);
        }
        forcedGlideJumpDown = false;
    }

    private boolean shouldSendLegalGlideStartPulse() {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (betaFlightCancelSuppressGlide && System.currentTimeMillis() <= betaFlightCancelUntilMs) {
            return false;
        }
        if (CombatStateManager.isMaceSmashLocked() || ActionManager.getInstance().isSwapInProgress()) {
            return false;
        }
        if (!isWearingElytra()) {
            return false;
        }
        if (mc.player.onGround()
                || mc.player.isFallFlying()
                || mc.player.isInWater()
                || mc.player.isInLava()
                || mc.player.isPassenger()
                || mc.player.onClimbable()
                || mc.player.getAbilities().flying
                || mc.player.hasEffect(MobEffects.LEVITATION)
                || mc.player.hurtTime > 0) {
            return false;
        }

        ItemStack chestStack = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chestStack.is(Items.ELYTRA) || !isElytraUsable(chestStack)) {
            return false;
        }

        if (ActionManager.getInstance().getClientTick() < lastElytraEquipCompletedTick) {
            return false;
        }

        // Strict vanilla fall preconditions (prevents GrimAC Elytra A/B)
        double velY = mc.player.getDeltaMovement().y;
        if (velY >= MAX_LEGAL_GLIDE_VELOCITY_Y || mc.player.fallDistance < MIN_LEGAL_FALL_DISTANCE) {
            return false;
        }

        // Only auto-glide if the player has been wearing the Elytra for >= 3.5 seconds without gliding
        if (elytraEquippedNotGlidingSinceMs == 0L) {
            return false;
        }
        return (System.currentTimeMillis() - elytraEquippedNotGlidingSinceMs) >= AUTO_GLIDE_IDLE_EQUIP_MS;
    }

    private void updateAirbornePeakAndWindChargeState() {
        if (mc.player == null) {
            return;
        }

        boolean onCooldownNow = mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.WIND_CHARGE));
        double currentVelY = mc.player.getDeltaMovement().y;
        double deltaVelY = currentVelY - prevTickVelY;
        prevTickVelY = currentVelY;

        if ((onCooldownNow && !wasWindChargeOnCooldown)
                || (!mc.player.onGround() && currentVelY > PRIORITY_3_EQUIP_WIND_CHARGE_VEL_Y && deltaVelY > 0.25D)) {
            lastWindChargeOrBlastTimeMs = System.currentTimeMillis();
            windChargeAscentDetected = true;
            if (!isWearingElytra()) {
                equipAfterWindChargeApexPending = true;
            }
        }
        wasWindChargeOnCooldown = onCooldownNow;

        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()) {
            airbornePeakY = mc.player.getY();
            windChargeAscentDetected = false;
            equipAfterWindChargeApexPending = false;
        } else {
            double y = mc.player.getY();
            if (Double.isNaN(airbornePeakY) || y > airbornePeakY) {
                airbornePeakY = y;
            }
        }
    }

    private double getEffectiveFallDistance() {
        if (mc.player == null) {
            return 0.0D;
        }
        double vanillaFall = mc.player.fallDistance;
        double trackedFall = Double.isNaN(airbornePeakY) ? 0.0D : Math.max(0.0D, airbornePeakY - mc.player.getY());
        return Math.max(vanillaFall, trackedFall);
    }

    private boolean shouldEquipElytraInFlightState() {
        if (mc.player == null || mc.player.onGround() || isWearingElytra()) {
            return false;
        }
        if (getValidElytraSlot() == -1) {
            return false;
        }
        if (CombatStateManager.isMaceSmashLocked()) {
            return false;
        }
        if (mc.player.swinging || mc.player.attackAnim > 0.0F || mc.player.hurtTime > 0) {
            return false;
        }
        if (System.currentTimeMillis() - lastWindChargeOrBlastTimeMs < WIND_CHARGE_SETTLE_DELAY_MS) {
            return false;
        }
        if (!swapCommitmentTimer.finished(computeDynamicReequipDelayMs())) {
            return false;
        }

        double velY = mc.player.getDeltaMovement().y;
        double effectiveFall = getEffectiveFallDistance();

        if (equipAfterWindChargeApexPending || windChargeAscentDetected) {
            return velY > PRIORITY_3_EQUIP_WIND_CHARGE_VEL_Y
                    || (velY < MAX_LEGAL_GLIDE_VELOCITY_Y && mc.player.fallDistance >= MIN_LEGAL_FALL_DISTANCE);
        }

        if (cachedTarget != null && cachedTarget.isAlive() && !isWhitelistedFriend(cachedTarget)) {
            if (shouldTriggerPriority1Unequip(cachedTarget)) {
                return false;
            }
        }

        // Respects Min Drop Dist (1.5m default) directly: never triggers on a 1.252m normal jump!
        Minecraft _mc = Minecraft.getInstance();
        boolean clearTwoBlockRadius = hasNoBlocksUnderFeetInRadius(_mc, getSyncedMinDropDist(), 2.0D);
        return clearTwoBlockRadius || (velY < MAX_LEGAL_GLIDE_VELOCITY_Y && effectiveFall >= getSyncedMinDropDist());
    }

    private boolean shouldEquipElytraInRecovery() {
        if (mc.player == null || mc.player.onGround() || isWearingElytra() || getValidElytraSlot() == -1) {
            return false;
        }
        if (!swapCommitmentTimer.finished(computeDynamicReequipDelayMs())) {
            return false;
        }
        if (cachedTarget != null && shouldTriggerPriority1Unequip(cachedTarget)) {
            return false;
        }
        return true;
    }

    private boolean canStartNewArmorSwap() {
        if (ActionManager.getInstance().isBlockedByHigherPriority(ActionPriority.AUTO_ELYTRA_EQUIP)
                || ActionManager.getInstance().isSwapInProgress()
                || !ActionManager.getInstance().canMutateHotbarSlotThisTick()) {
            return false;
        }
        return swapCommitmentTimer.finished(computeDynamicReequipDelayMs());
    }

    private double computeDynamicReequipDelayMs() {
        double baseDelay = reequipDelayMs.getValue();
        if (!randomization.getValue()) {
            return Math.max(0.0D, baseDelay);
        }
        double minRand = Math.min(minRandomMs.getValue(), maxRandomMs.getValue());
        double maxRand = Math.max(minRandomMs.getValue(), maxRandomMs.getValue());
        double extra = maxRand > minRand ? ThreadLocalRandom.current().nextDouble(minRand, maxRand) : minRand;
        return Math.max(0.0D, baseDelay + extra);
    }

    public static boolean isWhitelistedFriend(Entity entity) {
        return ActionManager.isWhitelistedFriend(Minecraft.getInstance(), entity);
    }

    /**
     * Uses the user's exact configured settings without any hardcoded clamping:
     * - Min Drop Dist: 1.5 blocks default
     * - Mace Pre-Hit Range: 2.7 blocks default
     * - Height Diff: 7.0 blocks default
     * - Height Range: 6.0 blocks default
     */
    public boolean shouldTriggerPriority1Unequip(LivingEntity target) {
        if (target == null || !target.isAlive() || isWhitelistedFriend(target) || mc.player == null
                || !MaceTarget.isEntityAllowedByAutoMaceTargets(mc, target)) {
            return false;
        }
        if (getValidChestplateSlot() == -1 || !ActionManager.hasLineOfSight(mc, target)) {
            return false;
        }
        if (dynamicInterceptBeta.getValue()) {
            return shouldTriggerBetaDynamicIntercept(target);
        }

        // 1. Velaris Post-Equip Protection: Never unequip within 220ms of equipping the Elytra
        //    so manual ElytraHelper swaps + Firework Rocket launches never get interrupted!
        if ((System.currentTimeMillis() - lastElytraEquippedTimeMs) < 220L) {
            return false;
        }

        Vec3 vel = mc.player.getDeltaMovement();
        boolean fastRocketGlide = mc.player.isFallFlying() && vel.length() >= 0.60D && vel.y <= 0.35D;
        // 2. Descent / Rocket-Mace Check: Never unequip while steeply climbing (vel.y > 0.35D),
        //    but allow fast horizontal/shallow Rocket Mace glides into the enemy!
        if (!fastRocketGlide && vel.y >= -0.08D) {
            return false;
        }

        double effectiveFall = getEffectiveFallDistance();
        double requiredMinDrop = minDropDist.getValue(); // 1.5 blocks default
        double verticalAltitudeOverTarget = mc.player.getY() - target.getY();
        boolean hasSmashHeight = effectiveFall >= requiredMinDrop
                || verticalAltitudeOverTarget >= requiredMinDrop
                || (mc.player.isFallFlying() && vel.y < -0.22D)
                || fastRocketGlide;
        if (!hasSmashHeight) {
            return false;
        }

        if (mc.player.isFallFlying()) {
            MaceTarget autoMace = MaceTarget.getInstance();
            if (autoMace != null && !autoMace.isGlidingTowardTarget(mc, target)) {
                return false;
            }
        }

        Vec3 eyePos = mc.player.getEyePosition(1.0F);
        AABB box = target.getBoundingBox();
        double eyeToBoxDist = getDistanceToBox(eyePos, box);
        double preHitRange = macePreHitRange.getValue(); // 2.7 blocks default

        // 3. Velaris 2-Tick Pre-Hit Unequip Window (~4.15m / 2.1 ticks lead):
        //    Tick 0 (-100ms): Selects Slot 9 (Chestplate) & equips Chestplate.
        //    Tick 1 (-50ms):  Restores Slot 4 (Firework Rocket) & unlocks ActionManager.
        //    Tick 2 (0ms):    Arrives at <= 3.0m in Chestplate holding Slot 4 -> switches to Slot 1 (Mace) & strikes!
        Vec3 predictedEye = eyePos.add(vel.scale(2.1D));
        double predictedDist = getDistanceToBox(predictedEye, box);

        if (eyeToBoxDist <= preHitRange + 1.45D && (eyeToBoxDist <= preHitRange || predictedDist <= preHitRange)) {
            return true;
        }

        // 4. Height Diff (default 7.0 blocks) & Height Range (default 6.0 blocks):
        double horizontalDist = Math.hypot(mc.player.getX() - target.getX(), mc.player.getZ() - target.getZ());
        if (verticalAltitudeOverTarget >= heightDiff.getValue()
                && (horizontalDist <= heightRange.getValue() || eyeToBoxDist <= heightRange.getValue())) {
            return true;
        }

        return false;
    }

    private void onBetaUnequipAccepted() {
        if (!dynamicInterceptBeta.getValue()) {
            return;
        }
        // Keep Chestplate on through the smash and suppress auto-glide; re-equip cleanly after Reequip Delay (ms)
        betaFlightCancelSuppressGlide = true;
        betaFlightCancelUntilMs = System.currentTimeMillis() + 750L;
        betaPendingReequip = false;
        betaReequipAfterTick = -100;
    }

    private void expireBetaFlightCancelIfNeeded() {
        if (!betaFlightCancelSuppressGlide) {
            return;
        }
        if (mc.player == null
                || mc.player.onGround()
                || System.currentTimeMillis() > betaFlightCancelUntilMs
                || mc.player.getDeltaMovement().y > 0.08D) {
            betaFlightCancelSuppressGlide = false;
            betaFlightCancelUntilMs = 0L;
            betaPendingReequip = false;
        }
    }

    private boolean requestBetaSilentElytraReequip() {
        if (mc.player == null || mc.screen != null || mc.player.isUsingItem()) {
            return false;
        }
        if (ElytraHelper.isManualSwapLockActive() || !ActionManager.getInstance().canMutateHotbarSlotThisTick()) {
            return false;
        }
        int elytraSlot = getValidElytraSlot();
        if (elytraSlot == -1) {
            return false;
        }
        int currentSlot = mc.player.getInventory().getSelectedSlot();
        boolean accepted = ActionManager.getInstance().submitFastTwoTickArmorSwap(
                mc,
                ActionPriority.AUTO_ELYTRA_UNEQUIP,
                "AutoElytra_BETA_CANCEL_REEQUIP",
                elytraSlot,
                currentSlot,
                clickSim.getValue()
        );
        if (accepted) {
            lastElytraEquippedTimeMs = System.currentTimeMillis();
            lastElytraEquipCompletedTick = ActionManager.getInstance().getClientTick() + 3;
        }
        return accepted;
    }

    private void updateTrackedEnemyVelocity(LivingEntity target) {
        if (target == null || !target.isAlive() || mc.player == null) {
            trackedEnemyId = -1;
            trackedEnemyUpdatedTick = -100;
            trackedEnemyVx = 0.0D;
            trackedEnemyVy = 0.0D;
            trackedEnemyVz = 0.0D;
            lastEnemyX = Double.NaN;
            lastEnemyY = Double.NaN;
            lastEnemyZ = Double.NaN;
            return;
        }
        int curTick = ActionManager.getInstance().getClientTick();
        if (target.getId() == trackedEnemyId && curTick == trackedEnemyUpdatedTick) {
            return;
        }
        trackedEnemyUpdatedTick = curTick;

        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();
        if (target.getId() != trackedEnemyId || Double.isNaN(lastEnemyX)) {
            trackedEnemyId = target.getId();
            lastEnemyX = x;
            lastEnemyY = y;
            lastEnemyZ = z;
            Vec3 dm = target.getDeltaMovement();
            trackedEnemyVx = dm.x;
            trackedEnemyVy = dm.y;
            trackedEnemyVz = dm.z;
            return;
        }
        double rawVx = x - lastEnemyX;
        double rawVy = y - lastEnemyY;
        double rawVz = z - lastEnemyZ;
        lastEnemyX = x;
        lastEnemyY = y;
        lastEnemyZ = z;

        double rawSpeedSq = rawVx * rawVx + rawVy * rawVy + rawVz * rawVz;
        if (rawSpeedSq > 16.0D) {
            trackedEnemyVx = 0.0D;
            trackedEnemyVy = 0.0D;
            trackedEnemyVz = 0.0D;
            return;
        }
        if (rawSpeedSq < 1.0E-8D) {
            Vec3 dm = target.getDeltaMovement();
            if (dm.lengthSqr() > 1.0E-5D) {
                rawVx = dm.x;
                rawVy = dm.y;
                rawVz = dm.z;
            }
        }
        trackedEnemyVx = 0.50D * rawVx + 0.50D * trackedEnemyVx;
        trackedEnemyVy = 0.50D * rawVy + 0.50D * trackedEnemyVy;
        trackedEnemyVz = 0.50D * rawVz + 0.50D * trackedEnemyVz;
    }

    private int getLocalPingMs() {
        try {
            if (mc != null && mc.player != null && mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if (info != null) {
                    return Math.max(0, info.getLatency());
                }
            }
        } catch (Throwable ignored) {
        }
        return 40;
    }

    /**
     * Beta 3D Dynamic Intercept Solver:
     * - Angle convention: 0 deg = straight/flat, +90 deg = straight down, -90 deg = straight up.
     * - Uses true 3D relative closing speed (works when enemy is also gliding with an Elytra).
     * - Simulates exact vanilla post-unequip free-fall physics (0.91 horiz drag, -0.08 gravity, 0.98 vert drag)
     *   tuned so Rocket-3 70 deg dives unequip at ~6-7 blocks and 40 deg dives unequip at ~4-5 blocks.
     */
    private boolean shouldTriggerBetaDynamicIntercept(LivingEntity target) {
        if (mc.player == null || target == null || !mc.player.isFallFlying()) {
            return false;
        }
        if ((System.currentTimeMillis() - lastElytraEquippedTimeMs) < 220L) {
            return false;
        }

        // Always ensure enemy 3D velocity is up-to-date even when called first by MaceTarget
        updateTrackedEnemyVelocity(target);

        Vec3 pVel = mc.player.getDeltaMovement();
        double pvx = pVel.x;
        double pvy = pVel.y;
        double pvz = pVel.z;

        // Never cancel flight when intentionally pitching upward to rocket away
        if (mc.player.getXRot() < -15.0F && pvy > 0.08D) {
            return false;
        }

        double evx = trackedEnemyVx;
        double evy = trackedEnemyVy;
        double evz = trackedEnemyVz;

        double ex = mc.player.getX();
        double ey = mc.player.getEyeY();
        double ez = mc.player.getZ();
        AABB box = target.getBoundingBox();

        double cx = Mth.clamp(ex, box.minX, box.maxX);
        double cy = Mth.clamp(ey, box.minY, box.maxY);
        double cz = Mth.clamp(ez, box.minZ, box.maxZ);

        double rx = cx - ex;
        double ry = cy - ey;
        double rz = cz - ez;
        double dist = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (dist < 0.01D || dist > 16.0D) {
            return false;
        }

        // True 3D relative closing speed (works for stationary AND gliding enemies)
        double relVx = pvx - evx;
        double relVy = pvy - evy;
        double relVz = pvz - evz;
        double relSpeed = Math.sqrt(relVx * relVx + relVy * relVy + relVz * relVz);
        if (relSpeed < 0.22D) {
            return false;
        }

        double closingSpeed = (relVx * rx + relVy * ry + relVz * rz) / dist;
        if (closingSpeed < 0.20D) {
            return false;
        }

        // Relaxed approach cone (0.55 ~ 56 deg) so curved Rocket-3 dives never delay unequip
        double alignment = closingSpeed / relSpeed;
        if (alignment < 0.55D && dist > 4.5D) {
            return false;
        }

        // 3D dive angle: 0 = flat, +90 = straight down, -90 = straight up
        double pHoriz = Math.hypot(pvx, pvz);
        double diveAngleDeg = Math.toDegrees(Math.atan2(-pvy, pHoriz));
        if (diveAngleDeg < 10.0D && mc.player.fallDistance < 1.5F) {
            return false;
        }

        // Close-range emergency cancel: never fly past a target inside 3.6m while still gliding
        if (dist <= 3.6D && closingSpeed >= 0.35D && (diveAngleDeg >= 15.0D || mc.player.fallDistance >= 1.5F)) {
            return true;
        }

        // Angle-, speed-, and ping-scaled lead window:
        // Rocket 3 @ 70 deg -> ~6.2-7.0m; Rocket 3 @ 40 deg -> ~4.4-5.1m; Elytra head-on -> ~8.0-9.0m
        double sinDive = Mth.clamp(Math.sin(Math.toRadians(Math.max(0.0D, diveAngleDeg))), 0.0D, 1.0D);
        double speedBonus = Mth.clamp((relSpeed - 1.15D) * 0.45D, 0.0D, 0.65D);
        double pingTicks = Mth.clamp(getLocalPingMs() / 100.0D, 0.0D, 1.4D);
        double leadTicks = 0.45D + 1.05D * sinDive + speedBonus + pingTicks;
        int fullTicks = Math.min(5, (int) Math.floor(leadTicks));
        double fracTick = leadTicks - Math.floor(leadTicks);

        // Vanilla travelFallFlying() clamps fal
        double simFallDist = (pvy <= -0.5D) ? Math.max(mc.player.fallDistance, 1.0D) : 1.0D;

        double simPx = ex, simPy = ey, simPz = ez;
        double simVx = pvx, simVy = pvy, simVz = pvz;
        double simOffX = 0.0D, simOffY = 0.0D, simOffZ = 0.0D;
        double simEvx = evx, simEvy = evy, simEvz = evz;
        double enemyDrag = target.isFallFlying() ? 0.99D : 0.91D;
        double strikeRadius = 2.55D;

        for (int t = 1; t <= fullTicks + 1; t++) {
            double stepFraction = (t <= fullTicks) ? 1.0D : fracTick;
            if (stepFraction <= 1.0E-4D) {
                break;
            }

            double prevPx = simPx, prevPy = simPy, prevPz = simPz;
            double prevOffX = simOffX, prevOffY = simOffY, prevOffZ = simOffZ;
            double prevFall = simFallDist;

            simPx += simVx * stepFraction;
            simPy += simVy * stepFraction;
            simPz += simVz * stepFraction;
            if (simVy < 0.0D) {
                simFallDist += (-simVy) * stepFraction;
            }

            simOffX += simEvx * stepFraction;
            simOffY += simEvy * stepFraction;
            simOffZ += simEvz * stepFraction;

            // 4-substep swept check per tick: prevents high-speed tunneling AND checks floor height per substep
            for (int s = 1; s <= 4; s++) {
                double f = s * 0.25D;
                double subPx = prevPx + (simPx - prevPx) * f;
                double subPy = prevPy + (simPy - prevPy) * f;
                double subPz = prevPz + (simPz - prevPz) * f;
                double subOffX = prevOffX + (simOffX - prevOffX) * f;
                double subOffY = prevOffY + (simOffY - prevOffY) * f;
                double subOffZ = prevOffZ + (simOffZ - prevOffZ) * f;
                double subFall = prevFall + (simFallDist - prevFall) * f;

                boolean aboveEnemyFloor = subPy >= (box.minY + subOffY - 0.55D);
                if (!aboveEnemyFloor || subFall < 1.50D) {
                    continue;
                }
                double subDist = distPointToShiftedBox(subPx, subPy, subPz, box, subOffX, subOffY, subOffZ);
                if (subDist <= strikeRadius) {
                    return true;
                }
            }

            if (stepFraction >= 0.999D) {
                simVx *= 0.91D;
                simVy = (simVy - 0.08D) * 0.98D;
                simVz *= 0.91D;

                simEvx *= enemyDrag;
                simEvz *= enemyDrag;
                if (!target.onGround() && !target.isFallFlying()) {
                    simEvy = (simEvy - 0.08D) * 0.98D;
                }
            }
        }
        return false;
    }

    private double distPointToShiftedBox(
            double px, double py, double pz,
            AABB box, double ox, double oy, double oz
    ) {
        double dx = px - Mth.clamp(px, box.minX + ox, box.maxX + ox);
        double dy = py - Mth.clamp(py, box.minY + oy, box.maxY + oy);
        double dz = pz - Mth.clamp(pz, box.minZ + oz, box.maxZ + oz);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double getDistanceToBox(Vec3 pos, AABB box) {
        double dx = pos.x - Mth.clamp(pos.x, box.minX, box.maxX);
        double dy = pos.y - Mth.clamp(pos.y, box.minY, box.maxY);
        double dz = pos.z - Mth.clamp(pos.z, box.minZ, box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private boolean isWearingElytra() {
        if (mc.player == null) {
            return false;
        }
        return InventoryUtils.isElytraEquipped()
                || mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private boolean isElytraUsable(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.ELYTRA)) {
            return false;
        }
        return stack.getMaxDamage() - stack.getDamageValue() > 2;
    }

    private void resetInternalState() {
        flightState = CombatFlightState.STATE_1_FLIGHT;
        lastElytraEquipCompletedTick = -100;
        jumpPulseToggle = false;
        forceCancelFlightInput = false;
        lastWindChargeOrBlastTimeMs = 0L;
        wasWindChargeOnCooldown = false;
        windChargeAscentDetected = false;
        equipAfterWindChargeApexPending = false;
        prevTickVelY = 0.0D;
        airbornePeakY = Double.NaN;
        lastProcessedMovementTick = -100;
        lastHotbarScanTick = -100;
        cachedChestplateSlot = -1;
        cachedElytraSlot = -1;
        cachedTarget = null;
        betaFlightCancelSuppressGlide = false;
        betaFlightCancelUntilMs = 0L;
        betaPendingReequip = false;
        betaReequipAfterTick = -100;
        elytraEquippedNotGlidingSinceMs = 0L;
        trackedEnemyUpdatedTick = -100;
        trackedEnemyId = -1;
        trackedEnemyVx = 0.0D;
        trackedEnemyVy = 0.0D;
        trackedEnemyVz = 0.0D;
        lastEnemyX = Double.NaN;
        lastEnemyY = Double.NaN;
        lastEnemyZ = Double.NaN;
        swapCommitmentTimer.reset();
        glideWindowTimer.reset();
    }

    // =========================================================================
    // Click-to-Bind Module Toggle Bind System
    // =========================================================================
    private void pollModuleToggleBind(Minecraft client) {
        if (client == null || client.player == null || client.screen != null || bindToggleKey.getValue()) {
            wasTogglePressed = true;
            return;
        }
        int toggleCode = (int) Math.round(savedToggleKeyCode.getValue());
        boolean isDown = isBoundInputDown(toggleCode);
        if (isDown && !wasTogglePressed) {
            setEnabled(!isEnabled());
        }
        wasTogglePressed = isDown;
    }

    private void pollToggleBindCapture() {
        long windowHandle = GLFW.glfwGetCurrentContext();
        if (windowHandle == 0L) {
            return;
        }

        if (bindToggleKey.getValue()) {
            if (!wasListeningToggle) {
                wasListeningToggle = true;
                waitingForToggleMouseRelease = true;
                updateSettingName(bindToggleKey, "Toggle Bind: [Press any key...]");
            }
            if (waitingForToggleMouseRelease) {
                if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS
                        && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
                    waitingForToggleMouseRelease = false;
                }
                return;
            }
            Integer captured = pollFirstPressedKeyOrMouse(windowHandle);
            if (captured != null) {
                savedToggleKeyCode.setValue((double) captured);
                bindToggleKey.setValue(false);
                wasListeningToggle = false;
                wasTogglePressed = true;
                lastDisplayedToggleKey = Integer.MIN_VALUE;
                refreshToggleBindLabel();
            }
            return;
        } else if (wasListeningToggle) {
            wasListeningToggle = false;
            lastDisplayedToggleKey = Integer.MIN_VALUE;
            refreshToggleBindLabel();
        }

        refreshToggleBindLabel();
    }

    private void refreshToggleBindLabel() {
        int code = (int) Math.round(savedToggleKeyCode.getValue());
        if (!bindToggleKey.getValue() && code != lastDisplayedToggleKey) {
            lastDisplayedToggleKey = code;
            updateSettingName(bindToggleKey, "Toggle Bind: [" + formatKeyCode(code) + "]");
        }
    }

    private Integer pollFirstPressedKeyOrMouse(long windowHandle) {
        if (GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_DELETE) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS) {
            return -1;
        }
        for (int btn = GLFW.GLFW_MOUSE_BUTTON_MIDDLE; btn <= GLFW.GLFW_MOUSE_BUTTON_8; btn++) {
            if (GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS) {
                return -10 - btn;
            }
        }
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                continue;
            }
            if (GLFW.glfwGetKey(windowHandle, key) == GLFW.GLFW_PRESS) {
                return key;
            }
        }
        return null;
    }

    private boolean isBoundInputDown(int code) {
        if (code == -1) {
            return false;
        }
        long windowHandle = GLFW.glfwGetCurrentContext();
        if (windowHandle == 0L) {
            return false;
        }
        if (code <= -10) {
            int btn = -10 - code;
            return btn >= 0 && btn <= GLFW.GLFW_MOUSE_BUTTON_8
                    && GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS;
        }
        return code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST
                && GLFW.glfwGetKey(windowHandle, code) == GLFW.GLFW_PRESS;
    }

    private String formatKeyCode(int code) {
        if (code == -1) {
            return "NONE";
        }
        if (code <= -10) {
            int btn = -10 - code;
            return switch (btn) {
                case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "MMB";
                case GLFW.GLFW_MOUSE_BUTTON_4 -> "MOUSE4";
                case GLFW.GLFW_MOUSE_BUTTON_5 -> "MOUSE5";
                default -> "MOUSE" + (btn + 1);
            };
        }
        if (code >= GLFW.GLFW_KEY_A && code <= GLFW.GLFW_KEY_Z) {
            return String.valueOf((char) code);
        }
        if (code >= GLFW.GLFW_KEY_0 && code <= GLFW.GLFW_KEY_9) {
            return String.valueOf((char) code);
        }
        try {
            if (GLFW.glfwGetCurrentContext() != 0L) {
                String glfwName = GLFW.glfwGetKeyName(code, 0);
                if (glfwName != null && !glfwName.isBlank()) {
                    return glfwName.toUpperCase();
                }
            }
        } catch (Throwable ignored) {
        }
        return switch (code) {
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPSLOCK";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            default -> "KEY_" + code;
        };
    }

    private void updateSettingName(Setting<?> setting, String newLabel) {
        if (setting == null || newLabel == null) {
            return;
        }
        Class<?> cls = setting.getClass();
        while (cls != null && cls != Object.class) {
            for (Field field : cls.getDeclaredFields()) {
                if (field.getType() == String.class) {
                    try {
                        field.setAccessible(true);
                        Object val = field.get(setting);
                        if (val instanceof String s && s.startsWith("Toggle Bind")) {
                            field.set(setting, newLabel);
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            cls = cls.getSuperclass();
        }
    }

    public float getRange() {
        return heightRange.getFloat();
    }

    public float getForward() {
        return macePreHitRange.getFloat();
    }

    public double getSyncedMinDropDist() {
        return Math.max(1.5D, minDropDist.getValue());
    }

    public boolean isAutomaceSyncEnabled() {
        return Boolean.TRUE.equals(automaceSync.getValue());
    }

    /**
     * Returns true when there are NO solid/collidable blocks underneath the player's feet
     * within the specified horizontal radius (2.0 blocks) and vertical depth.
     */
    private boolean hasNoBlocksUnderFeetInRadius(Minecraft mc, double depthBlocks, double horizRadius) {
        if (mc == null || mc.player == null || mc.level == null) {
            return false;
        }
        if (mc.player.onGround()) {
            return false;
        }
        AABB box = mc.player.getBoundingBox();
        AABB checkArea = new AABB(
                box.minX - horizRadius,
                box.minY - depthBlocks,
                box.minZ - horizRadius,
                box.maxX + horizRadius,
                box.minY - 0.05D,
                box.maxZ + horizRadius
        );
        return mc.level.noCollision(mc.player, checkArea);
    }

}
