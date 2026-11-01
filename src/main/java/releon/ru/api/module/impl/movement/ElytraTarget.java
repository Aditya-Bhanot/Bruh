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
import net.minecraft.world.inventory.Slot;
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
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AntiBot;
import releon.ru.api.module.impl.combat.MaceTarget;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.InventoryFlowManager;
import releon.ru.utils.inventory.InventoryTask;
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

    // Timers & Flight Drop State
    private final StopWatch reequipTimer = new StopWatch();
    private final StopWatch swapCooldownTimer = new StopWatch();

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
     * Called by AutoMace (MaceTarget) before executing a Mace smash:
     * Uses InventoryTask.moveItem (the client's native anti-cheat-safe InventoryFlowManager script)
     * to legitimately swap from Elytra to Chestplate before the attack packet is sent.
     */
    public boolean requestPreAttackUnequip(Minecraft client, LivingEntity target) {
        if (mc.player == null) {
            return false;
        }
        if (!InventoryUtils.isElytraEquipped()) {
            return !isBusy();
        }
        if (!isBusy() && swapCooldownTimer.finished(120.0D)) {
            if (swapToChestplateLegit()) {
                droppedForSmash = true;
                maceAttackConfirmed = false;
                dropStartY = mc.player.getY();
                scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
                reequipTimer.reset();
            }
        }
        return !InventoryUtils.isElytraEquipped() && !isBusy();
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

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            resetInternalState();
            return;
        }

        updateAirborneTracking();

        if (mc.screen != null || isBusy()) {
            return;
        }

        // 1. Disable on Ground: automatically swap back to Chestplate when on the ground
        if (disableOnGround.getValue() && mc.player.onGround()) {
            if (InventoryUtils.isElytraEquipped() && swapCooldownTimer.finished(150.0D)) {
                swapToChestplateLegit();
            }
            droppedForSmash = false;
            maceAttackConfirmed = false;
            pearlEquipPending = false;
            dropStartY = Double.NaN;
            return;
        }

        // 2. On Pearl: equip Elytra when an Ender Pearl is thrown (only when On Pearl is enabled)
        if (onPearl.getValue() && pearlEquipPending) {
            pearlEquipPending = false;
            if (!InventoryUtils.isElytraEquipped()) {
                swapToElytraLegit();
                return;
            }
        }

        // 3. Automace Sync target & state resolution
        if (automaceSync.getValue()) {
            MaceTarget autoMace = MaceTarget.getInstance();
            if (autoMace != null && autoMace.isEnabled()) {
                LivingEntity maceTarget = autoMace.getActiveTarget();
                currentTarget = (maceTarget != null && maceTarget.isAlive()) ? maceTarget : findCombatTarget();
            } else {
                currentTarget = findCombatTarget();
            }
        } else {
            currentTarget = findCombatTarget();
        }

        // 4. Re-Equip Elytra Phase after dropping to Chestplate for a Mace smash
        if (droppedForSmash) {
            if (shouldReequipElytraNow()) {
                if (swapToElytraLegit()) {
                    droppedForSmash = false;
                    maceAttackConfirmed = false;
                    dropStartY = Double.NaN;
                }
            }
            return;
        }

        // 5. Unequip Elytra -> Chestplate when entering pre-hit or height-drop window above a target
        if (InventoryUtils.isElytraEquipped() && !mc.player.onGround() && currentTarget != null) {
            if (shouldDropToChestplateForSmash(currentTarget) && swapCooldownTimer.finished(150.0D)) {
                if (swapToChestplateLegit()) {
                    droppedForSmash = true;
                    maceAttackConfirmed = false;
                    dropStartY = mc.player.getY();
                    scheduledReequipDelayMs = computeEffectiveReequipDelayMs();
                    reequipTimer.reset();
                }
                return;
            }
        }

        // 6. Equip Elytra while airborne when outside the target drop window
        if (!mc.player.onGround() && !InventoryUtils.isElytraEquipped() && !droppedForSmash) {
            boolean shouldEquipAirborne = inAir.getValue()
                    || mc.player.getDeltaMovement().y > 0.22D
                    || (getEffectiveFallDistance() >= minDropDist.getValue()
                        && reequipTimer.finished(scheduledReequipDelayMs));
            if (shouldEquipAirborne
                    && (currentTarget == null || !shouldDropToChestplateForSmash(currentTarget))
                    && swapCooldownTimer.finished(180.0D)) {
                swapToElytraLegit();
            }
        }
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        if (mc.player == null) {
            return;
        }
        // Natively deploy Elytra into glide mid-air when Elytra is equipped and usable (exact ElytraHelper logic)
        if (!droppedForSmash
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
     * Uses InventoryTask.moveItem(slot, 6, shouldQueueSwap(), true) — the exact anti-cheat-compliant
     * swap pipeline used by ElytraHelper.java in this client.
     */
    private boolean swapToChestplateLegit() {
        if (!InventoryUtils.isElytraEquipped()) {
            return true;
        }
        Slot chestSlot = InventoryTask.getSlot(CHESTPLATES);
        if (chestSlot == null) {
            return false;
        }
        if (clickSim.getValue() && mc.options != null) {
            KeyMapping.click(mc.options.keyAttack.getDefaultKey());
        }
        InventoryTask.moveItem(chestSlot, 6, shouldQueueSwap(), true);
        swapCooldownTimer.reset();
        return true;
    }

    /**
     * Uses InventoryTask.moveItem(slot, 6, shouldQueueSwap(), true) to legitimately equip the Elytra
     * into armor slot 6 via InventoryFlowManager without flagging InventoryMove or BadPackets.
     */
    private boolean swapToElytraLegit() {
        if (InventoryUtils.isElytraEquipped()) {
            return true;
        }
        Slot elytraSlot = InventoryTask.getSlot(Items.ELYTRA);
        if (elytraSlot == null) {
            return false;
        }
        if (clickSim.getValue() && mc.options != null) {
            KeyMapping.click(mc.options.keyAttack.getDefaultKey());
        }
        InventoryTask.moveItem(elytraSlot, 6, shouldQueueSwap(), true);
        swapCooldownTimer.reset();
        return true;
    }

    private boolean shouldQueueSwap() {
        return !InventoryTask.isMoveMode("ReallyWorld");
    }

    public boolean isBusy() {
        return !InventoryFlowManager.script.isFinished()
                || !InventoryFlowManager.postScript.isFinished()
                || !InventoryTask.isSwapAndUseIdle();
    }

    private boolean shouldDropToChestplateForSmash(LivingEntity target) {
        if (mc.player == null || !hasLineOfSight(target)) {
            return false;
        }

        Vec3 playerPos = mc.player.position();
        Vec3 playerEye = mc.player.getEyePosition(1.0F);
        Vec3 playerVel = mc.player.getDeltaMovement();
        AABB targetBox = target.getBoundingBox();

        double eyeToBoxDist = playerEye.distanceTo(clampToBox(playerEye, targetBox));
        double verticalAltitudeOverTarget = playerPos.y - target.getY();
        double descentSpeed = Math.max(0.05D, -playerVel.y);
        double horizontalSpeed = Math.hypot(playerVel.x, playerVel.z);
        double totalSpeed = Math.hypot(horizontalSpeed, descentSpeed);

        // Trigger 1: Pre-Hit Range (2.5) + Min Drop Dist (1.5) + swap lead so Chestplate finishes equipping before Swing Range
        double preHitLead = macePreHitRange.getValue() + minDropDist.getValue() + totalSpeed * 2.2D;
        if (eyeToBoxDist <= preHitLead && verticalAltitudeOverTarget >= -0.5D) {
            return true;
        }

        // Trigger 2: Height Diff (7.0) within Height Range (8.4)
        double horizontalDist = Math.hypot(playerPos.x - target.getX(), playerPos.z - target.getZ());
        if (eyeToBoxDist <= heightRange.getValue()
                && horizontalDist <= Math.max(3.8D, heightRange.getValue() * 0.55D)
                && verticalAltitudeOverTarget >= heightDiff.getValue()) {
            return true;
        }

        return false;
    }

    private boolean shouldReequipElytraNow() {
        if (mc.player == null || mc.player.onGround()) {
            return false;
        }
        if (!reequipTimer.finished(scheduledReequipDelayMs)) {
            return false;
        }

        if (maceAttackConfirmed || mc.player.getDeltaMovement().y > 0.02D) {
            return true;
        }

        if (currentTarget != null && currentTarget.isAlive()) {
            Vec3 eyePos = mc.player.getEyePosition(1.0F);
            double eyeDist = eyePos.distanceTo(clampToBox(eyePos, currentTarget.getBoundingBox()));
            if (eyeDist <= macePreHitRange.getValue() + 2.2D
                    && mc.player.getY() >= currentTarget.getY() - 0.5D
                    && mc.player.getDeltaMovement().y < 0.0D
                    && !reequipTimer.finished(scheduledReequipDelayMs + 350.0D)) {
                return false;
            }
        }

        double droppedDistance = Double.isNaN(dropStartY)
                ? getEffectiveFallDistance()
                : Math.max(getEffectiveFallDistance(), dropStartY - mc.player.getY());
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

    private LivingEntity findCombatTarget() {
        if (mc.player == null || mc.level == null) {
            return null;
        }
        double maxSearchRange = Math.max(heightRange.getValue(), macePreHitRange.getValue()) + 6.0D;
        Vec3 eyePos = mc.player.getEyePosition(1.0F);

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

            double dist = eyePos.distanceTo(clampToBox(eyePos, living.getBoundingBox()));
            if (dist > maxSearchRange || !hasLineOfSight(living)) {
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