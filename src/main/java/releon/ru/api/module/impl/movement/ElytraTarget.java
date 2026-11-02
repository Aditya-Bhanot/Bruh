package releon.ru.api.module.impl.movement;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.PlayerTravelEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AntiBot;
import releon.ru.api.module.impl.combat.MaceTarget;
import releon.ru.api.module.impl.combat.MaceTarget.CombatStateManager;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class ElytraTarget extends Module {
    private static ElytraTarget instance;
    public static boolean shouldElytraTarget;

    /**
     * Anti-Cheat "Unfair Advantage FF" (FastFly / FallFly / FastInventory) Prevention Constants:
     * 1. Minimum 2-tick separation between Slot Select -> Right-Click Equip -> Slot Restore
     *    (prevents concurrent UpdateSelectedSlotC2SPacket + PlayerInteractItemC2SPacket BadPackets flags).
     * 2. Strict Vanilla Falling Gate before Elytra equip/deployment:
     *    - Player MUST be descending (velocity.y < -0.08D) with positive server-acknowledged fallDistance (> 0.25F).
     *    - At least 250ms (5 ticks) must elapse after any Wind Charge blast or velocity spike so the server's
     *      explosion knockback envelope completes before fall-flying begins.
     * 3. Zero manual ServerboundPlayerCommandPacket(START_FALL_FLYING) or ServerboundSetCarriedItemPacket sends:
     *    - Vanilla MultiPlayerGameMode syncs the slotbar naturally, and Vanilla LocalPlayer#aiStep() triggers
     *      tryToStartFallFlying() via a clean rising-edge jump input on the next falling tick.
     */
    private static final int MIN_TICKS_BETWEEN_SWAP_STEPS = 2;
    private static final long MIN_STEP_DELAY_MS = 100L;
    private static final long WIND_CHARGE_SETTLE_DELAY_MS = 250L;
    private static final float MIN_LEGAL_FALL_DISTANCE = 0.25F;
    private static final double MAX_LEGAL_GLIDE_VELOCITY_Y = -0.08D;
    private static final double MACE_PRIORITY_MUTEX_DISTANCE = 3.5D;

    private static final List<Item> CHESTPLATES = List.of(
            Items.NETHERITE_CHESTPLATE,
            Items.DIAMOND_CHESTPLATE,
            Items.CHAINMAIL_CHESTPLATE,
            Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE,
            Items.LEATHER_CHESTPLATE
    );

    // 1. Timing & Randomization (GUI Layout Preserved)
    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization", "Enable randomized delay variance for elytra re-equip cooldowns.", true));
    private final NumberSetting minRandomMs = register(new NumberSetting(
            "Min Random (ms)", "Minimum random delay in milliseconds.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting maxRandomMs = register(new NumberSetting(
            "Max Random (ms)", "Maximum random delay in milliseconds.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting reequipDelayMs = register(new NumberSetting(
            "Reequip Delay (ms)", "Delay in milliseconds before re-equipping the elytra after dropping.", 217.0D, 0.0D, 1000.0D, 1.0D));

    // 2. State Conditions & Integration
    private final BooleanSetting automaceSync = register(new BooleanSetting(
            "Automace Sync", "Synchronize elytra drop and re-equip timing with AutoMace via CombatStateManager.", false));
    private final BooleanSetting inAir = register(new BooleanSetting(
            "In Air", "Automatically equip elytra whenever falling outside drop range.", false));
    private final BooleanSetting onPearl = register(new BooleanSetting(
            "On Pearl", "Automatically equip elytra when falling after throwing an ender pearl.", false));
    private final BooleanSetting disableOnGround = register(new BooleanSetting(
            "Disable on Ground", "Swap to chestplate and reset flight drop state when touching the ground.", true));
    private final BooleanSetting clickSim = register(new BooleanSetting(
            "ClickSim", "Simulate client click input during legit slotbar armor swaps.", true));

    // 3. Spatial & Distance Thresholds
    private final NumberSetting heightDiff = register(new NumberSetting(
            "Height Diff", "Required vertical height difference above target to initiate chestplate drop.", 7.0D, 0.0D, 50.0D, 0.1D));
    private final NumberSetting heightRange = register(new NumberSetting(
            "Height Range", "Maximum target distance within which height drop triggers.", 8.4D, 1.0D, 30.0D, 0.1D));
    private final NumberSetting macePreHitRange = register(new NumberSetting(
            "Mace Pre-Hit Range", "Pre-impact distance threshold to unequip elytra into chestplate before a mace hit.", 2.5D, 0.5D, 10.0D, 0.1D));
    private final NumberSetting minDropDist = register(new NumberSetting(
            "Min Drop Dist", "Minimum drop distance required for chestplate drop and smash preparation.", 1.5D, 0.0D, 20.0D, 0.1D));

    /**
     * Multi-Tick Anti-Cheat Safe Slotbar Swap Sequence:
     * - STEP_1_WAIT_AFTER_SELECT: Waits >= 2 ticks (100ms) after changing selectedSlot so Vanilla sends
     *   UpdateSelectedSlotC2SPacket on its own tick before right-clicking.
     * - STEP_2_WAIT_AFTER_USE: Waits >= 2 ticks (100ms) after calling useItem before restoring the original slot.
     */
    private enum SafeSlotbarSwapStage {
        IDLE,
        STEP_1_WAIT_AFTER_SELECT,
        STEP_2_WAIT_AFTER_USE
    }

    public enum CombatFlightState {
        STATE_1_FLIGHT,
        STATE_2_SMASH_WINDOW,
        STATE_3_RECOVERY
    }

    private final StopWatch stepDelayTimer = new StopWatch();
    private final StopWatch swapCommitmentTimer = new StopWatch();
    private final StopWatch glideWindowTimer = new StopWatch();

    private SafeSlotbarSwapStage swapStage = SafeSlotbarSwapStage.IDLE;
    private CombatFlightState flightState = CombatFlightState.STATE_1_FLIGHT;
    private int targetArmorHotbarSlot = -1;
    private int savedOriginalSlot = -1;
    private int stageStartTick = -1;
    private int lastElytraEquipCompletedTick = -1;
    private boolean swappingToElytra = false;
    private boolean jumpPulseToggle = false;

    // Wind Charge & Knockback Settle Tracking (Prevents FastFly / Velocity Desync kicks)
    private long lastWindChargeOrBlastTimeMs = 0L;
    private boolean wasWindChargeOnCooldown = false;
    private boolean wasPearlOnCooldown = false;
    private boolean windChargeAscentDetected = false;
    private boolean equipAfterWindChargeApexPending = false;
    private boolean pearlEquipPending = false;
    private double prevTickVelY = 0.0D;
    private double airbornePeakY = Double.NaN;
    private int lastTargetScanTick = -1;
    private int lastProcessedMovementTick = -1;
    private LivingEntity cachedTarget = null;

    public ElytraTarget() {
        super("AutoElytra", "Anti-cheat safe Elytra controller with FastFly & BadPackets protection.", ModuleCategory.MOVEMENT);
        instance = this;
    }

    public static ElytraTarget getInstance() {
        return instance;
    }

    public static boolean isTargetingActive() {
        return instance != null && instance.isEnabled() && shouldElytraTarget;
    }

    public boolean isSwapLocked() {
        return swapStage != SafeSlotbarSwapStage.IDLE || CombatStateManager.isElytraAscentLocked();
    }

    @Override
    protected void onEnable() {
        shouldElytraTarget = true;
        resetInternalState();
    }

    @Override
    protected void onDisable() {
        shouldElytraTarget = false;
        CombatStateManager.releaseElytraLock();
        resetInternalState();
    }

    /**
     * Called by AutoMace when approaching smash range (outside the < 3.5m strike mutex):
     * Initiates a legit multi-tick slotbar swap to Chestplate.
     */
    public boolean ensureUnequippedBeforeMaceAttack(LivingEntity target) {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (target != null && isWhitelistedFriend(target)) {
            return false;
        }
        if (!InventoryUtils.isElytraEquipped()) {
            return swapStage == SafeSlotbarSwapStage.IDLE;
        }
        if (CombatStateManager.isMaceSmashLocked() || isTargetInsideMacePriorityMutex(target)) {
            return false;
        }
        if (swapStage == SafeSlotbarSwapStage.IDLE && canStartNewArmorSwap()) {
            if (startSafeSlotbarArmorSwap(false)) {
                flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
            }
        }
        return false;
    }

    public void enterState3RecoveryLock() {
        flightState = CombatFlightState.STATE_3_RECOVERY;
        swapCommitmentTimer.reset();
    }

    public void notifyMaceAttackLanded() {
        enterState3RecoveryLock();
    }

    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        lastProcessedMovementTick = mc.player.tickCount;
        runAutoElytraCycle();
    }

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            CombatStateManager.releaseElytraLock();
            resetInternalState();
            return;
        }
        if (lastProcessedMovementTick != mc.player.tickCount) {
            runAutoElytraCycle();
        }
    }

    private void runAutoElytraCycle() {
        updateWindChargeAndPhysicsTracking();

        if (mc.screen != null) {
            return;
        }

        MaceTarget autoMace = MaceTarget.getInstance();
        boolean maceActive = autoMace != null && autoMace.isEnabled();
        LivingEntity maceTarget = maceActive ? autoMace.getActiveTarget() : null;

        // Priority Mutex: Never swap armor while AutoMace is in its < 3.5m strike sequence
        if (CombatStateManager.isMaceSmashLocked()
                || (maceActive && autoMace.isMidSlamSequence())
                || isTargetInsideMacePriorityMutex(maceTarget)) {
            if (swapStage != SafeSlotbarSwapStage.IDLE) {
                abortSwapForMacePriorityMutex();
            }
            return;
        }

        // Advance active multi-tick slotbar swap stages
        if (swapStage != SafeSlotbarSwapStage.IDLE) {
            advanceSafeSlotbarSwapStateMachine();
            return;
        }

        // Ground Handling: Unequip Elytra into Chestplate when grounded
        if (mc.player.onGround()) {
            if (disableOnGround.getValue() && InventoryUtils.isElytraEquipped() && canStartNewArmorSwap()) {
                startSafeSlotbarArmorSwap(false);
            }
            flightState = CombatFlightState.STATE_1_FLIGHT;
            pearlEquipPending = false;
            equipAfterWindChargeApexPending = false;
            return;
        }

        if (!canStartNewArmorSwap()) {
            return;
        }

        // 1 & 2. Anti-Cheat Safe Wind Charge / Pearl / Airborne Elytra Equip:
        // NEVER equip or deploy the Elytra while ascending (velocity.y >= 0) or while fallDistance <= 0.25F
        // or during the initial Wind Charge blast knockback ticks!
        // Wait until the player passes the jump/blast apex and is in a legal Vanilla falling state.
        if (equipAfterWindChargeApexPending && isLegalVanillaFallFlyingState()) {
            equipAfterWindChargeApexPending = false;
            flightState = CombatFlightState.STATE_1_FLIGHT;
            if (!InventoryUtils.isElytraEquipped()) {
                if (startSafeSlotbarArmorSwap(true)) {
                    return;
                }
            } else {
                glideWindowTimer.reset();
                return;
            }
        }

        if (onPearl.getValue() && pearlEquipPending && isLegalVanillaFallFlyingState()) {
            pearlEquipPending = false;
            if (!InventoryUtils.isElytraEquipped()) {
                if (startSafeSlotbarArmorSwap(true)) {
                    flightState = CombatFlightState.STATE_1_FLIGHT;
                    return;
                }
            }
        }

        // Target scan for pre-smash Chestplate drop
        if (automaceSync.getValue() && !maceActive) {
            return;
        }
        if (maceTarget != null && maceTarget.isAlive() && !isWhitelistedFriend(maceTarget)) {
            cachedTarget = maceTarget;
        } else if (lastTargetScanTick != mc.player.tickCount) {
            lastTargetScanTick = mc.player.tickCount;
            cachedTarget = findCombatTargetZeroAlloc();
        }

        if (cachedTarget != null && isWhitelistedFriend(cachedTarget)) {
            cachedTarget = null;
        }

        if (isTargetInsideMacePriorityMutex(cachedTarget)) {
            return;
        }

        switch (flightState) {
            case STATE_1_FLIGHT -> {
                if (InventoryUtils.isElytraEquipped()) {
                    if (mc.player.getDeltaMovement().y < 0.0D
                            && cachedTarget != null
                            && shouldDropToChestplateForSmash(cachedTarget)) {
                        if (startSafeSlotbarArmorSwap(false)) {
                            flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
                        }
                    }
                } else if (inAir.getValue()
                        && isLegalVanillaFallFlyingState()
                        && getEffectiveFallDistance() >= Math.max(minDropDist.getValue(), MIN_LEGAL_FALL_DISTANCE)) {
                    if (cachedTarget == null || !shouldDropToChestplateForSmash(cachedTarget)) {
                        startSafeSlotbarArmorSwap(true);
                    }
                }
            }

            case STATE_2_SMASH_WINDOW -> {
                // Hold Chestplate during Mace Smash Window
            }

            case STATE_3_RECOVERY -> {
                // Re-equip only after a new Wind Charge / jump reaches apex and enters legal falling state
            }
        }
    }

    /**
     * Tracks Wind Charge blasts and upward velocity spikes WITHOUT equipping during the blast tick:
     * Marks equipAfterWindChargeApexPending = true when a Wind Charge upward blast occurs, and waits until
     * the player transitions past the apex into a legal falling state (velocity.y < -0.08, fallDistance > 0.25)
     * before equipping or deploying the Elytra.
     */
    private void updateWindChargeAndPhysicsTracking() {
        if (mc.player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean windCooling = mc.player.getCooldowns().isOnCooldown(Items.WIND_CHARGE.getDefaultInstance());
        if (windCooling && !wasWindChargeOnCooldown) {
            lastWindChargeOrBlastTimeMs = now;
            windChargeAscentDetected = true;
            equipAfterWindChargeApexPending = true;
        }
        wasWindChargeOnCooldown = windCooling;

        boolean pearlCooling = mc.player.getCooldowns().isOnCooldown(Items.ENDER_PEARL.getDefaultInstance());
        if (pearlCooling && !wasPearlOnCooldown && onPearl.getValue()) {
            pearlEquipPending = true;
        }
        wasPearlOnCooldown = pearlCooling;

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
            // Detect upward blast impulse (velocity.y spike > 0.42) and reset settle timer so we never
            // send inventory or flight packets on the blast tick itself
            if (velY > 0.42D && (velY - prevTickVelY) > 0.25D) {
                lastWindChargeOrBlastTimeMs = now;
                windChargeAscentDetected = true;
                equipAfterWindChargeApexPending = true;
            }
        }

        prevTickVelY = velY;
    }

    /**
     * Verifies all Vanilla Minecraft & Anti-Cheat (Grim / Vulcan / Polar / NCP) preconditions for fall-flying:
     * 1. Player is airborne, not in water/lava, and does not have Levitation.
     * 2. Player has negative Y-velocity (velocity.y < -0.08D) — strictly falling, NEVER ascending.
     * 3. Player has positive server-recognized fallDistance (fallDistance > 0.25F).
     * 4. Player is not in knockback frames (hurtTime == 0) and at least 250ms (5 ticks) have elapsed
     *    since the Wind Charge blast impulse so client and server velocity envelopes are 100% synchronized.
     */
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
        return System.currentTimeMillis() - lastWindChargeOrBlastTimeMs >= WIND_CHARGE_SETTLE_DELAY_MS;
    }

    /**
     * Step 1 of Safe Slotbar Swap:
     * Physically selects the Elytra/Chestplate hotbar slot on the client without dispatching raw packets,
     * allowing Vanilla's MultiPlayerGameMode to send the single UpdateSelectedSlotC2SPacket naturally.
     */
    private boolean startSafeSlotbarArmorSwap(boolean toElytra) {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (CombatStateManager.isMaceSmashLocked()) {
            return false;
        }

        int armorSlot = toElytra ? findElytraInHotbarOnly() : findChestplateInHotbarOnly();
        if (armorSlot == -1) {
            return false;
        }

        if (!CombatStateManager.tryAcquireElytraAscentLock()) {
            return false;
        }

        savedOriginalSlot = mc.player.getInventory().getSelectedSlot();
        targetArmorHotbarSlot = armorSlot;
        swappingToElytra = toElytra;
        stageStartTick = mc.player.tickCount;

        mc.player.getInventory().setSelectedSlot(targetArmorHotbarSlot);
        stepDelayTimer.reset();
        swapStage = SafeSlotbarSwapStage.STEP_1_WAIT_AFTER_SELECT;
        return true;
    }

    /**
     * Advances Step 2 (Right-Click Equip after >= 2 ticks) and Step 3 (Restore Slotbar after >= 2 ticks):
     * Never sends UpdateSelectedSlotC2SPacket and PlayerInteractItemC2SPacket in the same tick.
     */
    private void advanceSafeSlotbarSwapStateMachine() {
        if (mc.player == null || mc.gameMode == null) {
            CombatStateManager.releaseElytraLock();
            swapStage = SafeSlotbarSwapStage.IDLE;
            return;
        }

        if (mc.player.swinging || mc.player.attackAnim > 0.0F || mc.player.hurtTime > 0) {
            return;
        }

        switch (swapStage) {
            case STEP_1_WAIT_AFTER_SELECT -> {
                // Enforce minimum 2-tick (100ms) separation between selecting the slot and right-clicking
                if (mc.player.tickCount - stageStartTick < MIN_TICKS_BETWEEN_SWAP_STEPS
                        || !stepDelayTimer.finished(MIN_STEP_DELAY_MS)) {
                    return;
                }

                if (targetArmorHotbarSlot >= 0 && targetArmorHotbarSlot <= 8) {
                    if (mc.player.getInventory().getSelectedSlot() != targetArmorHotbarSlot) {
                        mc.player.getInventory().setSelectedSlot(targetArmorHotbarSlot);
                        stageStartTick = mc.player.tickCount;
                        stepDelayTimer.reset();
                        return;
                    }

                    if (clickSim.getValue() && mc.options != null) {
                        KeyMapping.click(mc.options.keyUse.getDefaultKey());
                    }
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }

                stageStartTick = mc.player.tickCount;
                stepDelayTimer.reset();
                swapStage = SafeSlotbarSwapStage.STEP_2_WAIT_AFTER_USE;
            }

            case STEP_2_WAIT_AFTER_USE -> {
                // Enforce minimum 2-tick (100ms) separation after right-clicking before restoring the original slot
                if (mc.player.tickCount - stageStartTick < MIN_TICKS_BETWEEN_SWAP_STEPS
                        || !stepDelayTimer.finished(MIN_STEP_DELAY_MS)) {
                    return;
                }

                if (savedOriginalSlot >= 0 && savedOriginalSlot <= 8
                        && mc.player.getInventory().getSelectedSlot() != savedOriginalSlot) {
                    mc.player.getInventory().setSelectedSlot(savedOriginalSlot);
                }

                if (swappingToElytra) {
                    lastElytraEquipCompletedTick = mc.player.tickCount;
                    glideWindowTimer.reset();
                    jumpPulseToggle = false;
                }

                savedOriginalSlot = -1;
                targetArmorHotbarSlot = -1;
                stageStartTick = -1;
                swapCommitmentTimer.reset();
                swapStage = SafeSlotbarSwapStage.IDLE;
                CombatStateManager.releaseElytraLock();
            }

            default -> {
                swapStage = SafeSlotbarSwapStage.IDLE;
                CombatStateManager.releaseElytraLock();
            }
        }
    }

    private void abortSwapForMacePriorityMutex() {
        if (mc.player != null && savedOriginalSlot >= 0 && savedOriginalSlot <= 8
                && mc.player.getInventory().getSelectedSlot() != savedOriginalSlot) {
            mc.player.getInventory().setSelectedSlot(savedOriginalSlot);
        }
        savedOriginalSlot = -1;
        targetArmorHotbarSlot = -1;
        stageStartTick = -1;
        swapStage = SafeSlotbarSwapStage.IDLE;
        CombatStateManager.releaseElytraLock();
    }

    /**
     * Native Vanilla Fall-Flying Initiation (Zero Raw ServerboundPlayerCommandPacket):
     * Only pulses a clean rising-edge jump input inside InputEvent when:
     * 1. The swap state machine is completely IDLE and at least 1 full tick has elapsed since equipping the Elytra.
     * 2. isLegalVanillaFallFlyingState() is true (velocity.y < -0.08D, fallDistance > 0.25F, post-blast settled).
     * This causes Vanilla LocalPlayer#aiStep() to execute tryToStartFallFlying() natively at the exact
     * Vanilla tick phase, keeping client and server physics 100% synchronized.
     */
    @SubscribeEvent
    private void onInput(InputEvent event) {
        if (mc.player == null || mc.screen != null) {
            jumpPulseToggle = false;
            return;
        }

        if (flightState == CombatFlightState.STATE_1_FLIGHT
                && swapStage == SafeSlotbarSwapStage.IDLE
                && InventoryUtils.isElytraEquipped()
                && InventoryUtils.isElytraUsable()
                && mc.player.tickCount > lastElytraEquipCompletedTick
                && isLegalVanillaFallFlyingState()
                && (!glideWindowTimer.finished(450.0D) || windChargeAscentDetected)) {
            // Alternate false -> true so LocalPlayer#aiStep sees a clean rising jump edge (!wasJumping && jumping)
            jumpPulseToggle = !jumpPulseToggle;
            event.setJumping(jumpPulseToggle);
            if (mc.player.isFallFlying()) {
                windChargeAscentDetected = false;
            }
        } else {
            jumpPulseToggle = false;
        }
    }

    private boolean isTargetInsideMacePriorityMutex(LivingEntity target) {
        if (mc.player == null || target == null || !target.isAlive()) {
            return false;
        }
        if (mc.player.onGround() || mc.player.getDeltaMovement().y >= 0.0D) {
            return false;
        }
        double eyeX = mc.player.getX();
        double eyeY = mc.player.getEyeY();
        double eyeZ = mc.player.getZ();
        AABB box = target.getBoundingBox();
        double dx = eyeX - Mth.clamp(eyeX, box.minX, box.maxX);
        double dy = eyeY - Mth.clamp(eyeY, box.minY, box.maxY);
        double dz = eyeZ - Mth.clamp(eyeZ, box.minZ, box.maxZ);
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dist < MACE_PRIORITY_MUTEX_DISTANCE;
    }

    private boolean canStartNewArmorSwap() {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (CombatStateManager.isMaceSmashLocked()) {
            return false;
        }
        if (mc.player.swinging || mc.player.attackAnim > 0.0F || mc.player.hurtTime > 0) {
            return false;
        }
        if (isManualItemUseActive()) {
            return false;
        }
        if (System.currentTimeMillis() - lastWindChargeOrBlastTimeMs < WIND_CHARGE_SETTLE_DELAY_MS) {
            return false;
        }
        double minCooldown = Math.max(180.0D, reequipDelayMs.getValue());
        return swapCommitmentTimer.finished(minCooldown + computeUserRandomOffsetMs());
    }

    private boolean isManualItemUseActive() {
        if (mc.player == null || mc.options == null) {
            return false;
        }
        if (mc.player.isUsingItem()) {
            return true;
        }
        if (!mc.options.keyUse.isDown()) {
            return false;
        }
        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();
        return mainHand.has(DataComponents.FOOD) || offHand.has(DataComponents.FOOD)
                || mainHand.getItem() == Items.SHIELD || offHand.getItem() == Items.SHIELD
                || mainHand.getItem() == Items.WIND_CHARGE || offHand.getItem() == Items.WIND_CHARGE;
    }

    public static boolean isWhitelistedFriend(Entity entity) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        if (FriendUtils.isFriend(player.getName().getString())) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.isAlliedTo(player);
    }

    private int findElytraInHotbarOnly() {
        if (mc.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.ELYTRA) {
                return i;
            }
        }
        return -1;
    }

    private int findChestplateInHotbarOnly() {
        if (mc.player == null) {
            return -1;
        }
        for (Item chestItem : CHESTPLATES) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getItem() == chestItem) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean shouldDropToChestplateForSmash(LivingEntity target) {
        if (mc.player == null || target == null || isWhitelistedFriend(target)) {
            return false;
        }
        if (findChestplateInHotbarOnly() == -1 || !hasLineOfSight(target)) {
            return false;
        }

        Vec3 playerVel = mc.player.getDeltaMovement();
        if (playerVel.y >= -0.05D) {
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

        if (eyeToBoxDist < MACE_PRIORITY_MUTEX_DISTANCE) {
            return false;
        }

        double verticalAltitudeOverTarget = mc.player.getY() - target.getY();
        double descentSpeed = Math.max(0.05D, -playerVel.y);
        double totalSpeed = Math.sqrt(playerVel.x * playerVel.x + playerVel.z * playerVel.z + descentSpeed * descentSpeed);

        boolean hasSmashHeight = getEffectiveFallDistance() >= minDropDist.getValue()
                || verticalAltitudeOverTarget >= minDropDist.getValue();
        if (!hasSmashHeight || verticalAltitudeOverTarget < 0.35D) {
            return false;
        }

        // Lead by ~4.5 ticks so the multi-tick safe swap finishes before the < 3.5m Priority Mutex
        double preHitLead = Math.max(MACE_PRIORITY_MUTEX_DISTANCE + 1.0D, macePreHitRange.getValue() + minDropDist.getValue() + totalSpeed * 4.5D);
        if (eyeToBoxDist <= preHitLead) {
            return true;
        }

        double horizontalDist = Math.hypot(mc.player.getX() - target.getX(), mc.player.getZ() - target.getZ());
        return eyeToBoxDist <= heightRange.getValue()
                && horizontalDist <= Math.max(3.8D, heightRange.getValue() * 0.55D)
                && verticalAltitudeOverTarget >= heightDiff.getValue();
    }

    private double computeUserRandomOffsetMs() {
        if (!randomization.getValue()) {
            return 0.0D;
        }
        double minRand = Math.min(minRandomMs.getValue(), maxRandomMs.getValue());
        double maxRand = Math.max(minRandomMs.getValue(), maxRandomMs.getValue());
        if (maxRand <= 0.0D || Double.compare(minRand, maxRand) == 0) {
            return minRand;
        }
        return ThreadLocalRandom.current().nextDouble(minRand, maxRand);
    }

    private boolean hasLineOfSight(LivingEntity target) {
        if (mc.player == null || mc.level == null || target == null) {
            return false;
        }
        if (mc.player.hasLineOfSight(target)) {
            return true;
        }
        Vec3 eyePos = mc.player.getEyePosition(1.0F);
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eyePos,
                targetCenter,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                mc.player
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    private LivingEntity findCombatTargetZeroAlloc() {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        double maxSearchRange = Math.max(heightRange.getValue(), macePreHitRange.getValue()) + 6.0D;
        double maxRangeSqr = maxSearchRange * maxSearchRange;
        double eyeX = mc.player.getX();
        double eyeY = mc.player.getEyeY();
        double eyeZ = mc.player.getZ();

        LivingEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living == mc.player || !living.isAlive() || living.getHealth() <= 0.0F) {
                continue;
            }
            if (living instanceof ArmorStand) {
                continue;
            }
            if (living instanceof Player player) {
                if (AntiBot.shouldIgnore(player) || isWhitelistedFriend(player)) {
                    continue;
                }
            } else if (!(living instanceof Mob) && !(living instanceof Animal)) {
                continue;
            }

            AABB box = living.getBoundingBox();
            double dx = eyeX - Mth.clamp(eyeX, box.minX, box.maxX);
            double dy = eyeY - Mth.clamp(eyeY, box.minY, box.maxY);
            double dz = eyeZ - Mth.clamp(eyeZ, box.minZ, box.maxZ);
            double distSqr = dx * dx + dy * dy + dz * dz;

            if (distSqr > maxRangeSqr || distSqr >= bestDistSqr) {
                continue;
            }
            if (!hasLineOfSight(living)) {
                continue;
            }

            bestDistSqr = distSqr;
            best = living;
        }
        return best;
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
        swapStage = SafeSlotbarSwapStage.IDLE;
        flightState = CombatFlightState.STATE_1_FLIGHT;
        targetArmorHotbarSlot = -1;
        savedOriginalSlot = -1;
        stageStartTick = -1;
        lastElytraEquipCompletedTick = -1;
        swappingToElytra = false;
        jumpPulseToggle = false;
        lastWindChargeOrBlastTimeMs = 0L;
        wasWindChargeOnCooldown = false;
        wasPearlOnCooldown = false;
        windChargeAscentDetected = false;
        equipAfterWindChargeApexPending = false;
        pearlEquipPending = false;
        prevTickVelY = 0.0D;
        airbornePeakY = Double.NaN;
        lastTargetScanTick = -1;
        lastProcessedMovementTick = -1;
        cachedTarget = null;
    }

    public float getRange() {
        return heightRange.getFloat();
    }

    public float getForward() {
        return macePreHitRange.getFloat();
    }
}