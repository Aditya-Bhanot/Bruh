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

    private static final double MIN_HYSTERESIS_COOLDOWN_MS = 210.0D;

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

    private enum ElytraPhase {
        GLIDING_OR_IDLE,
        DROPPED_WAITING_FOR_REEQUIP,
        POST_REEQUIP_GRACE
    }

    // Timers for Hysteresis Gate & Re-equip Delay
    private final StopWatch hysteresisTimer = new StopWatch();
    private final StopWatch reequipDelayTimer = new StopWatch();
    private final StopWatch graceTimer = new StopWatch();

    private ElytraPhase phase = ElytraPhase.GLIDING_OR_IDLE;
    private boolean maceAttackConfirmed = false;
    private boolean pearlEquipPending = false;
    private double scheduledReequipDelayMs = 217.0D;
    private double dropStartY = Double.NaN;
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
     * Priority 1 Mutex Pre-Attack Unequip Gate Called by AutoMace:
     * Executes a single-tick hotbar swap (select Chestplate slot -> right-click useItem -> restore original slot
     * within the exact same tick) so the player is never stuck holding the Chestplate in Slot 5.
     */
    public boolean ensureUnequippedBeforeMaceAttack(LivingEntity target) {
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (!InventoryUtils.isElytraEquipped()) {
            return true;
        }
        if (isPriority2ManualInputActive()) {
            return false;
        }
        if (swapHotbarArmorSameTick(false)) {
            phase = ElytraPhase.DROPPED_WAITING_FOR_REEQUIP;
            maceAttackConfirmed = false;
            dropStartY = mc.player.getY();
            scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
            reequipDelayTimer.reset();
            hysteresisTimer.reset();
        }
        return !InventoryUtils.isElytraEquipped();
    }

    /**
     * Called by AutoMace the exact tick the Mace smash + SwapBack completes.
     */
    public void notifyMaceAttackLanded() {
        if (mc.player == null) {
            return;
        }
        maceAttackConfirmed = true;
        if (phase != ElytraPhase.DROPPED_WAITING_FOR_REEQUIP) {
            phase = ElytraPhase.DROPPED_WAITING_FOR_REEQUIP;
            dropStartY = mc.player.getY();
            scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
            reequipDelayTimer.reset();
        }
    }

    /**
     * Sub-Tick Movement Hook (LocalPlayer#aiStep / tickMovement):
     * Evaluates AutoElytra swaps at the start of the movement tick before network position packets are sent.
     */
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
        updateAirborneTracking();

        if (mc.screen != null) {
            return;
        }

        // Priority 1 Mutex Lockout: if AutoMace is inside its pre-hit/strike lockout window (<= 4.0 blocks)
        // or waiting to finish its immediate SwapBack, AutoElytra yields hotbar control completely.
        MaceTarget autoMace = MaceTarget.getInstance();
        boolean maceActive = autoMace != null && autoMace.isEnabled();
        if (maceActive && autoMace.isInStrikeMutexLockout()) {
            // Only allow pre-hit Chestplate equip if Elytra is still worn; never re-equip Elytra during lockout!
            if (InventoryUtils.isElytraEquipped() && !isPriority2ManualInputActive()) {
                if (swapHotbarArmorSameTick(false)) {
                    phase = ElytraPhase.DROPPED_WAITING_FOR_REEQUIP;
                    maceAttackConfirmed = false;
                    dropStartY = mc.player.getY();
                    scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
                    reequipDelayTimer.reset();
                    hysteresisTimer.reset();
                }
            }
            return;
        }

        // Priority 2 Mutex Lockout: yield hotbar control when player is manually throwing Wind Charges or eating GApples
        if (isPriority2ManualInputActive()) {
            return;
        }

        // Enforce minimum 200ms-220ms hysteresis cooldown between any AutoElytra swaps
        if (!hysteresisTimer.finished(Math.max(MIN_HYSTERESIS_COOLDOWN_MS, reequipDelayMs.getValue()))) {
            return;
        }

        // 1. Strict Ground & Altitude Hysteresis Gate:
        // Equip Chestplate when onGround is true OR (disableOnGround is true and fallDistance == 0 on ground)
        if (mc.player.onGround()) {
            if (disableOnGround.getValue() && InventoryUtils.isElytraEquipped()) {
                if (swapHotbarArmorSameTick(false)) {
                    hysteresisTimer.reset();
                }
            }
            phase = ElytraPhase.GLIDING_OR_IDLE;
            maceAttackConfirmed = false;
            pearlEquipPending = false;
            dropStartY = Double.NaN;
            return;
        }

        // 2. On Pearl (Strictly executed ONLY when On Pearl is enabled)
        if (onPearl.getValue() && pearlEquipPending) {
            pearlEquipPending = false;
            if (!InventoryUtils.isElytraEquipped()) {
                if (swapHotbarArmorSameTick(true)) {
                    phase = ElytraPhase.POST_REEQUIP_GRACE;
                    graceTimer.reset();
                    hysteresisTimer.reset();
                    return;
                }
            }
        }

        // 3. 20Hz Cached Target Resolution (Zero per-frame stream allocations)
        if (automaceSync.getValue() && !maceActive) {
            return;
        }
        if (maceActive && autoMace.getActiveTarget() != null && autoMace.getActiveTarget().isAlive()) {
            cachedTarget = autoMace.getActiveTarget();
        } else if (lastTargetScanTick != mc.player.tickCount) {
            lastTargetScanTick = mc.player.tickCount;
            cachedTarget = findCombatTargetZeroAlloc();
        }

        // 4. Deterministic 3-Phase State Machine
        switch (phase) {
            case GLIDING_OR_IDLE -> {
                if (InventoryUtils.isElytraEquipped()) {
                    if (cachedTarget != null && shouldDropToChestplateForSmash(cachedTarget)) {
                        if (swapHotbarArmorSameTick(false)) {
                            phase = ElytraPhase.DROPPED_WAITING_FOR_REEQUIP;
                            maceAttackConfirmed = false;
                            dropStartY = mc.player.getY();
                            scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
                            reequipDelayTimer.reset();
                            hysteresisTimer.reset();
                        }
                    }
                } else if (inAir.getValue() && mc.player.fallDistance > 0.0F) {
                    if (getEffectiveFallDistance() >= minDropDist.getValue()
                            && (cachedTarget == null || !shouldDropToChestplateForSmash(cachedTarget))) {
                        if (swapHotbarArmorSameTick(true)) {
                            phase = ElytraPhase.POST_REEQUIP_GRACE;
                            graceTimer.reset();
                            hysteresisTimer.reset();
                        }
                    }
                }
            }

            case DROPPED_WAITING_FOR_REEQUIP -> {
                if (!reequipDelayTimer.finished(scheduledReequipDelayMs)) {
                    return;
                }
                if (maceActive && (autoMace.isMidSlamSequence() || autoMace.isInStrikeMutexLockout())) {
                    return;
                }

                double traversedDrop = Double.isNaN(dropStartY)
                        ? getEffectiveFallDistance()
                        : Math.max(getEffectiveFallDistance(), dropStartY - mc.player.getY());

                if (maceAttackConfirmed
                        || mc.player.getDeltaMovement().y > 0.0D
                        || traversedDrop >= minDropDist.getValue()
                        || reequipDelayTimer.finished(scheduledReequipDelayMs + 120.0D)) {
                    if (!InventoryUtils.isElytraEquipped()) {
                        if (swapHotbarArmorSameTick(true)) {
                            phase = ElytraPhase.POST_REEQUIP_GRACE;
                            maceAttackConfirmed = false;
                            graceTimer.reset();
                            hysteresisTimer.reset();
                        }
                    } else {
                        phase = ElytraPhase.POST_REEQUIP_GRACE;
                        maceAttackConfirmed = false;
                        graceTimer.reset();
                    }
                }
            }

            case POST_REEQUIP_GRACE -> {
                if (graceTimer.finished(400.0D)) {
                    phase = ElytraPhase.GLIDING_OR_IDLE;
                }
            }
        }
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        if (mc.player == null || mc.screen != null) {
            return;
        }
        // Deploy Elytra into glide mid-air right after re-equipping
        if (phase == ElytraPhase.POST_REEQUIP_GRACE
                && InventoryUtils.isElytraEquipped()
                && InventoryUtils.isElytraUsable()
                && !mc.player.onGround()
                && !mc.player.isFallFlying()
                && !mc.player.isInWater()
                && !mc.player.isInLava()) {
            event.setJumping(mc.player.tickCount % 2 == 0);
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!onPearl.getValue() || !event.isSend() || mc.player == null) {
            return;
        }
        if (event.getPacket() instanceof ServerboundUseItemPacket usePacket) {
            InteractionHand hand = usePacket.getHand();
            ItemStack usedStack = mc.player.getItemInHand(hand);
            if (!usedStack.isEmpty() && usedStack.getItem() == Items.ENDER_PEARL) {
                pearlEquipPending = true;
            }
        }
    }

    /**
     * Priority 2 Mutex Check:
     * Returns true if the player is actively consuming food (Golden Apple) or holding right-click
     * with a Wind Charge, Ender Pearl, Potion, or Shield so AutoElytra never interrupts manual actions.
     */
    private boolean isPriority2ManualInputActive() {
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
        return isManualPriorityItem(mainHand) || isManualPriorityItem(offHand);
    }

    private boolean isManualPriorityItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item == Items.WIND_CHARGE
                || item == Items.GOLDEN_APPLE
                || item == Items.ENCHANTED_GOLDEN_APPLE
                || item == Items.ENDER_PEARL
                || item == Items.CHORUS_FRUIT
                || item == Items.SHIELD
                || stack.has(DataComponents.FOOD);
    }

    /**
     * Same-Tick Fast Legit Hotbar Swap (Eliminates Slot 5 Locking):
     * 1. Locates the target armor piece (Elytra or Chestplate) strictly in hotbar slots 0..8.
     * 2. Switches to that hotbar slot.
     * 3. Right-clicks (useItem) to equip the armor piece via vanilla Equippable component swap.
     * 4. Immediately restores the original hotbar slot within the exact same client tick.
     */
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

        // Step 1: Select the Chestplate/Elytra hotbar slot
        if (originalSlot != armorHotbarSlot) {
            InventoryUtils.selectSlot(armorHotbarSlot);
        }

        // Step 2: Right-click (useItem) to swap the held armor piece with the Chest slot
        if (clickSim.getValue() && mc.options != null) {
            KeyMapping.click(mc.options.keyUse.getDefaultKey());
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);

        // Step 3: Immediately restore the original hotbar slot within the same tick (zero Slot 5 locking!)
        if (originalSlot >= 0 && originalSlot <= 8 && originalSlot != armorHotbarSlot) {
            InventoryUtils.selectSlot(originalSlot);
            InventoryUtils.syncSelectedHotbarSlot(originalSlot);
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
        if (mc.player == null || findChestplateInHotbarOnly() == -1 || !hasLineOfSight(target)) {
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

        double clampedX = Mth.clamp(eyeX, box.minX, box.maxX);
        double clampedY = Mth.clamp(eyeY, box.minY, box.maxY);
        double clampedZ = Mth.clamp(eyeZ, box.minZ, box.maxZ);
        double dx = eyeX - clampedX;
        double dy = eyeY - clampedY;
        double dz = eyeZ - clampedZ;
        double eyeToBoxDist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double verticalAltitudeOverTarget = mc.player.getY() - target.getY();
        double descentSpeed = Math.max(0.05D, -playerVel.y);
        double totalSpeed = Math.sqrt(playerVel.x * playerVel.x + playerVel.z * playerVel.z + descentSpeed * descentSpeed);

        boolean hasSmashHeight = getEffectiveFallDistance() >= minDropDist.getValue()
                || verticalAltitudeOverTarget >= minDropDist.getValue();
        if (!hasSmashHeight || verticalAltitudeOverTarget < 0.35D) {
            return false;
        }

        // Trigger 1: Pre-Hit Range (2.5) + Min Drop Dist (1.5) + 1-tick velocity lead
        double preHitLead = macePreHitRange.getValue() + minDropDist.getValue() + totalSpeed * 1.35D;
        if (eyeToBoxDist <= preHitLead) {
            return true;
        }

        // Trigger 2: Height Diff (7.0) within Height Range (8.4)
        double horizontalDist = Math.hypot(mc.player.getX() - target.getX(), mc.player.getZ() - target.getZ());
        return eyeToBoxDist <= heightRange.getValue()
                && horizontalDist <= Math.max(3.8D, heightRange.getValue() * 0.55D)
                && verticalAltitudeOverTarget >= heightDiff.getValue();
    }

    private double computeEffectiveReequipDelayMs() {
        double baseDelay = Math.max(MIN_HYSTERESIS_COOLDOWN_MS, reequipDelayMs.getValue());
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
     * Zero-Allocation 20Hz Target Scanner:
     * Uses primitive double math instead of allocating temporary Vec3 / Stream objects per entity.
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
            if (living instanceof Player player) {
                if (AntiBot.shouldIgnore(player) || FriendUtils.isFriend(player.getName().getString())) {
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

    private void updateAirborneTracking() {
        if (mc.player == null) {
            return;
        }
        if (mc.player.onGround() || mc.player.isInWater() || mc.player.isInLava()) {
            airbornePeakY = mc.player.getY();
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

    private Vec3 clampToBox(Vec3 point, AABB box) {
        return new Vec3(
                Mth.clamp(point.x, box.minX, box.maxX),
                Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ)
        );
    }

    private void resetInternalState() {
        phase = ElytraPhase.GLIDING_OR_IDLE;
        maceAttackConfirmed = false;
        pearlEquipPending = false;
        scheduledReequipDelayMs = reequipDelayMs.getValue();
        dropStartY = Double.NaN;
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