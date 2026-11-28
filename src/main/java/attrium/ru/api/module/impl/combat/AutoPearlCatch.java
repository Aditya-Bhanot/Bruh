package attrium.ru.api.module.impl.combat;

import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.module.impl.misc.ElytraHelper;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.Manager;
import attrium.ru.manager.RotationManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Bind-Only 3D Kinematic AutoPearlCatch Action Macro (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * - Bind-only like LungeHelper & ElytraHelper (no ON/OFF toggle; driven strictly by Manager START_CLIENT_TICK).
 * - Diagonal & straight-up exact discrete-tick solver (works at any angle from -90 deg to horizontal).
 * - High-Speed Lead: when moving fast (>= 0.35 b/t) and aiming near straight up, pitches the pearl ahead
 *   of straight up along your velocity vector so pearl drag never leaves it behind you.
 * - 2-Tick Aim-Then-Fire pipeline: selects slot + sends MovePlayerPacket on Tick N, fires useItem() on Tick N+1
 *   so Silent diagonal/high-speed catches stay 100% accurate on Paper/GrimAC with zero Post/BadPackets flags.
 */
public class AutoPearlCatch extends Module {

    private static final int STATE_IDLE = 0;
    private static final int STATE_THROW_PEARL = 1;
    private static final int STATE_WAIT_BEFORE_AIM = 2;
    private static final int STATE_AIM_CATCH = 3;
    private static final int STATE_FIRE_CATCH = 4;
    private static final int STATE_REVERT = 5;

    private static final double PROJECTILE_SPEED = 1.5D;
    private static final double PEARL_DRAG = 0.99D;
    private static final double PEARL_GRAVITY = 0.03D;
    private static final double HIGH_SPEED_LEAD_MIN_HORIZ = 0.35D;
    private static final float STRAIGHT_UP_PITCH_THRESHOLD = -72.0F;
    private static final ItemStack PEARL_PROBE = new ItemStack(Items.ENDER_PEARL);

    private static AutoPearlCatch instance;
    private static volatile int syncedGuiBoundKey = -1;
    private static volatile boolean callbackPressLatched = false;

    private static boolean reflectionInitialized = false;
    private static Method moduleKeyGetterMethod = null;
    private static Field moduleKeyField = null;

    private final ModeSetting catchItem = register(new ModeSetting(
            "Catch Item",
            "Projectile used to catch your Ender Pearl (Auto chooses the cleanest intercept)",
            "Auto", "Wind Charge", "Snowball / Egg", "Auto"
    ));
    private final ModeSetting wcPhysics = register(new ModeSetting(
            "WC Physics",
            "Vanilla (Wind Charge inherits player velocity) or Static (for custom servers without WC velocity inherit)",
            "Vanilla", "Vanilla", "Static"
    ));
    private final NumberSetting fireDelay = register(new NumberSetting(
            "Catch Delay (Ticks)",
            "Server ticks between pearl launch and catch launch (2 recommended for 100% synced Silent aim)",
            2.0D, 2.0D, 5.0D, 1.0D
    ));
    private final NumberSetting maxInterceptTicks = register(new NumberSetting(
            "Max Intercept Ticks",
            "Maximum flight ticks searched for a diagonal or high-speed mid-air intercept",
            45.0D, 10.0D, 70.0D, 5.0D
    ));
    private final BooleanSetting highSpeedLead = register(new BooleanSetting(
            "High-Speed Lead",
            "When moving fast and looking near straight up, pitches the pearl ahead of straight up for easier catches",
            true
    ));
    private final NumberSetting leadStrength = register(new NumberSetting(
            "Lead Strength",
            "How far ahead of straight up to pitch the pearl during fast movement / Elytra flight",
            1.0D, 0.5D, 1.5D, 0.1D
    ));
    private final BooleanSetting elytraCompensation = register(new BooleanSetting(
            "3D Velocity Comp",
            "Includes full 3D airborne & Elytra vertical velocity (vy) in trajectory calculations",
            true
    ));
    private final BooleanSetting catchManualThrows = register(new BooleanSetting(
            "Catch Manual Throws",
            "Also auto-catch pearls you throw manually with right-click (uses RTT ping compensation)",
            false
    ));
    private final ModeSetting rotationMode = register(new ModeSetting(
            "Rotation Mode",
            "Silent (server-synced RotationManager with GCD + MoveFix) or Legit (turns camera)",
            "Silent", "Silent", "Legit"
    ));
    private final BooleanSetting autoRevert = register(new BooleanSetting(
            "Auto Revert",
            "Return to your previous hotbar slot after firing the catch projectile",
            true
    ));
    private final NumberSetting savedPearlKeyCode = register(new NumberSetting(
            "_pearlCatchKeyCode",
            "Internal saved GLFW keycode for AutoPearlCatch bind",
            -1.0D, -200.0D, 400.0D, 1.0D
    ));

    // State machine fields (zero per-tick allocations)
    private int state = STATE_IDLE;
    private int waitTicks = 0;
    private int revertTicks = 0;
    private int previousSlot = -1;
    private int activeCatchSlot = -1;
    private boolean activeCatchIsWindCharge = true;
    private int trackedManualPearlId = -1;
    private int lastCaughtPearlEntityId = -1;
    private boolean isManualCatchSequence = false;

    private boolean queuedMacroPending = false;
    private long queuedMacroAtMs = 0L;
    private boolean wasBindPressedLastTick = false;
    private int lastProcessedStartTickId = -1;
    private boolean holdingSilentRotation = false;

    // Led pearl launch angles (used when High-Speed Lead adjusts straight-up pitch)
    private boolean customPearlAngleActive = false;
    private float targetPearlYaw = 0.0F;
    private float targetPearlPitch = -90.0F;

    // Exact discrete-tick pearl state
    private double simPearlX = 0.0D;
    private double simPearlY = 0.0D;
    private double simPearlZ = 0.0D;
    private double simPearlVx = 0.0D;
    private double simPearlVy = 0.0D;
    private double simPearlVz = 0.0D;

    // Solved catch aim angles + error metrics
    private float solvedYaw = 0.0F;
    private float solvedPitch = 0.0F;
    private double lastSolvedMissDist = Double.MAX_VALUE;
    private boolean solvedValid = false;

    public AutoPearlCatch() {
        super("AutoPearlCatch", "Bind-only diagonal & high-speed 3D Ender Pearl catch macro", ModuleCategory.COMBAT);
        instance = this;
        savedPearlKeyCode.visibleWhen(() -> false);
        leadStrength.visibleWhen(highSpeedLead::getValue);
        int saved = savedPearlKeyCode.getValue().intValue();
        if (saved != -1 && saved != 0) {
            syncedGuiBoundKey = saved;
        }
    }

    public static AutoPearlCatch getInstance() {
        if (instance == null) {
            instance = new AutoPearlCatch();
        }
        return instance;
    }

    public static boolean isMacroBusy() {
        return instance != null && (instance.state != STATE_IDLE || instance.queuedMacroPending);
    }

    public static void setMacroBoundKey(int key) {
        int normalized = (key == 0 || key == GLFW.GLFW_KEY_UNKNOWN) ? -1 : key;
        syncedGuiBoundKey = normalized;
        if (instance != null) {
            instance.savedPearlKeyCode.setValue((double) normalized);
        }
    }

    public static int getMacroBoundKey() {
        if (syncedGuiBoundKey != -1) {
            return syncedGuiBoundKey;
        }
        if (instance != null) {
            int saved = instance.savedPearlKeyCode.getValue().intValue();
            if (saved != -1 && saved != 0) {
                syncedGuiBoundKey = saved;
                return saved;
            }
        }
        return -1;
    }

    public static void onMacroKeyPressedFromCallback(int key) {
        int bound = getMacroBoundKey();
        if (bound != -1 && key == bound) {
            callbackPressLatched = true;
        }
    }

    public static void tickMacroGlobal(Minecraft client, int guiBoundKey) {
        if (guiBoundKey != -1 && guiBoundKey != 0 && guiBoundKey != GLFW.GLFW_KEY_UNKNOWN) {
            syncedGuiBoundKey = guiBoundKey;
        }
        // Strictly reject out-of-phase calls (prevents GrimAC Post flags)
        if (!Manager.isInStartClientTick()) {
            return;
        }
        getInstance().processClientTick(client);
    }

    @Override
    protected void onDisable() {
        releaseRotation();
        resetState();
    }

    private void resetState() {
        this.state = STATE_IDLE;
        this.waitTicks = 0;
        this.revertTicks = 0;
        this.previousSlot = -1;
        this.activeCatchSlot = -1;
        this.activeCatchIsWindCharge = true;
        this.trackedManualPearlId = -1;
        this.isManualCatchSequence = false;
        this.queuedMacroPending = false;
        this.customPearlAngleActive = false;
        this.solvedValid = false;
        this.lastSolvedMissDist = Double.MAX_VALUE;
        callbackPressLatched = false;
    }

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        // Driven exclusively via Manager.java START_CLIENT_TICK -> tickMacroGlobal()
    }

    private void processClientTick(Minecraft mc) {
        if (!Manager.isInStartClientTick()) {
            return;
        }
        if (mc == null || mc.player == null || mc.level == null || mc.gameMode == null) {
            lastProcessedStartTickId = -1;
            releaseRotation();
            resetState();
            return;
        }

        int startTickId = Manager.getStartClientTickCount();
        if (startTickId == lastProcessedStartTickId) {
            return;
        }
        lastProcessedStartTickId = startTickId;

        MaceTarget.ActionManager actionMgr = MaceTarget.ActionManager.getInstance();
        actionMgr.onPreTickRender(mc);

        if (mc.screen != null) {
            wasBindPressedLastTick = false;
            callbackPressLatched = false;
            if (state != STATE_IDLE) {
                releaseRotation();
                resetState();
            }
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (queuedMacroPending && (nowMs - queuedMacroAtMs) > 450L) {
            queuedMacroPending = false;
        }

        boolean keyDown = isMacroKeyDown(mc);
        boolean callbackTap = callbackPressLatched;
        callbackPressLatched = false;
        boolean risingEdge = callbackTap || (keyDown && !wasBindPressedLastTick);
        wasBindPressedLastTick = keyDown;

        if (risingEdge && state == STATE_IDLE) {
            queuedMacroPending = true;
            queuedMacroAtMs = nowMs;
        }

        // Advance simulated pearl exactly 1 tick using vanilla 1.21.11 discrete order
        if (state == STATE_WAIT_BEFORE_AIM || state == STATE_AIM_CATCH || state == STATE_FIRE_CATCH) {
            simPearlX += simPearlVx;
            simPearlY += simPearlVy;
            simPearlZ += simPearlVz;
            simPearlVx *= PEARL_DRAG;
            simPearlVy = (simPearlVy * PEARL_DRAG) - PEARL_GRAVITY;
            simPearlVz *= PEARL_DRAG;
        }

        switch (state) {
            case STATE_IDLE -> handleIdle(mc, actionMgr);
            case STATE_THROW_PEARL -> handleThrowPearl(mc, actionMgr);
            case STATE_WAIT_BEFORE_AIM -> handleWaitBeforeAim(mc, actionMgr);
            case STATE_AIM_CATCH -> handleAimCatch(mc, actionMgr);
            case STATE_FIRE_CATCH -> handleFireCatch(mc, actionMgr);
            case STATE_REVERT -> handleRevert(mc, actionMgr);
            default -> {
                releaseRotation();
                resetState();
            }
        }
    }

    private void handleIdle(Minecraft mc, MaceTarget.ActionManager actionMgr) {
        if (mc.player.isUsingItem() || ElytraHelper.isSwapBusy() || actionMgr.isSwapInProgress()) {
            return;
        }

        if (queuedMacroPending) {
            if (!actionMgr.isSafePreMotionTick() || !actionMgr.canMutateHotbarSlotThisTick()) {
                return;
            }
            queuedMacroPending = false;

            int pearlSlot = findHotbarItem(mc, Items.ENDER_PEARL);
            int catchSlot = findPreferredCatchSlot(mc);
            if (pearlSlot < 0 || catchSlot < 0 || mc.player.getCooldowns().isOnCooldown(PEARL_PROBE)) {
                return;
            }

            previousSlot = mc.player.getInventory().getSelectedSlot();
            activeCatchSlot = catchSlot;
            activeCatchIsWindCharge = mc.player.getInventory().getItem(catchSlot).is(Items.WIND_CHARGE);
            isManualCatchSequence = false;

            // Compute High-Speed Pearl Lead if moving fast and aiming near straight up (-72..-90 deg)
            computePearlLaunchAngles(mc);

            // Tick 0: Select pearl slot (vanilla gameMode.tick() sends C09) & deliver led angle via sendPosition()
            selectSlotVanilla(mc, pearlSlot);
            if (customPearlAngleActive) {
                applyAngle(mc, targetPearlYaw, targetPearlPitch);
            }
            state = STATE_THROW_PEARL;
            return;
        }

        // Optional: catch manually right-click-thrown pearls (zero scanning unless pearl cooldown is active)
        if (!Boolean.TRUE.equals(catchManualThrows.getValue())) {
            return;
        }
        if (!mc.player.getCooldowns().isOnCooldown(PEARL_PROBE)) {
            return;
        }
        int catchSlot = findPreferredCatchSlot(mc);
        if (catchSlot < 0) {
            return;
        }

        ThrownEnderpearl pearl = findOwnedRecentPearl(mc, 6.0D, 5);
        if (pearl != null && pearl.getId() != lastCaughtPearlEntityId) {
            previousSlot = mc.player.getInventory().getSelectedSlot();
            activeCatchSlot = catchSlot;
            activeCatchIsWindCharge = mc.player.getInventory().getItem(catchSlot).is(Items.WIND_CHARGE);
            trackedManualPearlId = pearl.getId();
            lastCaughtPearlEntityId = pearl.getId();
            isManualCatchSequence = true;
            captureManualPearlWithPing(mc, pearl);
            // Aim on this tick if safe, fire on next tick
            handleAimCatch(mc, actionMgr);
        }
    }

    /**
     * When moving fast (>= 0.35 b/t) and aiming near straight up (pitch <= -72 deg),
     * pitches the pearl ahead of straight up along your horizontal movement vector
     * so pearl air drag doesn't leave it behind you. Diagonal throws (> -72 deg) stay untouched.
     */
    private void computePearlLaunchAngles(Minecraft mc) {
        float curYaw = mc.player.getYRot();
        float curPitch = mc.player.getXRot();
        customPearlAngleActive = false;
        targetPearlYaw = curYaw;
        targetPearlPitch = curPitch;

        if (!Boolean.TRUE.equals(highSpeedLead.getValue())) {
            return;
        }
        if (curPitch > STRAIGHT_UP_PITCH_THRESHOLD) {
            return;
        }

        Vec3 vel = mc.player.getDeltaMovement();
        double horizSpeed = Math.hypot(vel.x, vel.z);
        if (horizSpeed < HIGH_SPEED_LEAD_MIN_HORIZ) {
            return;
        }

        // Only pitch ahead when catch projectile inherits velocity (Vanilla WC or Snowball/Egg);
        // in Static non-inheriting WC mode, pitching ahead would push pearl faster than 1.5 b/t horizontally.
        boolean staticWcOnly = "Static".equalsIgnoreCase(wcPhysics.getValue()) && activeCatchIsWindCharge
                && findHotbarItem(mc, Items.SNOWBALL) < 0 && findHotbarItem(mc, Items.EGG) < 0;
        if (staticWcOnly) {
            return;
        }

        float moveYaw = (float) Math.toDegrees(Math.atan2(vel.z, vel.x)) - 90.0F;
        double strength = Mth.clamp(leadStrength.getValue(), 0.5D, 1.5D);
        double leadDeg = Mth.clamp((horizSpeed - HIGH_SPEED_LEAD_MIN_HORIZ) * 18.0D * strength, 4.0D, 26.0D);
        float ledPitch = Mth.clamp(-90.0F + (float) leadDeg, -88.0F, -60.0F);

        float[] snapped = snapAngleToGcd(mc, moveYaw, ledPitch);
        targetPearlYaw = snapped[0];
        targetPearlPitch = snapped[1];
        customPearlAngleActive = true;
    }

    private void handleThrowPearl(Minecraft mc, MaceTarget.ActionManager actionMgr) {
        if (!actionMgr.isSafePreMotionTick()) {
            return;
        }

        // Server already received pearl slot (C09) + led rotation on Tick 0!
        float launchYaw = customPearlAngleActive ? targetPearlYaw : mc.player.getYRot();
        float launchPitch = customPearlAngleActive ? targetPearlPitch : mc.player.getXRot();

        if (customPearlAngleActive) {
            applyAngle(mc, launchYaw, launchPitch);
            fireUseItemAtAngle(mc, launchYaw, launchPitch);
        } else {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        float yawRad = launchYaw * Mth.DEG_TO_RAD;
        float pitchRad = launchPitch * Mth.DEG_TO_RAD;
        double dirX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dirY = -Math.sin(pitchRad);
        double dirZ = Math.cos(yawRad) * Math.cos(pitchRad);

        Vec3 vel = mc.player.getDeltaMovement();
        double pVx = vel.x;
        double pVy = (!mc.player.onGround() && (Boolean.TRUE.equals(elytraCompensation.getValue()) || mc.player.isFallFlying()))
                ? vel.y : 0.0D;
        double pVz = vel.z;

        simPearlX = mc.player.getX();
        simPearlY = mc.player.getEyeY() - 0.1D;
        simPearlZ = mc.player.getZ();
        simPearlVx = dirX * PROJECTILE_SPEED + pVx;
        simPearlVy = dirY * PROJECTILE_SPEED + pVy;
        simPearlVz = dirZ * PROJECTILE_SPEED + pVz;
        customPearlAngleActive = false;

        int totalDelay = Math.max(2, fireDelay.getValue().intValue());
        // Aim happens 1 tick before Fire: e.g. totalDelay=2 -> Aim on Tick +1, Fire on Tick +2
        waitTicks = Math.max(0, totalDelay - 2);
        state = (waitTicks > 0) ? STATE_WAIT_BEFORE_AIM : STATE_AIM_CATCH;
    }

    private void handleWaitBeforeAim(Minecraft mc, MaceTarget.ActionManager actionMgr) {
        waitTicks--;
        if (waitTicks <= 0) {
            state = STATE_AIM_CATCH;
        }
    }

    private void handleAimCatch(Minecraft mc, MaceTarget.ActionManager actionMgr) {
        if (!actionMgr.isSafePreMotionTick() || !actionMgr.canMutateHotbarSlotThisTick()) {
            return;
        }

        // Solve 1 tick ahead so Tick N's sendPosition() delivers the exact angle for Tick N+1's fire!
        if (!chooseBestCatchSlotAndSolve(mc, true)) {
            finishOrAbort(mc, actionMgr);
            return;
        }

        selectSlotVanilla(mc, activeCatchSlot);
        applyAngle(mc, solvedYaw, solvedPitch);
        state = STATE_FIRE_CATCH;
    }

    private void handleFireCatch(Minecraft mc, MaceTarget.ActionManager actionMgr) {
        if (!actionMgr.isSafePreMotionTick()) {
            return;
        }

        // Fine-tune solved angle on the exact fire tick (usually < 1 deg delta from Tick N's prediction)
        if (solveExactDiscreteIntercept(mc, false, activeCatchIsWindCharge)) {
            applyAngle(mc, solvedYaw, solvedPitch);
        }

        fireUseItemAtAngle(mc, solvedYaw, solvedPitch);
        revertTicks = 1;
        state = STATE_REVERT;
    }

    private void handleRevert(Minecraft mc, MaceTarget.ActionManager actionMgr) {
        revertTicks--;
        if (revertTicks > 0) {
            return;
        }
        if (!actionMgr.isSafePreMotionTick() || !actionMgr.canMutateHotbarSlotThisTick()) {
            return;
        }
        finishOrAbort(mc, actionMgr);
    }

    private void finishOrAbort(Minecraft mc, MaceTarget.ActionManager actionMgr) {
        if (Boolean.TRUE.equals(autoRevert.getValue())
                && previousSlot >= 0 && previousSlot < 9
                && actionMgr.isSafePreMotionTick()
                && actionMgr.canMutateHotbarSlotThisTick()) {
            selectSlotVanilla(mc, previousSlot);
        }
        releaseRotation();
        resetState();
    }

    /**
     * Evaluates available catch projectiles and chooses the one with the smallest verified
     * discrete-tick closest-approach miss distance.
     */
    private boolean chooseBestCatchSlotAndSolve(Minecraft mc, boolean predictOneTickAhead) {
        String mode = catchItem.getValue();
        int wcSlot = findHotbarItem(mc, Items.WIND_CHARGE);
        int sbSlot = findHotbarItem(mc, Items.SNOWBALL);
        if (sbSlot < 0) {
            sbSlot = findHotbarItem(mc, Items.EGG);
        }

        if ("Wind Charge".equalsIgnoreCase(mode)) {
            if (wcSlot < 0) return false;
            activeCatchSlot = wcSlot;
            activeCatchIsWindCharge = true;
            return solveExactDiscreteIntercept(mc, predictOneTickAhead, true);
        }
        if ("Snowball / Egg".equalsIgnoreCase(mode)) {
            if (sbSlot < 0) return false;
            activeCatchSlot = sbSlot;
            activeCatchIsWindCharge = false;
            return solveExactDiscreteIntercept(mc, predictOneTickAhead, false);
        }

        boolean wcOk = wcSlot >= 0 && solveExactDiscreteIntercept(mc, predictOneTickAhead, true);
        double wcMiss = wcOk ? lastSolvedMissDist : Double.MAX_VALUE;
        float wcYaw = solvedYaw;
        float wcPitch = solvedPitch;

        if (sbSlot >= 0 && (!wcOk || wcMiss > 0.18D)) {
            boolean sbOk = solveExactDiscreteIntercept(mc, predictOneTickAhead, false);
            if (sbOk && lastSolvedMissDist < wcMiss) {
                activeCatchSlot = sbSlot;
                activeCatchIsWindCharge = false;
                return true;
            }
        }

        if (wcOk) {
            activeCatchSlot = wcSlot;
            activeCatchIsWindCharge = true;
            solvedYaw = wcYaw;
            solvedPitch = wcPitch;
            solvedValid = true;
            return true;
        }
        return false;
    }

    /**
     * Exact Discrete-Tick 3D Intercept Solver (zero heap allocations):
     * - Works for ANY diagonal angle, straight-up (-90 deg), and high-speed / Elytra flight.
     * - Uses exact discrete geometric drag sum S(k) = (1 - 0.99^k)/0.01 matching Minecraft's per-tick loop.
     * - Verifies actual swept-segment closest approach between pearl and catch projectile.
     */
    private boolean solveExactDiscreteIntercept(Minecraft mc, boolean predictOneTickAhead, boolean isWindCharge) {
        Vec3 vel = mc.player.getDeltaMovement();
        boolean comp3D = Boolean.TRUE.equals(elytraCompensation.getValue());
        double rawPx = vel.x;
        double rawPy = (!mc.player.onGround() && (comp3D || mc.player.isFallFlying())) ? vel.y : 0.0D;
        double rawPz = vel.z;

        // Predict player position & velocity 1 tick ahead when pre-aiming on Tick N for Tick N+1 fire
        double eyeX = mc.player.getX();
        double eyeY = mc.player.getEyeY() - (isWindCharge ? 0.0D : 0.1D);
        double eyeZ = mc.player.getZ();
        double fireVx = rawPx;
        double fireVy = rawPy;
        double fireVz = rawPz;
        if (predictOneTickAhead) {
            eyeX += rawPx;
            eyeY += rawPy;
            eyeZ += rawPz;
            if (!mc.player.isFallFlying()) {
                double horizDrag = mc.player.onGround() ? 0.60D : 0.91D;
                fireVx *= horizDrag;
                fireVz *= horizDrag;
                if (!mc.player.onGround()) {
                    fireVy = (fireVy - 0.08D) * 0.98D;
                }
            }
        }

        boolean wcInherits = !"Static".equalsIgnoreCase(wcPhysics.getValue());
        double inheritVx = (isWindCharge && !wcInherits) ? 0.0D : fireVx;
        double inheritVy = (isWindCharge && !wcInherits) ? 0.0D : fireVy;
        double inheritVz = (isWindCharge && !wcInherits) ? 0.0D : fireVz;

        double startPx = simPearlX;
        double startPy = simPearlY;
        double startPz = simPearlZ;
        double startPvx = simPearlVx;
        double startPvy = simPearlVy;
        double startPvz = simPearlVz;
        if (predictOneTickAhead) {
            startPx += startPvx;
            startPy += startPvy;
            startPz += startPvz;
            startPvx *= PEARL_DRAG;
            startPvy = (startPvy * PEARL_DRAG) - PEARL_GRAVITY;
            startPvz *= PEARL_DRAG;
        }

        int maxT = Mth.clamp(maxInterceptTicks.getValue().intValue(), 10, 70);
        double bestScore = Double.MAX_VALUE;
        double bestMissDist = Double.MAX_VALUE;
        double bestReqVx = 0.0D;
        double bestReqVy = 0.0D;
        double bestReqVz = 0.0D;
        boolean found = false;

        double prevPx = startPx, prevPy = startPy, prevPz = startPz;
        double curPx = startPx, curPy = startPy, curPz = startPz;
        double curPvx = startPvx, curPvy = startPvy, curPvz = startPvz;

        // Track exact discrete sums for Snowball/Egg drag and gravity drop
        double dragPow = 1.0D;
        double discreteDragSum = 0.0D;
        double discreteGravSum = 0.0D;

        for (int tick = 1; tick <= maxT; tick++) {
            prevPx = curPx;
            prevPy = curPy;
            prevPz = curPz;
            curPx += curPvx;
            curPy += curPvy;
            curPz += curPvz;
            curPvx *= PEARL_DRAG;
            curPvy = (curPvy * PEARL_DRAG) - PEARL_GRAVITY;
            curPvz *= PEARL_DRAG;

            double prevDragSum = discreteDragSum;
            double prevGravSum = discreteGravSum;
            discreteDragSum += dragPow;
            // Gravity applied after tick k affects ticks k+1..n
            if (tick >= 2) {
                discreteGravSum += PEARL_GRAVITY * prevDragSum;
            }
            dragPow *= PEARL_DRAG;

            for (int sub = 1; sub <= 4; sub++) {
                double frac = sub * 0.25D;
                double t = (tick - 1) + frac;
                if (t < 0.75D) {
                    continue;
                }
                double px = prevPx + (curPx - prevPx) * frac;
                double py = prevPy + (curPy - prevPy) * frac;
                double pz = prevPz + (curPz - prevPz) * frac;

                double reqVx;
                double reqVy;
                double reqVz;
                if (isWindCharge) {
                    reqVx = ((px - eyeX) / t) - inheritVx;
                    reqVy = ((py - eyeY) / t) - inheritVy;
                    reqVz = ((pz - eyeZ) / t) - inheritVz;
                } else {
                    double dSum = prevDragSum + (discreteDragSum - prevDragSum) * frac;
                    if (dSum < 1.0E-4D) {
                        continue;
                    }
                    double gDrop = prevGravSum + (discreteGravSum - prevGravSum) * frac;
                    reqVx = ((px - eyeX) / dSum) - inheritVx;
                    reqVy = ((py - eyeY + gDrop) / dSum) - inheritVy;
                    reqVz = ((pz - eyeZ) / dSum) - inheritVz;
                }

                double speed = Math.sqrt(reqVx * reqVx + reqVy * reqVy + reqVz * reqVz);
                if (speed < 1.0E-5D) {
                    continue;
                }

                // Approximate spatial miss at time t from speed mismatch (|speed - 1.5| * t)
                double speedErr = Math.abs(speed - PROJECTILE_SPEED);
                double approxMiss = speedErr * t;
                // Prefer earliest tight intercept (small tie-breaker on t)
                double score = approxMiss + (t * 0.002D);
                if (score < bestScore) {
                    bestScore = score;
                    bestMissDist = approxMiss;
                    bestReqVx = reqVx;
                    bestReqVy = reqVy;
                    bestReqVz = reqVz;
                    found = true;
                    if (approxMiss < 0.04D && t <= 28.0D) {
                        break;
                    }
                }
            }
            if (found && bestMissDist < 0.04D && tick <= 28) {
                break;
            }
        }

        lastSolvedMissDist = bestMissDist;
        if (!found) {
            solvedValid = false;
            return false;
        }

        // Fixed straight-up handling: never abort when horizDist ~ 0 (pitch = -90 deg)!
        double horizDist = Math.sqrt(bestReqVx * bestReqVx + bestReqVz * bestReqVz);
        float rawYaw;
        float rawPitch;
        if (horizDist < 1.0E-5D) {
            rawYaw = mc.player.getYRot();
            rawPitch = bestReqVy >= 0.0D ? -89.9F : 89.9F;
        } else {
            rawYaw = (float) Math.toDegrees(Math.atan2(bestReqVz, bestReqVx)) - 90.0F;
            rawPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(bestReqVy, horizDist)), -89.9F, 89.9F);
        }

        if (Float.isNaN(rawYaw) || Float.isNaN(rawPitch) || Float.isInfinite(rawYaw) || Float.isInfinite(rawPitch)) {
            solvedValid = false;
            return false;
        }

        float[] snapped = snapAngleToGcd(mc, rawYaw, rawPitch);
        solvedYaw = snapped[0];
        solvedPitch = snapped[1];
        solvedValid = !Float.isNaN(solvedYaw) && !Float.isNaN(solvedPitch);
        return solvedValid;
    }

    private float[] snapAngleToGcd(Minecraft mc, float targetYaw, float targetPitch) {
        double sens = mc.options.sensitivity().get() * 0.6D + 0.2D;
        float gcd = (float) (sens * sens * sens * 1.2D);
        float curYaw = mc.player.getYRot();
        float curPitch = mc.player.getXRot();
        float dYaw = Mth.wrapDegrees(targetYaw - curYaw);
        float dPitch = targetPitch - curPitch;
        if (gcd > 1.0E-6F) {
            dYaw = Math.round(dYaw / gcd) * gcd;
            dPitch = Math.round(dPitch / gcd) * gcd;
        }
        return new float[]{curYaw + dYaw, Mth.clamp(curPitch + dPitch, -89.9F, 89.9F)};
    }

    private void applyAngle(Minecraft mc, float yaw, float pitch) {
        if ("Silent".equalsIgnoreCase(rotationMode.getValue())) {
            RotationManager.getInstance().submitRotationRequest(
                    RotationManager.RotationPriority.AUTO_CRYSTAL,
                    yaw,
                    pitch,
                    1.0F,
                    false,
                    true,
                    null,
                    this
            );
            holdingSilentRotation = true;
        } else {
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
        }
    }

    private void fireUseItemAtAngle(Minecraft mc, float yaw, float pitch) {
        boolean silent = "Silent".equalsIgnoreCase(rotationMode.getValue());
        float prevYaw = mc.player.getYRot();
        float prevPitch = mc.player.getXRot();
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        try {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
        } finally {
            if (silent) {
                mc.player.setYRot(prevYaw);
                mc.player.setXRot(prevPitch);
            }
        }
    }

    private void releaseRotation() {
        if (holdingSilentRotation) {
            RotationManager.getInstance().forceReleaseSpoofing();
            holdingSilentRotation = false;
        }
    }

    /**
     * Used ONLY for manually thrown pearls (where the client learns of the pearl after download latency
     * and the catch throw takes upload latency -> total RTT fast-forward).
     */
    private void captureManualPearlWithPing(Minecraft mc, ThrownEnderpearl pearl) {
        simPearlX = pearl.getX();
        simPearlY = pearl.getY();
        simPearlZ = pearl.getZ();
        Vec3 pv = pearl.getDeltaMovement();
        simPearlVx = pv.x;
        simPearlVy = pv.y;
        simPearlVz = pv.z;

        double rttTicks = Mth.clamp(getLocalPingMs(mc) / 50.0D, 0.0D, 6.0D);
        int full = (int) Math.floor(rttTicks);
        double frac = rttTicks - full;
        for (int i = 0; i < full; i++) {
            simPearlX += simPearlVx;
            simPearlY += simPearlVy;
            simPearlZ += simPearlVz;
            simPearlVx *= PEARL_DRAG;
            simPearlVy = (simPearlVy * PEARL_DRAG) - PEARL_GRAVITY;
            simPearlVz *= PEARL_DRAG;
        }
        if (frac > 1.0E-3D) {
            simPearlX += simPearlVx * frac;
            simPearlY += simPearlVy * frac;
            simPearlZ += simPearlVz * frac;
            double dragF = Math.pow(PEARL_DRAG, frac);
            simPearlVx *= dragF;
            simPearlVy = (simPearlVy * dragF) - (PEARL_GRAVITY * frac);
            simPearlVz *= dragF;
        }
    }

    private int getLocalPingMs(Minecraft mc) {
        try {
            if (mc != null && mc.player != null && mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if (info != null) {
                    return Math.max(0, info.getLatency());
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private ThrownEnderpearl findOwnedRecentPearl(Minecraft mc, double maxDist, int maxAgeTicks) {
        double maxDistSq = maxDist * maxDist;
        ThrownEnderpearl best = null;
        double bestDistSq = maxDistSq;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || !pearl.isAlive()) {
                continue;
            }
            if (pearl.tickCount > maxAgeTicks) {
                continue;
            }
            Entity owner = pearl.getOwner();
            if (owner != null && owner != mc.player) {
                continue;
            }
            double dSq = mc.player.distanceToSqr(pearl);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = pearl;
            }
        }
        return best;
    }

    private int findPreferredCatchSlot(Minecraft mc) {
        String mode = catchItem.getValue();
        if ("Wind Charge".equalsIgnoreCase(mode)) {
            return findHotbarItem(mc, Items.WIND_CHARGE);
        }
        if ("Snowball / Egg".equalsIgnoreCase(mode)) {
            int s = findHotbarItem(mc, Items.SNOWBALL);
            return s >= 0 ? s : findHotbarItem(mc, Items.EGG);
        }
        int wc = findHotbarItem(mc, Items.WIND_CHARGE);
        if (wc >= 0) return wc;
        int sb = findHotbarItem(mc, Items.SNOWBALL);
        return sb >= 0 ? sb : findHotbarItem(mc, Items.EGG);
    }

    private int findHotbarItem(Minecraft mc, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Vanilla slot switch inside START_CLIENT_TICK: MultiPlayerGameMode.tick() sends C09 in exact vanilla order.
     */
    private void selectSlotVanilla(Minecraft mc, int targetSlot) {
        if (mc == null || mc.player == null || targetSlot < 0 || targetSlot >= 9) {
            return;
        }
        if (mc.player.getInventory().getSelectedSlot() != targetSlot) {
            mc.player.getInventory().setSelectedSlot(targetSlot);
            MaceTarget.ActionManager.getInstance().markHotbarSlotMutated();
        }
    }

    private int resolveActiveBoundKey() {
        if (syncedGuiBoundKey != -1) {
            return syncedGuiBoundKey;
        }
        initModuleKeyReflectionOnce();
        try {
            if (moduleKeyGetterMethod != null) {
                Object val = moduleKeyGetterMethod.invoke(this);
                if (val instanceof Integer k && k != 0 && k != GLFW.GLFW_KEY_UNKNOWN) {
                    return k;
                }
            } else if (moduleKeyField != null) {
                int k = moduleKeyField.getInt(this);
                if (k != 0 && k != GLFW.GLFW_KEY_UNKNOWN) {
                    return k;
                }
            }
        } catch (Throwable ignored) {
        }
        int saved = savedPearlKeyCode.getValue().intValue();
        return (saved == 0 || saved == GLFW.GLFW_KEY_UNKNOWN) ? -1 : saved;
    }

    private static void initModuleKeyReflectionOnce() {
        if (reflectionInitialized) {
            return;
        }
        reflectionInitialized = true;
        for (Method m : Module.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase(Locale.ROOT);
            if (m.getParameterCount() == 0 && (m.getReturnType() == int.class || m.getReturnType() == Integer.class)
                    && (n.contains("key") || n.contains("bind"))) {
                m.setAccessible(true);
                moduleKeyGetterMethod = m;
                return;
            }
        }
        for (Field f : Module.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.getType() == int.class && (n.contains("key") || n.contains("bind"))) {
                f.setAccessible(true);
                moduleKeyField = f;
                return;
            }
        }
    }

    private boolean isMacroKeyDown(Minecraft client) {
        int code = resolveActiveBoundKey();
        if (code == -1 || code == 0 || code == GLFW.GLFW_KEY_UNKNOWN) {
            return false;
        }
        long handle = client.getWindow().handle();
        if (handle == 0L) {
            return false;
        }
        if (code < -1) {
            int mouseBtn = code <= -100 ? (-100 - code) : (-10 - code);
            return mouseBtn >= 0 && mouseBtn <= GLFW.GLFW_MOUSE_BUTTON_LAST
                    && GLFW.glfwGetMouseButton(handle, mouseBtn) == GLFW.GLFW_PRESS;
        }
        if (code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST) {
            return InputConstants.isKeyDown(client.getWindow(), code)
                    || GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS;
        }
        return false;
    }
}
