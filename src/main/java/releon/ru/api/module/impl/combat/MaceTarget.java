package releon.ru.api.module.impl.combat;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.impl.PlayerTravelEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.util.MathUtils;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.module.impl.movement.ElytraTarget;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.manager.ActionManager;
import releon.ru.manager.ActionManager.ActionPriority;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.render.Render3D;

import java.util.List;
import java.util.Optional;

public class MaceTarget extends Module {
    private static MaceTarget instance;

    private static final String[] HOTBAR_SLOTS = new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9"};
    private static final int TARGET_WIREFRAME_COLOR = 0xAA5EF2D6;

    private static final double INNER_20_PERCENT_HALF_RATIO = 0.10D;
    private static final double MIN_FAR_ROTATION_DEG_PER_TICK = 40.0D;
    private static final double MAX_FAR_ROTATION_DEG_PER_TICK = 60.0D;
    private static final double PD_DECELERATION_CONE_DEGREES = 32.0D;
    private static final double MACE_PRIORITY_MUTEX_DISTANCE = 3.5D;
    private static final int MIN_TICKS_BETWEEN_MACE_STEPS = 2;

    /**
     * Bridge adapter delegating directly to the Phase 2 centralized ActionManager.
     */
    public static final class CombatStateManager {
        private CombatStateManager() {
        }

        public static void acquireMaceSmashLock() {
            ActionManager.getInstance().acquireLock(ActionPriority.COMBAT, "AutoMace");
        }

        public static void releaseMaceSmashLock() {
            ActionManager.getInstance().releaseLock(ActionPriority.COMBAT, "AutoMace");
        }

        public static boolean tryAcquireElytraAscentLock() {
            return ActionManager.getInstance().acquireLock(ActionPriority.MOVEMENT, "AutoElytra");
        }

        public static void releaseElytraLock() {
            ActionManager.getInstance().releaseLock(ActionPriority.MOVEMENT, "AutoElytra");
        }

        public static boolean isMaceSmashLocked() {
            ActionPriority active = ActionManager.getInstance().getActiveLockPriority();
            return active != null && active.outranksOrEquals(ActionPriority.COMBAT);
        }

        public static boolean isElytraAscentLocked() {
            return ActionManager.getInstance().getActiveLockPriority() == ActionPriority.MOVEMENT;
        }

        public static void reset() {
            ActionManager.getInstance().resetAll();
        }
    }

    private final NumberSetting swingRange = register(new NumberSetting(
            "Swing Range", "Maximum distance to swing and attack with the mace (capped at 3.0m vanilla reach).", 3.0D, 1.0D, 3.0D, 0.05D));
    private final NumberSetting aimRange = register(new NumberSetting(
            "Aim Range", "Maximum range to acquire and track targets.", 10.0D, 1.0D, 20.0D, 0.1D));
    private final NumberSetting maceFov = register(new NumberSetting(
            "Mace FOV", "Field of view cone in degrees for target acquisition.", 360.0D, 1.0D, 360.0D, 1.0D));
    private final NumberSetting aimInAir = register(new NumberSetting(
            "Aim In Air", "Minimum fall distance required before airborne aiming begins.", 0.0D, 0.0D, 10.0D, 0.1D));

    private final BooleanSetting autoSwitch = register(new BooleanSetting(
            "Auto Switch", "Automatically switch hotbar slot to the mace when attacking.", true));
    private final BooleanSetting swapBack = register(new BooleanSetting(
            "Swap Back", "Restore previous or selected hotbar slot after attacking.", true));
    private final BooleanSetting swapBackToSelectedSlot = register(new BooleanSetting(
            "SwapBack To Selected Slot", "Swap back to the configured Slot selection after slamming.", false));
    private final MultiModeSetting slotSetting = register(new MultiModeSetting(
            "Slot", "Preferred hotbar slots (1-9) for primary mace or swap-back target.", HOTBAR_SLOTS));
    private final MultiModeSetting extraSlotSetting = register(new MultiModeSetting(
            "Extra Slot", "Secondary hotbar slots (1-9) for axe or swap return.", HOTBAR_SLOTS));

