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
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.module.Module;
import net.minecraft.world.inventory.ClickType;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.movement.ElytraTarget;
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
    private static final int PREDICTION_FILL_COLOR = 0x385EF2D6;
    private static final int PREDICTION_OUTLINE_COLOR = 0xFF5EF2D6;

    // Anti-Cheat Safe Aiming & Deadzone Constants
    private static final double MICRO_DEADZONE_DEGREES = 0.45D;
    private static final double DECELERATION_CONE_DEGREES = 20.0D;
    private static final double MAX_SAFE_YAW_STEP = 48.0D;
    private static final double MAX_SAFE_PITCH_STEP = 36.0D;

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

    // 4. Aim Modes (Instant removed: Strict, Smooth, Silent), Extra Slot & Click Simulation Helpers (Screenshot 4/5 order)
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
    private final OptimizedSilentRotateConstructor rotateConstructor = new OptimizedSilentRotateConstructor();

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
    private boolean lastUsedDensityMace = false;
    private boolean lastUsedBreachMace = false;
    private boolean switchedTemporarily = false;
    private boolean silentRotationActive = false;
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

    @Override
    protected void onEnable() {
        resetAllStates();
    }

    @Override
    protected void onDisable() {
        if (targetMode.getValue() && Minecraft.getInstance().player != null) {
            equipChestplateOnDisable();
        }
        restoreHotbarSlotIfNeeded(true);
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

        if (targetMode.getValue()) {
            armorSwapHandler.processLoop();
            fireworkHandler.processLoop();
            if (!armorSwapHandler.isActive() && !fireworkHandler.isActive() && target != null && target.isAlive()) {
                processStage();
            }
        }
    }

    /**
     * Render-Frame Smooth Camera Interpolation (DrawEvent.Layer.GAME):
     * When "Smooth" (or "Strict") Aim Mode is selected, smoothly rotates the player's actual camera
     * across every render frame using critically damped spring-Hermite interpolation and mouse GCD alignment,
     * rather than falling back to AngleConnection's silent server-only rotation.
     */
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

        String mode = aimMode.getValue();
        if ("Silent".equalsIgnoreCase(mode)) {
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

        if (!canEngageModule(client)) {
            if (slamPhase != SlamPhase.IDLE) {
                restoreHotbarSlotIfNeeded(false);
                resetSlamState();
            }
            suspendSilentRotation();
            resetSmoothCameraState();
            target = null;
            targetFinder.releaseTarget();
            return;
        }

        findTarget(client);
        if (target == null || !target.isAlive() || !hasClearLineOfSightToTarget(client, target)) {
            if (slamPhase != SlamPhase.IDLE) {
                restoreHotbarSlotIfNeeded(false);
                resetSlamState();
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

        String mode = aimMode.getValue();
        if ("Smooth".equalsIgnoreCase(mode)) {
            // In Smooth mode, NEVER engage AngleConnection silent packet rotation; camera is smoothed directly
            suspendSilentRotation();
            long nowNs = System.nanoTime();
            if (lastSmoothFrameNs == 0L || nowNs - lastSmoothFrameNs > 25_000_000L) {
                applyFrameSmoothCameraAim(client, target, 1.0D);
                lastSmoothFrameNs = nowNs;
            }
        } else {
            // "Silent" or "Strict": queue optimized silent server rotation via AngleConnection
            Angle currentRef = AngleConnection.INSTANCE.getRotation() != null
                    ? AngleConnection.INSTANCE.getRotation()
                    : MathAngle.cameraAngle();
            Angle targetAngle = computeAccurateAimAngle(client, target, currentRef);
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
                restoreHotbarSlotIfNeeded(false);
                resetSlamState();
            }
            return;
        }

        // Handle StageHandler pending attack in Target Mode
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

        // Advance active multi-tick StunSlam / SwapBack state machine
        if (slamPhase != SlamPhase.IDLE) {
            processSlamStateMachine(client);
            return;
        }

        // Pre-impact SmartAutoSwap / Auto Switch slot preparation
        if ((smartAutoSwap.getValue() || autoSwitch.getValue()) && isFallingForSmash(client)) {
            double eyeDist = getPrecisionDistanceToTarget(client, target);
            double descentSpeed = Math.max(0.0D, -client.player.getDeltaMovement().y);
            if (eyeDist <= swingRange.getValue() + Math.max(0.8D, descentSpeed * 1.25D)) {
                int optimalSlot = resolveMaceSlot(client);
                if (optimalSlot != -1 && !shouldTriggerStunSlam(client, target)) {
                    if (savedHotbarSlot == -1) {
                        savedHotbarSlot = InventoryUtils.currentSlot();
                    }
                    InventoryUtils.selectSlot(optimalSlot);
                    InventoryUtils.syncSelectedHotbarSlot(optimalSlot);
                }
            }
        }

        // Evaluate and initiate smash / stun-slam when inside Swing Range and aiming at target hitbox
        if (cooldownTimer.finished(cooldownMs.getValue())) {
            tryInitiateSlamSequence(client, target);
        }
    }

    /**
     * Frame-Rate Independent Smooth Camera Aim (Used when Aim Mode is "Smooth" or "Strict"):
     * Smoothly interpolates client.player.getYRot() and client.player.getXRot() using a critically-damped
     * spring + Hermite ease-out curve with GCD mouse quantization.
     */
    private void applyFrameSmoothCameraAim(Minecraft client, LivingEntity targetEntity, double frameScale) {
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        Angle currentCamera = new Angle(currentYaw, currentPitch);

        Angle desired = computeAccurateAimAngle(client, targetEntity, currentCamera);
        if (desired == null) {
            return;
        }

        double yawDelta = Mth.wrapDegrees(desired.getYaw() - currentYaw);
        double pitchDelta = desired.getPitch() - currentPitch;
        double deltaLength = Math.hypot(yawDelta, pitchDelta);

        if (deltaLength <= MICRO_DEADZONE_DEGREES) {
            smoothYawVelocity *= 0.35D;
            smoothPitchVelocity *= 0.35D;
            return;
        }

        // Convert Aim Speed (1..100, default 35.0) and Aim Smooth (0.00..10.00) into critically damped spring parameters
        double speedNorm = Mth.clamp(aimSpeed.getValue() / 35.0D, 0.20D, 2.50D);
        double smoothNorm = Mth.clamp(aimSmooth.getValue() / 10.0D, 0.0D, 1.0D);

        double distanceFactor = Mth.clamp(deltaLength / DECELERATION_CONE_DEGREES, 0.15D, 1.0D);
        double hermite = distanceFactor * distanceFactor * (3.0D - 2.0D * distanceFactor);

        double stiffness = (0.26D * speedNorm * (0.45D + 0.55D * hermite)) / (1.0D + smoothNorm * 1.65D);
        double damping = 0.64D - smoothNorm * 0.12D;

        double maxYawPerFrame = (14.5D * speedNorm * (0.40D + 0.60D * hermite)) / (1.0D + smoothNorm * 1.2D);
        double maxPitchPerFrame = (11.5D * speedNorm * (0.40D + 0.60D * hermite)) / (1.0D + smoothNorm * 1.2D);

        smoothYawVelocity = (smoothYawVelocity + yawDelta * stiffness * frameScale) * damping;
        smoothPitchVelocity = (smoothPitchVelocity + pitchDelta * stiffness * frameScale) * damping;

        double yawStep = Mth.clamp(smoothYawVelocity, -maxYawPerFrame, maxYawPerFrame);
        double pitchStep = Mth.clamp(smoothPitchVelocity, -maxPitchPerFrame, maxPitchPerFrame);

        // Smooth step transition to eliminate frame-to-frame acceleration spikes
        double response = Mth.clamp(0.72D - smoothNorm * 0.25D, 0.30D, 0.85D);
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

        // Quantize to mouse GCD sensitivity step so server anti-cheat sees authentic mouse input deltas
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
        if (!holdingMace && !autoSwitch.getValue() && !smartAutoSwap.getValue()
                && !(stunSlam.getValue() && client.player.getMainHandItem().getItem() instanceof AxeItem)) {
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

    /**
     * Verifies that the active look ray (camera look in Smooth mode, server rotation in Silent/Strict mode)
     * intersects the target's bounding box BEFORE hitting any wall.
     */
    private boolean isLookRayValidOnTarget(Minecraft client, LivingEntity targetEntity) {
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Angle activeAngle;
        if ("Smooth".equalsIgnoreCase(aimMode.getValue())) {
            activeAngle = MathAngle.cameraAngle();
        } else {
            activeAngle = AngleConnection.INSTANCE.getRotation();
            if (activeAngle == null) {
                activeAngle = MathAngle.cameraAngle();
            }
        }

        double maxReach = swingRange.getValue() + 0.35D;
        Vec3 rayEnd = eyePos.add(activeAngle.toVector().scale(maxReach));

        AABB targetBox = targetEntity.getBoundingBox().inflate(0.12D);
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
        if (client.player == null || client.level == null) {
            return;
        }
        Stage currentStage = targetMode.getValue() ? stageHandler.getStage() : Stage.ATTACKING;
        Vec3 predictedCenter = predictor.getPredictedPosition(target, currentStage);
        Vec3 delta = predictedCenter.subtract(target.position());
        AABB predictedBox = target.getBoundingBox().move(delta);

        Render3D.drawBox(predictedBox, PREDICTION_FILL_COLOR, 1.5F, false, true, false);
        Render3D.drawBox(predictedBox, PREDICTION_OUTLINE_COLOR, 1.5F, true, false, false);
    }

    @SubscribeEvent
    public void onInput(InputEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !targetMode.getValue()) {
            return;
        }
        if (armorSwapHandler.getMovement().isBlocked() || fireworkHandler.getMovement().isBlocked()) {
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
        double requiredFall = Math.max(minFallDist.getValue() * 0.65D, aimInAir.getValue());
        return !client.player.onGround()
                && client.player.getDeltaMovement().y < 0.0D
                && getEffectiveFallDistance(client) >= requiredFall;
    }

    /**
     * High-Precision Aim Point & Velocity Lead Calculator:
     * Accounts for target velocity and player descent so both Smooth and Silent modes track
     * moving targets accurately during high-speed falls without lagging behind the hitbox.
     */
    private Angle computeAccurateAimAngle(Minecraft client, LivingEntity entity, Angle referenceAngle) {
        if (targetMode.getValue() && stageHandler.getStage() == Stage.FLYING_UP) {
            return flightController.calculateAngle(entity, Stage.FLYING_UP);
        }

        Vec3 playerVel = client.player.getDeltaMovement();
        // Predict eye position fractional lead and target motion lead for sub-tick intercept accuracy
        Vec3 eyePos = client.player.getEyePosition(1.0F).add(playerVel.scale(0.25D));
        Vec3 targetVel = entity.getDeltaMovement();
        AABB rawBox = entity.getBoundingBox().move(targetVel.x * 0.65D, targetVel.y * 0.35D, targetVel.z * 0.65D);

        double accuracy = Mth.clamp(hitboxAccuracy.getValue(), 0.0D, 1.0D);
        double contractRatio = Math.max(0.22D, accuracy * 0.45D);
        AABB contractedBox = rawBox.deflate(
                rawBox.getXsize() * contractRatio,
                rawBox.getYsize() * contractRatio,
                rawBox.getZsize() * contractRatio
        );

        Vec3 exactCenter = rawBox.getCenter();
        Angle currentAngle = referenceAngle != null ? referenceAngle : MathAngle.cameraAngle();

        // Inner core stability lock: only hold steady if already pointing inside the innermost 40% core
        AABB tightCore = rawBox.deflate(rawBox.getXsize() * 0.30D, rawBox.getYsize() * 0.30D, rawBox.getZsize() * 0.30D);
        if (!"Never".equalsIgnoreCase(stopAim.getValue())
                && doesRayIntersectBox(client.player.getEyePosition(1.0F), currentAngle, tightCore, aimRange.getValue() + 2.0D)
                && isLookRayValidOnTarget(client, entity)) {
            Angle toCenter = MathAngle.fromTo(client.player.getEyePosition(1.0F), exactCenter);
            if (MathAngle.difference(currentAngle, toCenter) <= 1.25D) {
                return currentAngle;
            }
        }

        String stopMode = stopAim.getValue();
        Vec3 aimPoint;
        if ("Exact center".equalsIgnoreCase(stopMode)) {
            aimPoint = exactCenter;
        } else if ("Hitbox".equalsIgnoreCase(stopMode)) {
            aimPoint = clampToBox(eyePos, contractedBox).lerp(exactCenter, 0.55D + accuracy * 0.45D);
        } else {
            aimPoint = computeBestVisibleMultipoint(client, client.player.getEyePosition(1.0F), currentAngle, contractedBox, exactCenter, accuracy);
        }

        Vec3 actualEye = client.player.getEyePosition(1.0F);
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

    /**
     * Queues Silent server rotation via AngleConnection with reset=2 so AngleConnection never
     * prematurely clears currentAngle while tracking.
     */
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

    private void tryInitiateSlamSequence(Minecraft client, LivingEntity targetEntity) {
        double currentDist = getPrecisionDistanceToTarget(client, targetEntity);
        double descentSpeed = client.player != null ? Math.max(0.0D, -client.player.getDeltaMovement().y) : 0.0D;

        // Pre-Attack Legit Elytra Unequip Sync: if wearing an Elytra as we enter strike proximity, legit-unequip first
        if (InventoryUtils.hasElytra() || (client.player != null && client.player.isFallFlying())) {
            if (currentDist <= swingRange.getValue() + Math.max(1.25D, descentSpeed * 1.75D)) {
                if (!unequipElytraLegitBeforeStrike(client, targetEntity)) {
                    return;
                }
            } else {
                return;
            }
        }

        if (!isWithinTickPerfectSmashWindow(client, targetEntity)) {
            return;
        }

        if (!isFallingForSmash(client)) {
            return;
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

        int currentHotbarSlot = InventoryUtils.currentSlot();
        if (savedHotbarSlot == -1) {
            savedHotbarSlot = currentHotbarSlot;
        }
        pendingMaceSlot = holdingMace && !optimalMace.getValue() ? currentHotbarSlot : (maceSlot != -1 ? maceSlot : currentHotbarSlot);
        switchedTemporarily = !holdingMace && !autoSwitch.getValue() && !smartAutoSwap.getValue();

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

        // 3. Direct Mace Smash
        executeDirectMaceSmash(client, targetEntity, pendingMaceSlot);
    }

    private boolean isWithinTickPerfectSmashWindow(Minecraft client, LivingEntity targetEntity) {
        double maxReach = swingRange.getValue();
        double currentDist = getPrecisionDistanceToTarget(client, targetEntity);
        return currentDist <= maxReach;
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
        return getEffectiveFallDistance(client) > minFallDist.getValue();
    }

    private void executeStunPhase(Minecraft client, LivingEntity targetEntity, int axeSlot, int maceSlot) {
        InventoryUtils.selectSlot(axeSlot);
        InventoryUtils.syncSelectedHotbarSlot(axeSlot);
        performAttack(client, targetEntity);

        stunClickDelayTimer.reset();
        int switchTicks = customStunSlam.getValue() ? resolveSwitchTicks() : 1;
        phaseTicksRemaining = Math.max(1, switchTicks);
        slamPhase = SlamPhase.WAIT_SWITCH_TO_MACE;
    }

    private void executeDirectMaceSmash(Minecraft client, LivingEntity targetEntity, int maceSlot) {
        int currentSlot = InventoryUtils.currentSlot();
        if (maceSlot != -1 && maceSlot != currentSlot) {
            InventoryUtils.selectSlot(maceSlot);
            InventoryUtils.syncSelectedHotbarSlot(maceSlot);
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

        performAttack(client, targetEntity);
        cooldownTimer.reset();
        postSlamFollowUpOrSwapBack(client);
    }

    private void processSlamStateMachine(Minecraft client) {
        if (slamPhase == SlamPhase.IDLE) {
            return;
        }
        if (target == null || !target.isAlive() || client.player == null || !hasClearLineOfSightToTarget(client, target)) {
            restoreHotbarSlotIfNeeded(false);
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
                    InventoryUtils.syncSelectedHotbarSlot(maceSlot);
                    recordUsedMaceEnchantments(client.player.getInventory().getItem(maceSlot));
                }
                maceSwapDelayTimer.reset();
                int attackTicks = customStunSlam.getValue() ? resolveAttackTicks() : 1;
                if (attackTicks <= 1 && maceSwapDelayMs.getValue() <= 0.0D) {
                    if (getPrecisionDistanceToTarget(client, target) <= swingRange.getValue() + 0.35D
                            && isLookRayValidOnTarget(client, target)) {
                        performAttack(client, target);
                        cooldownTimer.reset();
                    }
                    postSlamFollowUpOrSwapBack(client);
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
                if (getPrecisionDistanceToTarget(client, target) <= swingRange.getValue() + 0.35D
                        && isLookRayValidOnTarget(client, target)) {
                    performAttack(client, target);
                    cooldownTimer.reset();
                }
                postSlamFollowUpOrSwapBack(client);
            }
            case WAIT_DTAP_FOLLOWUP -> {
                if (getPrecisionDistanceToTarget(client, target) <= swingRange.getValue() + 0.2D
                        && target.hurtTime <= 2
                        && isLookRayValidOnTarget(client, target)) {
                    performAttack(client, target);
                    cooldownTimer.reset();
                    slamPhase = SlamPhase.WAIT_SWAP_BACK;
                    phaseTicksRemaining = 1;
                } else if (phaseTicksRemaining-- <= 0) {
                    slamPhase = SlamPhase.WAIT_SWAP_BACK;
                    phaseTicksRemaining = 1;
                }
            }
            case WAIT_SWAP_BACK -> {
                restoreHotbarSlotIfNeeded(false);
                resetSlamState();
            }
            default -> resetSlamState();
        }
    }

    private void postSlamFollowUpOrSwapBack(Minecraft client) {
        if (dTapHelper.getValue() && target != null && target.isAlive()) {
            slamPhase = SlamPhase.WAIT_DTAP_FOLLOWUP;
            phaseTicksRemaining = 4;
            return;
        }
        restoreHotbarSlotIfNeeded(false);
        resetSlamState();
    }

    private void restoreHotbarSlotIfNeeded(boolean force) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        boolean shouldSwapBack = swapBack.getValue()
                || "Silent".equalsIgnoreCase(stunSlamMode.getValue())
                || switchedTemporarily;
        if (!force) {
            if (!shouldSwapBack) {
                savedHotbarSlot = -1;
                switchedTemporarily = false;
                return;
            }
            if (noDensitySwapBack.getValue() && lastUsedDensityMace) {
                savedHotbarSlot = -1;
                switchedTemporarily = false;
                return;
            }
            if (noBreachSwapBack.getValue() && lastUsedBreachMace) {
                savedHotbarSlot = -1;
                switchedTemporarily = false;
                return;
            }
        } else if (!shouldSwapBack) {
            savedHotbarSlot = -1;
            switchedTemporarily = false;
            return;
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

        if (returnSlot >= 0 && returnSlot < 9 && returnSlot != InventoryUtils.currentSlot()) {
            InventoryUtils.selectSlot(returnSlot);
            InventoryUtils.syncSelectedHotbarSlot(returnSlot);
        }
        savedHotbarSlot = -1;
        switchedTemporarily = false;
    }

    private void performAttack(Minecraft client, LivingEntity targetEntity) {
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

        // Notify AutoElytra that the Mace attack has landed so it can schedule re-equipping after Reequip Delay
        if (isMaceStack(client.player.getMainHandItem())) {
            ElytraTarget autoElytra = ElytraTarget.getInstance();
            if (autoElytra != null && autoElytra.isEnabled()) {
                autoElytra.notifyMaceAttackLanded();
            }
        }
    }

    /**
     * Synchronizes with AutoElytra (or performs a standalone legit Chestplate/unequip swap)
     * so the Elytra is guaranteed to be unequipped before any Mace attack packet is sent.
     */
    private boolean unequipElytraLegitBeforeStrike(Minecraft client, LivingEntity targetEntity) {
        if (client.player == null) {
            return false;
        }
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled()) {
            return autoElytra.ensureUnequippedBeforeMaceAttack(client, targetEntity);
        }
        int chestSlot = InventoryUtils.findChestArmorSlot();
        if (chestSlot != -1) {
            InventoryUtils.swap(InventoryUtils.wrapSlot(chestSlot), 6);
            InventoryUtils.closeScreen();
        } else {
            InventoryUtils.click(6, 0, ClickType.QUICK_MOVE);
            InventoryUtils.closeScreen();
        }
        if (client.player.isFallFlying()) {
            client.player.stopFallFlying();
        }
        return !InventoryUtils.hasElytra() && !client.player.isFallFlying();
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

    private double getPrecisionDistanceToTarget(Minecraft client, LivingEntity entity) {
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 closest = clampToBox(eyePos, entity.getBoundingBox());
        return eyePos.distanceTo(closest);
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

    private void findTarget(Minecraft client) {
        double maxRange = targetMode.getValue()
                ? Math.max(aimRange.getValue(), 128.0D)
                : aimRange.getValue();
        double maxFov = maceFov.getValue();

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

            double dist = getPrecisionDistanceToTarget(client, living);
            if (dist > maxRange) {
                continue;
            }

            if (!hasClearLineOfSightToTarget(client, living)) {
                continue;
            }

            if (maxFov < 360.0D) {
                Angle angleToTarget = MathAngle.calculateAngle(living.getBoundingBox().getCenter());
                double fovDiff = MathAngle.difference(MathAngle.cameraAngle(), angleToTarget);
                if (fovDiff > maxFov * 0.5D) {
                    continue;
                }
            }

            double distSqr = dist * dist;
            if (distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                bestTarget = living;
            }
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
            int slot = InventoryUtils.findChestArmorSlot();
            if (slot != -1) {
                InventoryUtils.swap(InventoryUtils.wrapSlot(slot), 6);
                InventoryUtils.closeScreen();
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
        lastUsedDensityMace = false;
        lastUsedBreachMace = false;
        switchedTemporarily = false;
        silentRotationActive = false;
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
     * Optimized High-Accuracy Silent Rotation Constructor:
     * - Converges rapidly onto the predicted hitbox center when descending into Swing Range so silent
     *   server attacks never lag behind moving targets.
     * - Preserves GCD sensitivity alignment and smooth inertia blending to pass server anti-cheat checks.
     */
    private final class OptimizedSilentRotateConstructor extends RotateConstructor {
        private OptimizedSilentRotateConstructor() {
            super("OptimizedSilent");
        }

        @Override
        public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
            Angle angleDelta = MathAngle.calculateDelta(currentAngle, targetAngle);
            double yawDelta = angleDelta.getYaw();
            double pitchDelta = angleDelta.getPitch();
            double rotationDifference = Math.hypot(yawDelta, pitchDelta);

            if (rotationDifference <= MICRO_DEADZONE_DEGREES) {
                prevSilentYawStep *= 0.30F;
                prevSilentPitchStep *= 0.30F;
                return targetAngle.adjustSensitivity();
            }

            double t = Mth.clamp(rotationDifference / DECELERATION_CONE_DEGREES, 0.28D, 1.0D);
            double smoothstep = t * t * (3.0D - 2.0D * t);

            double userSpeedScale = Mth.clamp(aimSpeed.getValue() / 35.0D, 0.35D, 1.65D);
            double smoothDivisor = 1.0D + Math.max(0.0D, aimSmooth.getValue()) * 1.25D;

            double maxYawAllowed = (MAX_SAFE_YAW_STEP * userSpeedScale * smoothstep) / smoothDivisor;
            double maxPitchAllowed = (MAX_SAFE_PITCH_STEP * userSpeedScale * smoothstep) / smoothDivisor;

            double straightLineYaw = Math.max(1.2D, Math.abs(yawDelta / rotationDifference) * maxYawAllowed);
            double straightLinePitch = Math.max(0.9D, Math.abs(pitchDelta / rotationDifference) * maxPitchAllowed);

            float rawYawStep = (float) Mth.clamp(yawDelta, -straightLineYaw, straightLineYaw);
            float rawPitchStep = (float) Mth.clamp(pitchDelta, -straightLinePitch, straightLinePitch);

            if (rotationDifference > 3.5D) {
                rawYawStep += MathUtils.getRandom(-0.18F, 0.18F);
                rawPitchStep += MathUtils.getRandom(-0.14F, 0.14F);
            }

            float blendedYawStep = Mth.lerp(0.84F, prevSilentYawStep, rawYawStep);
            float blendedPitchStep = Mth.lerp(0.84F, prevSilentPitchStep, rawPitchStep);
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