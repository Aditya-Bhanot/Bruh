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

    // Benchmark Constants: 0.15° Deadzone, Zenith/Nadir Gimbal Lock Guard (>80° pitch = 60% yaw damping), Mutex Ranges
    private static final double MICRO_DEADZONE_DEGREES = 0.15D;
    private static final double DECELERATION_CONE_DEGREES = 16.0D;
    private static final float GIMBAL_LOCK_PITCH_THRESHOLD = 80.0F;
    private static final double GIMBAL_LOCK_YAW_DAMPING = 0.40D;
    private static final double PRE_HIT_MUTEX_LOCKOUT_RANGE = 4.0D;

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
            "Auto Switch", "Automatically switch hotbar slot to the mace when attacking.", false));
    private final BooleanSetting swapBack = register(new BooleanSetting(
            "Swap Back", "Restore previous or selected hotbar slot after attacking.", false));
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
            "Aim Speed", "Angular rotation speed during target tracking.", 35.0D, 1.0D, 100.0D, 0.5D));
    private final NumberSetting aimSmooth = register(new NumberSetting(
            "Aim Smooth", "Smoothing factor applied to rotation interpolation.", 0.00D, 0.00D, 10.00D, 0.01D));
    private final NumberSetting minFallDist = register(new NumberSetting(
            "Min Fall Dist", "Minimum fall distance required to trigger a mace slam.", 1.5D, 0.0D, 20.0D, 0.1D));
    private final NumberSetting cooldownMs = register(new NumberSetting(
            "Cooldown (ms)", "Minimum cooldown in milliseconds between slam sequences.", 125.0D, 0.0D, 1000.0D, 5.0D));
    private final NumberSetting maceSwapDelayMs = register(new NumberSetting(
            "Mace Swap Delay (ms)", "Delay in milliseconds after switching to mace before attacking.", 0.0D, 0.0D, 500.0D, 1.0D));
    private final NumberSetting densityHeight = register(new NumberSetting(
            "Density Height", "Fall distance threshold for prioritizing a Density mace.", 7.0D, 1.0D, 50.0D, 0.5D));
    private final BooleanSetting stunSlam = register(new BooleanSetting(
            "Stun Slam", "Break target shield with an axe before executing the mace slam.", true));
    private final ModeSetting stunSlamMode = register(new ModeSetting(
            "StunSlam", "Execution mode for shield stun-slam.", "Normal", "Normal", "Silent", "Legit", "Packet"));
    private final NumberSetting stunSlamAccuracy = register(new NumberSetting(
            "Stun Slam Accuracy", "Execution accuracy percentage for shield stun-slams.", 100.0D, 0.0D, 100.0D, 1.0D));

    // 3. StunSlam Ticks & RapptureShield Onetap (Screenshot 3/4 order)
    private final BooleanSetting axeOnlyStunSlam = register(new BooleanSetting(
            "Axe Only StunSlam", "Require an axe in hotbar to initiate a stun-slam.", false));
    private final BooleanSetting customStunSlam = register(new BooleanSetting(
            "CustomStunSlam", "Use custom tick ranges for stun-slam weapon switching and attack.", false));
    private final BooleanSetting properStunslam = register(new BooleanSetting(
            "ProperStunslam", "Verify target is actively blocking with a shield before stun-slamming.", false));
    private final NumberSetting minStunSlamTicksSwitch = register(new NumberSetting(
            "Min Stun Slam Ticks (Switch)", "Minimum ticks before switching from axe to mace.", 1.0D, 0.0D, 20.0D, 1.0D));
    private final NumberSetting maxStunSlamTicksSwitch = register(new NumberSetting(
            "Max Stun Slam Ticks (Switch)", "Maximum ticks before switching from axe to mace.", 1.0D, 0.0D, 20.0D, 1.0D));
    private final NumberSetting minStunSlamTicksAttack = register(new NumberSetting(
            "Min Stun Slam Ticks (Attack)", "Minimum ticks after switching to mace before attacking.", 1.0D, 0.0D, 20.0D, 1.0D));
    private final NumberSetting maxStunSlamTicksAttack = register(new NumberSetting(
            "Max Stun Slam Ticks (Attack)", "Maximum ticks after switching to mace before attacking.", 1.0D, 0.0D, 20.0D, 1.0D));
    private final BooleanSetting rapptureShieldOnetap = register(new BooleanSetting(
            "RapptureShield Onetap", "Execute high-CPS shield rupture and instant one-tap mace slam.", false));
    private final NumberSetting onetapMinCps = register(new NumberSetting(
            "Onetap Min CPS", "Minimum CPS rate for RapptureShield Onetap burst.", 900.0D, 1.0D, 1000.0D, 1.0D));
    private final NumberSetting onetapMaxCps = register(new NumberSetting(
            "Onetap Max CPS", "Maximum CPS rate for RapptureShield Onetap burst.", 975.0D, 1.0D, 1000.0D, 1.0D));
    private final BooleanSetting onlyOnNonGlide = register(new BooleanSetting(
            "Only On NonGlide", "Only aim and attack when not actively gliding with an Elytra.", false));

    // 4. Aim Modes (Strict, Smooth, Silent), Extra Slot & Click Simulation Helpers (Screenshot 4/5 order)
    private final BooleanSetting stillAim = register(new BooleanSetting(
            "Still Aim", "Continue aiming at targets while standing on the ground.", false));
    private final BooleanSetting weaponOnly = register(new BooleanSetting(
            "Weapon Only", "Only activate targeting and attacks while holding a weapon.", false));
    private final MultiModeSetting extraSlotSetting = register(new MultiModeSetting(
            "Extra Slot", "Secondary hotbar slots (1-9) for axe, backup mace, or swap return.", HOTBAR_SLOTS));
    private final BooleanSetting dTapHelper = register(new BooleanSetting(
            "DTap Helper", "Queue a follow-up double-tap attack when hurt-time allows.", false));
    private final BooleanSetting clickSim = register(new BooleanSetting(
            "ClickSim", "Simulate client mouse click and hand swing during attacks.", false));
    private final ModeSetting aimMode = register(new ModeSetting(
            "Aim Mode", "Rotation synchronization mode.", "Strict", "Strict", "Smooth", "Silent"));
    private final ModeSetting stopAim = register(new ModeSetting(
            "Stop Aim", "Target point convergence mode for rotation termination.", "Exact center", "Exact center", "Hitbox", "Multipoint", "Never"));
    private final NumberSetting hitboxAccuracy = register(new NumberSetting(
            "Hitbox Accuracy", "Hitbox contraction ratio for precision aim targeting.", 0.00D, 0.00D, 1.00D, 0.01D));

    // 5. Advanced Target Filtering (Screenshot 5 order)
    private final BooleanSetting ignoreFriends = register(new BooleanSetting(
            "Ignore Friends", "Ignore players registered in the friend list.", false));
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
    private final ClampedCubicRotateConstructor rotateConstructor = new ClampedCubicRotateConstructor();

    // Combat Timing & Staged Execution State
    private final StopWatch cooldownTimer = new StopWatch();
    private final StopWatch stunClickDelayTimer = new StopWatch();
    private final StopWatch maceSwapDelayTimer = new StopWatch();

    private enum SlamPhase {
        IDLE,
        WAIT_SWITCH_TO_MACE,
        WAIT_MACE_ATTACK,
        WAIT_DTAP_FOLLOWUP,
        WAIT_SWAP_BACK
    }

    private LivingEntity target;
    private SlamPhase slamPhase = SlamPhase.IDLE;
    private int phaseTicksRemaining = 0;
    private int savedHotbarSlot = -1;
    private int pendingMaceSlot = -1;
    private int lastTargetScanTick = -1;
    private boolean lastUsedDensityMace = false;
    private boolean lastUsedBreachMace = false;
    private boolean pendingImmediateSwapBack = false;
    private boolean silentRotationActive = false;
    private boolean inStrikeMutexLockout = false;
    private double airbornePeakY = Double.NaN;

    // Silent & Smooth Velocity State
    private float prevSilentYawStep = 0.0F;
    private float prevSilentPitchStep = 0.0F;
    private double smoothYawVelocity = 0.0D;
    private double smoothPitchVelocity = 0.0D;
    private double prevSmoothYawStep = 0.0D;
    private double prevSmoothPitchStep = 0.0D;
    private long lastSmoothFrameNs = 0L;

    public MaceTarget() {
        super("AutoMace", "Advanced automatic mace and stun-slam combat helper.", ModuleCategory.COMBAT);
        instance = this;
    }

    public static MaceTarget getInstance() {
        return instance;
    }

    public LivingEntity getActiveTarget() {
        return target;
    }

    public boolean isMidSlamSequence() {
        return slamPhase != SlamPhase.IDLE || pendingImmediateSwapBack;
    }

    /**
     * Priority 1 Mutex Lockout State:
     * True when AutoMace is within pre-hit range (<= 4.0 blocks) during an active smash fall
     * or waiting to complete its next-tick SwapBack. Locks out AutoElytra re-equipping.
     */
    public boolean isInStrikeMutexLockout() {
        return inStrikeMutexLockout || slamPhase != SlamPhase.IDLE || pendingImmediateSwapBack;
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
        if (targetMode.getValue() && Minecraft.getInstance().player != null) {
            equipChestplateOnDisable();
        }
        executeImmediateSwapBack(Minecraft.getInstance(), true);
        armorSwapHandler.forceRestore();
        fireworkHandler.forceRestore();
        suspendSilentRotation();
        resetAllStates();
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            suspendSilentRotation();
            resetAllStates();
            return;
        }

        // Ground Failsafe: if onGround becomes true while holding the Mace, immediately swap back to Sword / previous slot
        if (client.player.onGround()) {
            inStrikeMutexLockout = false;
            if (pendingImmediateSwapBack || isMaceStack(client.player.getMainHandItem())) {
                executeGroundFailsafeSwapBack(client);
            }
        }

        armorSwapHandler.processLoop();
        if (targetMode.getValue()) {
            fireworkHandler.processLoop();
            if (!armorSwapHandler.isActive() && !fireworkHandler.isActive() && target != null && target.isAlive()) {
                processStage();
            }
        }
    }

    /**
     * Sub-Tick Input & Physics Hook (LocalPlayer#aiStep / tickMovement):
     * Processes immediate next-tick SwapBack and evaluates tick-perfect strike interception
     * at the start of the movement tick before position packets are sent.
     */
    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        // 1. Ground Failsafe check at the start of the movement tick
        if (client.player.onGround()) {
            inStrikeMutexLockout = false;
            if (pendingImmediateSwapBack || isMaceStack(client.player.getMainHandItem())) {
                executeGroundFailsafeSwapBack(client);
            }
            return;
        }

        // 2. Immediate Next-Tick SwapBack after a single-tick Mace strike
        if (pendingImmediateSwapBack && slamPhase == SlamPhase.IDLE) {
            executeImmediateSwapBack(client, false);
            return;
        }

        // 3. Sub-tick strike evaluation when already aimed onto the target hitbox
        if (target != null && target.isAlive()
                && slamPhase == SlamPhase.IDLE
                && !isPriority2ManualInputActive(client)
                && cooldownTimer.finished(cooldownMs.getValue())
                && isFallingForSmash(client)
                && isWithinNextTickPredictedSmashWindow(client, target)
                && isLookRayValidOnTarget(client, target)) {
            tryInitiateSlamSequence(client, target);
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

        if ("Silent".equalsIgnoreCase(aimMode.getValue())) {
            lastSmoothFrameNs = 0L;
            return;
        }

        if (target == null || !target.isAlive() || !canEngageModule(client) || !shouldAimAtTarget(client)) {
            resetSmoothCameraState();
            return;
        }

        long nowNs = System.nanoTime();
        double frameScale = lastSmoothFrameNs > 0L ? (nowNs - lastSmoothFrameNs) / 16_666_666.7D : 1.0D;
        frameScale = Mth.clamp(frameScale, 0.05D, 2.5D);
        lastSmoothFrameNs = nowNs;

        applyFrameSmoothCameraAim(client, target, frameScale);
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

        // Ground Failsafe
        if (client.player.onGround()) {
            inStrikeMutexLockout = false;
            if (pendingImmediateSwapBack || isMaceStack(client.player.getMainHandItem())) {
                executeGroundFailsafeSwapBack(client);
            }
        }

        // Immediate Next-Tick SwapBack
        if (pendingImmediateSwapBack && slamPhase == SlamPhase.IDLE) {
            executeImmediateSwapBack(client, false);
        }

        if (!canEngageModule(client)) {
            if (slamPhase != SlamPhase.IDLE) {
                executeImmediateSwapBack(client, false);
                resetSlamState();
            }
            inStrikeMutexLockout = false;
            suspendSilentRotation();
            resetSmoothCameraState();
            target = null;
            targetFinder.releaseTarget();
            return;
        }

        // 20Hz Zero-Allocation Target Scanning (runs once per client tick)
        if (lastTargetScanTick != client.player.tickCount) {
            lastTargetScanTick = client.player.tickCount;
            findTargetZeroAlloc(client);
        }

        if (target == null || !target.isAlive() || !hasClearLineOfSightToTarget(client, target)) {
            if (slamPhase != SlamPhase.IDLE) {
                executeImmediateSwapBack(client, false);
                resetSlamState();
            }
            inStrikeMutexLockout = false;
            suspendSilentRotation();
            resetSmoothCameraState();
            target = null;
            targetFinder.releaseTarget();
            return;
        }

        predictor.update(target);

        double currentDist = getPrecisionDistanceToTarget(client, target);
        // Priority 1 Mutex Lockout: lock out AutoElytra when <= 4.0 blocks and falling with >= 1.5 fallDistance
        inStrikeMutexLockout = currentDist <= PRE_HIT_MUTEX_LOCKOUT_RANGE
                && isFallingForSmash(client)
                && client.player.getDeltaMovement().y < -0.1D;

        if (!shouldAimAtTarget(client)) {
            suspendSilentRotation();
            resetSmoothCameraState();
            return;
        }

        boolean inStrikeProximity = currentDist <= swingRange.getValue() + 0.65D && isFallingForSmash(client);

        String mode = aimMode.getValue();
        if ("Smooth".equalsIgnoreCase(mode)) {
            long nowNs = System.nanoTime();
            if (lastSmoothFrameNs == 0L || nowNs - lastSmoothFrameNs > 22_000_000L) {
                applyFrameSmoothCameraAim(client, target, 1.0D);
                lastSmoothFrameNs = nowNs;
            }
            if (inStrikeProximity || slamPhase != SlamPhase.IDLE) {
                Angle exactAngle = computePinpointStrikeAngle(client, target);
                if (exactAngle != null && MathAngle.difference(MathAngle.cameraAngle(), exactAngle) <= 22.0D) {
                    queueSilentServerRotation(exactAngle);
                } else {
                    suspendSilentRotation();
                }
            } else {
                suspendSilentRotation();
            }
        } else {
            Angle currentRef = AngleConnection.INSTANCE.getRotation() != null
                    ? AngleConnection.INSTANCE.getRotation()
                    : MathAngle.cameraAngle();
            Angle targetAngle = (inStrikeProximity || slamPhase != SlamPhase.IDLE)
                    ? computePinpointStrikeAngle(client, target)
                    : computeAccurateAimAngle(client, target, currentRef);
            if (targetAngle != null) {
                queueSilentServerRotation(targetAngle);
            } else {
                suspendSilentRotation();
            }
        }
    }

    private void runPostRotationPhase(Minecraft client) {
        if (target == null || !target.isAlive() || !hasClearLineOfSightToTarget(client, target)) {
            if (slamPhase != SlamPhase.IDLE) {
                executeImmediateSwapBack(client, false);
                resetSlamState();
            }
            inStrikeMutexLockout = false;
            return;
        }

        // Priority 2 Mutex Check: never switch slots or attack while player is holding right-click to eat a GApple / throw Wind Charge
        if (isPriority2ManualInputActive(client)) {
            return;
        }

        if (attackHandler.isPendingAttack()) {
            attackHandler.setPendingAttack(false);
            if (slamPhase == SlamPhase.IDLE && cooldownTimer.finished(cooldownMs.getValue())) {
                tryInitiateSlamSequence(client, target);
            }
            if (attackHandler.isShouldDisableAfterAttack()) {
                attackHandler.setShouldDisableAfterAttack(false);
                setEnabled(false);
                return;
            }
        }

        if (slamPhase != SlamPhase.IDLE) {
            processSlamStateMachine(client);
            return;
        }

        double eyeDist = getPrecisionDistanceToTarget(client, target);

        // Pre-Hit Legit Elytra Unequip (at <= 4.0 blocks): unequip Elytra in 1 tick via hotbar right-click without staying on Slot 5
        if (InventoryUtils.isElytraEquipped() && isFallingForSmash(client) && eyeDist <= PRE_HIT_MUTEX_LOCKOUT_RANGE) {
            if (!unequipElytraLegitBeforeStrike(client, target)) {
                return;
            }
        }

        // Strict Activation Gate: Do NOT switch to the Mace slot early while gliding in the air!
        // Only switch to the Mace and strike on the exact tick of bounding-box intersection.
        if (cooldownTimer.finished(cooldownMs.getValue())) {
            tryInitiateSlamSequence(client, target);
        }
    }

    /**
     * Smooth Top-Down Aim Interpolation with Zenith/Nadir Gimbal Lock Guard:
     * - Dampens horizontal yaw speed by 60% (GIMBAL_LOCK_YAW_DAMPING = 0.40) when pitch > 80°
     *   to prevent 180° camera whip-arounds when passing directly over the target's head.
     * - Enforces a 0.15° angular deadzone when already intersecting the expanded hitbox.
     */
    private void applyFrameSmoothCameraAim(Minecraft client, LivingEntity targetEntity, double frameScale) {
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        Angle currentCamera = new Angle(currentYaw, currentPitch);

        double dist = getPrecisionDistanceToTarget(client, targetEntity);
        boolean inStrikeWindow = dist <= swingRange.getValue() + 1.0D;

        Angle desired = inStrikeWindow
                ? computePinpointStrikeAngle(client, targetEntity)
                : computeAccurateAimAngle(client, targetEntity, currentCamera);
        if (desired == null) {
            return;
        }

        double yawDelta = Mth.wrapDegrees(desired.getYaw() - currentYaw);
        double pitchDelta = desired.getPitch() - currentPitch;
        double deltaLength = Math.hypot(yawDelta, pitchDelta);

        // 0.15° Deadzone when raycast is already intersecting the target's expanded hitbox
        if (deltaLength <= MICRO_DEADZONE_DEGREES
                || (deltaLength <= 0.85D && doesRayIntersectBox(client.player.getEyePosition(1.0F), currentCamera, targetEntity.getBoundingBox().inflate(0.12D), aimRange.getValue() + 1.0D))) {
            smoothYawVelocity *= 0.25D;
            smoothPitchVelocity *= 0.25D;
            return;
        }

        // Zenith/Nadir Gimbal Lock Guard: dampen yaw rotation speed by 60% when looking nearly straight down/up (>80°)
        boolean inGimbalZone = Math.abs(currentPitch) > GIMBAL_LOCK_PITCH_THRESHOLD
                || Math.abs(desired.getPitch()) > GIMBAL_LOCK_PITCH_THRESHOLD;
        double gimbalYawMultiplier = inGimbalZone ? GIMBAL_LOCK_YAW_DAMPING : 1.0D;

        double speedNorm = Mth.clamp(aimSpeed.getValue() / 35.0D, 0.25D, 2.60D);
        double smoothNorm = Mth.clamp(aimSmooth.getValue() / 10.0D, 0.0D, 1.0D);

        double strikeBoost = inStrikeWindow ? 1.45D : 1.0D;
        double distanceFactor = Mth.clamp(deltaLength / DECELERATION_CONE_DEGREES, 0.20D, 1.0D);
        // Clamped Cubic Hermite S-curve interpolation: 3t^2 - 2t^3
        double cubicHermite = distanceFactor * distanceFactor * (3.0D - 2.0D * distanceFactor);

        double baseStiffness = (0.32D * speedNorm * strikeBoost * (0.45D + 0.55D * cubicHermite)) / (1.0D + smoothNorm * 1.45D);
        double damping = 0.65D - smoothNorm * 0.10D;

        double maxYawPerFrame = (16.5D * speedNorm * strikeBoost * gimbalYawMultiplier * (0.45D + 0.55D * cubicHermite)) / (1.0D + smoothNorm * 1.1D);
        double maxPitchPerFrame = (15.0D * speedNorm * strikeBoost * (0.45D + 0.55D * cubicHermite)) / (1.0D + smoothNorm * 1.1D);

        smoothYawVelocity = (smoothYawVelocity + yawDelta * baseStiffness * gimbalYawMultiplier * frameScale) * damping;
        smoothPitchVelocity = (smoothPitchVelocity + pitchDelta * baseStiffness * frameScale) * damping;

        double yawStep = Mth.clamp(smoothYawVelocity, -maxYawPerFrame, maxYawPerFrame);
        double pitchStep = Mth.clamp(smoothPitchVelocity, -maxPitchPerFrame, maxPitchPerFrame);

        double response = Mth.clamp(0.76D - smoothNorm * 0.22D, 0.35D, 0.88D);
        yawStep = prevSmoothYawStep + (yawStep - prevSmoothYawStep) * response;
        pitchStep = prevSmoothPitchStep + (pitchStep - prevSmoothPitchStep) * response;

        if (Math.abs(yawStep) > Math.abs(yawDelta)) {
            yawStep = yawDelta;
        }
        if (Math.abs(pitchStep) > Math.abs(pitchDelta)) {
            pitchStep = pitchDelta;
        }

        prevSmoothYawStep = yawStep;
        prevSmoothPitchStep = pitchStep;

        double gcd = MathUtils.computeGcd();
        float finalYaw = currentYaw + (float) yawStep;
        float finalPitch = Mth.clamp(currentPitch + (float) pitchStep, -89.5F, 89.5F);
        if (gcd > 0.0D) {
            finalYaw = currentYaw + Math.round((finalYaw - currentYaw) / (float) gcd) * (float) gcd;
            finalPitch = Mth.clamp(currentPitch + Math.round((finalPitch - currentPitch) / (float) gcd) * (float) gcd, -89.5F, 89.5F);
        }

        client.player.setYRot(finalYaw);
        client.player.setXRot(finalPitch);
    }

    private Angle computePinpointStrikeAngle(Minecraft client, LivingEntity entity) {
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        AABB box = entity.getBoundingBox();
        AABB safeInnerBox = box.deflate(
                box.getXsize() * 0.14D,
                box.getYsize() * 0.10D,
                box.getZsize() * 0.14D
        );

        Vec3 center = safeInnerBox.getCenter();
        double accuracy = Mth.clamp(hitboxAccuracy.getValue(), 0.0D, 1.0D);
        Vec3 closestOnCore = clampToBox(eyePos, safeInnerBox).lerp(center, 0.45D + accuracy * 0.55D);

        if (!isPointUnobstructed(client, eyePos, closestOnCore)) {
            Angle ref = AngleConnection.INSTANCE.getRotation() != null
                    ? AngleConnection.INSTANCE.getRotation()
                    : MathAngle.cameraAngle();
            Vec3 visiblePoint = computeBestVisibleMultipoint(client, eyePos, ref, safeInnerBox, center, accuracy);
            if (visiblePoint == null) {
                return null;
            }
            closestOnCore = visiblePoint;
        }

        return MathAngle.fromTo(eyePos, closestOnCore).adjustSensitivity();
    }

    private boolean canEngageModule(Minecraft client) {
        if (client.player == null || client.level == null) {
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

        if (slamPhase != SlamPhase.IDLE && !client.player.onGround()) {
            return true;
        }

        boolean holdingMace = isMaceStack(client.player.getMainHandItem());
        int hotbarMace = resolveMaceSlot(client);
        if (!holdingMace && hotbarMace == -1) {
            return false;
        }

        if (client.player.isInWater() || client.player.isInLava() || client.player.getAbilities().flying) {
            return false;
        }
        if (client.player.onGround()) {
            return stillAim.getValue();
        }

        if (client.player.getDeltaMovement().y >= 0.0D && !stillAim.getValue()) {
            return false;
        }

        double effectiveFall = getEffectiveFallDistance(client);
        return effectiveFall >= aimInAir.getValue();
    }

    private boolean isPriority2ManualInputActive(Minecraft client) {
        if (client.player == null || client.options == null) {
            return false;
        }
        // Do not switch or attack if actively holding right-click to eat a Golden Apple or use an item
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

    private boolean hasClearLineOfSightToTarget(Minecraft client, LivingEntity entity) {
        if (client.player == null || client.level == null || entity == null) {
            return false;
        }
        if (client.player.hasLineOfSight(entity)) {
            return true;
        }
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        AABB box = entity.getBoundingBox();
        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;

        double[] heightFractions = new double[]{0.85D, 0.65D, 0.50D, 0.25D};
        for (double frac : heightFractions) {
            Vec3 testPoint = new Vec3(centerX, Mth.lerp(frac, box.minY, box.maxY), centerZ);
            if (isPointUnobstructed(client, eyePos, testPoint)) {
                return true;
            }
        }
        return false;
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

    private boolean isLookRayValidOnTarget(Minecraft client, LivingEntity targetEntity) {
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Angle activeAngle = AngleConnection.INSTANCE.getCurrentAngle();
        if (activeAngle == null) {
            activeAngle = MathAngle.cameraAngle();
        }

        double maxReach = swingRange.getValue() + 0.5D;
        Vec3 rayEnd = eyePos.add(activeAngle.toVector().scale(maxReach));

        AABB targetBox = targetEntity.getBoundingBox().inflate(0.14D);
        if (targetBox.contains(eyePos)) {
            return true;
        }

        Optional<Vec3> entityHit = targetBox.clip(eyePos, rayEnd);
        if (entityHit.isEmpty()) {
            if ("Smooth".equalsIgnoreCase(aimMode.getValue())) {
                Angle camAngle = MathAngle.cameraAngle();
                Vec3 camEnd = eyePos.add(camAngle.toVector().scale(maxReach));
                entityHit = targetBox.clip(eyePos, camEnd);
            }
            if (entityHit.isEmpty()) {
                return false;
            }
        }

        BlockHitResult blockHit = client.level.clip(new ClipContext(
                eyePos,
                entityHit.get(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                client.player
        ));
        return blockHit.getType() == HitResult.Type.MISS;
    }

    @SubscribeEvent
    public void onWorldRender(WorldRenderEvent event) {
        if (!renderPred.getValue() || target == null || !target.isAlive()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
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
        prevSilentYawStep = 0.0F;
        prevSilentPitchStep = 0.0F;
    }

    private void resetSmoothCameraState() {
        smoothYawVelocity = 0.0D;
        smoothPitchVelocity = 0.0D;
        prevSmoothYawStep = 0.0D;
        prevSmoothPitchStep = 0.0D;
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
        if (target == null || client.player == null) {
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
        if (slamPhase != SlamPhase.IDLE) {
            return true;
        }
        if (stillAim.getValue()) {
            return true;
        }
        double requiredFall = Math.max(minFallDist.getValue(), aimInAir.getValue());
        return !client.player.onGround()
                && client.player.getDeltaMovement().y < 0.0D
                && getEffectiveFallDistance(client) >= requiredFall;
    }

    private Angle computeAccurateAimAngle(Minecraft client, LivingEntity entity, Angle referenceAngle) {
        if (targetMode.getValue() && stageHandler.getStage() == Stage.FLYING_UP) {
            return flightController.calculateAngle(entity, Stage.FLYING_UP);
        }

        Vec3 actualEye = client.player.getEyePosition(1.0F);
        AABB actualBox = entity.getBoundingBox();
        double accuracy = Mth.clamp(hitboxAccuracy.getValue(), 0.0D, 1.0D);
        double contractRatio = Math.max(0.18D, accuracy * 0.45D);
        AABB contractedBox = actualBox.deflate(
                actualBox.getXsize() * contractRatio,
                actualBox.getYsize() * contractRatio,
                actualBox.getZsize() * contractRatio
        );

        Vec3 exactCenter = contractedBox.getCenter();
        Angle currentAngle = referenceAngle != null ? referenceAngle : MathAngle.cameraAngle();

        // 0.15° Deadzone when crosshair raycast already intersects the expanded hitbox
        if (!"Never".equalsIgnoreCase(stopAim.getValue())
                && doesRayIntersectBox(actualEye, currentAngle, actualBox.inflate(0.10D), aimRange.getValue() + 1.0D)) {
            Angle toCenter = MathAngle.fromTo(actualEye, exactCenter);
            if (MathAngle.difference(currentAngle, toCenter) <= MICRO_DEADZONE_DEGREES) {
                return currentAngle;
            }
        }

        String stopMode = stopAim.getValue();
        Vec3 aimPoint;
        if ("Exact center".equalsIgnoreCase(stopMode)) {
            aimPoint = exactCenter;
        } else if ("Hitbox".equalsIgnoreCase(stopMode)) {
            aimPoint = clampToBox(actualEye, contractedBox).lerp(exactCenter, 0.50D + accuracy * 0.50D);
        } else {
            aimPoint = computeBestVisibleMultipoint(client, actualEye, currentAngle, contractedBox, exactCenter, accuracy);
        }

        if (aimPoint == null || !isPointUnobstructed(client, actualEye, aimPoint)) {
            aimPoint = computeBestVisibleMultipoint(client, actualEye, currentAngle, contractedBox, exactCenter, accuracy);
            if (aimPoint == null) {
                return null;
            }
        }

        Angle desired = MathAngle.fromTo(actualEye, aimPoint);
        if (MathAngle.difference(currentAngle, desired) <= MICRO_DEADZONE_DEGREES) {
            return currentAngle;
        }
        return desired.adjustSensitivity();
    }

    private void queueSilentServerRotation(Angle targetAngle) {
        silentRotationActive = true;
        AngleConnection.INSTANCE.rotateTo(
                new Angle.VecRotation(targetAngle, targetAngle.toVector()),
                target,
                2,
                new AngleConfig(rotateConstructor, true, true),
                TaskPriority.HIGH_IMPORTANCE_1,
                this
        );
    }

    /**
     * Strict Activation Gate & Single-Tick Swap -> Strike -> Immediate Next-Tick SwapBack:
     * Only switches to the Mace on the exact tick of bounding-box intersection (when falling with
     * fallDistance >= 1.5 and not eating a Golden Apple).
     */
    private void tryInitiateSlamSequence(Minecraft client, LivingEntity targetEntity) {
        if (isPriority2ManualInputActive(client)) {
            return;
        }

        if (!isFallingForSmash(client)) {
            return;
        }

        if (!isWithinNextTickPredictedSmashWindow(client, targetEntity)) {
            return;
        }

        // Ensure Elytra is unequipped first via same-tick hotbar right-click swap
        if (InventoryUtils.isElytraEquipped()) {
            if (!unequipElytraLegitBeforeStrike(client, targetEntity)) {
                return;
            }
        }

        if (!isLookRayValidOnTarget(client, targetEntity)) {
            return;
        }

        boolean targetBlocking = isTargetBlockingWithShield(targetEntity);
        boolean wantStunSlam = shouldTriggerStunSlam(client, targetEntity);

        if (onlyStunslam.getValue() && !wantStunSlam) {
            return;
        }

        int maceSlot = resolveMaceSlot(client);
        boolean holdingMace = isMaceStack(client.player.getMainHandItem());
        if (!holdingMace && maceSlot == -1) {
            return;
        }

        int currentHotbarSlot = client.player.getInventory().getSelectedSlot();
        if (savedHotbarSlot == -1) {
            savedHotbarSlot = isMaceStack(client.player.getInventory().getItem(currentHotbarSlot))
                    ? findFallbackSwordOrWeaponSlot(client, currentHotbarSlot)
                    : currentHotbarSlot;
        }
        pendingMaceSlot = holdingMace && !optimalMace.getValue() ? currentHotbarSlot : (maceSlot != -1 ? maceSlot : currentHotbarSlot);

        // 1. RapptureShield Onetap mode
        if (rapptureShieldOnetap.getValue() && targetBlocking) {
            int axeSlot = resolveAxeSlot(client);
            if (axeSlot != -1) {
                executeStunPhase(client, targetEntity, axeSlot, pendingMaceSlot);
                return;
            }
        }

        // 2. StunSlam sequence (Axe shield disable -> next-tick Mace smash)
        if (wantStunSlam) {
            double accuracyRoll = ThreadLocalRandom.current().nextDouble(0.0D, 100.0D);
            if (accuracyRoll <= stunSlamAccuracy.getValue()) {
                int axeSlot = resolveAxeSlot(client);
                if (axeSlot != -1) {
                    executeStunPhase(client, targetEntity, axeSlot, pendingMaceSlot);
                    return;
                } else if (axeOnlyStunSlam.getValue()) {
                    return;
                }
            } else if (onlyStunslam.getValue()) {
                return;
            }
        }

        // 3. Single-Tick Direct Mace Smash -> Immediate Next-Tick SwapBack
        executeDirectMaceSmash(client, targetEntity, pendingMaceSlot);
    }

    /**
     * Checks whether the target entity is within Swing Range (3.0-3.5 blocks) on the current tick
     * OR on the predicted next tick (playerPos + playerVelocity) while within valid server attack reach.
     */
    private boolean isWithinNextTickPredictedSmashWindow(Minecraft client, LivingEntity targetEntity) {
        double maxReach = swingRange.getValue();
        double currentDist = getPrecisionDistanceToTarget(client, targetEntity);
        if (currentDist <= maxReach) {
            return true;
        }
        // Check next-tick predicted position (playerEye + playerVelocity) capped to server reach envelope (3.5 blocks)
        if (currentDist <= maxReach + 0.5D) {
            Vec3 vel = client.player.getDeltaMovement();
            double nextEyeX = client.player.getX() + vel.x;
            double nextEyeY = client.player.getEyeY() + vel.y;
            double nextEyeZ = client.player.getZ() + vel.z;
            AABB box = targetEntity.getBoundingBox();
            double dx = nextEyeX - Mth.clamp(nextEyeX, box.minX, box.maxX);
            double dy = nextEyeY - Mth.clamp(nextEyeY, box.minY, box.maxY);
            double dz = nextEyeZ - Mth.clamp(nextEyeZ, box.minZ, box.maxZ);
            return (dx * dx + dy * dy + dz * dz) <= (maxReach * maxReach);
        }
        return false;
    }

    private boolean isFallingForSmash(Minecraft client) {
        if (client.player == null) {
            return false;
        }
        if (onlyOnNonGlide.getValue() && client.player.isFallFlying()) {
            return false;
        }
        if (client.player.onGround() || client.player.isInWater() || client.player.isInLava()) {
            return false;
        }
        if (client.player.getDeltaMovement().y >= 0.0D) {
            return false;
        }
        return getEffectiveFallDistance(client) >= minFallDist.getValue();
    }

    private void executeStunPhase(Minecraft client, LivingEntity targetEntity, int axeSlot, int maceSlot) {
        InventoryUtils.selectSlot(axeSlot);
        performAttack(client, targetEntity, false);

        stunClickDelayTimer.reset();
        int switchTicks = customStunSlam.getValue() ? resolveSwitchTicks() : 1;
        phaseTicksRemaining = Math.max(1, switchTicks);
        slamPhase = SlamPhase.WAIT_SWITCH_TO_MACE;
    }

    private void executeDirectMaceSmash(Minecraft client, LivingEntity targetEntity, int maceSlot) {
        int currentSlot = client.player.getInventory().getSelectedSlot();
        if (maceSlot != -1 && maceSlot != currentSlot) {
            InventoryUtils.selectSlot(maceSlot);
            recordUsedMaceEnchantments(client.player.getInventory().getItem(maceSlot));
            if (maceSwapDelayMs.getValue() > 0.0D) {
                maceSwapDelayTimer.reset();
                phaseTicksRemaining = 1;
                slamPhase = SlamPhase.WAIT_MACE_ATTACK;
                return;
            }
        } else {
            recordUsedMaceEnchantments(client.player.getMainHandItem());
        }

        performAttack(client, targetEntity, true);
        cooldownTimer.reset();
        schedulePostSlamSwapBack(client);
    }

    private void processSlamStateMachine(Minecraft client) {
        if (slamPhase == SlamPhase.IDLE) {
            return;
        }
        if (target == null || !target.isAlive() || client.player == null || !hasClearLineOfSightToTarget(client, target)) {
            executeImmediateSwapBack(client, false);
            resetSlamState();
            return;
        }

        switch (slamPhase) {
            case WAIT_SWITCH_TO_MACE -> {
                if (stunSlamClickDelay.getValue() && !stunClickDelayTimer.finished(stunSlamClickDelayMs.getValue())) {
                    return;
                }
                if (phaseTicksRemaining > 1) {
                    phaseTicksRemaining--;
                    return;
                }
                int maceSlot = pendingMaceSlot != -1 ? pendingMaceSlot : resolveMaceSlot(client);
                if (maceSlot != -1) {
                    InventoryUtils.selectSlot(maceSlot);
                    recordUsedMaceEnchantments(client.player.getInventory().getItem(maceSlot));
                }
                maceSwapDelayTimer.reset();
                int attackTicks = customStunSlam.getValue() ? resolveAttackTicks() : 1;
                if (attackTicks <= 1 && maceSwapDelayMs.getValue() <= 0.0D) {
                    if (getPrecisionDistanceToTarget(client, target) <= swingRange.getValue() + 0.5D) {
                        performAttack(client, target, true);
                        cooldownTimer.reset();
                    }
                    schedulePostSlamSwapBack(client);
                } else {
                    phaseTicksRemaining = Math.max(1, attackTicks - 1);
                    slamPhase = SlamPhase.WAIT_MACE_ATTACK;
                }
            }
            case WAIT_MACE_ATTACK -> {
                if (maceSwapDelayMs.getValue() > 0.0D && !maceSwapDelayTimer.finished(maceSwapDelayMs.getValue())) {
                    return;
                }
                if (phaseTicksRemaining > 1) {
                    phaseTicksRemaining--;
                    return;
                }
                if (getPrecisionDistanceToTarget(client, target) <= swingRange.getValue() + 0.5D) {
                    performAttack(client, target, true);
                    cooldownTimer.reset();
                }
                schedulePostSlamSwapBack(client);
            }
            case WAIT_DTAP_FOLLOWUP -> {
                if (getPrecisionDistanceToTarget(client, target) <= swingRange.getValue() + 0.3D
                        && target.hurtTime <= 2) {
                    performAttack(client, target, true);
                    cooldownTimer.reset();
                    slamPhase = SlamPhase.WAIT_SWAP_BACK;
                    phaseTicksRemaining = 1;
                } else if (phaseTicksRemaining-- <= 0) {
                    slamPhase = SlamPhase.WAIT_SWAP_BACK;
                    phaseTicksRemaining = 1;
                }
            }
            case WAIT_SWAP_BACK -> {
                executeImmediateSwapBack(client, false);
                resetSlamState();
            }
            default -> resetSlamState();
        }
    }

    private void schedulePostSlamSwapBack(Minecraft client) {
        if (dTapHelper.getValue() && target != null && target.isAlive()) {
            slamPhase = SlamPhase.WAIT_DTAP_FOLLOWUP;
            phaseTicksRemaining = 3;
            return;
        }
        // Queue immediate next-tick SwapBack so the player is never stuck holding the Mace after slamming
        pendingImmediateSwapBack = true;
        resetSlamState();
    }

    /**
     * Restores the previous hotbar slot (e.g. Slot 1 Netherite Sword or Slot 4 Wind Charge)
     * on the very next tick after the Mace strike, then releases the Priority 1 Mutex Lockout
     * and notifies AutoElytra that the smash cycle is complete.
     */
    private void executeImmediateSwapBack(Minecraft client, boolean force) {
        pendingImmediateSwapBack = false;
        inStrikeMutexLockout = false;

        if (client == null || client.player == null) {
            savedHotbarSlot = -1;
            return;
        }

        if (!force) {
            if (noDensitySwapBack.getValue() && lastUsedDensityMace) {
                savedHotbarSlot = -1;
                notifyAutoElytraStrikeComplete();
                return;
            }
            if (noBreachSwapBack.getValue() && lastUsedBreachMace) {
                savedHotbarSlot = -1;
                notifyAutoElytraStrikeComplete();
                return;
            }
        }

        int returnSlot = savedHotbarSlot;
        if (swapBackToSelectedSlot.getValue()) {
            int configuredSlot = getFirstSelectedHotbarSlot(slotSetting, -1);
            if (configuredSlot == -1) {
                configuredSlot = getFirstSelectedHotbarSlot(extraSlotSetting, -1);
            }
            if (configuredSlot != -1) {
                returnSlot = configuredSlot;
            }
        }

        if (returnSlot < 0 || returnSlot > 8 || isMaceStack(client.player.getInventory().getItem(returnSlot))) {
            returnSlot = findFallbackSwordOrWeaponSlot(client, client.player.getInventory().getSelectedSlot());
        }

        if (returnSlot >= 0 && returnSlot <= 8 && client.player.getInventory().getSelectedSlot() != returnSlot) {
            InventoryUtils.selectSlot(returnSlot);
        }

        savedHotbarSlot = -1;
        notifyAutoElytraStrikeComplete();
    }

    /**
     * Ground Failsafe:
     * If player.onGround becomes true while holding the Mace, immediately force a swap back to
     * savedHotbarSlot or Slot 1 (Netherite Sword).
     */
    private void executeGroundFailsafeSwapBack(Minecraft client) {
        pendingImmediateSwapBack = false;
        inStrikeMutexLockout = false;
        if (client.player == null) {
            return;
        }
        if (!isMaceStack(client.player.getMainHandItem())) {
            savedHotbarSlot = -1;
            return;
        }
        int targetSlot = (savedHotbarSlot >= 0 && savedHotbarSlot <= 8 && !isMaceStack(client.player.getInventory().getItem(savedHotbarSlot)))
                ? savedHotbarSlot
                : findFallbackSwordOrWeaponSlot(client, client.player.getInventory().getSelectedSlot());
        if (targetSlot >= 0 && targetSlot <= 8 && client.player.getInventory().getSelectedSlot() != targetSlot) {
            InventoryUtils.selectSlot(targetSlot);
        }
        savedHotbarSlot = -1;
    }

    private int findFallbackSwordOrWeaponSlot(Minecraft client, int currentSlot) {
        if (client.player == null) {
            return 0;
        }
        // Prefer Sword in hotbar (e.g. Slot 1 Netherite Sword)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.SWORDS)) {
                return i;
            }
        }
        // Next prefer Wind Charge in hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.WIND_CHARGE) {
                return i;
            }
        }
        // Fallback to Slot 1 (index 0) if not the Mace slot
        return currentSlot == 0 ? 1 : 0;
    }

    private void notifyAutoElytraStrikeComplete() {
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled()) {
            autoElytra.notifyMaceAttackLanded();
        }
    }

    private void performAttack(Minecraft client, LivingEntity targetEntity, boolean isMaceHit) {
        if (client.player == null || client.gameMode == null || targetEntity == null) {
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

    private boolean unequipElytraLegitBeforeStrike(Minecraft client, LivingEntity targetEntity) {
        if (!InventoryUtils.isElytraEquipped()) {
            return true;
        }
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null) {
            return autoElytra.ensureUnequippedBeforeMaceAttack(targetEntity);
        }
        return false;
    }

    private Vec3 computeBestVisibleMultipoint(Minecraft client, Vec3 eyePos, Angle currentAngle, AABB box, Vec3 center, double accuracy) {
        Vec3 bestPoint = null;
        double bestScore = Double.MAX_VALUE;
        double[] samples = new double[]{0.20D, 0.50D, 0.80D};
        for (double sx : samples) {
            for (double sy : samples) {
                for (double sz : samples) {
                    Vec3 candidate = new Vec3(
                            Mth.lerp(sx, box.minX, box.maxX),
                            Mth.lerp(sy, box.minY, box.maxY),
                            Mth.lerp(sz, box.minZ, box.maxZ)
                    ).lerp(center, accuracy);
                    if (!isPointUnobstructed(client, eyePos, candidate)) {
                        continue;
                    }
                    Angle candidateAngle = MathAngle.fromTo(eyePos, candidate);
                    double angularDiff = MathAngle.difference(currentAngle, candidateAngle);
                    double distSqr = eyePos.distanceToSqr(candidate);
                    double score = angularDiff + distSqr * 0.05D;
                    if (score < bestScore) {
                        bestScore = score;
                        bestPoint = candidate;
                    }
                }
            }
        }
        return bestPoint;
    }

    private Vec3 clampToBox(Vec3 point, AABB box) {
        return new Vec3(
                Mth.clamp(point.x, box.minX, box.maxX),
                Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ)
        );
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

    private int resolveSwitchTicks() {
        int min = (int) Math.round(minStunSlamTicksSwitch.getValue());
        int max = (int) Math.round(maxStunSlamTicksSwitch.getValue());
        if (max <= min) {
            return min;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private int resolveAttackTicks() {
        int min = (int) Math.round(minStunSlamTicksAttack.getValue());
        int max = (int) Math.round(maxStunSlamTicksAttack.getValue());
        if (max <= min) {
            return min;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Zero-Allocation Primitive Eye-to-AABB Distance Calculation:
     * Avoids allocating temporary Vec3 objects on every distance check.
     */
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

    /**
     * 20Hz Zero-Allocation Target Scanner:
     * Uses primitive double math instead of allocating Vec3 or Stream objects per entity.
     */
    private void findTargetZeroAlloc(Minecraft client) {
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
                if (ignoreFriends.getValue() && FriendUtils.isFriend(player.getName().getString())) {
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
                Angle angleToTarget = MathAngle.calculateAngle(box.getCenter());
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

    private void equipChestplateOnDisable() {
        if (InventoryUtils.hasElytra()) {
            ElytraTarget autoElytra = ElytraTarget.getInstance();
            if (autoElytra != null) {
                autoElytra.ensureUnequippedBeforeMaceAttack(null);
            }
        }
    }

    private void resetSlamState() {
        slamPhase = SlamPhase.IDLE;
        phaseTicksRemaining = 0;
        pendingMaceSlot = -1;
    }

    private void resetAllStates() {
        resetSlamState();
        savedHotbarSlot = -1;
        lastTargetScanTick = -1;
        lastUsedDensityMace = false;
        lastUsedBreachMace = false;
        pendingImmediateSwapBack = false;
        silentRotationActive = false;
        inStrikeMutexLockout = false;
        airbornePeakY = Double.NaN;
        prevSilentYawStep = 0.0F;
        prevSilentPitchStep = 0.0F;
        resetSmoothCameraState();
        armorSwapHandler.reset();
        fireworkHandler.reset();
        attackHandler.reset();
        predictor.reset();
        target = null;
        targetFinder.releaseTarget();
        stageHandler.reset();
    }

    /**
     * Clamped Cubic Interpolation Constructor with Zenith/Nadir Gimbal Lock Guard:
     * - Dampens horizontal yaw speed by 60% when pitch > 80° to prevent 180° whip-arounds over the target's head.
     * - Applies a 0.15° deadzone and converges cleanly onto the hitbox core inside the strike window.
     */
    private final class ClampedCubicRotateConstructor extends RotateConstructor {
        private ClampedCubicRotateConstructor() {
            super("ClampedCubicMace");
        }

        @Override
        public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
            Angle angleDelta = MathAngle.calculateDelta(currentAngle, targetAngle);
            double yawDelta = angleDelta.getYaw();
            double pitchDelta = angleDelta.getPitch();
            double rotationDifference = Math.hypot(yawDelta, pitchDelta);

            if (rotationDifference <= MICRO_DEADZONE_DEGREES) {
                prevSilentYawStep = 0.0F;
                prevSilentPitchStep = 0.0F;
                return targetAngle.adjustSensitivity();
            }

            Minecraft client = Minecraft.getInstance();
            boolean inStrikeZone = false;
            if (client.player != null && entity instanceof LivingEntity living) {
                double dist = getPrecisionDistanceToTarget(client, living);
                inStrikeZone = (dist <= swingRange.getValue() + 0.55D && isFallingForSmash(client))
                        || slamPhase != SlamPhase.IDLE;
            }

            // Zenith/Nadir Gimbal Lock Guard (>80° pitch = 60% yaw damping)
            boolean inGimbalZone = Math.abs(currentAngle.getPitch()) > GIMBAL_LOCK_PITCH_THRESHOLD
                    || Math.abs(targetAngle.getPitch()) > GIMBAL_LOCK_PITCH_THRESHOLD;
            double gimbalYawMultiplier = inGimbalZone ? GIMBAL_LOCK_YAW_DAMPING : 1.0D;

            if (inStrikeZone && !inGimbalZone) {
                prevSilentYawStep = (float) yawDelta;
                prevSilentPitchStep = (float) pitchDelta;
                return targetAngle.adjustSensitivity();
            }

            double t = Mth.clamp(rotationDifference / DECELERATION_CONE_DEGREES, 0.30D, 1.0D);
            double cubicSmoothstep = t * t * (3.0D - 2.0D * t);

            double userSpeedScale = Mth.clamp(aimSpeed.getValue() / 35.0D, 0.45D, 2.0D);
            double smoothDivisor = 1.0D + Math.max(0.0D, aimSmooth.getValue()) * 1.15D;

            double maxYawAllowed = (60.0D * userSpeedScale * gimbalYawMultiplier * cubicSmoothstep) / smoothDivisor;
            double maxPitchAllowed = (52.0D * userSpeedScale * cubicSmoothstep) / smoothDivisor;

            double straightLineYaw = Math.max(1.2D, Math.abs(yawDelta / rotationDifference) * maxYawAllowed);
            double straightLinePitch = Math.max(1.4D, Math.abs(pitchDelta / rotationDifference) * maxPitchAllowed);

            float rawYawStep = (float) Mth.clamp(yawDelta, -straightLineYaw, straightLineYaw);
            float rawPitchStep = (float) Mth.clamp(pitchDelta, -straightLinePitch, straightLinePitch);

            float blendedYawStep = Mth.lerp(0.86F, prevSilentYawStep, rawYawStep);
            float blendedPitchStep = Mth.lerp(0.86F, prevSilentPitchStep, rawPitchStep);
            prevSilentYawStep = blendedYawStep;
            prevSilentPitchStep = blendedPitchStep;

            float nextYaw = currentAngle.getYaw() + blendedYawStep;
            float nextPitch = Mth.clamp(currentAngle.getPitch() + blendedPitchStep, -89.5F, 89.5F);

            return new Angle(nextYaw, nextPitch).adjustSensitivity();
        }

        @Override
        public Vec3 randomValue() {
            return Vec3.ZERO;
        }
    }
}