    private final NumberSetting aimSpeed = register(new NumberSetting(
            "Aim Speed", "Angular rotation speed during distance-scaled PD deceleration.", 50.0D, 1.0D, 100.0D, 0.5D));
    private final NumberSetting aimSmooth = register(new NumberSetting(
            "Aim Smooth", "Smoothing derivative damping factor applied to rotation interpolation.", 0.00D, 0.00D, 10.00D, 0.01D));
    private final NumberSetting minFallDist = register(new NumberSetting(
            "Min Fall Dist", "Minimum fall distance required to trigger a mace slam.", 1.5D, 0.0D, 20.0D, 0.1D));
    private final NumberSetting cooldownMs = register(new NumberSetting(
            "Cooldown (ms)", "Minimum cooldown in milliseconds between slam sequences.", 125.0D, 0.0D, 1000.0D, 5.0D));
    private final NumberSetting maceSwapDelayMs = register(new NumberSetting(
            "Mace Swap Delay (ms)", "Delay in milliseconds after switching to mace before attacking.", 100.0D, 0.0D, 500.0D, 1.0D));

    private final BooleanSetting stunSlam = register(new BooleanSetting(
            "Stun Slam", "Break target shield with an axe before executing the mace slam.", true));
    private final BooleanSetting properStunslam = register(new BooleanSetting(
            "ProperStunslam", "Verify target is actively blocking with a shield before stun-slamming.", false));
    private final BooleanSetting onlyOnNonGlide = register(new BooleanSetting(
            "Only On NonGlide", "Only aim and attack when not actively gliding with an Elytra.", false));
    private final BooleanSetting weaponOnly = register(new BooleanSetting(
            "Weapon Only", "Only activate targeting and attacks while holding a weapon.", false));
    private final BooleanSetting clickSim = register(new BooleanSetting(
            "ClickSim", "Simulate client mouse click and hand swing during attacks.", true));
    private final BooleanSetting ignoreFriends = register(new BooleanSetting(
            "Ignore Friends", "Ignore players registered in the friend list.", true));
    private final BooleanSetting targetEsp = register(new BooleanSetting(
            "Target ESP", "Render a minimal single-pass wireframe outline on the active target.", true));

    private enum SmashSequenceState {
        IDLE,
        STEP_1_AXE_STUN_ATTACK,
        STEP_2_SELECT_MACE_AFTER_STUN,
        STEP_1_MACE_SMASH_ATTACK,
        STEP_2_SWAPBACK_RESTORE
    }

    private final StopWatch cooldownTimer = new StopWatch();
    private final StopWatch stateTickTimer = new StopWatch();

    private LivingEntity target;
    private SmashSequenceState smashState = SmashSequenceState.IDLE;
    private int stateCommittedTick = -1;
    private int savedHotbarSlot = -1;
    private int targetMaceSlot = -1;
    private int lastTargetScanTick = -1;
    private double airbornePeakY = Double.NaN;

    private double prevYawError = 0.0D;
    private double prevPitchError = 0.0D;
    private boolean hasPrevError = false;
    private long lastSmoothFrameNs = 0L;

    public MaceTarget() {
        super("AutoMace", "ActionManager-synchronized PD kinematic aim assist & mace controller (Priority 2: COMBAT).", ModuleCategory.COMBAT);
        instance = this;
    }

    public static MaceTarget getInstance() {
        return instance;
    }

    public LivingEntity getActiveTarget() {
        return target;
    }

    public boolean isMidSlamSequence() {
        return smashState != SmashSequenceState.IDLE || CombatStateManager.isMaceSmashLocked();
    }

    public boolean isInStrikeMutexLockout() {
        return smashState != SmashSequenceState.IDLE || CombatStateManager.isMaceSmashLocked();
    }

    public int getPreferredMaceHotbarSlot() {
        return resolveMaceSlot(Minecraft.getInstance());
    }

    @Override
    protected void onEnable() {
        resetAllStates();
    }

    @Override
    protected void onDisable() {
        CombatStateManager.releaseMaceSmashLock();
        resetAllStates();
    }

