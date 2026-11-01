package releon.ru.api.module.impl.movement;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class ElytraTarget extends Module {
    private static ElytraTarget instance;
    public static boolean shouldElytraTarget;

    private static final List<Item> CHESTPLATES = List.of(
            Items.NETHERITE_CHESTPLATE,
            Items.DIAMOND_CHESTPLATE,
            Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE,
            Items.CHAINMAIL_CHESTPLATE,
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
     * Legitimate Multi-Tick Swap State Machine:
     * - If the target armor piece (Chestplate or Elytra) is in the hotbar (0..8), uses vanilla right-click
     *   equipment swapping (SELECT_HOTBAR -> USE_ITEM -> RESTORE_HOTBAR), which requires zero inventory screen
     *   packets and is 100% legal while moving/flying.
     * - If the target armor piece is in the main inventory (9..35), executes a genuine multi-tick movement-halted
     *   inventory swap:
     *   Tick 1 (INV_PRE_STOP): Blocks W/A/S/D, jump, and sprint in InputEvent so the server sees the player stop inputs.
     *   Tick 2 (INV_DO_CLICK): Performs the slot swap while inputs remain halted.
     *   Tick 3 (INV_CLOSE_RESUME): Sends close-container packet and restores player movement.
     */
    private enum LegitSwapStage {
        IDLE,
        HOTBAR_USE_AND_RESTORE,
        INV_PRE_STOP,
        INV_DO_CLICK,
        INV_CLOSE_RESUME
    }

    private final StopWatch reequipTimer = new StopWatch();
    private final StopWatch stageDelayTimer = new StopWatch();
    private final StopWatch deployGlideTimer = new StopWatch();

    private LegitSwapStage swapStage = LegitSwapStage.IDLE;
    private boolean swappingToElytra = false;
    private int targetInventorySlot = -1;
    private int savedHotbarSlot = -1;
    private double stageWaitMs = 0.0D;

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
     * Synchronized Pre-Attack Unequip Request called by AutoMace (MaceTarget):
     * Initiates a legitimate Elytra -> Chestplate swap before the Mace attack.
     * Returns true as soon as the Elytra is unequipped and no swap is pending.
     */
    public boolean requestPreAttackUnequip(Minecraft client, LivingEntity target) {
        if (client.player == null) {
            return false;
        }
        if (!InventoryUtils.hasElytra()) {
            return swapStage == LegitSwapStage.IDLE;
        }
        if (swapStage == LegitSwapStage.IDLE) {
            if (startLegitUnequipToChestplate(client)) {
                droppedForSmash = true;
                maceAttackConfirmed = false;
                dropStartY = client.player.getY();
                scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
                reequipTimer.reset();
            }
        }
        return !InventoryUtils.hasElytra() && swapStage == LegitSwapStage.IDLE;
    }

    /**
     * Called by AutoMace (MaceTarget) the exact tick a Mace smash or StunSlam lands.
     */
    public void notifyMaceAttackLanded() {
        maceAttackConfirmed = true;
        droppedForSmash = true;
        scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
        reequipTimer.reset();
    }

    public boolean isBusySwapping() {
        return swapStage != LegitSwapStage.IDLE;
    }

    @SubscribeEvent
    public void onInput(InputEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        // 1. Block movement & sprint during multi-tick inventory swaps so InventoryMove anti-cheat checks never flag
        if (swapStage == LegitSwapStage.INV_PRE_STOP
                || swapStage == LegitSwapStage.INV_DO_CLICK
                || swapStage == LegitSwapStage.INV_CLOSE_RESUME) {
            event.setDirectionalLow(false, false, false, false);
            event.setJumping(false);
            event.setSprinting(false);
            client.player.setSprinting(false);
            return;
        }

        // 2. Natively deploy Elytra glide mid-air via jump pulse right after re-equipping
        if (!droppedForSmash
                && swapStage == LegitSwapStage.IDLE
                && InventoryUtils.hasElytra()
                && !client.player.onGround()
                && !client.player.isFallFlying()
                && !client.player.isInWater()
                && !client.player.isInLava()
                && !client.player.getAbilities().flying
                && !deployGlideTimer.finished(400.0D)) {
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

        // Advance active legitimate swap state machine first
        if (swapStage != LegitSwapStage.IDLE) {
            processLegitSwapStateMachine(client);
            return;
        }

        // 1. Disable on Ground (Strictly executed when enabled)
        if (disableOnGround.getValue() && client.player.onGround()) {
            if (InventoryUtils.hasElytra()) {
                startLegitUnequipToChestplate(client);
            }
            droppedForSmash = false;
            maceAttackConfirmed = false;
            pearlEquipPending = false;
            dropStartY = Double.NaN;
            return;
        }

        // 2. On Pearl (Strictly skipped when Disabled)
        if (onPearl.getValue() && pearlEquipPending) {
            pearlEquipPending = false;
            if (!InventoryUtils.hasElytra()) {
                startLegitEquipElytra(client);
                return;
            }
        }

        // 3. Target resolution (Synchronizes with AutoMace when Automace Sync is enabled)
        if (automaceSync.getValue()) {
            MaceTarget autoMace = MaceTarget.getInstance();
            if (autoMace == null || !autoMace.isEnabled() || !isHoldingOrHasMace(client)) {
                return;
            }
            LivingEntity maceTarget = autoMace.getActiveTarget();
            currentTarget = (maceTarget != null && maceTarget.isAlive()) ? maceTarget : findCombatTarget(client);
        } else {
            currentTarget = findCombatTarget(client);
        }

        // 4. Re-Equip Phase after dropping to Chestplate for a Mace smash
        if (droppedForSmash) {
            if (shouldReequipElytraNow(client)) {
                if (startLegitEquipElytra(client)) {
                    droppedForSmash = false;
                    maceAttackConfirmed = false;
                    dropStartY = Double.NaN;
                }
            }
            return;
        }

        // 5. Evaluate Spatial & Distance Thresholds to legit-unequip Elytra -> Chestplate before Mace hit
        if (InventoryUtils.hasElytra() && !client.player.onGround() && currentTarget != null) {
            if (shouldDropToChestplateForSmash(client, currentTarget)) {
                if (startLegitUnequipToChestplate(client)) {
                    droppedForSmash = true;
                    maceAttackConfirmed = false;
                    dropStartY = client.player.getY();
                    scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
                    reequipTimer.reset();
                }
                return;
            }
        }

        // 6. In Air auto-equip (Strictly skipped when Disabled)
        if (inAir.getValue() && !client.player.onGround() && !InventoryUtils.hasElytra() && !droppedForSmash) {
            if (getEffectiveFallDistance(client) >= minDropDist.getValue()
                    && (currentTarget == null || !shouldDropToChestplateForSmash(client, currentTarget))) {
                startLegitEquipElytra(client);
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

    /**
     * Initiates a legitimate Elytra -> Chestplate swap:
     * 1. Prefers hotbar right-click swap if a Chestplate is in hotbar slots 0..8 (100% vanilla while moving).
     * 2. Otherwise starts a 3-stage movement-halted inventory swap (INV_PRE_STOP -> INV_DO_CLICK -> INV_CLOSE_RESUME).
     */
    private boolean startLegitUnequipToChestplate(Minecraft client) {
        if (!InventoryUtils.hasElytra()) {
            return true;
        }

        // Check if a chestplate is in the hotbar (slots 0..8) for instant vanilla right-click equip
        int hotbarChest = findHotbarChestplate(client);
        if (hotbarChest != -1 && !client.player.isUsingItem()) {
            savedHotbarSlot = InventoryUtils.currentSlot();
            InventoryUtils.selectSlot(hotbarChest);
            InventoryUtils.syncSelectedHotbarSlot(hotbarChest);
            if (clickSim.getValue() && client.options != null) {
                KeyMapping.click(client.options.keyUse.getDefaultKey());
            }
            if (client.gameMode != null) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                client.player.swing(InteractionHand.MAIN_HAND);
            }
            swappingToElytra = false;
            stageWaitMs = computeRandomStageDelayMs();
            stageDelayTimer.reset();
            swapStage = LegitSwapStage.HOTBAR_USE_AND_RESTORE;
            return true;
        }

        // Otherwise use multi-tick movement-halted inventory swap
        targetInventorySlot = InventoryUtils.findChestArmorSlot();
        swappingToElytra = false;
        stageWaitMs = computeRandomStageDelayMs();
        stageDelayTimer.reset();
        swapStage = LegitSwapStage.INV_PRE_STOP;
        return true;
    }

    /**
     * Initiates a legitimate Chestplate -> Elytra swap:
     * 1. Prefers hotbar right-click swap if an Elytra is in hotbar slots 0..8.
     * 2. Otherwise starts a 3-stage movement-halted inventory swap.
     */
    private boolean startLegitEquipElytra(Minecraft client) {
        if (InventoryUtils.hasElytra()) {
            deployGlideTimer.reset();
            return true;
        }

        int hotbarElytra = InventoryUtils.findHotbarItem(Items.ELYTRA);
        if (hotbarElytra != -1 && !client.player.isUsingItem()) {
            savedHotbarSlot = InventoryUtils.currentSlot();
            InventoryUtils.selectSlot(hotbarElytra);
            InventoryUtils.syncSelectedHotbarSlot(hotbarElytra);
            if (clickSim.getValue() && client.options != null) {
                KeyMapping.click(client.options.keyUse.getDefaultKey());
            }
            if (client.gameMode != null) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                client.player.swing(InteractionHand.MAIN_HAND);
            }
            swappingToElytra = true;
            stageWaitMs = computeRandomStageDelayMs();
            stageDelayTimer.reset();
            swapStage = LegitSwapStage.HOTBAR_USE_AND_RESTORE;
            deployGlideTimer.reset();
            return true;
        }

        int elytraSlot = InventoryUtils.findElytraSlot();
        if (elytraSlot == -1) {
            return false;
        }
        targetInventorySlot = elytraSlot;
        swappingToElytra = true;
        stageWaitMs = computeRandomStageDelayMs();
        stageDelayTimer.reset();
        swapStage = LegitSwapStage.INV_PRE_STOP;
        return true;
    }

    private void processLegitSwapStateMachine(Minecraft client) {
        if (client.player == null) {
            swapStage = LegitSwapStage.IDLE;
            return;
        }
        if (!stageDelayTimer.finished(stageWaitMs)) {
            return;
        }

        switch (swapStage) {
            case HOTBAR_USE_AND_RESTORE -> {
                if (savedHotbarSlot >= 0 && savedHotbarSlot < 9 && savedHotbarSlot != InventoryUtils.currentSlot()) {
                    InventoryUtils.selectSlot(savedHotbarSlot);
                    InventoryUtils.syncSelectedHotbarSlot(savedHotbarSlot);
                }
                savedHotbarSlot = -1;
                if (swappingToElytra) {
                    deployGlideTimer.reset();
                }
                swapStage = LegitSwapStage.IDLE;
            }
            case INV_PRE_STOP -> {
                // Movement was blocked on this tick; now transition to the click tick
                client.player.setSprinting(false);
                stageWaitMs = computeRandomStageDelayMs();
                stageDelayTimer.reset();
                swapStage = LegitSwapStage.INV_DO_CLICK;
            }
            case INV_DO_CLICK -> {
                if (clickSim.getValue() && client.options != null) {
                    KeyMapping.click(client.options.keyAttack.getDefaultKey());
                }
                if (swappingToElytra) {
                    int slot = targetInventorySlot != -1 ? targetInventorySlot : InventoryUtils.findElytraSlot();
                    if (slot != -1) {
                        InventoryUtils.swap(InventoryUtils.wrapSlot(slot), 6);
                    }
                } else {
                    int slot = targetInventorySlot != -1 ? targetInventorySlot : InventoryUtils.findChestArmorSlot();
                    if (slot != -1) {
                        InventoryUtils.swap(InventoryUtils.wrapSlot(slot), 6);
                    } else {
                        InventoryUtils.click(6, 0, ClickType.QUICK_MOVE);
                    }
                }
                stageWaitMs = computeRandomStageDelayMs();
                stageDelayTimer.reset();
                swapStage = LegitSwapStage.INV_CLOSE_RESUME;
            }
            case INV_CLOSE_RESUME -> {
                InventoryUtils.closeScreen();
                if (swappingToElytra) {
                    deployGlideTimer.reset();
                }
                targetInventorySlot = -1;
                swapStage = LegitSwapStage.IDLE;
            }
            default -> swapStage = LegitSwapStage.IDLE;
        }
    }

    private int findHotbarChestplate(Minecraft client) {
        if (client.player == null) {
            return -1;
        }
        for (Item chestItem : CHESTPLATES) {
            int slot = InventoryUtils.findHotbarItem(chestItem);
            if (slot != -1) {
                return slot;
            }
        }
        return -1;
    }

    private boolean shouldDropToChestplateForSmash(Minecraft client, LivingEntity target) {
        if (!hasLineOfSight(client, target)) {
            return false;
        }

        Vec3 playerPos = client.player.position();
        Vec3 playerEye = client.player.getEyePosition(1.0F);
        Vec3 playerVel = client.player.getDeltaMovement();
        AABB targetBox = target.getBoundingBox();

        double eyeToBoxDist = playerEye.distanceTo(clampToBox(playerEye, targetBox));
        double verticalAltitudeOverTarget = playerPos.y - target.getY();
        double descentSpeed = Math.max(0.05D, -playerVel.y);
        double horizontalSpeed = Math.hypot(playerVel.x, playerVel.z);
        double totalSpeed = Math.hypot(horizontalSpeed, descentSpeed);

        // Account for 2-tick legit swap lead + Min Drop Dist (1.5) so Chestplate is equipped before entering 3.0-block Swing Range
        double swapLeadBlocks = totalSpeed * 2.4D;
        double preHitLead = macePreHitRange.getValue() + minDropDist.getValue() + swapLeadBlocks;
        if (eyeToBoxDist <= preHitLead && verticalAltitudeOverTarget >= -0.5D) {
            return true;
        }

        // Height Diff (7.0) within Height Range (8.4)
        double horizontalDist = Math.hypot(playerPos.x - target.getX(), playerPos.z - target.getZ());
        if (eyeToBoxDist <= heightRange.getValue()
                && horizontalDist <= Math.max(3.8D, heightRange.getValue() * 0.55D)
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

        if (maceAttackConfirmed || client.player.getDeltaMovement().y > 0.02D) {
            return true;
        }

        if (currentTarget != null && currentTarget.isAlive()) {
            Vec3 eyePos = client.player.getEyePosition(1.0F);
            double eyeDist = eyePos.distanceTo(clampToBox(eyePos, currentTarget.getBoundingBox()));
            if (eyeDist <= macePreHitRange.getValue() + 2.2D
                    && client.player.getY() >= currentTarget.getY() - 0.5D
                    && client.player.getDeltaMovement().y < 0.0D
                    && !reequipTimer.finished(scheduledReequipDelayMs + 350.0D)) {
                return false;
            }
        }

        double droppedDistance = Double.isNaN(dropStartY)
                ? getEffectiveFallDistance(client)
                : Math.max(getEffectiveFallDistance(client), dropStartY - client.player.getY());
        return droppedDistance >= minDropDist.getValue();
    }

    private double computeRandomStageDelayMs() {
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

    private double computeEffectiveReequipDelayMs() {
        return reequipDelayMs.getValue() + computeRandomStageDelayMs();
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
        double maxSearchRange = Math.max(heightRange.getValue(), macePreHitRange.getValue()) + 6.0D;
        Vec3 eyePos = client.player.getEyePosition(1.0F);

        LivingEntity best = null;
        double bestDistSqr = Double.MAX_VALUE;

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

            double distSqr = dist * dist;
            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
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
        swapStage = LegitSwapStage.IDLE;
        swappingToElytra = false;
        targetInventorySlot = -1;
        savedHotbarSlot = -1;
        stageWaitMs = 0.0D;
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