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
import attrium.ru.mixin.accessor.CarriedItemSyncInvoker;
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

/**
 * Revamped Ping-Compensated 3D Kinematic AutoPearlCatch (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * - Exact 1.21.11 projectile physics:
 *   * ThrownEnderpearl: spawn eyeY-0.1, speed 1.5, drag 0.99/tick, gravity -0.03/tick, inherits playerVel.
 *   * WindCharge:       spawn eyeY,     speed 1.5, inertia 1.0,    gravity  0.0/tick,  inherits playerVel.
 *   * Snowball / Egg:   spawn eyeY-0.1, speed 1.5, drag 0.99/tick, gravity -0.03/tick, inherits playerVel.
 * - Fast-forwards live server pearl positions by RTT (pingMs / 50 ticks) so high-ping catches stay accurate.
 * - GrimAC-safe: runs strictly in Manager.START_CLIENT_TICK, respects ActionManager catch-up/slot mutex,
 *   and never mutates hotbar slots from onDisable().
 * - Zero idle entity scanning in public lobbies (gated by Ender Pearl cooldown in On Manual Throw mode).
 */
public class AutoPearlCatch extends Module {

    private static final int STATE_IDLE = 0;
    private static final int STATE_THROW_PEARL = 1;
    private static final int STATE_AIM_AND_SWAP = 2;
    private static final int STATE_REVERT = 3;

    private static final double PROJECTILE_SPEED = 1.5D;
    private static final double PEARL_DRAG = 0.99D;
    private static final double PEARL_GRAVITY = 0.03D;
    private static final ItemStack PEARL_PROBE = new ItemStack(Items.ENDER_PEARL);

    private final ModeSetting triggerMode = register(new ModeSetting(
            "Trigger Mode",
            "Keybind (throws pearl and catches it when enabled) or On Manual Throw",
            "Keybind", "Keybind", "On Manual Throw"
    ));
    private final ModeSetting catchItem = register(new ModeSetting(
            "Catch Item",
            "Projectile used to intercept the thrown Ender Pearl (Auto picks lowest-error projectile)",
            "Auto", "Wind Charge", "Snowball / Egg", "Auto"
    ));
    private final NumberSetting fireDelay = register(new NumberSetting(
            "Fire Delay",
            "Ticks between pearl release and catch projectile release (1 recommended)",
            1.0D, 1.0D, 3.0D, 1.0D
    ));
    private final NumberSetting maxInterceptTicks = register(new NumberSetting(
            "Max Intercept Ticks",
            "Maximum ticks ahead the 3D solver searches for an exact intercept",
            40.0D, 6.0D, 60.0D, 2.0D
    ));
    private final BooleanSetting pingCompensation = register(new BooleanSetting(
            "Ping Compensation",
            "Fast-forwards live server pearl positions by your ping (RTT) for accurate high-ping catches",
            true
    ));
    private final BooleanSetting elytraCompensation = register(new BooleanSetting(
            "Elytra Compensation",
            "Accounts for full 3D airborne & Elytra gliding velocity (including vertical vy)",
            true
    ));
    private final ModeSetting rotationMode = register(new ModeSetting(
            "Rotation Mode",
            "Silent (RotationManager with GCD + MoveFix) or Legit (turns client camera)",
            "Silent", "Silent", "Legit"
    ));
    private final BooleanSetting autoRevert = register(new BooleanSetting(
            "Auto Revert",
            "Return to previous hotbar slot 2 ticks after firing the catch item",
            true
    ));

    // Zero-allocation primitive state machine fields
    private int state = STATE_IDLE;
    private int delayTicks = 0;
    private int revertTicks = 0;
    private int previousSlot = -1;
    private int activeCatchSlot = -1;
    private boolean activeCatchIsWindCharge = true;
    private int trackedPearlEntityId = -1;
    private int lastCaughtPearlEntityId = -1;
    private boolean keybindSequencePending = false;
    private boolean holdingSilentRotation = false;
    private int lastProcessedStartTickId = -1;

    // Fallback / live simulated pearl kinematics
    private double simPearlX = 0.0D;
    private double simPearlY = 0.0D;
    private double simPearlZ = 0.0D;
    private double simPearlVx = 0.0D;
    private double simPearlVy = 0.0D;
    private double simPearlVz = 0.0D;

    // Solved aim angles + error metric
    private float solvedYaw = 0.0F;
    private float solvedPitch = 0.0F;
    private double lastSolvedSpeedErr = Double.MAX_VALUE;
    private boolean solvedValid = false;

    public AutoPearlCatch() {
        super("AutoPearlCatch", "Ping-compensated 3D kinematic Ender Pearl catch (moving & Elytra-safe)", ModuleCategory.COMBAT);
    }