    @Override
    public void onTick(Minecraft client) {
        if (!isEnabled()) {
            return;
        }
        if (client.player == null || client.level == null) {
            CombatStateManager.releaseMaceSmashLock();
            resetAllStates();
            return;
        }

        ActionManager.getInstance().ensureTickSynchronized(client);

        if (isGroundedOrAscendingLockout(client)) {
            releaseCameraAndClearTargetLock();
        }
    }

    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        if (!isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && isGroundedOrAscendingLockout(client)) {
            releaseCameraAndClearTargetLock();
        }
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        if (!isEnabled() || event.getLayer() != DrawEvent.Layer.GAME) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.screen != null) {
            lastSmoothFrameNs = 0L;
            return;
        }

        if (isGroundedOrAscendingLockout(client)) {
            releaseCameraAndClearTargetLock();
            return;
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)
                || !canEngageModule(client) || !shouldAimAtTarget(client)) {
            resetKinematicAimState();
            return;
        }

        long nowNs = System.nanoTime();
        double frameScale = lastSmoothFrameNs > 0L ? (nowNs - lastSmoothFrameNs) / 50_000_000.0D : (1.0D / 3.0D);
        frameScale = Mth.clamp(frameScale, 0.05D, 1.0D);
        lastSmoothFrameNs = nowNs;

        applyDistanceScaledPdAim(client, target, frameScale);
    }

    @SubscribeEvent
    public void onRotationUpdate(RotationUpdateEvent event) {
        if (!isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        ActionManager.getInstance().ensureTickSynchronized(client);

        if (event.getPhase() == EventPhase.PRE) {
            updateAirbornePeak(client);
            runPreRotationPhase(client);
            return;
        }

        if (event.getPhase() == EventPhase.POST) {
            runPostRotationPhase(client);
        }
    }

    private void runPreRotationPhase(Minecraft client) {
        if (isGroundedOrAscendingLockout(client)) {
            releaseCameraAndClearTargetLock();
            return;
        }

        if (smashState == SmashSequenceState.STEP_2_SWAPBACK_RESTORE) {
            return;
        }

        if (!canEngageModule(client)) {
            if (smashState != SmashSequenceState.IDLE) {
                transitionToSwapBackRestore(client);
            } else {
                CombatStateManager.releaseMaceSmashLock();
            }
            releaseCameraAndClearTargetLock();
            return;
        }

        if (lastTargetScanTick != client.player.tickCount) {
            lastTargetScanTick = client.player.tickCount;
            findTargetFromPreFilteredCache(client);
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target) || !ActionManager.hasLineOfSight(client, target)) {
            if (smashState != SmashSequenceState.IDLE) {
                transitionToSwapBackRestore(client);
            } else {
                CombatStateManager.releaseMaceSmashLock();
            }
            releaseCameraAndClearTargetLock();
            return;
        }

        updatePriority2CombatMutexLock(client, target);

        if (!shouldAimAtTarget(client)) {
            resetKinematicAimState();
            return;
        }

        long nowNs = System.nanoTime();
        if (lastSmoothFrameNs == 0L || nowNs - lastSmoothFrameNs > 22_000_000L) {
            applyDistanceScaledPdAim(client, target, 1.0D);
            lastSmoothFrameNs = nowNs;
        }
    }

    private void runPostRotationPhase(Minecraft client) {
        // Yield immediately if Priority 1 CRITICAL (AutoTotem / AutoHeal) owns the ActionManager mutex
        if (ActionManager.getInstance().isBlockedByHigherPriority(ActionPriority.COMBAT)) {
            return;
        }

        if (smashState != SmashSequenceState.IDLE) {
            processSmashSequenceStateMachine(client);
            return;
        }

        if (isGroundedOrAscendingLockout(client)) {
            CombatStateManager.releaseMaceSmashLock();
            return;
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target) || !ActionManager.hasLineOfSight(client, target)) {
            CombatStateManager.releaseMaceSmashLock();
            return;
        }

        if (isManualInputBlockingSwap(client)) {
            return;
        }

        if (!cooldownTimer.finished(cooldownMs.getValue())) {
            return;
        }

        tryInitiateSafeLegitMaceSmash(client, target);
    }

    private boolean isGroundedOrAscendingLockout(Minecraft client) {
        if (client.player == null) {
            return true;
        }
        return client.player.onGround()
                || client.player.getDeltaMovement().y >= 0.0D
                || client.player.isInWater()
                || client.player.isInLava()
                || client.player.getAbilities().flying;
    }

    private void releaseCameraAndClearTargetLock() {
        resetKinematicAimState();
        target = null;
    }

    private void applyDistanceScaledPdAim(Minecraft client, LivingEntity targetEntity, double tickFraction) {
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        Angle currentCamera = new Angle(currentYaw, currentPitch);

        if (isCrosshairInsideInner20PercentDeadzone(client, targetEntity, currentCamera)) {
            hasPrevError = false;
            prevYawError = 0.0D;
            prevPitchError = 0.0D;
            return;
        }

        Angle centerAngle = computeExactCenterAimAngle(client, targetEntity);
        if (centerAngle == null) {
            return;
        }

        double yawError = Mth.wrapDegrees(centerAngle.getYaw() - currentYaw);
        double pitchError = centerAngle.getPitch() - currentPitch;
        double angularDistance = Math.hypot(yawError, pitchError);

        if (angularDistance < 0.04D) {
            return;
        }

        boolean insideOuterHitbox = doesRayIntersectBox(
                client.player.getEyePosition(1.0F),
                currentCamera,
                targetEntity.getBoundingBox(),
                aimRange.getValue() + 1.5D
        );

        double userSpeedRatio = Mth.clamp(aimSpeed.getValue() / 50.0D, 0.35D, 1.50D);
        double maxSpeedPerTick = Mth.lerp(
                Mth.clamp(angularDistance / 45.0D, 0.0D, 1.0D),
                MIN_FAR_ROTATION_DEG_PER_TICK,
                MAX_FAR_ROTATION_DEG_PER_TICK
        ) * userSpeedRatio;

        double distNorm = Mth.clamp(angularDistance / PD_DECELERATION_CONE_DEGREES, 0.08D, 1.0D);
        double smoothDecelCurve = distNorm * (2.0D - distNorm);
        double hitboxProximityScale = insideOuterHitbox ? 0.38D : 1.0D;

        double smoothFactor = Mth.clamp(aimSmooth.getValue() / 10.0D, 0.0D, 0.85D);
        double kp = Mth.clamp((0.68D * smoothDecelCurve * hitboxProximityScale) * (1.0D - smoothFactor * 0.45D), 0.14D, 0.82D);
        double kd = 0.18D + smoothFactor * 0.15D;

        double dYaw = hasPrevError ? (yawError - prevYawError) : 0.0D;
        double dPitch = hasPrevError ? (pitchError - prevPitchError) : 0.0D;
        prevYawError = yawError;
        prevPitchError = pitchError;
        hasPrevError = true;

        double rawYawStep = (kp * yawError + kd * dYaw) * (tickFraction * 2.2D);
        double rawPitchStep = (kp * pitchError + kd * dPitch) * (tickFraction * 2.2D);

        double maxStepThisFrame = maxSpeedPerTick * smoothDecelCurve * hitboxProximityScale * tickFraction;
        double yawWeight = Math.abs(yawError) / angularDistance;
        double pitchWeight = Math.abs(pitchError) / angularDistance;
        double maxYawStep = Math.max(0.25D, maxStepThisFrame * yawWeight);
        double maxPitchStep = Math.max(0.20D, maxStepThisFrame * pitchWeight);

        double clampedYawStep = Mth.clamp(rawYawStep, -maxYawStep, maxYawStep);
        double clampedPitchStep = Mth.clamp(rawPitchStep, -maxPitchStep, maxPitchStep);

        if (Math.abs(clampedYawStep) > Math.abs(yawError)) {
            clampedYawStep = yawError;
        }
        if (Math.abs(clampedPitchStep) > Math.abs(pitchError)) {
            clampedPitchStep = pitchError;
        }

        float nextYaw = currentYaw + (float) clampedYawStep;
        float nextPitch = Mth.clamp(currentPitch + (float) clampedPitchStep, -89.5F, 89.5F);

        double gcd = MathUtils.computeGcd();
        if (gcd > 0.0D) {
            nextYaw = currentYaw + Math.round((nextYaw - currentYaw) / (float) gcd) * (float) gcd;
            nextPitch = Mth.clamp(currentPitch + Math.round((nextPitch - currentPitch) / (float) gcd) * (float) gcd, -89.5F, 89.5F);
        }

        client.player.setYRot(nextYaw);
        client.player.setXRot(nextPitch);
    }

    private boolean isCrosshairInsideInner20PercentDeadzone(Minecraft client, LivingEntity entity, Angle currentAngle) {
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        AABB fullBox = entity.getBoundingBox();
        Vec3 exactCenter = getExactBoundingBoxCenter(fullBox);

        double innerHalfX = fullBox.getXsize() * INNER_20_PERCENT_HALF_RATIO;
        double innerHalfY = fullBox.getYsize() * INNER_20_PERCENT_HALF_RATIO;
        double innerHalfZ = fullBox.getZsize() * INNER_20_PERCENT_HALF_RATIO;

        AABB inner20PercentBox = new AABB(
                exactCenter.x - innerHalfX, exactCenter.y - innerHalfY, exactCenter.z - innerHalfZ,
                exactCenter.x + innerHalfX, exactCenter.y + innerHalfY, exactCenter.z + innerHalfZ
        );

        return doesRayIntersectBox(eyePos, currentAngle, inner20PercentBox, aimRange.getValue() + 2.0D);
    }

    private Vec3 getExactBoundingBoxCenter(AABB box) {
        return new Vec3(
                (box.minX + box.maxX) * 0.5D,
                box.minY + (box.getYsize() * 0.5D),
                (box.minZ + box.maxZ) * 0.5D
        );
    }

    private Angle computeExactCenterAimAngle(Minecraft client, LivingEntity entity) {
        Vec3 actualEye = client.player.getEyePosition(1.0F);
        Vec3 exactCenter = getExactBoundingBoxCenter(entity.getBoundingBox());
        return MathAngle.fromTo(actualEye, exactCenter).adjustSensitivity();
    }

    private void updatePriority2CombatMutexLock(Minecraft client, LivingEntity targetEntity) {
        if (client.player == null || targetEntity == null || isGroundedOrAscendingLockout(client)) {
            if (smashState == SmashSequenceState.IDLE) {
                CombatStateManager.releaseMaceSmashLock();
            }
            return;
        }

        double currentDist = getPrecisionDistanceToTarget(client, targetEntity);
        if (isFallingForSmash(client) && currentDist < MACE_PRIORITY_MUTEX_DISTANCE) {
            CombatStateManager.acquireMaceSmashLock();
        } else if (smashState == SmashSequenceState.IDLE) {
            CombatStateManager.releaseMaceSmashLock();
        }
    }

    private void tryInitiateSafeLegitMaceSmash(Minecraft client, LivingEntity targetEntity) {
        if (targetEntity == null || isWhitelistedFriend(client, targetEntity)) {
            return;
        }
        if (isGroundedOrAscendingLockout(client) || !isFallingForSmash(client)) {
            return;
        }

        double currentDist = getPrecisionDistanceToTarget(client, targetEntity);
        if (currentDist >= MACE_PRIORITY_MUTEX_DISTANCE && currentDist > swingRange.getValue()) {
            if (InventoryUtils.isElytraEquipped()) {
                ElytraTarget autoElytra = ElytraTarget.getInstance();
                if (autoElytra != null && autoElytra.isEnabled()) {
                    autoElytra.ensureUnequippedBeforeMaceAttack(targetEntity);
                }
            }
            return;
        }

        int maceSlot = resolveMaceSlot(client);
        boolean holdingMace = isMaceStack(client.player.getMainHandItem());
        if (!holdingMace && (!autoSwitch.getValue() || maceSlot == -1)) {
            return;
        }

        if (!ActionManager.getInstance().acquireLock(ActionPriority.COMBAT, "AutoMace")) {
            return;
        }

        int currentSlot = client.player.getInventory().getSelectedSlot();
        savedHotbarSlot = currentSlot;
        targetMaceSlot = holdingMace ? currentSlot : maceSlot;

        if (shouldTriggerStunSlam(client, targetEntity)) {
            int axeSlot = resolveAxeSlot(client);
            if (axeSlot != -1) {
                if (currentSlot != axeSlot) {
                    selectHotbarSlotLegit(client, axeSlot);
                    stateCommittedTick = client.player.tickCount;
                    stateTickTimer.reset();
                    smashState = SmashSequenceState.STEP_1_AXE_STUN_ATTACK;
                } else if (currentDist <= swingRange.getValue() && performThrottledAttack(client, targetEntity)) {
                    stateCommittedTick = client.player.tickCount;
                    stateTickTimer.reset();
                    smashState = SmashSequenceState.STEP_2_SELECT_MACE_AFTER_STUN;
                }
                return;
            }
        }

        if (currentSlot != targetMaceSlot) {
            selectHotbarSlotLegit(client, targetMaceSlot);
            stateCommittedTick = client.player.tickCount;
            stateTickTimer.reset();
            smashState = SmashSequenceState.STEP_1_MACE_SMASH_ATTACK;
            return;
        }

        if (currentDist <= swingRange.getValue() && performThrottledAttack(client, targetEntity)) {
            cooldownTimer.reset();
            transitionToSwapBackRestore(client);
        }
    }

    private void processSmashSequenceStateMachine(Minecraft client) {
        if (client.player == null) {
            CombatStateManager.releaseMaceSmashLock();
            smashState = SmashSequenceState.IDLE;
            return;
        }

        switch (smashState) {
            case STEP_1_AXE_STUN_ATTACK -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                if (client.player.tickCount - stateCommittedTick < MIN_TICKS_BETWEEN_MACE_STEPS) {
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() + 0.30D && performThrottledAttack(client, target)) {
                    stateCommittedTick = client.player.tickCount;
                    stateTickTimer.reset();
                    smashState = SmashSequenceState.STEP_2_SELECT_MACE_AFTER_STUN;
                } else if (client.player.onGround() || stateTickTimer.finished(220.0D)) {
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_2_SELECT_MACE_AFTER_STUN -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                if (client.player.tickCount - stateCommittedTick < MIN_TICKS_BETWEEN_MACE_STEPS) {
                    return;
                }
                int maceSlot = targetMaceSlot != -1 ? targetMaceSlot : resolveMaceSlot(client);
                if (maceSlot != -1) {
                    selectHotbarSlotLegit(client, maceSlot);
                }
                stateCommittedTick = client.player.tickCount;
                stateTickTimer.reset();
                smashState = SmashSequenceState.STEP_1_MACE_SMASH_ATTACK;
            }

            case STEP_1_MACE_SMASH_ATTACK -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                double requiredDelayMs = Math.max(100.0D, maceSwapDelayMs.getValue());
                if (client.player.tickCount - stateCommittedTick < MIN_TICKS_BETWEEN_MACE_STEPS
                        || !stateTickTimer.finished(requiredDelayMs)) {
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() + 0.25D && performThrottledAttack(client, target)) {
                    cooldownTimer.reset();
                    transitionToSwapBackRestore(client);
                } else if (client.player.onGround() || stateTickTimer.finished(requiredDelayMs + 220.0D)) {
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_2_SWAPBACK_RESTORE -> {
                if (client.player.tickCount - stateCommittedTick < MIN_TICKS_BETWEEN_MACE_STEPS) {
                    return;
                }
                executeSafeLegitSwapBack(client);
                smashState = SmashSequenceState.IDLE;
                CombatStateManager.releaseMaceSmashLock();
            }

            default -> {
                smashState = SmashSequenceState.IDLE;
                CombatStateManager.releaseMaceSmashLock();
            }
        }
    }

    private void selectHotbarSlotLegit(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) {
            return;
        }
        if (client.player.getInventory().getSelectedSlot() != slot) {
            client.player.getInventory().setSelectedSlot(slot);
        }
    }

    private void transitionToSwapBackRestore(Minecraft client) {
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled()) {
            autoElytra.enterState3RecoveryLock();
        }
        if (!swapBack.getValue()) {
            savedHotbarSlot = -1;
            targetMaceSlot = -1;
            smashState = SmashSequenceState.IDLE;
            CombatStateManager.releaseMaceSmashLock();
            return;
        }
        stateCommittedTick = client.player != null ? client.player.tickCount : -1;
        stateTickTimer.reset();
        smashState = SmashSequenceState.STEP_2_SWAPBACK_RESTORE;
    }

    private void executeSafeLegitSwapBack(Minecraft client) {
        if (client == null || client.player == null) {
            savedHotbarSlot = -1;
            return;
        }

        int returnSlot = savedHotbarSlot >= 0 && savedHotbarSlot <= 8
                ? savedHotbarSlot
                : findSwordSlotInHotbar(client, client.player.getInventory().getSelectedSlot());

        if (swapBackToSelectedSlot.getValue()) {
            int configuredSlot = getFirstSelectedHotbarSlot(slotSetting, -1);
            if (configuredSlot == -1) {
                configuredSlot = getFirstSelectedHotbarSlot(extraSlotSetting, -1);
            }
            if (configuredSlot != -1) {
                returnSlot = configuredSlot;
            }
        }

        selectHotbarSlotLegit(client, returnSlot);
        savedHotbarSlot = -1;
        targetMaceSlot = -1;
    }

    /**
     * 3. Packet Dispatch Pacing (Global CPS & Outgoing Packet Cap):
     * Verifies with ActionManager that no other interaction packet was sent this tick and that the
     * realistic human CPS interval has elapsed before dispatching the attack.
     */
    private boolean performThrottledAttack(Minecraft client, LivingEntity targetEntity) {
        if (client.player == null || client.gameMode == null || targetEntity == null) {
            return false;
        }
        if (isWhitelistedFriend(client, targetEntity)) {
            return false;
        }
        if (!ActionManager.verifyStrict3mAttackRaycast(client, targetEntity, swingRange.getValue())) {
            return false;
        }
        if (!ActionManager.getInstance().canDispatchInteraction(client, ActionPriority.COMBAT)) {
            return false;
        }

        if (client.player.isUsingItem() && client.player.getUseItem().getItem() == Items.SHIELD) {
            client.gameMode.releaseUsingItem(client.player);
            client.player.stopUsingItem();
        }

        if (clickSim.getValue() && client.options != null) {
            KeyMapping.click(client.options.keyAttack.getDefaultKey());
        }

        client.gameMode.attack(client.player, targetEntity);
        client.player.swing(InteractionHand.MAIN_HAND);
        ActionManager.getInstance().recordInteractionDispatched(client);
        return true;
    }

    private boolean canEngageModule(Minecraft client) {
        if (client.player == null || client.level == null || isGroundedOrAscendingLockout(client)) {
            return false;
        }
        if (onlyOnNonGlide.getValue() && client.player.isFallFlying()) {
            return false;
        }
        if (weaponOnly.getValue() && !isHoldingWeapon(client.player.getMainHandItem())) {
            return false;
        }
        if (smashState != SmashSequenceState.IDLE) {
            return true;
        }

        boolean holdingMace = isMaceStack(client.player.getMainHandItem());
        int hotbarMace = resolveMaceSlot(client);
        if (!holdingMace && hotbarMace == -1) {
            return false;
        }

        return getEffectiveFallDistance(client) >= aimInAir.getValue();
    }

    private boolean isManualInputBlockingSwap(Minecraft client) {
        if (client.player == null || client.options == null) {
            return false;
        }
        if (client.player.isUsingItem() && client.options.keyUse.isDown()) {
            return true;
        }
        if (client.options.keyUse.isDown()) {
            Item mainItem = client.player.getMainHandItem().getItem();
            Item offItem = client.player.getOffhandItem().getItem();
            return mainItem == Items.GOLDEN_APPLE
                    || mainItem == Items.ENCHANTED_GOLDEN_APPLE
                    || mainItem == Items.WIND_CHARGE
                    || offItem == Items.GOLDEN_APPLE
                    || offItem == Items.ENCHANTED_GOLDEN_APPLE;
        }
        return false;
    }

    private boolean isWhitelistedFriend(Minecraft client, Entity entity) {
        return ignoreFriends.getValue() && ActionManager.isWhitelistedFriend(client, entity);
    }

    @SubscribeEvent
    public void onWorldRender(WorldRenderEvent event) {
        if (!isEnabled() || !targetEsp.getValue() || target == null || !target.isAlive()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || isGroundedOrAscendingLockout(client)) {
            return;
        }
        if (client.player.distanceToSqr(target) <= 4096.0D) {
            Render3D.drawBox(target.getBoundingBox(), TARGET_WIREFRAME_COLOR, 1.0F, true, false, false);
        }
    }

    private void resetKinematicAimState() {
        hasPrevError = false;
        prevYawError = 0.0D;
        prevPitchError = 0.0D;
        lastSmoothFrameNs = 0L;
    }

    private void updateAirbornePeak(Minecraft client) {
        if (client.player == null) {
            airbornePeakY = Double.NaN;
            return;
        }
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

    private boolean shouldAimAtTarget(Minecraft client) {
        if (target == null || client.player == null || isGroundedOrAscendingLockout(client) || isWhitelistedFriend(client, target)) {
            return false;
        }
        if (!ActionManager.hasLineOfSight(client, target)) {
            return false;
        }
        if (getPrecisionDistanceToTarget(client, target) > aimRange.getValue()) {
            return false;
        }
        double requiredFall = Math.max(minFallDist.getValue(), aimInAir.getValue());
        return getEffectiveFallDistance(client) >= requiredFall;
    }

    private boolean isFallingForSmash(Minecraft client) {
        if (client.player == null || isGroundedOrAscendingLockout(client)) {
            return false;
        }
        if (onlyOnNonGlide.getValue() && client.player.isFallFlying()) {
            return false;
        }
        return getEffectiveFallDistance(client) >= minFallDist.getValue();
    }

    private int findSwordSlotInHotbar(Minecraft client, int currentSlot) {
        if (client.player == null) {
            return 0;
        }
        ItemStack slot0 = client.player.getInventory().getItem(0);
        if (!slot0.isEmpty() && slot0.is(ItemTags.SWORDS)) {
            return 0;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.SWORDS)) {
                return i;
            }
        }
        return currentSlot == 0 ? 1 : 0;
    }

    private boolean doesRayIntersectBox(Vec3 origin, Angle angle, AABB box, double maxDist) {
        if (box.contains(origin)) {
            return true;
        }
        Vec3 dir = angle.toVector();
        Vec3 end = origin.add(dir.scale(maxDist));
        Optional<Vec3> hit = box.clip(origin, end);
        return hit.isPresent();
    }

    private boolean shouldTriggerStunSlam(Minecraft client, LivingEntity targetEntity) {
        if (!stunSlam.getValue()) {
            return false;
        }
        boolean blocking = isTargetBlockingWithShield(targetEntity);
        if (properStunslam.getValue()) {
            return blocking && isTargetFacingPlayer(client, targetEntity);
        }
        return blocking;
    }

    private boolean isTargetBlockingWithShield(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        if (entity.isBlocking()) {
            return true;
        }
        return entity.isUsingItem() && entity.getUseItem().getItem() == Items.SHIELD;
    }

    private boolean isTargetFacingPlayer(Minecraft client, LivingEntity targetEntity) {
        Vec3 toPlayer = client.player.position().subtract(targetEntity.position());
        toPlayer = new Vec3(toPlayer.x, 0.0D, toPlayer.z).normalize();
        Vec3 targetLook = targetEntity.getViewVector(1.0F);
        targetLook = new Vec3(targetLook.x, 0.0D, targetLook.z).normalize();
        return toPlayer.dot(targetLook) > 0.0D;
    }

    private int resolveMaceSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            if (!isSlotAllowedByFilter(i, slotSetting, extraSlotSetting)) {
                continue;
            }
            if (isMaceStack(client.player.getInventory().getItem(i))) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (isMaceStack(client.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private int resolveAxeSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            if (extraSlotSetting.selectedCount() > 0 && !extraSlotSetting.isSelected(String.valueOf(i + 1))) {
                continue;
            }
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.getItem() instanceof AxeItem || stack.is(ItemTags.AXES))) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.getItem() instanceof AxeItem || stack.is(ItemTags.AXES))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSlotAllowedByFilter(int slotIndex, MultiModeSetting primary, MultiModeSetting secondary) {
        if (primary.selectedCount() == 0 && secondary.selectedCount() == 0) {
            return true;
        }
        String label = String.valueOf(slotIndex + 1);
        return primary.isSelected(label) || secondary.isSelected(label);
    }

    private int getFirstSelectedHotbarSlot(MultiModeSetting setting, int fallback) {
        for (int i = 0; i < 9; i++) {
            if (setting.isSelected(String.valueOf(i + 1))) {
                return i;
            }
        }
        return fallback;
    }

    private boolean isMaceStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getItem() == Items.MACE
                || stack.getItem().getDescriptionId().toLowerCase().contains("mace");
    }

    private boolean isHoldingWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return isMaceStack(stack)
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem
                || stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.AXES);
    }

    private double getPrecisionDistanceToTarget(Minecraft client, LivingEntity entity) {
        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();
        AABB box = entity.getBoundingBox();
        double dx = eyeX - Mth.clamp(eyeX, box.minX, box.maxX);
        double dy = eyeY - Mth.clamp(eyeY, box.minY, box.maxY);
        double dz = eyeZ - Mth.clamp(eyeZ, box.minZ, box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 2. Pre-Filtered Target Acquisition:
     * Queries ActionManager's once-per-tick Valid Targets cache instead of iterating mc.level.entitiesForRendering().
     */
    private void findTargetFromPreFilteredCache(Minecraft client) {
        if (isGroundedOrAscendingLockout(client)) {
            target = null;
            return;
        }

        List<LivingEntity> candidates = ActionManager.getInstance().getValidTargets(client);
        if (candidates.isEmpty()) {
            target = null;
            return;
        }

        double maxRange = aimRange.getValue();
        double maxRangeSqr = maxRange * maxRange;
        double maxFov = maceFov.getValue();
        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();

        LivingEntity bestTarget = null;
        double bestDistSqr = Double.MAX_VALUE;

        for (int i = 0, size = candidates.size(); i < size; i++) {
            LivingEntity living = candidates.get(i);
            if (!living.isAlive() || isWhitelistedFriend(client, living)) {
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

            if (maxFov < 360.0D) {
                Angle angleToTarget = MathAngle.calculateAngle(getExactBoundingBoxCenter(box));
                double fovDiff = MathAngle.difference(MathAngle.cameraAngle(), angleToTarget);
                if (fovDiff > maxFov * 0.5D) {
                    continue;
                }
            }

            if (!ActionManager.hasLineOfSight(client, living)) {
                continue;
            }

            bestDistSqr = distSqr;
            bestTarget = living;
        }

        target = bestTarget;
    }

    private void resetAllStates() {
        smashState = SmashSequenceState.IDLE;
        stateCommittedTick = -1;
        savedHotbarSlot = -1;
        targetMaceSlot = -1;
        lastTargetScanTick = -1;
        airbornePeakY = Double.NaN;
        resetKinematicAimState();
        target = null;
    }
}