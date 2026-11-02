package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
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
import releon.ru.api.module.impl.combat.MaceTarget.CombatStateManager;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.manager.ActionManager;
import releon.ru.manager.ActionManager.ActionPriority;
import releon.ru.utils.inventory.lookup.InventoryUtils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Split-Priority AutoElytra Controller:
 * - Priority 1 (Highest): AUTO_ELYTRA_UNEQUIP (Flight Cancel / Chestplate Swap)
 *     Trigger: When target distance < 4.5m and v_y < -0.1 while wearing an Elytra.
 *     Action: Overrides the AutoMace lock, immediately disables the flight state, and executes the 2-tick chestplate swap.
 * - Priority 3 (Lowest): AUTO_ELYTRA_EQUIP (Flight Deploy)
 *     Trigger: Wind Charge upward velocity (v_y > 0.6) -> post-apex legal glide transition.
 *     Action: Only executes if AutoMace is completely idle.
 */
public final class ElytraTarget extends Module {
    private static ElytraTarget instance;
    public static boolean shouldElytraTarget;

    // 1. Split-Priority Mutex Thresholds
    private static final double PRIORITY_1_UNEQUIP_TARGET_DIST = 4.5D;
    private static final double PRIORITY_1_UNEQUIP_MAX_VEL_Y = -0.1D;
    private static final double PRIORITY_3_EQUIP_WIND_CHARGE_VEL_Y = 0.6D;

    private static final long WIND_CHARGE_SETTLE_DELAY_MS = 250L;
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

    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization", "Enable randomized delay variance for elytra re-equip cooldowns.", true));
    private final NumberSetting minRandomMs = register(new NumberSetting(
            "Min Random (ms)", "Minimum random delay in milliseconds.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting maxRandomMs = register(new NumberSetting(
            "Max Random (ms)", "Maximum random delay in milliseconds.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting reequipDelayMs = register(new NumberSetting(
            "Reequip Delay (ms)", "Delay in milliseconds before re-equipping the elytra after dropping.", 217.0D, 0.0D, 1000.0D, 1.0D));
    private final BooleanSetting automaceSync = register(new BooleanSetting(
            "Automace Sync", "Synchronize elytra unequip/equip priorities with AutoMace via ActionManager.", true));
    private final BooleanSetting disableOnGround = register(new BooleanSetting(
            "Disable on Ground", "Swap to chestplate and reset flight state when touching the ground.", true));
    private final BooleanSetting clickSim = register(new BooleanSetting(
            "ClickSim", "Simulate client click input during consolidated slotbar armor swaps.", true));
    private final NumberSetting heightDiff = register(new NumberSetting(
            "Height Diff", "Required vertical height difference above target to initiate chestplate drop.", 7.0D, 0.0D, 50.0D, 0.1D));
    private final NumberSetting heightRange = register(new NumberSetting(
            "Height Range", "Maximum target distance within which height drop triggers.", 8.4D, 1.0D, 30.0D, 0.1D));
    private final NumberSetting macePreHitRange = register(new NumberSetting(
            "Mace Pre-Hit Range", "Pre-impact distance threshold (< 4.5m default) to unequip elytra before a mace hit.", 4.5D, 0.5D, 10.0D, 0.1D));
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
    private boolean jumpPulseToggle = false;
    private boolean forceCancelFlightInput = false;

    private long lastWindChargeOrBlastTimeMs = 0L;
    private boolean wasWindChargeOnCooldown = false;
    private boolean windChargeAscentDetected = false;
    private boolean equipAfterWindChargeApexPending = false;
    private double prevTickVelY = 0.0D;
    private double airbornePeakY = Double.NaN;
    private int lastProcessedMovementTick = -1;
    private LivingEntity cachedTarget = null;

