package releon.ru.api.module.impl.movement;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
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
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.PlayerTravelEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AntiBot;
import releon.ru.api.module.impl.combat.MaceTarget;
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

    // Humanized Legit Timing Constants (Zero 1-tick / 50ms swaps, 8-14 CPS pacing)
    private static final double MIN_PRE_CLICK_DELAY_MS = 150.0D;
    private static final double MAX_PRE_CLICK_DELAY_MS = 260.0D;
    private static final double MIN_POST_CLICK_RESTORE_MS = 250.0D;
    private static final double MAX_POST_CLICK_RESTORE_MS = 380.0D;
    private static final double MIN_CPS = 8.0D;
    private static final double MAX_CPS = 14.0D;
    private static final double WIND_CHARGE_ASCENSION_VELOCITY_Y = 0.6D;
    private static final double ASCENSION_PLATEAU_VELOCITY_Y = 0.05D;
    private static final long WIND_CHARGE_PROPULSION_WINDOW_MS = 1600L;

    private static final List<Item> CHESTPLATES = List.of(
            Items.NETHERITE_CHESTPLATE,
            Items.DIAMOND_CHESTPLATE,
            Items.CHAINMAIL_CHESTPLATE,
            Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE,
            Items.LEATHER_CHESTPLATE
    );

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
            "Automace Sync", "Synchronize elytra drop and re-equip timing with AutoMace.", false));
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

    /**
     * Multi-Tick Humanized Hotbar Swap Sequence (Zero Packet Spoofing):
     * - Physically selects the hotbar slot on client (0-8).
     * - Waits a randomized 150ms-260ms (3 to 5 ticks) before right-clicking to equip.
     * - Waits a randomized 250ms-380ms (5 to 8 ticks) before restoring the original weapon slot.
     * - Strictly pauses if the player is swinging a weapon or taking knockback (hurtTime > 0).
     */
    private enum LegitHotbarSwapPhase {
        IDLE,
        WAIT_BEFORE_RIGHT_CLICK,
        WAIT_BEFORE_RESTORE_SLOT
    }

    public enum CombatFlightState {
        STATE_1_FLIGHT,
        STATE_2_SMASH_WINDOW,
        STATE_3_RECOVERY
    }

    private final StopWatch swapPhaseTimer = new StopWatch();
    private final StopWatch swapCommitmentTimer = new StopWatch();
    private final StopWatch cpsLimiterTimer = new StopWatch();
    private final StopWatch glideDeployTimer = new StopWatch();

    private LegitHotbarSwapPhase swapPhase = LegitHotbarSwapPhase.IDLE;
    private CombatFlightState flightState = CombatFlightState.STATE_1_FLIGHT;
    private int targetArmorHotbarSlot = -1;
    private int savedOriginalSlot = -1;
    private boolean swappingToElytra = false;
    private double currentPhaseDelayMs = 180.0D;
    private double currentCpsIntervalMs = 95.0D;

    private long lastWindChargeTimeMs = 0L;
    private boolean wasWindChargeOnCooldown = false;
    private boolean wasOnGroundLastTick = true;
    private boolean manualJumpTriggered = false;
    private boolean pearlEquipPending = false;
    private double airbornePeakY = Double.NaN;
    private int lastTargetScanTick = -1;
    private int lastProcessedMovementTick = -1;
    private LivingEntity cachedTarget = null;

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

    public boolean isSwapLocked() {
        return swapPhase != LegitHotbarSwapPhase.IDLE;
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
     * Called by AutoMace when approaching the smash window:
     * Initiates the humanized multi-tick hotbar swap to Chestplate if not already started.
     * Returns true only after the Chestplate is equipped AND the humanized slot restore has finished.
     */
    public boolean ensureUnequippedBeforeMaceAttack(LivingEntity target) {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (target != null && isWhitelistedFriend(target)) {
            return false;
        }
        if (mc.player.getDeltaMovement().y > ASCENSION_PLATEAU_VELOCITY_Y) {
            return false;
        }
        if (!InventoryUtils.isElytraEquipped()) {
            return swapPhase == LegitHotbarSwapPhase.IDLE;
        }
        if (swapPhase == LegitHotbarSwapPhase.IDLE && canStartNewArmorAction()) {
            if (initiateHumanizedHotbarArmorSwap(false)) {
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
            resetInternalState();
            return;
        }
        if (lastProcessedMovementTick != mc.player.tickCount) {
            runAutoElytraCycle();
        }
    }

    private void runAutoElytraCycle() {
        updateAirborneAndWindChargeTracking();

        if (mc.screen != null) {
            return;
        }

        // State Machine Lock: if a humanized swap sequence is in progress, advance it to completion first
        if (swapPhase != LegitHotbarSwapPhase.IDLE) {
            processHumanizedSwapStateMachine();
            return;
        }

        // Wait if AutoMace is locked in its own smash or swap-back sequence
        MaceTarget autoMace = MaceTarget.getInstance();
        boolean maceActive = autoMace != null && autoMace.isEnabled();
        if (maceActive && autoMace.isMidSlamSequence()) {
            return;
        }

        // Never execute an armor swap while swinging a weapon, taking knockback, or eating/using an item
        if (!canStartNewArmorAction()) {
            return;
        }

        double velY = mc.player.getDeltaMovement().y;

        // 1. Kinetic Wind Charge Ascension (Positive Y-Velocity > 0.6 after Wind Charge)
        if (!mc.player.onGround() && velY > WIND_CHARGE_ASCENSION_VELOCITY_Y && wasRecentlyPropelledByWindCharge()) {
            flightState = CombatFlightState.STATE_1_FLIGHT;
            if (!InventoryUtils.isElytraEquipped()) {
                initiateHumanizedHotbarArmorSwap(true);
            } else {
                glideDeployTimer.reset();
            }
            return;
        }

        // Sustain Gliding while ascending (velY > 0.05)
        if (!mc.player.onGround() && velY > ASCENSION_PLATEAU_VELOCITY_Y && InventoryUtils.isElytraEquipped()) {
            flightState = CombatFlightState.STATE_1_FLIGHT;
            return;
        }

        // 2. Ground handling
        if (mc.player.onGround()) {
            if (disableOnGround.getValue() && InventoryUtils.isElytraEquipped()) {
                initiateHumanizedHotbarArmorSwap(false);
            }
            flightState = CombatFlightState.STATE_1_FLIGHT;
            pearlEquipPending = false;
            return;
        }

        // 3. On Pearl (only when enabled)
        if (onPearl.getValue() && pearlEquipPending) {
            pearlEquipPending = false;
            if (!InventoryUtils.isElytraEquipped()) {
                if (initiateHumanizedHotbarArmorSwap(true)) {
                    flightState = CombatFlightState.STATE_1_FLIGHT;
                    return;
                }
            }
        }

        // 4. Upstream Friend-Filtered 20Hz Target Scan
        if (automaceSync.getValue() && !maceActive) {
            return;
        }
        if (maceActive && autoMace.getActiveTarget() != null && autoMace.getActiveTarget().isAlive()
                && !isWhitelistedFriend(autoMace.getActiveTarget())) {
            cachedTarget = autoMace.getActiveTarget();
        } else if (lastTargetScanTick != mc.player.tickCount) {
            lastTargetScanTick = mc.player.tickCount;
            cachedTarget = findCombatTargetZeroAlloc();
        }

        if (cachedTarget != null && isWhitelistedFriend(cachedTarget)) {
            cachedTarget = null;
        }

        // 5. 3-State Execution
        switch (flightState) {
            case STATE_1_FLIGHT -> {
                if (InventoryUtils.isElytraEquipped()) {
                    if (velY <= ASCENSION_PLATEAU_VELOCITY_Y
                            && cachedTarget != null
                            && shouldDropToChestplateForSmash(cachedTarget)) {
                        if (initiateHumanizedHotbarArmorSwap(false)) {
                            flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
                        }
                    }
                } else if (inAir.getValue() && manualJumpTriggered) {
                    if (getEffectiveFallDistance() >= minDropDist.getValue()
                            && (cachedTarget == null || !shouldDropToChestplateForSmash(cachedTarget))) {
                        if (initiateHumanizedHotbarArmorSwap(true)) {
                            manualJumpTriggered = false;
                        }
                    }
                }
            }

            case STATE_2_SMASH_WINDOW -> {
                // Hold Chestplate equipped until smash completes and enters STATE_3_RECOVERY
            }

            case STATE_3_RECOVERY -> {
                // Hold Sword (Slot 1). Only re-equip Elytra if player manually jumped with In Air enabled
                // or used another Wind Charge (handled in Step 1).
                if (inAir.getValue() && manualJumpTriggered && !InventoryUtils.isElytraEquipped()) {
                    if (initiateHumanizedHotbarArmorSwap(true)) {
                        flightState = CombatFlightState.STATE_1_FLIGHT;
                        manualJumpTriggered = false;
                    }
                }
            }
        }
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        if (mc.player == null || mc.screen != null) {
            return;
        }
        if (flightState == CombatFlightState.STATE_1_FLIGHT
                && swapPhase == LegitHotbarSwapPhase.IDLE
                && InventoryUtils.isElytraEquipped()
                && InventoryUtils.isElytraUsable()
                && !mc.player.onGround()
                && !mc.player.isFallFlying()
                && !mc.player.isInWater()
                && !mc.player.isInLava()
                && (!glideDeployTimer.finished(450.0D) || mc.player.getDeltaMovement().y > WIND_CHARGE_ASCENSION_VELOCITY_Y)) {
            event.setJumping(mc.player.tickCount % 2 == 0);
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isSend() || mc.player == null) {
            return;
        }
        if (event.getPacket() instanceof ServerboundUseItemPacket usePacket) {
            InteractionHand hand = usePacket.getHand();
            ItemStack usedStack = mc.player.getItemInHand(hand);
            if (!usedStack.isEmpty()) {
                if (usedStack.getItem() == Items.WIND_CHARGE) {
                    lastWindChargeTimeMs = System.currentTimeMillis();
                } else if (onPearl.getValue() && usedStack.getItem() == Items.ENDER_PEARL) {
                    pearlEquipPending = true;
                }
            }
        }
    }

    /**
     * Anti-Cheat Armor Swapping Guard:
     * Never execute an armor swap while actively swinging a weapon (swinging / attackAnim > 0),
     * taking knockback (hurtTime > 0), or using food/shields, and enforce the minimum swap cooldown.
     */
    private boolean canStartNewArmorAction() {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (mc.player.swinging || mc.player.attackAnim > 0.0F) {
            return false;
        }
        if (mc.player.hurtTime > 0) {
            return false;
        }
        if (isManualItemUseActive()) {
            return false;
        }
        double minCooldown = Math.max(reequipDelayMs.getValue(), MIN_POST_CLICK_RESTORE_MS);
        return swapCommitmentTimer.finished(minCooldown + computeUserRandomOffsetMs());
    }

    private boolean initiateHumanizedHotbarArmorSwap(boolean toElytra) {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        int armorSlot = toElytra ? findElytraInHotbarOnly() : findChestplateInHotbarOnly();
        if (armorSlot == -1) {
            return false;
        }

        savedOriginalSlot = mc.player.getInventory().getSelectedSlot();
        targetArmorHotbarSlot = armorSlot;
        swappingToElytra = toElytra;

        // Step 1: Physically move the client hotbar selector (0-8) and render it client-side
        mc.player.getInventory().setSelectedSlot(targetArmorHotbarSlot);

        // Wait a randomized 150ms - 260ms (3 to 5 ticks) before right-clicking
        currentPhaseDelayMs = ThreadLocalRandom.current().nextDouble(MIN_PRE_CLICK_DELAY_MS, MAX_PRE_CLICK_DELAY_MS)
                + computeUserRandomOffsetMs();
        swapPhaseTimer.reset();
        swapPhase = LegitHotbarSwapPhase.WAIT_BEFORE_RIGHT_CLICK;
        return true;
    }

    private void processHumanizedSwapStateMachine() {
        if (mc.player == null || mc.gameMode == null) {
            swapPhase = LegitHotbarSwapPhase.IDLE;
            return;
        }

        // Pause progression if taking knockback or swinging a weapon to prevent concurrent action flags
        if (mc.player.hurtTime > 0 || mc.player.swinging || mc.player.attackAnim > 0.0F) {
            return;
        }

        if (!swapPhaseTimer.finished(currentPhaseDelayMs)) {
            return;
        }

        switch (swapPhase) {
            case WAIT_BEFORE_RIGHT_CLICK -> {
                // Respect 8-14 CPS interaction pacing limit
                if (!cpsLimiterTimer.finished(currentCpsIntervalMs)) {
                    return;
                }
                if (targetArmorHotbarSlot < 0 || targetArmorHotbarSlot > 8) {
                    swapPhase = LegitHotbarSwapPhase.IDLE;
                    return;
                }
                if (mc.player.getInventory().getSelectedSlot() != targetArmorHotbarSlot) {
                    mc.player.getInventory().setSelectedSlot(targetArmorHotbarSlot);
                }

                if (clickSim.getValue() && mc.options != null) {
                    KeyMapping.click(mc.options.keyUse.getDefaultKey());
                }
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                mc.player.swing(InteractionHand.MAIN_HAND);

                currentCpsIntervalMs = 1000.0D / ThreadLocalRandom.current().nextDouble(MIN_CPS, MAX_CPS);
                cpsLimiterTimer.reset();

                // Wait a randomized 250ms - 380ms (5 to 8 ticks) before restoring the original slot
                currentPhaseDelayMs = ThreadLocalRandom.current().nextDouble(MIN_POST_CLICK_RESTORE_MS, MAX_POST_CLICK_RESTORE_MS)
                        + computeUserRandomOffsetMs();
                swapPhaseTimer.reset();
                swapPhase = LegitHotbarSwapPhase.WAIT_BEFORE_RESTORE_SLOT;
            }

            case WAIT_BEFORE_RESTORE_SLOT -> {
                if (savedOriginalSlot >= 0 && savedOriginalSlot <= 8
                        && mc.player.getInventory().getSelectedSlot() != savedOriginalSlot) {
                    mc.player.getInventory().setSelectedSlot(savedOriginalSlot);
                }
                if (swappingToElytra) {
                    glideDeployTimer.reset();
                }
                savedOriginalSlot = -1;
                targetArmorHotbarSlot = -1;
                swapCommitmentTimer.reset();
                swapPhase = LegitHotbarSwapPhase.IDLE;
            }

            default -> swapPhase = LegitHotbarSwapPhase.IDLE;
        }
    }

    private void updateAirborneAndWindChargeTracking() {
        if (mc.player == null) {
            return;
        }

        boolean onGroundNow = mc.player.onGround();
        if (wasOnGroundLastTick && !onGroundNow && mc.options != null && mc.options.keyJump.isDown()) {
            manualJumpTriggered = true;
        } else if (onGroundNow) {
            manualJumpTriggered = false;
        }
        wasOnGroundLastTick = onGroundNow;

        boolean windChargeCoolingDown = mc.player.getCooldowns().isOnCooldown(Items.WIND_CHARGE.getDefaultInstance());
        if (windChargeCoolingDown && !wasWindChargeOnCooldown) {
            lastWindChargeTimeMs = System.currentTimeMillis();
        }
        wasWindChargeOnCooldown = windChargeCoolingDown;

        if (mc.options != null && mc.options.keyUse.isDown() && isHoldingWindCharge()) {
            lastWindChargeTimeMs = System.currentTimeMillis();
        }

        if (onGroundNow || mc.player.isInWater() || mc.player.isInLava()) {
            airbornePeakY = mc.player.getY();
        } else {
            double y = mc.player.getY();
            if (Double.isNaN(airbornePeakY) || y > airbornePeakY) {
                airbornePeakY = y;
            }
        }
    }

    private boolean wasRecentlyPropelledByWindCharge() {
        if (System.currentTimeMillis() - lastWindChargeTimeMs <= WIND_CHARGE_PROPULSION_WINDOW_MS) {
            return true;
        }
        if (mc.player != null && mc.player.getCooldowns().isOnCooldown(Items.WIND_CHARGE.getDefaultInstance())) {
            return true;
        }
        return isHoldingWindCharge();
    }

    private boolean isHoldingWindCharge() {
        if (mc.player == null) {
            return false;
        }
        return mc.player.getMainHandItem().getItem() == Items.WIND_CHARGE
                || mc.player.getOffhandItem().getItem() == Items.WIND_CHARGE;
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
                || mainHand.getItem() == Items.SHIELD || offHand.getItem() == Items.SHIELD;
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

        double verticalAltitudeOverTarget = mc.player.getY() - target.getY();
        double descentSpeed = Math.max(0.05D, -playerVel.y);
        double totalSpeed = Math.sqrt(playerVel.x * playerVel.x + playerVel.z * playerVel.z + descentSpeed * descentSpeed);

        boolean hasSmashHeight = getEffectiveFallDistance() >= minDropDist.getValue()
                || verticalAltitudeOverTarget >= minDropDist.getValue();
        if (!hasSmashHeight || verticalAltitudeOverTarget < 0.35D) {
            return false;
        }

        // Lead by ~5 ticks to allow the humanized 150-260ms swap to complete before reaching strike range
        double preHitLead = macePreHitRange.getValue() + minDropDist.getValue() + totalSpeed * 4.5D;
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
        swapPhase = LegitHotbarSwapPhase.IDLE;
        flightState = CombatFlightState.STATE_1_FLIGHT;
        targetArmorHotbarSlot = -1;
        savedOriginalSlot = -1;
        swappingToElytra = false;
        currentPhaseDelayMs = 180.0D;
        lastWindChargeTimeMs = 0L;
        wasWindChargeOnCooldown = false;
        wasOnGroundLastTick = true;
        manualJumpTriggered = false;
        pearlEquipPending = false;
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