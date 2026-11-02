package releon.ru.api.module.impl.combat;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.PlayerTravelEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConfig;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.target.TargetFinder;
import releon.ru.api.module.impl.combat.aura.util.MathUtils;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.module.impl.combat.macetarget.armor.ArmorSwapHandler;
import releon.ru.api.module.impl.combat.macetarget.armor.FireworkHandler;
import releon.ru.api.module.impl.combat.macetarget.attack.AttackHandler;
import releon.ru.api.module.impl.combat.macetarget.flight.FlightController;
import releon.ru.api.module.impl.combat.macetarget.prediction.TargetPredictor;
import releon.ru.api.module.impl.combat.macetarget.stage.StageHandler;
import releon.ru.api.module.impl.combat.macetarget.state.MaceState.Stage;
import releon.ru.api.module.impl.movement.ElytraTarget;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.inventory.swap.SwapSettings;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public class MaceTarget extends Module {
    private static MaceTarget instance;

    private static final String[] HOTBAR_SLOTS = new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9"};
    private static final int PREDICTION_FILL_COLOR = 0x225EF2D6;
    private static final int PREDICTION_OUTLINE_COLOR = 0xAA5EF2D6;

    // 1. AutoMace Kinematic PD Aiming & 20% Inner Deadzone Constants
    private static final double INNER_20_PERCENT_HALF_RATIO = 0.10D;
    private static final double MIN_FAR_ROTATION_DEG_PER_TICK = 40.0D;
    private static final double MAX_FAR_ROTATION_DEG_PER_TICK = 60.0D;
    private static final double PD_DECELERATION_CONE_DEGREES = 32.0D;
    private static final double MACE_PRIORITY_MUTEX_DISTANCE = 3.5D;
    private static final int MIN_TICKS_BETWEEN_MACE_STEPS = 2;

    /**
     * 3. Priority Mutex & CombatStateManager:
     * Arbitrates hotbar ownership between AutoMace and AutoElytra.
     * - Priority 1 (Highest): MACE_SMASH — Active whenever AutoMace is in a swing/hit sequence or target distance < 3.5m
     *   while falling. Strictly forbids AutoElytra from initiating a swap until the Mace strike lands and cooldown begins.
     * - Priority 2: ELYTRA_ASCENT — Claimed by AutoElytra during its multi-tick safe slotbar swap cycle.
     */
    public static final class CombatStateManager {
        public enum HotbarLockOwner {
            NONE,
            PRIORITY_1_MACE_SMASH,
            PRIORITY_2_ELYTRA_ASCENT
        }

        private static volatile HotbarLockOwner currentOwner = HotbarLockOwner.NONE;
        private static volatile long lockTimestampMs = 0L;
        private static final long FAILSAFE_LOCK_TIMEOUT_MS = 650L;

        private CombatStateManager() {
        }

        private static void checkTimeout() {
            if (currentOwner != HotbarLockOwner.NONE
                    && System.currentTimeMillis() - lockTimestampMs > FAILSAFE_LOCK_TIMEOUT_MS) {
                currentOwner = HotbarLockOwner.NONE;
            }
        }

        public static synchronized void acquireMaceSmashLock() {
            currentOwner = HotbarLockOwner.PRIORITY_1_MACE_SMASH;
            lockTimestampMs = System.currentTimeMillis();
        }

        public static synchronized void releaseMaceSmashLock() {
            if (currentOwner == HotbarLockOwner.PRIORITY_1_MACE_SMASH) {
                currentOwner = HotbarLockOwner.NONE;
            }
        }

        public static synchronized boolean tryAcquireElytraAscentLock() {
            checkTimeout();
            if (currentOwner == HotbarLockOwner.PRIORITY_1_MACE_SMASH) {
                return false;
            }
            currentOwner = HotbarLockOwner.PRIORITY_2_ELYTRA_ASCENT;
            lockTimestampMs = System.currentTimeMillis();
            return true;
        }

        public static synchronized void releaseElytraLock() {
            if (currentOwner == HotbarLockOwner.PRIORITY_2_ELYTRA_ASCENT) {
                currentOwner = HotbarLockOwner.NONE;
            }
        }

        public static boolean isMaceSmashLocked() {
            checkTimeout();
            return currentOwner == HotbarLockOwner.PRIORITY_1_MACE_SMASH;
        }

        public static boolean isElytraAscentLocked() {
            checkTimeout();
            return currentOwner == HotbarLockOwner.PRIORITY_2_ELYTRA_ASCENT;
        }

        public static synchronized void reset() {
            currentOwner = HotbarLockOwner.NONE;
            lockTimestampMs = 0L;
        }
    }

    // 1. Targeting & Aim Dynamics + Inventory Swapping (Screenshot 1 order)
    private final NumberSetting swingRange = register(new NumberSetting(
            "Swing Range", "Maximum distance to swing and attack with the mace.", 3.0D, 1.0D, 6.0D, 0.1D));
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
    private final BooleanSetting noDensitySwapBack = register(new BooleanSetting(
            "No Density SwapBack", "Prevent swapping back if the attacking mace has Density.", false));
    private final BooleanSetting noBreachSwapBack = register(new BooleanSetting(
            "No Breach SwapBack", "Prevent swapping back if the attacking mace has Breach.", false));

    // 2. Slam Mechanics, Delays & Aim Speed/Smooth (Screenshot 2/3 order)
    private final BooleanSetting stunSlamClickDelay = register(new BooleanSetting(
            "StunSlam Click Delay", "Enable millisecond delay between shield stun and mace slam.", false));
    private final NumberSetting stunSlamClickDelayMs = register(new NumberSetting(
            "StunSlam Click Delay (ms)", "Millisecond delay between axe stun click and mace slam.", 0.0D, 0.0D, 500.0D, 1.0D));
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
    private final NumberSetting densityHeight = register(new NumberSetting(
            "Density Height", "Fall distance threshold for prioritizing a Density mace.", 7.0D, 1.0D, 50.0D, 0.5D));
    private final BooleanSetting stunSlam = register(new BooleanSetting(
            "Stun Slam", "Break target shield with an axe before executing the mace slam.", true));
    private final ModeSetting stunSlamMode = register(new ModeSetting(
            "StunSlam", "Execution mode for shield stun-slam.", "Normal", "Normal", "Legit"));
    private final NumberSetting stunSlamAccuracy = register(new NumberSetting(
            "Stun Slam Accuracy", "Execution accuracy percentage for shield stun-slams.", 100.0D, 0.0D, 100.0D, 1.0D));

    // 3. StunSlam Ticks & Pacing (Screenshot 3/4 order)
    private final BooleanSetting axeOnlyStunSlam = register(new BooleanSetting(
            "Axe Only StunSlam", "Require an axe in hotbar to initiate a stun-slam.", false));
    private final BooleanSetting customStunSlam = register(new BooleanSetting(
            "CustomStunSlam", "Use custom tick ranges for stun-slam weapon switching and attack.", false));
    private final BooleanSetting properStunslam = register(new BooleanSetting(
            "ProperStunslam", "Verify target is actively blocking with a shield before stun-slamming.", false));
    private final NumberSetting minStunSlamTicksSwitch = register(new NumberSetting(
            "Min Stun Slam Ticks (Switch)", "Minimum ticks before switching from axe to mace.", 2.0D, 1.0D, 20.0D, 1.0D));
    private final NumberSetting maxStunSlamTicksSwitch = register(new NumberSetting(
            "Max Stun Slam Ticks (Switch)", "Maximum ticks before switching from axe to mace.", 3.0D, 1.0D, 20.0D, 1.0D));
    private final NumberSetting minStunSlamTicksAttack = register(new NumberSetting(
            "Min Stun Slam Ticks (Attack)", "Minimum ticks after switching to mace before attacking.", 2.0D, 1.0D, 20.0D, 1.0D));
    private final NumberSetting maxStunSlamTicksAttack = register(new NumberSetting(
            "Max Stun Slam Ticks (Attack)", "Maximum ticks after switching to mace before attacking.", 3.0D, 1.0D, 20.0D, 1.0D));
    private final BooleanSetting rapptureShieldOnetap = register(new BooleanSetting(
            "RapptureShield Onetap", "Execute shield rupture and mace slam sequence.", false));
    private final NumberSetting onetapMinCps = register(new NumberSetting(
            "Onetap Min CPS", "Minimum CPS rate.", 10.0D, 1.0D, 975.0D, 1.0D));
    private final NumberSetting onetapMaxCps = register(new NumberSetting(
            "Onetap Max CPS", "Maximum CPS rate.", 14.0D, 1.0D, 975.0D, 1.0D));
    private final BooleanSetting onlyOnNonGlide = register(new BooleanSetting(
            "Only On NonGlide", "Only aim and attack when not actively gliding with an Elytra.", false));

    // 4. Aim Modes, Extra Slot & Helpers
    private final BooleanSetting stillAim = register(new BooleanSetting(
            "Still Aim", "Reserved setting (strictly overridden by ground & elevation lockout).", false));
    private final BooleanSetting weaponOnly = register(new BooleanSetting(
            "Weapon Only", "Only activate targeting and attacks while holding a weapon.", false));
    private final MultiModeSetting extraSlotSetting = register(new MultiModeSetting(
            "Extra Slot", "Secondary hotbar slots (1-9) for axe, backup mace, or swap return.", HOTBAR_SLOTS));
    private final BooleanSetting dTapHelper = register(new BooleanSetting(
            "DTap Helper", "Queue a follow-up double-tap attack when hurt-time allows.", false));
    private final BooleanSetting clickSim = register(new BooleanSetting(
            "ClickSim", "Simulate client mouse click and hand swing during attacks.", true));
    private final ModeSetting aimMode = register(new ModeSetting(
            "Aim Mode", "Rotation synchronization mode (client-server matched).", "Smooth", "Smooth", "Silent", "Strict"));
    private final ModeSetting stopAim = register(new ModeSetting(
            "Stop Aim", "Target point convergence mode for rotation termination.", "Exact center", "Exact center", "Hitbox", "Multipoint", "Never"));
    private final NumberSetting hitboxAccuracy = register(new NumberSetting(
            "Hitbox Accuracy", "Hitbox contraction ratio for precision aim targeting.", 0.00D, 0.00D, 1.00D, 0.01D));

    // 5. Advanced Target Filtering
    private final BooleanSetting ignoreFriends = register(new BooleanSetting(
            "Ignore Friends", "Ignore players registered in the friend list.", true));
    private final BooleanSetting renderPred = register(new BooleanSetting(
            "Render Pred", "Render predicted target bounding box in 3D world space.", false));
    private final BooleanSetting optimalMace = register(new BooleanSetting(
            "OptimalMace", "Select the optimal mace (Density vs Breach) for current fall height.", false));
    private final BooleanSetting smartAutoSwap = register(new BooleanSetting(
            "SmartAutoSwap", "Pre-synchronize hotbar weapon switching based on impact kinematics.", false));
    private final BooleanSetting densityIfLower = register(new BooleanSetting(
            "Density If Lower", "Prefer Density mace even when fall distance is below Density Height.", false));
    private final BooleanSetting onlyStunslam = register(new BooleanSetting(
            "Only Stunslam", "Only execute attacks when performing a shield stun-slam.", false));
    private final BooleanSetting targetMode = register(new BooleanSetting(
            "Target Mode", "Enable autonomous Elytra flight and stage targeting state machine.", false));

    // Controllers & State Handlers
    private final TargetPredictor predictor = new TargetPredictor();
    private final FlightController flightController = new FlightController(predictor);
    private final AttackHandler attackHandler = new AttackHandler();
    private final ArmorSwapHandler armorSwapHandler = new ArmorSwapHandler(this::buildSettings);
    private final FireworkHandler fireworkHandler = new FireworkHandler(this::buildSettings);
    private final StopWatch fireworkTimer = new StopWatch();
    private final StageHandler stageHandler = new StageHandler(armorSwapHandler, fireworkHandler, attackHandler, fireworkTimer);
    private final TargetFinder targetFinder = new TargetFinder();
    private final PdKinematicRotateConstructor rotateConstructor = new PdKinematicRotateConstructor();

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
    private int targetAxeSlot = -1;
    private int lastTargetScanTick = -1;
    private boolean lastUsedDensityMace = false;
    private boolean lastUsedBreachMace = false;
    private boolean silentRotationActive = false;
    private double airbornePeakY = Double.NaN;

    // Proportional-Derivative (PD) Distance-Scaled Deceleration State
    private double prevYawError = 0.0D;
    private double prevPitchError = 0.0D;
    private boolean hasPrevError = false;
    private long lastSmoothFrameNs = 0L;

    public MaceTarget() {
        super("AutoMace", "Strict elevation-gated PD kinematic aim assist and legit slotbar mace controller.", ModuleCategory.COMBAT);
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
        suspendSilentRotation();
        CombatStateManager.releaseMaceSmashLock();
        resetAllStates();
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            suspendSilentRotation();
            CombatStateManager.releaseMaceSmashLock();
            resetAllStates();
            return;
        }

        if (isGroundedOrAscendingLockout(client)) {
            releaseCameraAndClearTargetLock();
        }

        armorSwapHandler.processLoop();
        if (targetMode.getValue()) {
            fireworkHandler.processLoop();
            if (!armorSwapHandler.isActive() && !fireworkHandler.isActive() && target != null && target.isAlive()) {
                processStage();
            }
        }
    }

    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && isGroundedOrAscendingLockout(client)) {
            releaseCameraAndClearTargetLock();
        }
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        if (event.getLayer() != DrawEvent.Layer.GAME) {
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
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

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
        updateHandlers();

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
            findTargetZeroAlloc(client);
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target) || !hasClearLineOfSightToTarget(client, target)) {
            if (smashState != SmashSequenceState.IDLE) {
                transitionToSwapBackRestore(client);
            } else {
                CombatStateManager.releaseMaceSmashLock();
            }
            releaseCameraAndClearTargetLock();
            return;
        }

        updatePriority1MutexLock(client, target);

        predictor.update(target);

        if (!shouldAimAtTarget(client)) {
            resetKinematicAimState();
            return;
        }

        suspendSilentRotation();
        long nowNs = System.nanoTime();
        if (lastSmoothFrameNs == 0L || nowNs - lastSmoothFrameNs > 22_000_000L) {
            applyDistanceScaledPdAim(client, target, 1.0D);
            lastSmoothFrameNs = nowNs;
        }
    }

    private void runPostRotationPhase(Minecraft client) {
        if (smashState != SmashSequenceState.IDLE) {
            processSmashSequenceStateMachine(client);
            return;
        }

        if (isGroundedOrAscendingLockout(client)) {
            CombatStateManager.releaseMaceSmashLock();
            return;
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target) || !hasClearLineOfSightToTarget(client, target)) {
            CombatStateManager.releaseMaceSmashLock();
            return;
        }

        if (isManualInputBlockingSwap(client)) {
            return;
        }

        if (CombatStateManager.isElytraAscentLocked() && !CombatStateManager.isMaceSmashLocked()) {
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
        if (targetMode.getValue() && stageHandler.getStage() == Stage.FLYING_UP) {
            return false;
        }
        return client.player.onGround()
                || client.player.getDeltaMovement().y >= 0.0D
                || client.player.isInWater()
                || client.player.isInLava()
                || client.player.getAbilities().flying;
    }

    private void releaseCameraAndClearTargetLock() {
        suspendSilentRotation();
        resetKinematicAimState();
        target = null;
        targetFinder.releaseTarget();
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
        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerY = box.minY + (box.getYsize() * 0.5D);
        double centerZ = (box.minZ + box.maxZ) * 0.5D;
        return new Vec3(centerX, centerY, centerZ);
    }

    private Angle computeExactCenterAimAngle(Minecraft client, LivingEntity entity) {
        if (targetMode.getValue() && stageHandler.getStage() == Stage.FLYING_UP) {
            return flightController.calculateAngle(entity, Stage.FLYING_UP);
        }
        Vec3 actualEye = client.player.getEyePosition(1.0F);
        Vec3 exactCenter = getExactBoundingBoxCenter(entity.getBoundingBox());
        return MathAngle.fromTo(actualEye, exactCenter).adjustSensitivity();
    }

    private void updatePriority1MutexLock(Minecraft client, LivingEntity targetEntity) {
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

        boolean wantStunSlam = shouldTriggerStunSlam(client, targetEntity);
        if (onlyStunslam.getValue() && !wantStunSlam) {
            return;
        }

        int maceSlot = resolveMaceSlot(client);
        boolean holdingMace = isMaceStack(client.player.getMainHandItem());
        if (!holdingMace && (!autoSwitch.getValue() || maceSlot == -1)) {
            return;
        }

        CombatStateManager.acquireMaceSmashLock();

        int currentSlot = client.player.getInventory().getSelectedSlot();
        savedHotbarSlot = currentSlot;
        targetMaceSlot = holdingMace && !optimalMace.getValue() ? currentSlot : (maceSlot != -1 ? maceSlot : currentSlot);

        if (wantStunSlam) {
            double accuracyRoll = ThreadLocalRandom.current().nextDouble(0.0D, 100.0D);
            if (accuracyRoll <= stunSlamAccuracy.getValue()) {
                int axeSlot = resolveAxeSlot(client);
                if (axeSlot != -1) {
                    targetAxeSlot = axeSlot;
                    if (currentSlot != axeSlot) {
                        selectHotbarSlotLegit(client, axeSlot);
                        stateCommittedTick = client.player.tickCount;
                        stateTickTimer.reset();
                        smashState = SmashSequenceState.STEP_1_AXE_STUN_ATTACK;
                    } else if (currentDist <= swingRange.getValue()) {
                        performMaceOrAxeAttack(client, targetEntity);
                        stateCommittedTick = client.player.tickCount;
                        stateTickTimer.reset();
                        smashState = SmashSequenceState.STEP_2_SELECT_MACE_AFTER_STUN;
                    }
                    return;
                } else if (axeOnlyStunSlam.getValue()) {
                    CombatStateManager.releaseMaceSmashLock();
                    return;
                }
            } else if (onlyStunslam.getValue()) {
                CombatStateManager.releaseMaceSmashLock();
                return;
            }
        }

        if (currentSlot != targetMaceSlot) {
            selectHotbarSlotLegit(client, targetMaceSlot);
            recordUsedMaceEnchantments(client.player.getInventory().getItem(targetMaceSlot));
            stateCommittedTick = client.player.tickCount;
            stateTickTimer.reset();
            smashState = SmashSequenceState.STEP_1_MACE_SMASH_ATTACK;
            return;
        }

        if (currentDist <= swingRange.getValue()) {
            recordUsedMaceEnchantments(client.player.getMainHandItem());
            performMaceOrAxeAttack(client, targetEntity);
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
                if (dist <= swingRange.getValue() + 0.30D) {
                    performMaceOrAxeAttack(client, target);
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
                    recordUsedMaceEnchantments(client.player.getInventory().getItem(maceSlot));
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
                if (dist <= swingRange.getValue() + 0.25D) {
                    performMaceOrAxeAttack(client, target);
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

    /**
     * Pure Client Slotbar Change (Zero Duplicate UpdateSelectedSlotC2SPacket):
     * Lets Vanilla MultiPlayerGameMode#ensureHasSentCarriedItem() dispatch the slot update cleanly.
     */
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
            targetAxeSlot = -1;
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
        if (noDensitySwapBack.getValue() && lastUsedDensityMace) {
            savedHotbarSlot = -1;
            return;
        }
        if (noBreachSwapBack.getValue() && lastUsedBreachMace) {
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
        targetAxeSlot = -1;
    }

    private void performMaceOrAxeAttack(Minecraft client, LivingEntity targetEntity) {
        if (client.player == null || client.gameMode == null || targetEntity == null) {
            return;
        }
        if (isWhitelistedFriend(client, targetEntity)) {
            return;
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
    }

    private boolean canEngageModule(Minecraft client) {
        if (client.player == null || client.level == null) {
            return false;
        }
        if (isGroundedOrAscendingLockout(client)) {
            return false;
        }
        if (onlyOnNonGlide.getValue() && client.player.isFallFlying()) {
            return false;
        }
        if (weaponOnly.getValue() && !isHoldingWeapon(client.player.getMainHandItem())) {
            return false;
        }
        if (targetMode.getValue()) {
            return true;
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
        if (!(entity instanceof Player player)) {
            return false;
        }
        if (FriendUtils.isFriend(player.getName().getString())) {
            return true;
        }
        return ignoreFriends.getValue() && client.player != null && client.player.isAlliedTo(player);
    }

    private boolean hasClearLineOfSightToTarget(Minecraft client, LivingEntity entity) {
        if (client.player == null || client.level == null || entity == null) {
            return false;
        }
        if (client.player.hasLineOfSight(entity)) {
            return true;
        }
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 center = getExactBoundingBoxCenter(entity.getBoundingBox());
        return isPointUnobstructed(client, eyePos, center);
    }

    private boolean isPointUnobstructed(Minecraft client, Vec3 fromEye, Vec3 targetPoint) {
        if (client.level == null || client.player == null) {
            return false;
        }
        BlockHitResult hit = client.level.clip(new ClipContext(
                fromEye,
                targetPoint,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                client.player
        ));
        return hit.getType() == HitResult.Type.MISS;
    }

    @SubscribeEvent
    public void onWorldRender(WorldRenderEvent event) {
        if (!renderPred.getValue() || target == null || !target.isAlive()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || isGroundedOrAscendingLockout(client) || isWhitelistedFriend(client, target)) {
            return;
        }
        Stage currentStage = targetMode.getValue() ? stageHandler.getStage() : Stage.ATTACKING;
        Vec3 predictedCenter = predictor.getPredictedPosition(target, currentStage);
        double dx = predictedCenter.x - target.getX();
        double dy = predictedCenter.y - target.getY();
        double dz = predictedCenter.z - target.getZ();
        AABB predictedBox = target.getBoundingBox().move(dx, dy, dz);

        Render3D.drawBox(predictedBox, PREDICTION_FILL_COLOR, 1.2F, false, true, false);
        Render3D.drawBox(predictedBox, PREDICTION_OUTLINE_COLOR, 1.2F, true, false, false);
    }

    @SubscribeEvent
    public void onInput(InputEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (armorSwapHandler.getMovement().isBlocked()) {
            event.setDirectionalLow(false, false, false, false);
            event.setJumping(false);
            event.setSprinting(false);
            client.player.setSprinting(false);
            return;
        }
        if (!targetMode.getValue()) {
            return;
        }
        if (fireworkHandler.getMovement().isBlocked()) {
            event.setDirectionalLow(false, false, false, false);
            event.setJumping(false);
            event.setSprinting(false);
            client.player.setSprinting(false);
        }
        if (target != null && InventoryUtils.hasElytra() && stageHandler.getStage() == Stage.FLYING_UP) {
            if (client.player.onGround()) {
                event.setJumping(true);
            } else if (!client.player.isFallFlying() && !client.player.getAbilities().flying) {
                event.setJumping(client.player.tickCount % 2 == 0);
            }
        }
    }

    private void suspendSilentRotation() {
        if (silentRotationActive) {
            AngleConnection.INSTANCE.startReturning();
            silentRotationActive = false;
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
        if (!hasClearLineOfSightToTarget(client, target)) {
            return false;
        }
        double dist = getPrecisionDistanceToTarget(client, target);
        double maxRange = targetMode.getValue() ? Math.max(aimRange.getValue(), 128.0D) : aimRange.getValue();
        if (dist > maxRange) {
            return false;
        }
        if (targetMode.getValue()) {
            Stage currentStage = stageHandler.getStage();
            return (currentStage == Stage.FLYING_UP && InventoryUtils.hasElytra() && client.player.isFallFlying())
                    || currentStage == Stage.TARGETTING
                    || currentStage == Stage.ATTACKING;
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
        boolean preferDensity = shouldPreferDensityMace(client);
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < 9; i++) {
            if (!isSlotAllowedByFilter(i, slotSetting, extraSlotSetting)) {
                continue;
            }
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!isMaceStack(stack)) {
                continue;
            }
            int score = scoreMaceStack(stack, preferDensity);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot != -1) {
            return bestSlot;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!isMaceStack(stack)) {
                continue;
            }
            int score = scoreMaceStack(stack, preferDensity);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private boolean shouldPreferDensityMace(Minecraft client) {
        double fall = getEffectiveFallDistance(client);
        boolean aboveDensityHeight = fall >= densityHeight.getValue();
        if (densityIfLower.getValue()) {
            return !aboveDensityHeight || fall >= minFallDist.getValue();
        }
        return aboveDensityHeight;
    }

    private int scoreMaceStack(ItemStack stack, boolean preferDensity) {
        int densityLevel = getEnchantmentLevel(stack, "density");
        int breachLevel = getEnchantmentLevel(stack, "breach");
        int windBurstLevel = getEnchantmentLevel(stack, "wind_burst");

        if (!optimalMace.getValue()) {
            return 100 + densityLevel * 10 + breachLevel * 10 + windBurstLevel * 5;
        }
        if (preferDensity) {
            return 1000 + densityLevel * 200 + windBurstLevel * 25 + breachLevel * 10;
        }
        return 1000 + breachLevel * 200 + windBurstLevel * 25 + densityLevel * 10;
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

    private void recordUsedMaceEnchantments(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            lastUsedDensityMace = false;
            lastUsedBreachMace = false;
            return;
        }
        lastUsedDensityMace = getEnchantmentLevel(stack, "density") > 0;
        lastUsedBreachMace = getEnchantmentLevel(stack, "breach") > 0;
    }

    private int getEnchantmentLevel(ItemStack stack, String enchantmentKeySubstring) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            String id = entry.getKey().getRegisteredName().toLowerCase();
            if (id.contains(enchantmentKeySubstring)) {
                return entry.getIntValue();
            }
        }
        return 0;
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

    private SwapSettings buildSettings() {
        return SwapSettings.instant();
    }

    private void updateHandlers() {
        stageHandler.setSilentMode(true);
        stageHandler.setReallyWorldMode(false);
        stageHandler.setHeight(Math.max(20.0F, densityHeight.getFloat() * 3.0F));
        flightController.setPredictionEnabled(true);
        flightController.setHeight(Math.max(20.0F, densityHeight.getFloat() * 3.0F));
    }

    private void findTargetZeroAlloc(Minecraft client) {
        if (isGroundedOrAscendingLockout(client)) {
            target = null;
            return;
        }

        double maxRange = targetMode.getValue()
                ? Math.max(aimRange.getValue(), 128.0D)
                : aimRange.getValue();
        double maxRangeSqr = maxRange * maxRange;
        double maxFov = maceFov.getValue();
        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();

        LivingEntity bestTarget = null;
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
                if (AntiBot.shouldIgnore(player)) {
                    continue;
                }
                if (ignoreFriends.getValue() && isWhitelistedFriend(client, player)) {
                    continue;
                }
            } else if (!(living instanceof Mob) && !(living instanceof Animal)) {
                continue;
            }

            if (onlyStunslam.getValue() && properStunslam.getValue() && !isTargetBlockingWithShield(living)) {
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

            if (!hasClearLineOfSightToTarget(client, living)) {
                continue;
            }

            bestDistSqr = distSqr;
            bestTarget = living;
        }

        target = bestTarget;
    }

    private void processStage() {
        boolean hasElytra = InventoryUtils.hasElytra();
        switch (stageHandler.getStage()) {
            case PREPARE -> stageHandler.handlePrepare(hasElytra);
            case FLYING_UP -> stageHandler.handleFlyingUp(target, hasElytra);
            case TARGETTING -> stageHandler.handleTargetting(target);
            case ATTACKING -> stageHandler.handleAttacking(target, hasElytra);
        }
    }

    private void resetAllStates() {
        smashState = SmashSequenceState.IDLE;
        stateCommittedTick = -1;
        savedHotbarSlot = -1;
        targetMaceSlot = -1;
        targetAxeSlot = -1;
        lastTargetScanTick = -1;
        lastUsedDensityMace = false;
        lastUsedBreachMace = false;
        silentRotationActive = false;
        airbornePeakY = Double.NaN;
        resetKinematicAimState();
        armorSwapHandler.reset();
        fireworkHandler.reset();
        attackHandler.reset();
        predictor.reset();
        target = null;
        targetFinder.releaseTarget();
        stageHandler.reset();
    }

    private final class PdKinematicRotateConstructor extends RotateConstructor {
        private PdKinematicRotateConstructor() {
            super("PdKinematicMace");
        }

        @Override
        public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && entity instanceof LivingEntity living) {
                if (isCrosshairInsideInner20PercentDeadzone(client, living, currentAngle)) {
                    return currentAngle;
                }
            }

            Angle angleDelta = MathAngle.calculateDelta(currentAngle, targetAngle);
            float yawDelta = angleDelta.getYaw();
            float pitchDelta = angleDelta.getPitch();
            double angularDistance = Math.hypot(yawDelta, pitchDelta);
            if (angularDistance < 0.05D) {
                return currentAngle;
            }

            double distNorm = Mth.clamp(angularDistance / PD_DECELERATION_CONE_DEGREES, 0.12D, 1.0D);
            double decelFactor = distNorm * (2.0D - distNorm);
            double maxTickSpeed = Mth.lerp(
                    Mth.clamp(angularDistance / 45.0D, 0.0D, 1.0D),
                    MIN_FAR_ROTATION_DEG_PER_TICK,
                    MAX_FAR_ROTATION_DEG_PER_TICK
            ) * decelFactor;

            float stepYaw = (float) Mth.clamp(yawDelta * 0.65D, -maxTickSpeed, maxTickSpeed);
            float stepPitch = (float) Mth.clamp(pitchDelta * 0.65D, -maxTickSpeed, maxTickSpeed);

            return new Angle(
                    currentAngle.getYaw() + stepYaw,
                    Mth.clamp(currentAngle.getPitch() + stepPitch, -89.5F, 89.5F)
            ).adjustSensitivity();
        }

        @Override
        public Vec3 randomValue() {
            return Vec3.ZERO;
        }
    }
}