    public ElytraTarget() {
        super("AutoElytra", "Split-priority ActionManager Elytra controller (Priority 1 Unequip / Priority 3 Equip).", ModuleCategory.MOVEMENT);
        instance = this;
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

    @Override
    protected void onEnable() {
        shouldElytraTarget = true;
        resetInternalState();
    }

    @Override
    protected void onDisable() {
        shouldElytraTarget = false;
        ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
        ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
        resetInternalState();
    }

    /**
     * Priority 1 (Highest): AutoElytra_UNEQUIP (Flight Cancel / Chestplate Swap).
     * Overrides the AutoMace lock to immediately cancel flight state and execute the 2-tick chestplate swap.
     */
    public boolean ensureUnequippedBeforeMaceAttack(LivingEntity target) {
        if (!isEnabled() || mc.player == null || mc.screen != null) {
            return false;
        }
        if (target != null && isWhitelistedFriend(target)) {
            return false;
        }
        if (!InventoryUtils.isElytraEquipped()) {
            forceCancelFlightInput = false;
            return !ActionManager.getInstance().isSwapInProgress();
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
        ActionManager actionManager = ActionManager.getInstance();
        actionManager.ensureTickSynchronized(mc);
        updateWindChargeAndPhysicsTracking();

        if (mc.screen != null) {
            return;
        }

        if (!InventoryUtils.isElytraEquipped()) {
            forceCancelFlightInput = false;
            actionManager.releaseLock(ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
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
            double searchRange = Math.max(heightRange.getValue(), macePreHitRange.getValue()) + 6.0D;
            cachedTarget = actionManager.getNearestVisibleTarget(mc, searchRange, true);
        }

        double velY = mc.player.getDeltaMovement().y;

        // =========================================================================================
        // PRIORITY 1 (HIGHEST): AutoElytra_UNEQUIP (Flight Cancel / Chestplate Swap)
        // Trigger: Target distance < 4.5m (or configured pre-hit range) AND v_y < -0.1 while wearing Elytra.
        // Action: Overrides AutoMace_STRIKE lock, immediately disables flight state, and executes 2-tick chestplate swap.
        // =========================================================================================
        if (InventoryUtils.isElytraEquipped() && !mc.player.onGround()) {
            if (velY < PRIORITY_1_UNEQUIP_MAX_VEL_Y
                    && cachedTarget != null
                    && shouldTriggerPriority1Unequip(cachedTarget)) {
                if (requestPriority1ChestplateUnequip()) {
                    flightState = CombatFlightState.STATE_2_SMASH_WINDOW;
                    return;
                }
            }
        }

        // Ground Handling: Unequip Elytra into Chestplate when grounded
        if (mc.player.onGround()) {
            forceCancelFlightInput = false;
            if (disableOnGround.getValue() && InventoryUtils.isElytraEquipped() && canStartPriority3Equip()) {
                requestPriority1ChestplateUnequip();
            }
            flightState = CombatFlightState.STATE_1_FLIGHT;
            equipAfterWindChargeApexPending = false;
            return;
        }

        // =========================================================================================
        // PRIORITY 3 (LOWEST): AutoElytra_EQUIP (Flight Deploy)
        // Trigger: Wind Charge upward velocity spike (v_y > 0.6) -> post-apex legal glide transition.
        // Action: ONLY executes if AutoMace is completely idle!
        // =========================================================================================
        boolean autoMaceCompletelyIdle = !maceActive || !autoMace.isMidSlamSequence();
        if (autoMaceCompletelyIdle
                && !actionManager.isBlockedByHigherPriority(ActionPriority.AUTO_ELYTRA_EQUIP)
                && canStartPriority3Equip()) {
            if (equipAfterWindChargeApexPending && isLegalVanillaFallFlyingState()) {
                equipAfterWindChargeApexPending = false;
                flightState = CombatFlightState.STATE_1_FLIGHT;
                if (!InventoryUtils.isElytraEquipped()) {
                    requestPriority3ElytraEquip();
                } else {
                    glideWindowTimer.reset();
                }
            }
        }
    }

    /**
     * Priority 1 (Highest): AutoElytra_UNEQUIP.
     * Preempts Priority 2 (AutoMace_STRIKE), cancels flight input immediately, and executes the 2-tick chestplate swap.
     */
    private boolean requestPriority1ChestplateUnequip() {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (mc.player.swinging || mc.player.attackAnim > 0.0F || isManualItemUseActive()) {
            return false;
        }
        int chestplateSlot = findChestplateInHotbarOnly();
        if (chestplateSlot == -1) {
            return false;
        }

        forceCancelFlightInput = true;
        int originalSlot = mc.player.getInventory().getSelectedSlot();

        boolean accepted = ActionManager.getInstance().submitConsolidatedSwap(
                mc,
                ActionPriority.AUTO_ELYTRA_UNEQUIP,
                "AutoElytra_UNEQUIP",
                chestplateSlot,
                originalSlot,
                true,
                clickSim.getValue(),
                null
        );

        if (accepted) {
            swapCommitmentTimer.reset();
        }
        return accepted;
    }

    /**
     * Priority 3 (Lowest): AutoElytra_EQUIP.
     * Only executes when AutoMace is completely idle.
     */
    private boolean requestPriority3ElytraEquip() {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        int elytraSlot = findElytraInHotbarOnly();
        if (elytraSlot == -1) {
            return false;
        }
        int originalSlot = mc.player.getInventory().getSelectedSlot();

        boolean accepted = ActionManager.getInstance().submitConsolidatedSwap(
                mc,
                ActionPriority.AUTO_ELYTRA_EQUIP,
                "AutoElytra_EQUIP",
                elytraSlot,
                originalSlot,
                true,
                clickSim.getValue(),
                null
        );

        if (accepted) {
            forceCancelFlightInput = false;
            swapCommitmentTimer.reset();
            lastElytraEquipCompletedTick = mc.player.tickCount + 2;
            glideWindowTimer.reset();
            jumpPulseToggle = false;
        }
        return accepted;
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
            // Priority 3 Trigger: Wind Charge upward velocity (v_y > 0.6)
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
        return System.currentTimeMillis() - lastWindChargeOrBlastTimeMs >= WIND_CHARGE_SETTLE_DELAY_MS;
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
                && mc.player.tickCount > lastElytraEquipCompletedTick
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
        return ActionManager.isWhitelistedFriend(Minecraft.getInstance(), entity);
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

    /**
     * Priority 1 (AutoElytra_UNEQUIP) Trigger Check:
     * Fires immediately when target distance < 4.5m (or configured pre-hit range) and v_y < -0.1,
     * overriding AutoMace so the Chestplate is equipped before the smash strike.
     */
    private boolean shouldTriggerPriority1Unequip(LivingEntity target) {
        if (mc.player == null || target == null || isWhitelistedFriend(target)) {
            return false;
        }
        if (findChestplateInHotbarOnly() == -1 || !ActionManager.hasLineOfSight(mc, target)) {
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

        double unequipTriggerRadius = Math.max(PRIORITY_1_UNEQUIP_TARGET_DIST, macePreHitRange.getValue());
        if (eyeToBoxDist < unequipTriggerRadius) {
            return true;
        }

        double verticalAltitudeOverTarget = mc.player.getY() - target.getY();
        boolean hasSmashHeight = getEffectiveFallDistance() >= minDropDist.getValue()
                || verticalAltitudeOverTarget >= minDropDist.getValue();
        if (!hasSmashHeight || verticalAltitudeOverTarget < 0.35D) {
            return false;
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
        jumpPulseToggle = false;
        forceCancelFlightInput = false;
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
        return heightRange.getFloat();
    }

    public float getForward() {
        return macePreHitRange.getFloat();
    }
}