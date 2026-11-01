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
import net.minecraft.world.inventory.ClickType;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import java.util.List;
import releon.ru.utils.inventory.InventoryFlowManager;
import releon.ru.utils.inventory.InventoryTask;
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

    // Precision Aiming & Strike Convergence Constants
    private static final double MICRO_DEADZONE_DEGREES = 0.25D;
    private static final double DECELERATION_CONE_DEGREES = 16.0D;

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
    private final PinpointMaceRotateConstructor rotateConstructor = new PinpointMaceRotateConstructor();

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

        armorSwapHandler.processLoop();
        if (targetMode.getValue()) {
            fireworkHandler.processLoop();
            if (!armorSwapHandler.isActive() && !fireworkHandler.isActive() && target != null && target.isAlive()) {
                processStage();
            }
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

        double currentDist = getPrecisionDistanceToTarget(client, target);
        boolean inStrikeProximity = currentDist <= swingRange.getValue() + 0.65D && isFallingForSmash(client);

        String mode = aimMode.getValue();
        if ("Smooth".equalsIgnoreCase(mode)) {
            long nowNs = System.nanoTime();
            if (lastSmoothFrameNs == 0L || nowNs - lastSmoothFrameNs > 22_000_000L) {
                applyFrameSmoothCameraAim(client, target, 1.0D);
                lastSmoothFrameNs = nowNs;
            }
            // When inside immediate strike range in Smooth mode, also lock server packet rotation onto the hitbox
            // if the camera is within 18 degrees so high-velocity falls never miss due to between-frame fall motion
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
            // "Silent" or "Strict": queue pinpoint server rotation via AngleConnection
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
                restoreHotbarSlotIfNeeded(false);
                resetSlamState();
            }
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

        // Advance active multi-tick StunSlam / SwapBack state machine first
        if (slamPhase != SlamPhase.IDLE) {
            processSlamStateMachine(client);
            return;
        }

        double eyeDist = getPrecisionDistanceToTarget(client, target);
        double descentSpeed = Math.max(0.05D, -client.player.getDeltaMovement().y);

        // Pre-Attack Legit Elytra Unequip Sync: initiate legit unequip early enough (2-3 ticks before Swing Range)
        if (InventoryUtils.hasElytra() && isFallingForSmash(client)) {
            if (eyeDist <= swingRange.getValue() + Math.max(2.0D, descentSpeed * 2.6D)) {
                unequipElytraLegitBeforeStrike(client, target);
            }
        }

        // Pre-impact SmartAutoSwap / Auto Switch slot preparation
        if ((smartAutoSwap.getValue() || autoSwitch.getValue()) && isFallingForSmash(client)) {
            if (eyeDist <= swingRange.getValue() + Math.max(1.0D, descentSpeed * 1.5D)) {
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

        if (deltaLength <= MICRO_DEADZONE_DEGREES) {
            smoothYawVelocity *= 0.30D;
            smoothPitchVelocity *= 0.30D;
            return;
        }

        double speedNorm = Mth.clamp(aimSpeed.getValue() / 35.0D, 0.25D, 2.80D);
        double smoothNorm = Mth.clamp(aimSmooth.getValue() / 10.0D, 0.0D, 1.0D);

        // Boost responsiveness as the player approaches Swing Range so fast vertical falls never lag behind pitch
        double strikeBoost = inStrikeWindow ? 1.55D : 1.0D;
        double distanceFactor = Mth.clamp(deltaLength / DECELERATION_CONE_DEGREES, 0.25D, 1.0D);
        double hermite = distanceFactor * distanceFactor * (3.0D - 2.0D * distanceFactor);

        double stiffness = (0.34D * speedNorm * strikeBoost * (0.50D + 0.50D * hermite)) / (1.0D + smoothNorm * 1.45D);
        double damping = 0.66D - smoothNorm * 0.10D;

        double maxYawPerFrame = (18.0D * speedNorm * strikeBoost * (0.45D + 0.55D * hermite)) / (1.0D + smoothNorm * 1.1D);
        double maxPitchPerFrame = (15.5D * speedNorm * strikeBoost * (0.45D + 0.55D * hermite)) / (1.0D + smoothNorm * 1.1D);

        smoothYawVelocity = (smoothYawVelocity + yawDelta * stiffness * frameScale) * damping;
        smoothPitchVelocity = (smoothPitchVelocity + pitchDelta * stiffness * frameScale) * damping;

        double yawStep = Mth.clamp(smoothYawVelocity, -maxYawPerFrame, maxYawPerFrame);
        double pitchStep = Mth.clamp(smoothPitchVelocity, -maxPitchPerFrame, maxPitchPerFrame);

        double response = Mth.clamp(0.78D - smoothNorm * 0.22D, 0.35D, 0.90D);
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

    /**
     * Computes the exact, guaranteed-inside-AABB strike angle on the target's current server hitbox.
     * Targets the closest unobstructed point inside the inner 76% core of target.getBoundingBox()
     * so the look ray has maximum angular margin on all 6 faces of the hitbox.
     */
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

    private boolean isLookRayValidOnTarget(Minecraft client, LivingEntity targetEntity) {
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Angle activeAngle = AngleConnection.INSTANCE.getCurrentAngle();
        if (activeAngle == null) {
            activeAngle = MathAngle.cameraAngle();
        }

        double maxReach = swingRange.getValue() + 0.45D;
        Vec3 rayEnd = eyePos.add(activeAngle.toVector().scale(maxReach));

        AABB targetBox = targetEntity.getBoundingBox().inflate(0.14D);
        if (targetBox.contains(eyePos)) {
            return true;
        }

        Optional<Vec3> entityHit = targetBox.clip(eyePos, rayEnd);
        if (entityHit.isEmpty()) {
            // Also check camera angle if Smooth mode is active
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
        Vec3 delta = predictedCenter.subtract(target.position());
        AABB predictedBox = target.getBoundingBox().move(delta);

        Render3D.drawBox(predictedBox, PREDICTION_FILL_COLOR, 1.5F, false, true, false);
        Render3D.drawBox(predictedBox, PREDICTION_OUTLINE_COLOR, 1.5F, true, false, false);
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
        double requiredFall = Math.max(minFallDist.getValue() * 0.55D, aimInAir.getValue());
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

    private void tryInitiateSlamSequence(Minecraft client, LivingEntity targetEntity) {
        // Ensure Elytra is legit-unequipped before sending any Mace attack packet
        if (InventoryUtils.hasElytra()) {
            if (!unequipElytraLegitBeforeStrike(client, targetEntity)) {
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
                    if (getPrecisionDistanceToTarget(client, target) <= swingRange.getValue() + 0.45D) {
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
                if (getPrecisionDistanceToTarget(client, target) <= swingRange.getValue() + 0.45D) {
                    performAttack(client, target);
                    cooldownTimer.reset();
                }
                postSlamFollowUpOrSwapBack(client);
            }
            case WAIT_DTAP_FOLLOWUP -> {
                if (getPrecisionDistanceToTarget(client, target) <= swingRange.getValue() + 0.3D
                        && target.hurtTime <= 2) {
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

        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled()) {
            autoElytra.notifyMaceAttackLanded();
        }
    }

    private static final List<Item> CHESTPLATES = List.of(
            Items.NETHERITE_CHESTPLATE,
            Items.DIAMOND_CHESTPLATE,
            Items.CHAINMAIL_CHESTPLATE,
            Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE,
            Items.LEATHER_CHESTPLATE
    );

    private boolean unequipElytraLegitBeforeStrike(Minecraft client, LivingEntity targetEntity) {
        if (!InventoryUtils.isElytraEquipped()) {
            return InventoryFlowManager.script.isFinished() && InventoryFlowManager.postScript.isFinished();
        }
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled()) {
            return autoElytra.requestPreAttackUnequip(client, targetEntity);
        }
        if (InventoryFlowManager.script.isFinished()
                && InventoryFlowManager.postScript.isFinished()
                && InventoryTask.isSwapAndUseIdle()) {
            Slot chestSlot = InventoryTask.getSlot(CHESTPLATES);
            if (chestSlot != null) {
                InventoryTask.moveItem(chestSlot, 6, !InventoryTask.isMoveMode("ReallyWorld"), true);
            }
        }
        return !InventoryUtils.isElytraEquipped();
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
     * Pinpoint Mace Rotation Constructor:
     * - Outside Swing Range: smoothly accelerates and tracks the target with GCD sensitivity alignment.
     * - Inside Strike Proximity (dist <= Swing Range + 0.65 or during active StunSlam): converges directly
     *   onto the safe inner core of the target's server bounding box on that exact tick with zero random drift,
     *   so high-speed falls and follow-up StunSlam hits never lag behind or miss the hitbox.
     */
    private final class PinpointMaceRotateConstructor extends RotateConstructor {
        private PinpointMaceRotateConstructor() {
            super("PinpointMace");
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
                inStrikeZone = (dist <= swingRange.getValue() + 0.65D && isFallingForSmash(client))
                        || slamPhase != SlamPhase.IDLE;
            }

            // On the strike tick, lock directly onto the GCD-quantized hitbox core so the hit never misses
            if (inStrikeZone) {
                prevSilentYawStep = (float) yawDelta;
                prevSilentPitchStep = (float) pitchDelta;
                return targetAngle.adjustSensitivity();
            }

            double t = Mth.clamp(rotationDifference / DECELERATION_CONE_DEGREES, 0.35D, 1.0D);
            double smoothstep = t * t * (3.0D - 2.0D * t);

            double userSpeedScale = Mth.clamp(aimSpeed.getValue() / 35.0D, 0.45D, 2.0D);
            double smoothDivisor = 1.0D + Math.max(0.0D, aimSmooth.getValue()) * 1.15D;

            double maxYawAllowed = (64.0D * userSpeedScale * smoothstep) / smoothDivisor;
            double maxPitchAllowed = (52.0D * userSpeedScale * smoothstep) / smoothDivisor;

            double straightLineYaw = Math.max(1.8D, Math.abs(yawDelta / rotationDifference) * maxYawAllowed);
            double straightLinePitch = Math.max(1.4D, Math.abs(pitchDelta / rotationDifference) * maxPitchAllowed);

            float rawYawStep = (float) Mth.clamp(yawDelta, -straightLineYaw, straightLineYaw);
            float rawPitchStep = (float) Mth.clamp(pitchDelta, -straightLinePitch, straightLinePitch);

            float blendedYawStep = Mth.lerp(0.88F, prevSilentYawStep, rawYawStep);
            float blendedPitchStep = Mth.lerp(0.88F, prevSilentPitchStep, rawPitchStep);
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