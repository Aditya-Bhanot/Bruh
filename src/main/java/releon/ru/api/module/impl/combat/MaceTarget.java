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

    // 100% Anti-Cheat Compliant "Legit Mode" Constants (GrimAC, Vulcan, Polar, NoCheatPlus)
    private static final double MICRO_DEADZONE_DEGREES = 0.20D;
    private static final double DECELERATION_CONE_DEGREES = 22.0D;
    private static final float GIMBAL_LOCK_PITCH_THRESHOLD = 80.0F;
    private static final double GIMBAL_LOCK_YAW_DAMPING = 0.40D;

    // Humanized Swap Delays (Ban all 1-tick / 50ms swaps)
    private static final double MIN_MACE_PRE_ATTACK_DELAY_MS = 150.0D; // 3 ticks
    private static final double MAX_MACE_PRE_ATTACK_DELAY_MS = 300.0D; // 6 ticks
    private static final double MIN_POST_STRIKE_SWAPBACK_MS = 250.0D;  // 5 ticks
    private static final double MAX_POST_STRIKE_SWAPBACK_MS = 400.0D;  // 8 ticks

    // Realistic Human CPS Cap (8 - 14 CPS => ~71ms - 125ms minimum interval)
    private static final double REALISTIC_MIN_CPS = 8.0D;
    private static final double REALISTIC_MAX_CPS = 14.0D;

    // Stochastic Upper-Torso/Head Targeting Update Interval (400ms - 600ms)
    private static final long MIN_STOCHASTIC_UPDATE_MS = 400L;
    private static final long MAX_STOCHASTIC_UPDATE_MS = 600L;

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
            "StunSlam", "Execution mode for shield stun-slam.", "Normal", "Normal", "Legit"));
    private final NumberSetting stunSlamAccuracy = register(new NumberSetting(
            "Stun Slam Accuracy", "Execution accuracy percentage for shield stun-slams.", 100.0D, 0.0D, 100.0D, 1.0D));

    // 3. StunSlam Ticks & Realistic CPS Pacing (Screenshot 3/4 order)
    private final BooleanSetting axeOnlyStunSlam = register(new BooleanSetting(
            "Axe Only StunSlam", "Require an axe in hotbar to initiate a stun-slam.", false));
    private final BooleanSetting customStunSlam = register(new BooleanSetting(
            "CustomStunSlam", "Use custom tick ranges for stun-slam weapon switching and attack.", false));
    private final BooleanSetting properStunslam = register(new BooleanSetting(
            "ProperStunslam", "Verify target is actively blocking with a shield before stun-slamming.", false));
    private final NumberSetting minStunSlamTicksSwitch = register(new NumberSetting(
            "Min Stun Slam Ticks (Switch)", "Minimum ticks before switching from axe to mace.", 3.0D, 3.0D, 20.0D, 1.0D));
    private final NumberSetting maxStunSlamTicksSwitch = register(new NumberSetting(
            "Max Stun Slam Ticks (Switch)", "Maximum ticks before switching from axe to mace.", 6.0D, 3.0D, 20.0D, 1.0D));
    private final NumberSetting minStunSlamTicksAttack = register(new NumberSetting(
            "Min Stun Slam Ticks (Attack)", "Minimum ticks after switching to mace before attacking.", 3.0D, 3.0D, 20.0D, 1.0D));
    private final NumberSetting maxStunSlamTicksAttack = register(new NumberSetting(
            "Max Stun Slam Ticks (Attack)", "Maximum ticks after switching to mace before attacking.", 6.0D, 3.0D, 20.0D, 1.0D));
    private final BooleanSetting rapptureShieldOnetap = register(new BooleanSetting(
            "RapptureShield Onetap", "Execute shield rupture and mace slam within realistic CPS limits.", false));
    private final NumberSetting onetapMinCps = register(new NumberSetting(
            "Onetap Min CPS", "Minimum realistic CPS rate.", 8.0D, 1.0D, 20.0D, 1.0D));
    private final NumberSetting onetapMaxCps = register(new NumberSetting(
            "Onetap Max CPS", "Maximum realistic CPS rate.", 14.0D, 1.0D, 20.0D, 1.0D));
    private final BooleanSetting onlyOnNonGlide = register(new BooleanSetting(
            "Only On NonGlide", "Only aim and attack when not actively gliding with an Elytra.", false));

    // 4. Aim Modes ("Strict" and "Instant" removed; Smooth is default), Extra Slot & Helpers
    private final BooleanSetting stillAim = register(new BooleanSetting(
            "Still Aim", "Continue aiming at targets while standing on the ground.", false));
    private final BooleanSetting weaponOnly = register(new BooleanSetting(
            "Weapon Only", "Only activate targeting and attacks while holding a weapon.", false));
    private final MultiModeSetting extraSlotSetting = register(new MultiModeSetting(
            "Extra Slot", "Secondary hotbar slots (1-9) for axe, backup mace, or swap return.", HOTBAR_SLOTS));
    private final BooleanSetting dTapHelper = register(new BooleanSetting(
            "DTap Helper", "Queue a follow-up double-tap attack when hurt-time allows.", false));
    private final BooleanSetting clickSim = register(new BooleanSetting(
            "ClickSim", "Simulate client mouse click and hand swing during attacks.", true));
    private final ModeSetting aimMode = register(new ModeSetting(
            "Aim Mode", "Rotation synchronization mode.", "Smooth", "Smooth", "Silent"));
    private final ModeSetting stopAim = register(new ModeSetting(
            "Stop Aim", "Target point convergence mode for rotation termination.", "Hitbox", "Exact center", "Hitbox", "Multipoint", "Never"));
    private final NumberSetting hitboxAccuracy = register(new NumberSetting(
            "Hitbox Accuracy", "Hitbox contraction ratio for precision aim targeting.", 0.00D, 0.00D, 1.00D, 0.01D));

    // 5. Advanced Target Filtering (Ignore Friends Enabled by Default)
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
    private final HumanizedSplineRotateConstructor rotateConstructor = new HumanizedSplineRotateConstructor();

    // Action Commitment Lock & Realistic CPS Pacing State Machine
    private enum LegitSmashState {
        IDLE,
        WAIT_AXE_PRE_SWAP_DELAY,      // 150ms - 300ms (3 to 6 ticks) after selecting Axe
        WAIT_MACE_SWITCH_AFTER_STUN,  // 150ms - 300ms (3 to 6 ticks) before switching to Mace
        WAIT_MACE_PRE_ATTACK_DELAY,   // 150ms - 300ms (3 to 6 ticks) after selecting Mace before attacking
        WAIT_ANIM_AND_SWAPBACK        // 250ms - 400ms (5 to 8 ticks) + attackAnim completion before swapping back
    }

    private final StopWatch cooldownTimer = new StopWatch();
    private final StopWatch stateDelayTimer = new StopWatch();
    private final StopWatch cpsTimer = new StopWatch();

    private LivingEntity target;
    private LegitSmashState smashState = LegitSmashState.IDLE;
    private double currentRequiredDelayMs = 200.0D;
    private double currentCpsIntervalMs = 95.0D;
    private int savedHotbarSlot = -1;
    private int targetMaceSlot = -1;
    private int targetAxeSlot = -1;
    private int lastTargetScanTick = -1;
    private boolean lastUsedDensityMace = false;
    private boolean lastUsedBreachMace = false;
    private boolean silentRotationActive = false;
    private double airbornePeakY = Double.NaN;

    // Stochastic Upper-Torso/Head Targeting State (Updated every 400ms - 600ms)
    private double currentStochasticX = 0.0D;
    private double currentStochasticY = 0.72D;
    private double currentStochasticZ = 0.0D;
    private double nextStochasticX = 0.0D;
    private double nextStochasticY = 0.72D;
    private double nextStochasticZ = 0.0D;
    private long nextStochasticUpdateAtMs = 0L;

    // Spline, Perlin-Style Harmonic Noise, & Natural Overshoot Camera State
    private double smoothYawVelocity = 0.0D;
    private double smoothPitchVelocity = 0.0D;
    private double prevSmoothYawStep = 0.0D;
    private double prevSmoothPitchStep = 0.0D;
    private double perlinPhaseYaw = 0.0D;
    private double perlinPhasePitch = 0.0D;
    private float prevSilentYawStep = 0.0F;
    private float prevSilentPitchStep = 0.0F;
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
        return smashState != LegitSmashState.IDLE;
    }

    public boolean isInStrikeMutexLockout() {
        return smashState != LegitSmashState.IDLE;
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
        resetAllStates();
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            suspendSilentRotation();
            resetAllStates();
            return;
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
        if (client.player == null || client.level == null) {
            return;
        }
        updateStochasticTargetOffset();
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

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)
                || !canEngageModule(client) || !shouldAimAtTarget(client)) {
            resetSmoothCameraState();
            return;
        }

        long nowNs = System.nanoTime();
        double frameScale = lastSmoothFrameNs > 0L ? (nowNs - lastSmoothFrameNs) / 16_666_666.7D : 1.0D;
        frameScale = Mth.clamp(frameScale, 0.05D, 2.5D);
        lastSmoothFrameNs = nowNs;

        applySplineOvershootCameraAim(client, target, frameScale);
    }

    @SubscribeEvent
    public void onRotationUpdate(RotationUpdateEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        if (event.getPhase() == EventPhase.PRE) {
            updateAirbornePeak(client);
            updateStochasticTargetOffset();
            runPreRotationPhase(client);
            return;
        }

        if (event.getPhase() == EventPhase.POST) {
            runPostRotationPhase(client);
        }
    }

    private void runPreRotationPhase(Minecraft client) {
        updateHandlers();

        // If State Machine Lock is active (waiting for attack anim + 250-400ms swapback), process it even after landing
        if (smashState == LegitSmashState.WAIT_ANIM_AND_SWAPBACK) {
            return;
        }

        if (!canEngageModule(client)) {
            if (smashState != LegitSmashState.IDLE) {
                transitionToSwapBackWait(client);
            }
            suspendSilentRotation();
            resetSmoothCameraState();
            target = null;
            targetFinder.releaseTarget();
            return;
        }

        if (lastTargetScanTick != client.player.tickCount) {
            lastTargetScanTick = client.player.tickCount;
            findTargetZeroAlloc(client);
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target) || !hasClearLineOfSightToTarget(client, target)) {
            if (smashState != LegitSmashState.IDLE) {
                transitionToSwapBackWait(client);
            }
            suspendSilentRotation();
            resetSmoothCameraState();
            target = null;
            targetFinder.releaseTarget();
            return;
        }

        predictor.update(target);

        if (!shouldAimAtTarget(client)) {
            suspendSilentRotation();
            resetSmoothCameraState();
            return;
        }

        if ("Smooth".equalsIgnoreCase(aimMode.getValue())) {
            suspendSilentRotation();
            long nowNs = System.nanoTime();
            if (lastSmoothFrameNs == 0L || nowNs - lastSmoothFrameNs > 22_000_000L) {
                applySplineOvershootCameraAim(client, target, 1.0D);
                lastSmoothFrameNs = nowNs;
            }
        } else {
            Angle currentRef = AngleConnection.INSTANCE.getRotation() != null
                    ? AngleConnection.INSTANCE.getRotation()
                    : MathAngle.cameraAngle();
            Angle targetAngle = computeStochasticAimAngle(client, target, currentRef);
            if (targetAngle != null) {
                queueSilentServerRotation(targetAngle);
            } else {
                suspendSilentRotation();
            }
        }
    }

    private void runPostRotationPhase(Minecraft client) {
        // 1. State Machine Lock: always advance an active smash / swap-back sequence to completion first
        if (smashState != LegitSmashState.IDLE) {
            processLockedSmashStateMachine(client);
            return;
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target) || !hasClearLineOfSightToTarget(client, target)) {
            return;
        }

        if (isPriority2ManualInputActive(client)) {
            return;
        }

        // Wait if AutoElytra is currently executing its humanized armor swap
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled() && autoElytra.isSwapLocked()) {
            return;
        }

        if (!cooldownTimer.finished(cooldownMs.getValue())) {
            return;
        }

        tryInitiateLegitSmashSequence(client, target);
    }

    /**
     * 2. Kinematic Camera Control (Spline Interpolation, Perlin Harmonic Noise, & Slight Overshoot-Correction):
     * - Smoothly accelerates toward the stochastic upper-torso/head point.
     * - Underdamped spring factor (0.78 damping) produces a natural slight overshoot when sweeping across wide
     *   angles and smoothly settles back into the bounding box.
     * - Caps maximum angular velocity to realistic mouse DPI limits and decelerates inside the target bounding box.
     */
    private void applySplineOvershootCameraAim(Minecraft client, LivingEntity targetEntity, double frameScale) {
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        Angle currentCamera = new Angle(currentYaw, currentPitch);

        Angle desired = computeStochasticAimAngle(client, targetEntity, currentCamera);
        if (desired == null) {
            return;
        }

        double yawDelta = Mth.wrapDegrees(desired.getYaw() - currentYaw);
        double pitchDelta = desired.getPitch() - currentPitch;
        double deltaLength = Math.hypot(yawDelta, pitchDelta);

        boolean insideBoundingBox = doesRayIntersectBox(
                client.player.getEyePosition(1.0F),
                currentCamera,
                targetEntity.getBoundingBox(),
                aimRange.getValue() + 1.0D
        );

        if (deltaLength <= MICRO_DEADZONE_DEGREES && insideBoundingBox) {
            smoothYawVelocity *= 0.35D;
            smoothPitchVelocity *= 0.35D;
            return;
        }

        // Zenith/Nadir Gimbal Lock Guard (>80° pitch = 60% horizontal yaw damping)
        boolean inGimbalZone = Math.abs(currentPitch) > GIMBAL_LOCK_PITCH_THRESHOLD
                || Math.abs(desired.getPitch()) > GIMBAL_LOCK_PITCH_THRESHOLD;
        double gimbalYawMultiplier = inGimbalZone ? GIMBAL_LOCK_YAW_DAMPING : 1.0D;

        // Decelerate naturally once the crosshair enters the target's bounding box
        double insideBoxDeceleration = insideBoundingBox ? 0.42D : 1.0D;

        double speedNorm = Mth.clamp(aimSpeed.getValue() / 35.0D, 0.20D, 1.65D);
        double smoothNorm = Mth.clamp(aimSmooth.getValue() / 10.0D, 0.0D, 1.0D);

        // Cubic Bézier spline progression factor
        double t = Mth.clamp(deltaLength / DECELERATION_CONE_DEGREES, 0.15D, 1.0D);
        double invT = 1.0D - t;
        double bezierFactor = (3.0D * invT * invT * t * 0.30D)
                + (3.0D * invT * t * t * 0.85D)
                + (t * t * t);

        // Slightly underdamped spring (0.76 - 0.80 damping) creates a natural human overshoot & self-correction
        double stiffness = (0.18D * speedNorm * insideBoxDeceleration * (0.35D + 0.65D * bezierFactor)) / (1.0D + smoothNorm * 1.5D);
        double damping = 0.78D - smoothNorm * 0.08D;

        // Simulated Mouse DPI Speed Caps (Prevents robotic high-speed snaps)
        double maxYawPerFrame = (11.5D * speedNorm * gimbalYawMultiplier * insideBoxDeceleration) / (1.0D + smoothNorm);
        double maxPitchPerFrame = (9.5D * speedNorm * insideBoxDeceleration) / (1.0D + smoothNorm);

        smoothYawVelocity = (smoothYawVelocity + yawDelta * stiffness * gimbalYawMultiplier * frameScale) * damping;
        smoothPitchVelocity = (smoothPitchVelocity + pitchDelta * stiffness * frameScale) * damping;

        // Multi-octave Perlin-style harmonic modifier for organic human hand tracking
        perlinPhaseYaw += 0.09D * frameScale;
        perlinPhasePitch += 0.07D * frameScale;
        double perlinYaw = (Math.sin(perlinPhaseYaw) * 0.6D + Math.cos(perlinPhaseYaw * 2.3D) * 0.4D) * 0.08D * (1.0D - smoothNorm * 0.5D);
        double perlinPitch = (Math.cos(perlinPhasePitch) * 0.6D + Math.sin(perlinPhasePitch * 1.9D) * 0.4D) * 0.06D * (1.0D - smoothNorm * 0.5D);

        double yawStep = Mth.clamp(smoothYawVelocity + perlinYaw, -maxYawPerFrame, maxYawPerFrame);
        double pitchStep = Mth.clamp(smoothPitchVelocity + perlinPitch, -maxPitchPerFrame, maxPitchPerFrame);

        double response = Mth.clamp(0.68D - smoothNorm * 0.20D, 0.30D, 0.82D);
        yawStep = prevSmoothYawStep + (yawStep - prevSmoothYawStep) * response;
        pitchStep = prevSmoothPitchStep + (pitchStep - prevSmoothPitchStep) * response;

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

    /**
     * Stochastic Targeting (Updated every 400ms - 600ms):
     * Smoothly interpolates the target aim point across randomized vectors within the upper torso
     * and head hitbox (55% - 88% height, +-26% width) rather than locking onto the exact center.
     */
    private void updateStochasticTargetOffset() {
        long now = System.currentTimeMillis();
        if (now >= nextStochasticUpdateAtMs) {
            ThreadLocalRandom rand = ThreadLocalRandom.current();
            nextStochasticX = rand.nextDouble(-0.26D, 0.26D);
            nextStochasticY = rand.nextDouble(0.55D, 0.88D);
            nextStochasticZ = rand.nextDouble(-0.26D, 0.26D);
            nextStochasticUpdateAtMs = now + rand.nextLong(MIN_STOCHASTIC_UPDATE_MS, MAX_STOCHASTIC_UPDATE_MS + 1L);
        }
        currentStochasticX = Mth.lerp(0.08D, currentStochasticX, nextStochasticX);
        currentStochasticY = Mth.lerp(0.08D, currentStochasticY, nextStochasticY);
        currentStochasticZ = Mth.lerp(0.08D, currentStochasticZ, nextStochasticZ);
    }

    private Angle computeStochasticAimAngle(Minecraft client, LivingEntity entity, Angle referenceAngle) {
        if (targetMode.getValue() && stageHandler.getStage() == Stage.FLYING_UP) {
            return flightController.calculateAngle(entity, Stage.FLYING_UP);
        }

        Vec3 actualEye = client.player.getEyePosition(1.0F);
        AABB box = entity.getBoundingBox();

        double centerX = (box.minX + box.maxX) * 0.5D + box.getXsize() * currentStochasticX;
        double targetY = Mth.lerp(currentStochasticY, box.minY, box.maxY);
        double centerZ = (box.minZ + box.maxZ) * 0.5D + box.getZsize() * currentStochasticZ;

        AABB safeBox = box.deflate(box.getXsize() * 0.12D, box.getYsize() * 0.08D, box.getZsize() * 0.12D);
        Vec3 stochasticPoint = clampToBox(new Vec3(centerX, targetY, centerZ), safeBox);

        if (!isPointUnobstructed(client, actualEye, stochasticPoint)) {
            Angle currentAngle = referenceAngle != null ? referenceAngle : MathAngle.cameraAngle();
            stochasticPoint = computeBestVisibleMultipoint(client, actualEye, currentAngle, safeBox, safeBox.getCenter(), 0.3D);
            if (stochasticPoint == null) {
                return null;
            }
        }

        return MathAngle.fromTo(actualEye, stochasticPoint).adjustSensitivity();
    }

    /**
     * 1 & 3. "Legit" Inventory Swap Mechanics & State Machine Lock:
     * - Physically moves the hotbar selector (0-8) on the client without packet spoofing.
     * - When swapping to the Mace (or Axe for StunSlam), waits a randomized 150ms - 300ms (3 to 6 ticks)
     *   before initiating the attack.
     * - After the Mace strike connects, waits for the physical attack animation and a randomized
     *   250ms - 400ms (5 to 8 ticks) before swapping back to the original slot / Sword.
     */
    private void tryInitiateLegitSmashSequence(Minecraft client, LivingEntity targetEntity) {
        if (targetEntity == null || isWhitelistedFriend(client, targetEntity)) {
            return;
        }
        if (!isFallingForSmash(client)) {
            return;
        }
        // Never swap while swinging a weapon or taking knockback
        if (client.player.swinging || client.player.attackAnim > 0.0F || client.player.hurtTime > 0) {
            return;
        }

        double currentDist = getPrecisionDistanceToTarget(client, targetEntity);
        double descentSpeed = Math.max(0.05D, -client.player.getDeltaMovement().y);
        // Lead window for the 150ms - 300ms (3 to 6 ticks) pre-attack swap delay
        double swapLeadWindow = swingRange.getValue() + descentSpeed * 4.5D;
        if (currentDist > swapLeadWindow) {
            return;
        }

        if (InventoryUtils.isElytraEquipped()) {
            ElytraTarget autoElytra = ElytraTarget.getInstance();
            if (autoElytra != null && autoElytra.isEnabled()) {
                autoElytra.ensureUnequippedBeforeMaceAttack(targetEntity);
            }
            return;
        }

        boolean wantStunSlam = shouldTriggerStunSlam(client, targetEntity);
        if (onlyStunslam.getValue() && !wantStunSlam) {
            return;
        }

        int maceSlot = resolveMaceSlot(client);
        boolean holdingMace = isMaceStack(client.player.getMainHandItem());
        if (!holdingMace && maceSlot == -1) {
            return;
        }

        int currentSlot = client.player.getInventory().getSelectedSlot();
        savedHotbarSlot = findSwordSlotInHotbar(client, currentSlot);
        targetMaceSlot = holdingMace && !optimalMace.getValue() ? currentSlot : (maceSlot != -1 ? maceSlot : currentSlot);

        // StunSlam sequence: Physically select Axe -> wait 150-300ms -> attack shield -> wait 150-300ms -> select Mace -> wait 150-300ms -> smash
        if (wantStunSlam) {
            double accuracyRoll = ThreadLocalRandom.current().nextDouble(0.0D, 100.0D);
            if (accuracyRoll <= stunSlamAccuracy.getValue()) {
                int axeSlot = resolveAxeSlot(client);
                if (axeSlot != -1) {
                    targetAxeSlot = axeSlot;
                    if (currentSlot != axeSlot) {
                        client.player.getInventory().setSelectedSlot(axeSlot);
                        currentRequiredDelayMs = randomPreAttackSwapDelayMs();
                        stateDelayTimer.reset();
                        smashState = LegitSmashState.WAIT_AXE_PRE_SWAP_DELAY;
                    } else if (currentDist <= swingRange.getValue() && isLookRayValidOnTarget(client, targetEntity) && canFireRealisticCpsClick()) {
                        performLegitAttack(client, targetEntity);
                        currentRequiredDelayMs = randomPreAttackSwapDelayMs();
                        stateDelayTimer.reset();
                        smashState = LegitSmashState.WAIT_MACE_SWITCH_AFTER_STUN;
                    }
                    return;
                } else if (axeOnlyStunSlam.getValue()) {
                    return;
                }
            } else if (onlyStunslam.getValue()) {
                return;
            }
        }

        // Standard Mace Smash: if not already holding the Mace, physically switch and wait 150ms - 300ms (3 to 6 ticks)
        if (currentSlot != targetMaceSlot) {
            client.player.getInventory().setSelectedSlot(targetMaceSlot);
            recordUsedMaceEnchantments(client.player.getInventory().getItem(targetMaceSlot));
            currentRequiredDelayMs = randomPreAttackSwapDelayMs();
            stateDelayTimer.reset();
            smashState = LegitSmashState.WAIT_MACE_PRE_ATTACK_DELAY;
            return;
        }

        // Already holding the Mace for >= 150ms: strike when inside Swing Range and ray is valid
        if (currentDist <= swingRange.getValue() && isLookRayValidOnTarget(client, targetEntity) && canFireRealisticCpsClick()) {
            recordUsedMaceEnchantments(client.player.getMainHandItem());
            performLegitAttack(client, targetEntity);
            cooldownTimer.reset();
            transitionToSwapBackWait(client);
        }
    }

    private void processLockedSmashStateMachine(Minecraft client) {
        if (client.player == null) {
            smashState = LegitSmashState.IDLE;
            return;
        }

        switch (smashState) {
            case WAIT_AXE_PRE_SWAP_DELAY -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackWait(client);
                    return;
                }
                if (!stateDelayTimer.finished(currentRequiredDelayMs)) {
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() && isLookRayValidOnTarget(client, target) && canFireRealisticCpsClick()) {
                    performLegitAttack(client, target);
                    currentRequiredDelayMs = randomPreAttackSwapDelayMs();
                    stateDelayTimer.reset();
                    smashState = LegitSmashState.WAIT_MACE_SWITCH_AFTER_STUN;
                } else if (client.player.onGround() || stateDelayTimer.finished(currentRequiredDelayMs + 450.0D)) {
                    transitionToSwapBackWait(client);
                }
            }

            case WAIT_MACE_SWITCH_AFTER_STUN -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackWait(client);
                    return;
                }
                // Wait until attack animation finishes and 150ms - 300ms delay completes before switching to Mace
                if (client.player.attackAnim > 0.35F || !stateDelayTimer.finished(currentRequiredDelayMs)) {
                    return;
                }
                int maceSlot = targetMaceSlot != -1 ? targetMaceSlot : resolveMaceSlot(client);
                if (maceSlot != -1) {
                    client.player.getInventory().setSelectedSlot(maceSlot);
                    recordUsedMaceEnchantments(client.player.getInventory().getItem(maceSlot));
                }
                currentRequiredDelayMs = randomPreAttackSwapDelayMs();
                stateDelayTimer.reset();
                smashState = LegitSmashState.WAIT_MACE_PRE_ATTACK_DELAY;
            }

            case WAIT_MACE_PRE_ATTACK_DELAY -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackWait(client);
                    return;
                }
                // Enforce the mandatory 150ms - 300ms (3 to 6 ticks) delay after switching to the Mace before attacking
                if (!stateDelayTimer.finished(currentRequiredDelayMs)) {
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() && isLookRayValidOnTarget(client, target) && canFireRealisticCpsClick()) {
                    performLegitAttack(client, target);
                    cooldownTimer.reset();
                    transitionToSwapBackWait(client);
                } else if (client.player.onGround() || stateDelayTimer.finished(currentRequiredDelayMs + 500.0D)) {
                    transitionToSwapBackWait(client);
                }
            }

            case WAIT_ANIM_AND_SWAPBACK -> {
                // State Machine Lock: wait for physical attack animation to finish AND 250ms - 400ms (5 to 8 ticks)
                // hit delay before restoring the original / Sword slot
                if (!stateDelayTimer.finished(currentRequiredDelayMs)) {
                    return;
                }
                if (client.player.swinging && client.player.attackAnim > 0.15F && !stateDelayTimer.finished(currentRequiredDelayMs + 200.0D)) {
                    return;
                }
                if (client.player.hurtTime > 0 && !stateDelayTimer.finished(currentRequiredDelayMs + 150.0D)) {
                    return;
                }
                executeLegitSwapBackToSword(client);
                smashState = LegitSmashState.IDLE;
            }

            default -> smashState = LegitSmashState.IDLE;
        }
    }

    private void transitionToSwapBackWait(Minecraft client) {
        currentRequiredDelayMs = randomPostStrikeSwapBackDelayMs();
        stateDelayTimer.reset();
        smashState = LegitSmashState.WAIT_ANIM_AND_SWAPBACK;
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled()) {
            autoElytra.enterState3RecoveryLock();
        }
    }

    private void executeLegitSwapBackToSword(Minecraft client) {
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

        int returnSlot = findSwordSlotInHotbar(client, client.player.getInventory().getSelectedSlot());
        if (swapBackToSelectedSlot.getValue()) {
            int configuredSlot = getFirstSelectedHotbarSlot(slotSetting, -1);
            if (configuredSlot == -1) {
                configuredSlot = getFirstSelectedHotbarSlot(extraSlotSetting, -1);
            }
            if (configuredSlot != -1) {
                returnSlot = configuredSlot;
            }
        }

        if (returnSlot >= 0 && returnSlot <= 8 && client.player.getInventory().getSelectedSlot() != returnSlot) {
            client.player.getInventory().setSelectedSlot(returnSlot);
        }
        savedHotbarSlot = -1;
        targetMaceSlot = -1;
        targetAxeSlot = -1;
    }

    private double randomPreAttackSwapDelayMs() {
        double base = ThreadLocalRandom.current().nextDouble(MIN_MACE_PRE_ATTACK_DELAY_MS, MAX_MACE_PRE_ATTACK_DELAY_MS);
        return Math.max(base, maceSwapDelayMs.getValue());
    }

    private double randomPostStrikeSwapBackDelayMs() {
        return ThreadLocalRandom.current().nextDouble(MIN_POST_STRIKE_SWAPBACK_MS, MAX_POST_STRIKE_SWAPBACK_MS);
    }

    /**
     * Realistic 8 - 14 CPS Rate Limiter:
     * Prevents any superhuman macro click intervals (<71ms).
     */
    private boolean canFireRealisticCpsClick() {
        return cpsTimer.finished(currentCpsIntervalMs);
    }

    private void performLegitAttack(Minecraft client, LivingEntity targetEntity) {
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

        double minCps = Mth.clamp(Math.min(onetapMinCps.getValue(), onetapMaxCps.getValue()), REALISTIC_MIN_CPS, REALISTIC_MAX_CPS);
        double maxCps = Mth.clamp(Math.max(onetapMinCps.getValue(), onetapMaxCps.getValue()), minCps, REALISTIC_MAX_CPS);
        double chosenCps = ThreadLocalRandom.current().nextDouble(minCps, maxCps + 0.01D);
        currentCpsIntervalMs = 1000.0D / chosenCps;
        cpsTimer.reset();
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
        if (smashState != LegitSmashState.IDLE) {
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

        return getEffectiveFallDistance(client) >= aimInAir.getValue();
    }

    private boolean isPriority2ManualInputActive(Minecraft client) {
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
        Angle activeAngle = "Silent".equalsIgnoreCase(aimMode.getValue()) && AngleConnection.INSTANCE.getCurrentAngle() != null
                ? AngleConnection.INSTANCE.getCurrentAngle()
                : MathAngle.cameraAngle();

        double maxReach = swingRange.getValue() + 0.35D;
        Vec3 rayEnd = eyePos.add(activeAngle.toVector().scale(maxReach));

        AABB targetBox = targetEntity.getBoundingBox().inflate(0.10D);
        if (targetBox.contains(eyePos)) {
            return true;
        }

        Optional<Vec3> entityHit = targetBox.clip(eyePos, rayEnd);
        if (entityHit.isEmpty()) {
            return false;
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
        if (client.player == null || client.level == null || isWhitelistedFriend(client, target)) {
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
        if (target == null || client.player == null || isWhitelistedFriend(client, target)) {
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
        if (smashState != LegitSmashState.IDLE && smashState != LegitSmashState.WAIT_ANIM_AND_SWAPBACK) {
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
        if (savedHotbarSlot >= 0 && savedHotbarSlot <= 8
                && !isMaceStack(client.player.getInventory().getItem(savedHotbarSlot))
                && client.player.getInventory().getItem(savedHotbarSlot).getItem() != Items.ELYTRA) {
            return savedHotbarSlot;
        }
        return currentSlot == 0 ? 1 : 0;
    }

    private Vec3 computeBestVisibleMultipoint(Minecraft client, Vec3 eyePos, Angle currentAngle, AABB box, Vec3 center, double accuracy) {
        Vec3 bestPoint = null;
        double bestScore = Double.MAX_VALUE;
        double[] samples = new double[]{0.25D, 0.55D, 0.82D};
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

    private void resetAllStates() {
        smashState = LegitSmashState.IDLE;
        savedHotbarSlot = -1;
        targetMaceSlot = -1;
        targetAxeSlot = -1;
        lastTargetScanTick = -1;
        lastUsedDensityMace = false;
        lastUsedBreachMace = false;
        silentRotationActive = false;
        airbornePeakY = Double.NaN;
        nextStochasticUpdateAtMs = 0L;
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
     * Humanized Spline & Perlin-Noise RotateConstructor (No Instant Snapping):
     * Caps angular velocity to realistic mouse DPI limits, decelerates inside the target bounding box,
     * and applies organic harmonic noise + inertia blending.
     */
    private final class HumanizedSplineRotateConstructor extends RotateConstructor {
        private HumanizedSplineRotateConstructor() {
            super("HumanizedSplineMace");
        }

        @Override
        public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
            Angle angleDelta = MathAngle.calculateDelta(currentAngle, targetAngle);
            double yawDelta = angleDelta.getYaw();
            double pitchDelta = angleDelta.getPitch();
            double rotationDifference = Math.hypot(yawDelta, pitchDelta);

            if (rotationDifference <= MICRO_DEADZONE_DEGREES) {
                prevSilentYawStep *= 0.35F;
                prevSilentPitchStep *= 0.35F;
                return currentAngle;
            }

            Minecraft client = Minecraft.getInstance();
            boolean insideBox = false;
            if (client.player != null && entity != null) {
                insideBox = doesRayIntersectBox(
                        client.player.getEyePosition(1.0F),
                        currentAngle,
                        entity.getBoundingBox(),
                        aimRange.getValue() + 1.0D
                );
            }

            boolean inGimbalZone = Math.abs(currentAngle.getPitch()) > GIMBAL_LOCK_PITCH_THRESHOLD
                    || Math.abs(targetAngle.getPitch()) > GIMBAL_LOCK_PITCH_THRESHOLD;
            double gimbalYawMultiplier = inGimbalZone ? GIMBAL_LOCK_YAW_DAMPING : 1.0D;
            double insideBoxDecel = insideBox ? 0.45D : 1.0D;

            double t = Mth.clamp(rotationDifference / DECELERATION_CONE_DEGREES, 0.18D, 1.0D);
            double invT = 1.0D - t;
            double bezier = (3.0D * invT * invT * t * 0.30D) + (3.0D * invT * t * t * 0.85D) + (t * t * t);

            double userSpeedScale = Mth.clamp(aimSpeed.getValue() / 35.0D, 0.25D, 1.45D);
            double smoothDivisor = 1.0D + Math.max(0.0D, aimSmooth.getValue()) * 1.4D;

            // Cap rotation speed to human mouse DPI limits (<= 26° yaw / <= 20° pitch per tick)
            double maxYawAllowed = (26.0D * userSpeedScale * gimbalYawMultiplier * insideBoxDecel * bezier) / smoothDivisor;
            double maxPitchAllowed = (20.0D * userSpeedScale * insideBoxDecel * bezier) / smoothDivisor;

            double straightLineYaw = Math.max(0.6D, Math.abs(yawDelta / rotationDifference) * maxYawAllowed);
            double straightLinePitch = Math.max(0.5D, Math.abs(pitchDelta / rotationDifference) * maxPitchAllowed);

            float rawYawStep = (float) Mth.clamp(yawDelta, -straightLineYaw, straightLineYaw);
            float rawPitchStep = (float) Mth.clamp(pitchDelta, -straightLinePitch, straightLinePitch);

            if (rotationDifference > 1.5D) {
                rawYawStep += MathUtils.getRandom(-0.22F, 0.22F);
                rawPitchStep += MathUtils.getRandom(-0.18F, 0.18F);
            }

            // Underdamped inertia blend (0.76) allows slight natural overshoot and smooth correction
            float blendedYawStep = Mth.lerp(0.76F, prevSilentYawStep, rawYawStep);
            float blendedPitchStep = Mth.lerp(0.76F, prevSilentPitchStep, rawPitchStep);
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