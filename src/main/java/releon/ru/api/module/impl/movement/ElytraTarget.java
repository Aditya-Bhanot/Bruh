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

    // Anti-Cheat Swap Commitment & Kinetic Boost Constants
    private static final double MIN_SWAP_COMMITMENT_MS = 185.0D;
    private static final int MIN_SWAP_COMMITMENT_TICKS = 4;
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
     * Anti-Cheat Compliant 3-State Combat Lifecycle:
     * - STATE_1_FLIGHT: Active during Wind Charge kinetic ascension (velocity.y > 0.6) and glide.
     *   Sustains Elytra while ascending (velocity.y > 0.05) and only transitions to unequip once
     *   vertical momentum plateaus (velocity.y <= 0.05) and drops into a valid target smash window.
     * - STATE_2_SMASH_WINDOW: Chestplate is equipped for the descending Mace strike. Locks out any
     *   Elytra re-equip until the strike and minimum commitment cooldown complete.
     * - STATE_3_RECOVERY: Post-smash recovery state (holding Sword in Slot 1). Strictly locks out
     *   automatic mid-air Elytra re-equipping until the player either uses another Wind Charge
     *   (velocity.y > 0.6) or lands and manually jumps.
     */
    public enum CombatFlightState {
        STATE_1_FLIGHT,
        STATE_2_SMASH_WINDOW,
        STATE_3_RECOVERY
    }

    private final StopWatch swapCommitmentTimer = new StopWatch();
    private final StopWatch glideDeployTimer = new StopWatch();

    private CombatFlightState flightState = CombatFlightState.STATE_1_FLIGHT;
    private int ticksSinceLastSwap = 100;
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

    public CombatFlightState getFlightState() {
        return flightState;
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
     * Called by AutoMace when the player enters the exact strike window and needs the Elytra
     * unequipped before swinging the Mace. Respects the minimum swap commitment cooldown and
     * never unequips while ascending from a Wind Charge boost.
     */
    public boolean ensureUnequippedBeforeMaceAttack(LivingEntity target) {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        // Global Friend Whitelist Failsafe: never unequip or react to a whitelisted friend
        if (target != null && isWhitelistedFriend(target)) {
            return false;
        }
        // Sustain Gliding during Wind Charge ascension: never unequip while ascending!
        if (mc.player.getDeltaMovement().y > ASCENSION_PLATEAU_VELOCITY_Y) {
            return false;
        }
        if (!InventoryUtils.isElytraEquipped()) {
            return true;
        }
        if (!canExecuteSwapNow()) {
            return false;
        }
        if (swapHotbarArmorSameTick(false)) {
            flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
            recordSwapCommitted();
        }
        return !InventoryUtils.isElytraEquipped();
    }

    /**
     * Called by AutoMace once the Mace attack + Recovery Sword switch has completed.
     * Locks AutoElytra into STATE_3_RECOVERY so it holds the Sword (Slot 1) and does NOT
     * re-equip the Elytra until the player manually jumps or uses another Wind Charge.
     */
    public void enterState3RecoveryLock() {
        flightState = CombatFlightState.STATE_3_RECOVERY;
        recordSwapCommitted();
    }

    /**
     * Legacy compatibility hook called by AutoMace upon landing a smash.
     */
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
        ticksSinceLastSwap++;
        updateAirborneAndWindChargeTracking();

        if (mc.screen != null) {
            return;
        }

        double velY = mc.player.getDeltaMovement().y;

        // =========================================================================
        // 1. KINETIC WIND CHARGE ASCENSION (Positive Y-Velocity Trigger > 0.6)
        // =========================================================================
        if (!mc.player.onGround() && velY > WIND_CHARGE_ASCENSION_VELOCITY_Y && wasRecentlyPropelledByWindCharge()) {
            // Unlock from Recovery or Smash Window into State 1 (Flight)
            flightState = CombatFlightState.STATE_1_FLIGHT;
            if (!InventoryUtils.isElytraEquipped() && canExecuteSwapNow()) {
                if (swapHotbarArmorSameTick(true)) {
                    recordSwapCommitted();
                    glideDeployTimer.reset();
                }
            } else if (InventoryUtils.isElytraEquipped()) {
                glideDeployTimer.reset();
            }
            return;
        }

        // Sustain Gliding: while ascending (velocity.y > 0.05), keep Elytra equipped and do NOT unequip!
        if (!mc.player.onGround() && velY > ASCENSION_PLATEAU_VELOCITY_Y && InventoryUtils.isElytraEquipped()) {
            flightState = CombatFlightState.STATE_1_FLIGHT;
            return;
        }

        // =========================================================================
        // 2. GROUND & MANUAL JUMP STATE TRANSITIONS
        // =========================================================================
        if (mc.player.onGround()) {
            if (disableOnGround.getValue() && InventoryUtils.isElytraEquipped() && canExecuteSwapNow()) {
                if (swapHotbarArmorSameTick(false)) {
                    recordSwapCommitted();
                }
            }
            // Reset to State 1 on ground so next takeoff or manual jump starts cleanly
            flightState = CombatFlightState.STATE_1_FLIGHT;
            pearlEquipPending = false;
            return;
        }

        // Priority 1 Mutex Lockout: yield to AutoMace during active strike / recovery swap
        MaceTarget autoMace = MaceTarget.getInstance();
        boolean maceActive = autoMace != null && autoMace.isEnabled();
        if (maceActive && autoMace.isMidSlamSequence()) {
            return;
        }

        // Priority 2 Manual Input Lockout (eating GApple / using item)
        if (isManualItemUseActive()) {
            return;
        }

        if (!canExecuteSwapNow()) {
            return;
        }

        // On Pearl trigger (only when On Pearl is enabled)
        if (onPearl.getValue() && pearlEquipPending) {
            pearlEquipPending = false;
            if (!InventoryUtils.isElytraEquipped()) {
                if (swapHotbarArmorSameTick(true)) {
                    flightState = CombatFlightState.STATE_1_FLIGHT;
                    recordSwapCommitted();
                    glideDeployTimer.reset();
                    return;
                }
            }
        }

        // =========================================================================
        // 3. UPSTREAM FRIEND-FILTERED 20Hz TARGET RESOLUTION
        // =========================================================================
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

        // Failsafe: if cachedTarget is a friend, discard immediately
        if (cachedTarget != null && isWhitelistedFriend(cachedTarget)) {
            cachedTarget = null;
        }

        // =========================================================================
        // 4. 3-STATE ANTI-THRASHING EXECUTION MACHINE
        // =========================================================================
        switch (flightState) {
            case STATE_1_FLIGHT -> {
                if (InventoryUtils.isElytraEquipped()) {
                    // Transition: only unequip Elytra once vertical momentum plateaus (velY <= 0.05)
                    // and player transitions into a standard drop onto a valid non-friend target
                    if (velY <= ASCENSION_PLATEAU_VELOCITY_Y
                            && cachedTarget != null
                            && shouldDropToChestplateForSmash(cachedTarget)) {
                        if (swapHotbarArmorSameTick(false)) {
                            flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
                            recordSwapCommitted();
                        }
                    }
                } else if (inAir.getValue() && manualJumpTriggered) {
                    if (getEffectiveFallDistance() >= minDropDist.getValue()
                            && (cachedTarget == null || !shouldDropToChestplateForSmash(cachedTarget))) {
                        if (swapHotbarArmorSameTick(true)) {
                            manualJumpTriggered = false;
                            recordSwapCommitted();
                            glideDeployTimer.reset();
                        }
                    }
                }
            }

            case STATE_2_SMASH_WINDOW -> {
                // Chestplate is equipped for the descending smash.
                // Do NOT re-equip the Elytra on the next tick! Wait until the smash completes (entering STATE_3_RECOVERY)
                // or the player touches the ground / fires a new Wind Charge.
            }

            case STATE_3_RECOVERY -> {
                // Recovery Lock: Hold Sword (Slot 1). Do NOT attempt to equip Elytra again in mid-air
                // unless the player manually jumped from the ground (with In Air enabled) or used a Wind Charge (handled in Step 1).
                if (inAir.getValue() && manualJumpTriggered && !InventoryUtils.isElytraEquipped()) {
                    if (swapHotbarArmorSameTick(true)) {
                        flightState = CombatFlightState.STATE_1_FLIGHT;
                        manualJumpTriggered = false;
                        recordSwapCommitted();
                        glideDeployTimer.reset();
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
        // Deploy Elytra into glide mid-air during STATE_1_FLIGHT right after Wind Charge boost or equip
        if (flightState == CombatFlightState.STATE_1_FLIGHT
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

    private void updateAirborneAndWindChargeTracking() {
        if (mc.player == null) {
            return;
        }

        // Detect manual jump takeoff from ground
        boolean onGroundNow = mc.player.onGround();
        if (wasOnGroundLastTick && !onGroundNow && mc.options != null && mc.options.keyJump.isDown()) {
            manualJumpTriggered = true;
        } else if (onGroundNow) {
            manualJumpTriggered = false;
        }
        wasOnGroundLastTick = onGroundNow;

        // Track Wind Charge usage via item cooldown transition or right-click with Wind Charge in hand
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

    private boolean canExecuteSwapNow() {
        double requiredCooldownMs = Math.max(MIN_SWAP_COMMITMENT_MS, computeRandomOffsetMs());
        return ticksSinceLastSwap >= MIN_SWAP_COMMITMENT_TICKS
                && swapCommitmentTimer.finished(requiredCooldownMs);
    }

    private void recordSwapCommitted() {
        ticksSinceLastSwap = 0;
        swapCommitmentTimer.reset();
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

    /**
     * Global Upstream Friend Whitelist Check:
     * Immediately filters out any player on the FriendUtils whitelist or allied team.
     */
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

    private boolean swapHotbarArmorSameTick(boolean toElytra) {
        if (mc.player == null || mc.gameMode == null || mc.screen != null) {
            return false;
        }
        if (toElytra && InventoryUtils.isElytraEquipped()) {
            return true;
        }
        if (!toElytra && !InventoryUtils.isElytraEquipped()) {
            return true;
        }

        int armorHotbarSlot = toElytra ? findElytraInHotbarOnly() : findChestplateInHotbarOnly();
        if (armorHotbarSlot == -1) {
            return false;
        }

        int originalSlot = mc.player.getInventory().getSelectedSlot();

        if (originalSlot != armorHotbarSlot) {
            InventoryUtils.selectSlot(armorHotbarSlot);
        }

        if (clickSim.getValue() && mc.options != null) {
            KeyMapping.click(mc.options.keyUse.getDefaultKey());
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);

        // Restore original slot (e.g. Wind Charge or Sword) within the same tick
        if (originalSlot >= 0 && originalSlot <= 8 && originalSlot != armorHotbarSlot) {
            InventoryUtils.selectSlot(originalSlot);
        }

        return toElytra ? InventoryUtils.isElytraEquipped() : !InventoryUtils.isElytraEquipped();
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
        // Never unequip while ascending; require downward transition (velocity.y < -0.05)
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

        double preHitLead = macePreHitRange.getValue() + minDropDist.getValue() + totalSpeed * 1.25D;
        if (eyeToBoxDist <= preHitLead) {
            return true;
        }

        double horizontalDist = Math.hypot(mc.player.getX() - target.getX(), mc.player.getZ() - target.getZ());
        return eyeToBoxDist <= heightRange.getValue()
                && horizontalDist <= Math.max(3.8D, heightRange.getValue() * 0.55D)
                && verticalAltitudeOverTarget >= heightDiff.getValue();
    }

    private double computeRandomOffsetMs() {
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

    /**
     * Global Friend-Filtered 20Hz Target Scanner:
     * Filters out whitelisted friends BEFORE calculating distances, bounding boxes, or Y-differentials.
     */
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
            // Upstream Global Friend & AntiBot Filter BEFORE any spatial/AABB math
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
        flightState = CombatFlightState.STATE_1_FLIGHT;
        ticksSinceLastSwap = 100;
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