    @Override
    protected void onEnable() {
        resetState();
        if ("Keybind".equalsIgnoreCase(triggerMode.getValue())) {
            keybindSequencePending = true;
        }
    }

    @Override
    protected void onDisable() {
        // Never mutate hotbar slots directly from GUI/onDisable (prevents GrimAC Post flags)
        releaseRotation();
        resetState();
    }

    private void resetState() {
        this.state = STATE_IDLE;
        this.delayTicks = 0;
        this.revertTicks = 0;
        this.previousSlot = -1;
        this.activeCatchSlot = -1;
        this.activeCatchIsWindCharge = true;
        this.trackedPearlEntityId = -1;
        this.keybindSequencePending = false;
        this.solvedValid = false;
        this.lastSolvedSpeedErr = Double.MAX_VALUE;
    }

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        if (!this.isEnabled() || !Manager.isInStartClientTick()) {
            return;
        }
        Minecraft mc = event != null ? event.getClient() : Minecraft.getInstance();
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
            return;
        }

        // Advance fallback simulated pearl 1 tick using exact vanilla order (pos += vel, then drag & gravity)
        if (state == STATE_AIM_AND_SWAP) {
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
            case STATE_AIM_AND_SWAP -> handleAimAndSwap(mc, actionMgr);
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

        int catchSlot = findPreferredCatchSlot(mc);
        if (catchSlot < 0) {
            if ("Keybind".equalsIgnoreCase(triggerMode.getValue()) && keybindSequencePending) {
                keybindSequencePending = false;
                setEnabled(false);
            }
            return;
        }

        if ("Keybind".equalsIgnoreCase(triggerMode.getValue())) {
            if (!keybindSequencePending) {
                return;
            }
            if (!actionMgr.isSafePreMotionTick() || !actionMgr.canMutateHotbarSlotThisTick()) {
                return;
            }
            int pearlSlot = findHotbarItem(mc, Items.ENDER_PEARL);
            if (pearlSlot < 0 || mc.player.getCooldowns().isOnCooldown(PEARL_PROBE)) {
                keybindSequencePending = false;
                setEnabled(false);
                return;
            }
            keybindSequencePending = false;
            previousSlot = mc.player.getInventory().getSelectedSlot();
            activeCatchSlot = catchSlot;
            activeCatchIsWindCharge = mc.player.getInventory().getItem(catchSlot).is(Items.WIND_CHARGE);
            selectSlotSynced(mc, pearlSlot);
            state = STATE_THROW_PEARL;
            return;
        }

        // On Manual Throw: zero entity scanning unless player actually just threw an Ender Pearl (cooldown active)
        if (!mc.player.getCooldowns().isOnCooldown(PEARL_PROBE)) {
            return;
        }

        ThrownEnderpearl pearl = findOwnedRecentPearl(mc, 5.5D, 5);
        if (pearl != null && pearl.getId() != lastCaughtPearlEntityId) {
            previousSlot = mc.player.getInventory().getSelectedSlot();
            activeCatchSlot = catchSlot;
            activeCatchIsWindCharge = mc.player.getInventory().getItem(catchSlot).is(Items.WIND_CHARGE);
            trackedPearlEntityId = pearl.getId();
            lastCaughtPearlEntityId = pearl.getId();
            captureLivePearlStateWithPing(mc, pearl);
            delayTicks = Math.max(1, fireDelay.getValue().intValue());
            state = STATE_AIM_AND_SWAP;
        }
    }

    private void handleThrowPearl(Minecraft mc, MaceTarget.ActionManager actionMgr) {
        if (!actionMgr.isSafePreMotionTick()) {
            return;
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);

        float yawRad = mc.player.getYRot() * Mth.DEG_TO_RAD;
        float pitchRad = mc.player.getXRot() * Mth.DEG_TO_RAD;
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

        delayTicks = Math.max(1, fireDelay.getValue().intValue());
        state = STATE_AIM_AND_SWAP;
    }

    private void handleAimAndSwap(Minecraft mc, MaceTarget.ActionManager actionMgr) {
        delayTicks--;
        if (delayTicks > 0) {
            return;
        }
        if (!actionMgr.isSafePreMotionTick() || !actionMgr.canMutateHotbarSlotThisTick()) {
            return;
        }

        // Sync with live pearl entity if available, fast-forwarded by RTT so we aim at its true server position
        ThrownEnderpearl livePearl = findTrackedOrRecentPearl(mc);
        if (livePearl != null) {
            trackedPearlEntityId = livePearl.getId();
            lastCaughtPearlEntityId = livePearl.getId();
            captureLivePearlStateWithPing(mc, livePearl);
        }

        if (!chooseBestCatchSlotAndSolve(mc, false)) {
            finishOrAbort(mc, actionMgr);
            return;
        }
        selectSlotSynced(mc, activeCatchSlot);
        applySolvedRotation(mc);
        fireWithSolvedAngle(mc);
        revertTicks = 2;
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
            selectSlotSynced(mc, previousSlot);
        }
        releaseRotation();
        boolean keybindMode = "Keybind".equalsIgnoreCase(triggerMode.getValue());
        resetState();
        if (keybindMode) {
            setEnabled(false);
        }
    }

    /**
     * In "Auto" mode, compares Wind Charge vs Snowball/Egg intercept accuracy and picks the cleaner match.
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
            return solveExact3DIntercept(mc, predictOneTickAhead, true);
        }
        if ("Snowball / Egg".equalsIgnoreCase(mode)) {
            if (sbSlot < 0) return false;
            activeCatchSlot = sbSlot;
            activeCatchIsWindCharge = false;
            return solveExact3DIntercept(mc, predictOneTickAhead, false);
        }

        boolean wcOk = wcSlot >= 0 && solveExact3DIntercept(mc, predictOneTickAhead, true);
        double wcErr = wcOk ? lastSolvedSpeedErr : Double.MAX_VALUE;
        float wcYaw = solvedYaw;
        float wcPitch = solvedPitch;

        if (sbSlot >= 0 && (!wcOk || wcErr > 0.10D)) {
            boolean sbOk = solveExact3DIntercept(mc, predictOneTickAhead, false);
            if (sbOk && lastSolvedSpeedErr < wcErr) {
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
     * Exact 3D Interception Solver (zero heap allocations):
     * - WindCharge:   spawn eyeY,     inertia 1.0, gravity 0.0,  inherits playerVel.
     * - Snowball/Egg: spawn eyeY-0.1, drag 0.99,   gravity 0.03, inherits playerVel.
     */
    private boolean solveExact3DIntercept(Minecraft mc, boolean predictOneTickAhead, boolean isWindCharge) {
        Vec3 vel = mc.player.getDeltaMovement();
        boolean comp3D = Boolean.TRUE.equals(elytraCompensation.getValue());
        double playerVx = vel.x;
        double playerVy = (!mc.player.onGround() && (comp3D || mc.player.isFallFlying())) ? vel.y : 0.0D;
        double playerVz = vel.z;

        double spawnYOffset = isWindCharge ? 0.0D : 0.1D;
        double eyeX = mc.player.getX() + (predictOneTickAhead ? playerVx : 0.0D);
        double eyeY = (mc.player.getEyeY() - spawnYOffset) + (predictOneTickAhead ? playerVy : 0.0D);
        double eyeZ = mc.player.getZ() + (predictOneTickAhead ? playerVz : 0.0D);

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

        int maxT = Mth.clamp(maxInterceptTicks.getValue().intValue(), 6, 60);
        double bestSpeedErr = Double.MAX_VALUE;
        double bestReqVx = 0.0D;
        double bestReqVy = 0.0D;
        double bestReqVz = 0.0D;
        boolean found = false;

        double prevPx = startPx, prevPy = startPy, prevPz = startPz;
        double curPx = startPx, curPy = startPy, curPz = startPz;
        double curPvx = startPvx, curPvy = startPvy, curPvz = startPvz;

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

            for (int sub = 1; sub <= 4; sub++) {
                double frac = sub * 0.25D;
                double t = (tick - 1) + frac;
                if (t < 0.5D) {
                    continue;
                }
                double px = prevPx + (curPx - prevPx) * frac;
                double py = prevPy + (curPy - prevPy) * frac;
                double pz = prevPz + (curPz - prevPz) * frac;

                double reqVx;
                double reqVy;
                double reqVz;
                if (isWindCharge) {
                    // WindCharge: inertia 1.0, gravity 0.0, inherits shooter velocity via shootFromRotation()
                    reqVx = ((px - eyeX) / t) - playerVx;
                    reqVy = ((py - eyeY) / t) - playerVy;
                    reqVz = ((pz - eyeZ) / t) - playerVz;
                } else {
                    // Snowball / Egg: drag 0.99, gravity 0.03, inherits shooter velocity
                    double dragIntegral = (1.0D - Math.pow(0.99D, t)) / 0.01D;
                    if (dragIntegral < 1.0E-4D) {
                        continue;
                    }
                    double gravDrop = 0.03D * (t - dragIntegral) / 0.01D;
                    reqVx = ((px - eyeX) / dragIntegral) - playerVx;
                    reqVy = ((py - eyeY + gravDrop) / dragIntegral) - playerVy;
                    reqVz = ((pz - eyeZ) / dragIntegral) - playerVz;
                }

                double speed = Math.sqrt(reqVx * reqVx + reqVy * reqVy + reqVz * reqVz);
                double err = Math.abs(speed - PROJECTILE_SPEED);
                if (err < bestSpeedErr) {
                    bestSpeedErr = err;
                    bestReqVx = reqVx;
                    bestReqVy = reqVy;
                    bestReqVz = reqVz;
                    found = true;
                    if (err < 0.015D) {
                        break;
                    }
                }
            }
            if (found && bestSpeedErr < 0.015D) {
                break;
            }
        }

        lastSolvedSpeedErr = bestSpeedErr;
        if (!found) {
            solvedValid = false;
            return false;
        }

        double horizDist = Math.sqrt(bestReqVx * bestReqVx + bestReqVz * bestReqVz);
        if (horizDist < 1.0E-5D) {
            solvedValid = false;
            return false;
        }

        float rawYaw = (float) Math.toDegrees(Math.atan2(bestReqVz, bestReqVx)) - 90.0F;
        float rawPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(bestReqVy, horizDist)), -89.0F, 89.0F);
        if (Float.isNaN(rawYaw) || Float.isNaN(rawPitch) || Float.isInfinite(rawYaw) || Float.isInfinite(rawPitch)) {
            solvedValid = false;
            return false;
        }

        double sens = mc.options.sensitivity().get() * 0.6D + 0.2D;
        float gcd = (float) (sens * sens * sens * 1.2D);
        float curYaw = mc.player.getYRot();
        float curPitch = mc.player.getXRot();
        float dYaw = Mth.wrapDegrees(rawYaw - curYaw);
        float dPitch = rawPitch - curPitch;
        if (gcd > 1.0E-6F) {
            dYaw = Math.round(dYaw / gcd) * gcd;
            dPitch = Math.round(dPitch / gcd) * gcd;
        }

        solvedYaw = curYaw + dYaw;
        solvedPitch = Mth.clamp(curPitch + dPitch, -89.0F, 89.0F);
        if (Float.isNaN(solvedYaw) || Float.isNaN(solvedPitch)
                || Float.isInfinite(solvedYaw) || Float.isInfinite(solvedPitch)) {
            solvedValid = false;
            return false;
        }
        solvedValid = true;
        return true;
    }

    private void applySolvedRotation(Minecraft mc) {
        if (!solvedValid) {
            return;
        }
        if ("Silent".equalsIgnoreCase(rotationMode.getValue())) {
            RotationManager.getInstance().submitRotationRequest(
                    RotationManager.RotationPriority.AUTO_CRYSTAL,
                    solvedYaw,
                    solvedPitch,
                    1.0F,
                    false,
                    true,
                    null,
                    this
            );
            holdingSilentRotation = true;
        } else {
            mc.player.setYRot(solvedYaw);
            mc.player.setXRot(solvedPitch);
        }
    }

    private void releaseRotation() {
        if (holdingSilentRotation) {
            RotationManager.getInstance().forceReleaseSpoofing();
            holdingSilentRotation = false;
        }
    }

    /**
     * Captures a live ThrownEnderpearl and fast-forwards it by RTT (download + upload latency)
     * so the catch projectile intercepts the pearl's true server-side position on arrival.
     */
    private void captureLivePearlStateWithPing(Minecraft mc, ThrownEnderpearl pearl) {
        simPearlX = pearl.getX();
        simPearlY = pearl.getY();
        simPearlZ = pearl.getZ();
        Vec3 pv = pearl.getDeltaMovement();
        simPearlVx = pv.x;
        simPearlVy = pv.y;
        simPearlVz = pv.z;

        if (!Boolean.TRUE.equals(pingCompensation.getValue())) {
            return;
        }
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

    private ThrownEnderpearl findTrackedOrRecentPearl(Minecraft mc) {
        if (trackedPearlEntityId != -1) {
            Entity e = mc.level.getEntity(trackedPearlEntityId);
            if (e instanceof ThrownEnderpearl p && p.isAlive()) {
                return p;
            }
        }
        return findOwnedRecentPearl(mc, 14.0D, 8);
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

    private void selectSlotSynced(Minecraft mc, int targetSlot) {
        if (mc == null || mc.player == null || mc.gameMode == null || targetSlot < 0 || targetSlot >= 9) {
            return;
        }
        if (mc.player.getInventory().getSelectedSlot() != targetSlot) {
            mc.player.getInventory().setSelectedSlot(targetSlot);
            ((CarriedItemSyncInvoker) mc.gameMode).attrium$ensureHasSentCarriedItem();
            MaceTarget.ActionManager.getInstance().markHotbarSlotMutated();
        }
    }

    private void fireWithSolvedAngle(Minecraft mc) {
        if (!solvedValid) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            return;
        }
        boolean silent = "Silent".equalsIgnoreCase(rotationMode.getValue());
        float prevYaw = mc.player.getYRot();
        float prevPitch = mc.player.getXRot();
        mc.player.setYRot(solvedYaw);
        mc.player.setXRot(solvedPitch);
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
}
