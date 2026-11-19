package attrium.ru.api.module.impl.movement;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
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
    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization", "Enable randomized delay variance for elytra re-equip cooldowns.", false));
    private final NumberSetting minRandomMs = register(new NumberSetting(
            "Min Random (ms)", "Minimum random delay in milliseconds.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting maxRandomMs = register(new NumberSetting(
            "Max Random (ms)", "Maximum random delay in milliseconds.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting reequipDelayMs = register(new NumberSetting(
            "Reequip Delay (ms)", "Dynamic delay in milliseconds before re-equipping the elytra (200ms default).", 200.0D, 0.0D, 1000.0D, 1.0D));
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
            "Mace Pre-Hit Range", "Pre-impact distance threshold to unequip elytra before a mace hit (2.7 default).", 2.7D, 0.5D, 12.0D, 0.1D));
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

    private int cachedChestplateSlot = -1;
    private int cachedElytraSlot = -1;
    private int lastHotbarScanTick = -100;

    private boolean wasTogglePressed = false;
    private boolean waitingForToggleMouseRelease = false;
    private boolean wasListeningToggle = false;
    private int lastDisplayedToggleKey = Integer.MIN_VALUE;

    public ElytraTarget() {
        super("AutoElytra", "GrimAC-safe fast legit hotbar Elytra controller (Elytra Mace & Rocket Mace).", ModuleCategory.MOVEMENT);
        instance = this;
        savedToggleKeyCode.visibleWhen(() -> false);
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
            minDropDist.setValue(1.5D);
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

    public boolean isReadyForMaceStrike() {
        if (!isEnabled() || mc.player == null) {
            return true;
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
        ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
        ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
        resetInternalState();
        refreshToggleBindLabel();
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
        }
        return false;
    }

    public void enterState3RecoveryLock() {
        flightState = CombatFlightState.STATE_3_RECOVERY;
        forceCancelFlightInput = false;
        swapCommitmentTimer.reset();
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
        if (!isEnabled() || mc.player == null || mc.level == null) {
            return;
        }
        ActionManager.getInstance().onPreTickRender(mc);
        int currentTick = ActionManager.getInstance().getClientTick();
        if (lastProcessedMovementTick != currentTick) {
            lastProcessedMovementTick = currentTick;
            runAutoElytraCycle();
        }
    }

    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        if (!isEnabled() || mc.player == null || mc.level == null) {
            return;
        }
        int currentTick = ActionManager.getInstance().getClientTick();
        if (lastProcessedMovementTick != currentTick) {
            lastProcessedMovementTick = currentTick;
            runAutoElytraCycle();
        }
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

        ActionManager actionManager = ActionManager.getInstance();
        actionManager.ensureTickSynchronized(mc);

        updateAirbornePeakAndWindChargeState();
        updateHotbarArmorCache(false);

        boolean wearingElytra = isWearingElytra();
        if (wearingElytra && !wasWearingElytraLastTick) {
            // Detected Elytra equip (via AutoElytra or manual ElytraHelper swap):
            // Start 220ms post-equip grace window and arm instant legal glide pulse!
            lastElytraEquippedTimeMs = System.currentTimeMillis();
            lastElytraEquipCompletedTick = actionManager.getClientTick();
            glideWindowTimer.reset();
            forceCancelFlightInput = false;
            if (flightState != CombatFlightState.STATE_1_FLIGHT) {
                flightState = CombatFlightState.STATE_1_FLIGHT;
            }
        }
        wasWearingElytraLastTick = wearingElytra;
        if (!wearingElytra) {
            forceCancelFlightInput = false;
        }

        if (actionManager.isBlockedByHigherPriority(ActionPriority.AUTO_ELYTRA_UNEQUIP)) {
            forceCancelFlightInput = false;
            return;
        }

        // Ground landing handling
        if (mc.player.onGround()) {
            forceCancelFlightInput = false;
            if (disableOnGround.getValue() && wearingElytra && !actionManager.isSwapInProgress() && canStartNewArmorSwap()) {
                requestPriority1ChestplateUnequip();
            }
            if (flightState != CombatFlightState.STATE_1_FLIGHT && !CombatStateManager.isMaceSmashLocked()) {
                flightState = CombatFlightState.STATE_1_FLIGHT;
            }
            return;
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

        // =========================================================================
        // PRIORITY 1 (HIGHEST): AUTO_ELYTRA_UNEQUIP
        // Triggers on Mace Pre-Hit Range (2.7m default) OR Height Diff (7.0m) + Height Range (6.0m)
        // =========================================================================
        if (wearingElytra && shouldTriggerPriority1Unequip(cachedTarget)) {
            forceCancelFlightInput = true;
            if (!actionManager.isSwapInProgress()) {
                if (requestPriority1ChestplateUnequip()) {
                    flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
                }
            }
            return;
        }

        if (automaceSync.getValue() && CombatStateManager.isMaceSmashLocked() && !wearingElytra) {
            flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
            return;
        }

        // =========================================================================
        // PRIORITY 3: AUTO_ELYTRA_EQUIP (Re-wear Elytra after Dynamic 200ms Delay)
        // =========================================================================
        switch (flightState) {
            case STATE_1_FLIGHT -> {
                forceCancelFlightInput = false;
                if (!wearingElytra) {
                    if (shouldEquipElytraInFlightState() && canStartNewArmorSwap()) {
                        requestPriority3ElytraEquip();
                    }
                }
            }

            case STATE_2_SMASH_WINDOW -> {
                if (wearingElytra) {
                    forceCancelFlightInput = true;
                    if (!actionManager.isSwapInProgress()) {
                        requestPriority1ChestplateUnequip();
                    }
                    return;
                }

                forceCancelFlightInput = false;

                // Velaris Smash Window Hold:
                // Keep Chestplate locked on while descending (vel.y < -0.05D) toward cachedTarget so the Mace smash
                // ALWAYS lands before the 200ms Reequip Delay timer begins!
                double velY = mc.player.getDeltaMovement().y;
                boolean stillDescendingOnTarget = velY < -0.05D
                        && cachedTarget != null
                        && cachedTarget.isAlive()
                        && getDistanceToBox(mc.player.getEyePosition(1.0F), cachedTarget.getBoundingBox()) <= Math.max(heightRange.getValue(), macePreHitRange.getValue()) + 3.0D;
                boolean maceStillStriking = (autoMace != null && autoMace.isEnabled() && autoMace.isMidSlamSequence())
                        || CombatStateManager.isMaceSmashLocked();

                if (velY > 0.08D) {
                    // Player just bounced upward (e.g. Wind Burst / Rocket bounce): start 200ms Reequip Delay now!
                    enterState3RecoveryLock();
                } else if (!stillDescendingOnTarget && !maceStillStriking && swapCommitmentTimer.finished(computeDynamicReequipDelayMs())) {
                    flightState = CombatFlightState.STATE_3_RECOVERY;
                } else if (swapCommitmentTimer.finished(1200.0D)) {
                    flightState = CombatFlightState.STATE_3_RECOVERY;
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
                    if (requestPriority3ElytraEquip()) {
                        flightState = CombatFlightState.STATE_1_FLIGHT;
                    }
                }
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
        if (mc.player == null || mc.screen != null || mc.player.isUsingItem()) {
            return false;
        }
        int chestplateSlot = getValidChestplateSlot();
        int currentSlot = mc.player.getInventory().getSelectedSlot();

        // Keep original hotbar slot (Sword / Wind Charge / Hand) after equipping Chestplate so AutoMace
        // only swaps to the Mace on the exact hit tick (Mace Cooldown Swap for maximum damage!),
        // while notifying AutoMace to lock onto cachedTarget for the smash window!
        int nextSlotAfterUnequip = currentSlot;
        MaceTarget autoMace = MaceTarget.getInstance();
        if (autoMace != null && autoMace.isEnabled() && cachedTarget != null && !mc.player.onGround()) {
            autoMace.notifyElytraUnequippedForSmash(mc, currentSlot, cachedTarget);
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
            forceCancelFlightInput = false;
            swapCommitmentTimer.reset();
            lastElytraEquippedTimeMs = System.currentTimeMillis();
            lastElytraEquipCompletedTick = ActionManager.getInstance().getClientTick() + 1;
            glideWindowTimer.reset();
            jumpPulseToggle = false;
            equipAfterWindChargeApexPending = false;
        }
        return accepted;
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        if (!isEnabled() || mc.player == null || mc.level == null || mc.screen != null) {
            jumpPulseToggle = false;
            return;
        }

        if (forceCancelFlightInput && mc.player.isFallFlying()) {
            event.setJumping(false);
            jumpPulseToggle = false;
            return;
        }

        if (shouldSendLegalGlideStartPulse()) {
            jumpPulseToggle = !jumpPulseToggle;
            event.setJumping(jumpPulseToggle);
            if (mc.player.isFallFlying()) {
                windChargeAscentDetected = false;
            }
        } else {
            jumpPulseToggle = false;
        }
    }

    private boolean shouldSendLegalGlideStartPulse() {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (flightState != CombatFlightState.STATE_1_FLIGHT) {
            return false;
        }
        if (ActionManager.getInstance().isSwapInProgress()) {
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

        if (ActionManager.getInstance().getClientTick() <= lastElytraEquipCompletedTick) {
            return false;
        }

        // Vanilla LocalPlayer.tryToStartFallFlying() requires only !onGround && !isFallFlying && vel.y < 0.0D!
        double velY = mc.player.getDeltaMovement().y;
        if (velY >= -0.02D) {
            return false;
        }

        boolean recentlyEquippedElytra = (System.currentTimeMillis() - lastElytraEquippedTimeMs) <= 900L;
        if (!recentlyEquippedElytra && !windChargeAscentDetected && getEffectiveFallDistance() < minDropDist.getValue()) {
            return false;
        }

        return recentlyEquippedElytra || !glideWindowTimer.finished(650.0D) || windChargeAscentDetected;
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
        return velY < MAX_LEGAL_GLIDE_VELOCITY_Y && effectiveFall >= minDropDist.getValue();
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
                || ActionManager.getInstance().isSwapInProgress()) {
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

        // 1. Velaris Post-Equip Protection: Never unequip within 220ms of equipping the Elytra
        //    so manual ElytraHelper swaps + Firework Rocket launches never get interrupted!
        if ((System.currentTimeMillis() - lastElytraEquippedTimeMs) < 220L) {
            return false;
        }

        Vec3 vel = mc.player.getDeltaMovement();
        // 2. Strict Descent Check: Never unequip while ascending (vel.y >= -0.08D) from Wind Burst or Rocket climb,
        //    even when the player is looking straight down at the target!
        if (vel.y >= -0.08D) {
            return false;
        }

        double effectiveFall = getEffectiveFallDistance();
        double requiredMinDrop = minDropDist.getValue(); // 1.5 blocks default
        double verticalAltitudeOverTarget = mc.player.getY() - target.getY();
        boolean hasSmashHeight = effectiveFall >= requiredMinDrop
                || verticalAltitudeOverTarget >= requiredMinDrop
                || (mc.player.isFallFlying() && vel.y < -0.22D);
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
}