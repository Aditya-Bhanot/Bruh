package attrium.ru.manager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import attrium.ru.api.module.Module;
import attrium.ru.mixin.MovePlayerPacketAccessor;
import attrium.ru.api.module.impl.combat.aura.Angle;
import attrium.ru.api.module.impl.combat.aura.AngleConfig;
import attrium.ru.api.module.impl.combat.aura.AngleConnection;
import attrium.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import attrium.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import attrium.ru.api.module.impl.combat.aura.util.MathUtils;
import attrium.ru.api.module.impl.combat.aura.util.TaskPriority;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Centralized Rotation Controller & Silent Aim Engine (`RotationManager`)
 * with strict `Float.isNaN` / `Float.isInfinite` math guards and dead-target auto-release.
 */
public final class RotationManager {
    private static final RotationManager INSTANCE = new RotationManager();

    public enum RotationPriority {
        SURVIVAL_EMERGENCY(1),
        AUTO_MACE_SMASH(2),
        AUTO_CRYSTAL(3),
        SILENT_AURA(4),
        MOVEMENT_ASSIST(5);

        private final int rank;

        RotationPriority(int rank) {
            this.rank = rank;
        }

        public int getRank() {
            return rank;
        }

        public boolean outranksOrEquals(RotationPriority other) {
            return other == null || this.rank <= other.rank;
        }
    }

    private static boolean packetReflectionInitialized = false;
    private static Field movePacketYRotField = null;
    private static Field movePacketXRotField = null;
    private static Field movePacketHasRotField = null;
    private static Constructor<?> rotPacketCtor4 = null;
    private static Constructor<?> rotPacketCtor3 = null;

    private final RotateConstructor silentRotateConstructor = new LinearConstructor();
    private final AngleConfig cachedMoveFixOnConfig = new AngleConfig(silentRotateConstructor, true, true);
    private final AngleConfig cachedMoveFixOffConfig = new AngleConfig(silentRotateConstructor, false, false);
    private static ServerboundMovePlayerPacket reusableLookPacketSingleton = null;

    private final float[] gcdScratch = new float[2];
    private final float[] moveFixScratch = new float[2];

    private long activeArbitrationGameTime = -1L;
    private RotationPriority winningTickPriority = null;
    private float requestedTargetYaw = 0.0F;
    private float requestedTargetPitch = 0.0F;
    private float requestedSmoothingTicks = 1.0F;
    private boolean requestedBezierCurve = true;
    private boolean requestedMoveFix = true;
    private LivingEntity requestedTargetEntity = null;
    private Module winningModuleOwner = null;

    private float serverSpoofedYaw = 0.0F;
    private float serverSpoofedPitch = 0.0F;
    private float prevServerSpoofedYaw = 0.0F;
    private float prevServerSpoofedPitch = 0.0F;
    private boolean spoofingActive = false;
    private boolean returningToVisualCamera = false;
    private int smoothingStepCounter = 0;
    private int holdTicksRemaining = 0;
    private long lastTickProcessedGameTime = -1L;

    private RotationManager() {
    }

    public static RotationManager getInstance() {
        return INSTANCE;
    }

    public float getServerSpoofedYaw() {
        return serverSpoofedYaw;
    }

    public float getServerSpoofedPitch() {
        return serverSpoofedPitch;
    }

    public boolean isSpoofingActive() {
        return spoofingActive;
    }

    public boolean isMoveFixEnabledForCurrentTick() {
        return spoofingActive && requestedMoveFix;
    }

    public RotationPriority getWinningTickPriority() {
        return winningTickPriority;
    }

    public synchronized boolean submitRotationRequest(
            RotationPriority priority,
            float targetYaw,
            float targetPitch,
            float smoothingTicks,
            boolean bezierCurve,
            boolean applyMoveFix,
            LivingEntity targetEntity,
            Module ownerModule
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || priority == null) {
            return false;
        }

        // Section 3 Math & Target Clearing Guard: Abort immediately if yaw/pitch is NaN/Infinite or targetEntity is dead!
        if (Float.isNaN(targetYaw) || Float.isNaN(targetPitch)
                || Float.isInfinite(targetYaw) || Float.isInfinite(targetPitch)) {
            return false;
        }
        if (targetEntity != null && (!targetEntity.isAlive() || targetEntity.isDeadOrDying() || targetEntity.getHealth() <= 0.0F)) {
            requestedTargetEntity = null;
            forceReleaseSpoofing();
            return false;
        }

        long nowGameTime = mc.level.getGameTime();
        if (nowGameTime != activeArbitrationGameTime) {
            activeArbitrationGameTime = nowGameTime;
            winningTickPriority = null;
        }

        if (winningTickPriority != null && !priority.outranksOrEquals(winningTickPriority)) {
            return false;
        }

        winningTickPriority = priority;
        requestedTargetYaw = targetYaw;
        requestedTargetPitch = Mth.clamp(targetPitch, -89.5F, 89.5F);
        requestedSmoothingTicks = Math.max(1.0F, smoothingTicks);
        requestedBezierCurve = bezierCurve;
        requestedMoveFix = applyMoveFix;
        requestedTargetEntity = targetEntity;
        winningModuleOwner = ownerModule;
        holdTicksRemaining = 2;

        stepAndCommitWinningRotation(mc);
        return true;
    }

    public boolean submitInstantSilentRotation(
            RotationPriority priority,
            float targetYaw,
            float targetPitch,
            boolean applyMoveFix,
            Module ownerModule
    ) {
        return submitRotationRequest(
                priority,
                targetYaw,
                targetPitch,
                1.0F,
                false,
                applyMoveFix,
                null,
                ownerModule
        );
    }

    public synchronized void onClientTick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            forceReleaseSpoofing();
            return;
        }

        if (requestedTargetEntity != null
                && (!requestedTargetEntity.isAlive() || requestedTargetEntity.isDeadOrDying() || requestedTargetEntity.getHealth() <= 0.0F)) {
            requestedTargetEntity = null;
            forceReleaseSpoofing();
            return;
        }

        long gameTime = mc.level.getGameTime();
        if (gameTime == lastTickProcessedGameTime) {
            return;
        }
        lastTickProcessedGameTime = gameTime;

        if (gameTime != activeArbitrationGameTime) {
            winningTickPriority = null;
            if (holdTicksRemaining > 0) {
                holdTicksRemaining--;
            } else if (spoofingActive) {
                stepReturnTowardVisualCamera(mc);
            }
        }
    }

    private void stepAndCommitWinningRotation(Minecraft mc) {
        if (Float.isNaN(requestedTargetYaw) || Float.isNaN(requestedTargetPitch)
                || Float.isInfinite(requestedTargetYaw) || Float.isInfinite(requestedTargetPitch)) {
            return;
        }

        if (!spoofingActive) {
            serverSpoofedYaw = mc.player.getYRot();
            serverSpoofedPitch = mc.player.getXRot();
            prevServerSpoofedYaw = serverSpoofedYaw;
            prevServerSpoofedPitch = serverSpoofedPitch;
            spoofingActive = true;
            returningToVisualCamera = false;
            smoothingStepCounter = 0;
        } else if (returningToVisualCamera) {
            returningToVisualCamera = false;
            smoothingStepCounter = 0;
        }

        prevServerSpoofedYaw = serverSpoofedYaw;
        prevServerSpoofedPitch = serverSpoofedPitch;

        float totalDeltaYaw = Mth.wrapDegrees(requestedTargetYaw - serverSpoofedYaw);
        float totalDeltaPitch = requestedTargetPitch - serverSpoofedPitch;
        double angularErrorDeg = Math.hypot(totalDeltaYaw, totalDeltaPitch);

        float totalTicks = Math.max(1.0F, requestedSmoothingTicks);
        smoothingStepCounter = Math.min((int) Math.ceil(totalTicks), smoothingStepCounter + 1);

        float stepFactor;
        if (totalTicks <= 1.05F || angularErrorDeg < 5.0D) {
            stepFactor = 1.0F;
        } else {
            float rawFraction = Mth.clamp(1.0F / totalTicks, 0.15F, 1.0F);
            if (requestedBezierCurve) {
                float u = Mth.clamp(smoothingStepCounter / totalTicks, rawFraction, 1.0F);
                float bezierEase = u * u * (3.0F - 2.0F * u);
                stepFactor = Mth.clamp(Math.max(rawFraction, bezierEase * 0.72F), 0.18F, 0.96F);
            } else {
                stepFactor = rawFraction;
            }

            if (requestedTargetEntity != null && mc.player.distanceToSqr(requestedTargetEntity) <= 16.0D) {
                stepFactor = Mth.clamp(stepFactor * 2.15F, 0.38F, 1.0F);
            }
        }

        float wristVariance = (stepFactor < 0.99F)
                ? (float) ThreadLocalRandom.current().nextDouble(0.985D, 1.015D)
                : 1.0F;
        float rawStepYaw = totalDeltaYaw * stepFactor * wristVariance;
        float rawStepPitch = totalDeltaPitch * stepFactor * wristVariance;

        snapDeltasToSensitivityGcdZeroAlloc(mc, rawStepYaw, rawStepPitch, gcdScratch);
        float calculatedYaw = serverSpoofedYaw + gcdScratch[0];
        float calculatedPitch = Mth.clamp(serverSpoofedPitch + gcdScratch[1], -89.5F, 89.5F);
        if (Float.isNaN(calculatedYaw) || Float.isNaN(calculatedPitch)
                || Float.isInfinite(calculatedYaw) || Float.isInfinite(calculatedPitch)) {
            return;
        }

        serverSpoofedYaw = calculatedYaw;
        serverSpoofedPitch = calculatedPitch;

        if (requestedMoveFix) {
            applyMoveFixMatrixToPlayer(mc.player);
        }
        syncWithAngleConnection();
    }

    private void stepReturnTowardVisualCamera(Minecraft mc) {
        if (!spoofingActive || mc.player == null) {
            return;
        }
        returningToVisualCamera = true;
        prevServerSpoofedYaw = serverSpoofedYaw;
        prevServerSpoofedPitch = serverSpoofedPitch;

        float visualYaw = mc.player.getYRot();
        float visualPitch = mc.player.getXRot();
        float remYaw = Mth.wrapDegrees(visualYaw - serverSpoofedYaw);
        float remPitch = visualPitch - serverSpoofedPitch;

        if (Math.abs(remYaw) < 1.2F && Math.abs(remPitch) < 1.2F) {
            forceReleaseSpoofing();
            return;
        }

        float invTicks = 1.0F / Math.max(2.0F, requestedSmoothingTicks);
        snapDeltasToSensitivityGcdZeroAlloc(mc, remYaw * invTicks, remPitch * invTicks, gcdScratch);
        float calculatedYaw = serverSpoofedYaw + gcdScratch[0];
        float calculatedPitch = Mth.clamp(serverSpoofedPitch + gcdScratch[1], -89.5F, 89.5F);
        if (Float.isNaN(calculatedYaw) || Float.isNaN(calculatedPitch)
                || Float.isInfinite(calculatedYaw) || Float.isInfinite(calculatedPitch)) {
            forceReleaseSpoofing();
            return;
        }
        serverSpoofedYaw = calculatedYaw;
        serverSpoofedPitch = calculatedPitch;
        syncWithAngleConnection();
    }

    public synchronized void forceReleaseSpoofing() {
        if (spoofingActive) {
            try {
                AngleConnection.INSTANCE.startReturning();
            } catch (Throwable ignored) {
            }
        }
        spoofingActive = false;
        returningToVisualCamera = false;
        winningTickPriority = null;
        requestedTargetEntity = null;
        smoothingStepCounter = 0;
        holdTicksRemaining = 0;
    }

    public static void snapDeltasToSensitivityGcdZeroAlloc(
            Minecraft mc,
            float deltaYaw,
            float deltaPitch,
            float[] outSnappedDeltas
    ) {
        if (Float.isNaN(deltaYaw) || Float.isNaN(deltaPitch)
                || Float.isInfinite(deltaYaw) || Float.isInfinite(deltaPitch)) {
            outSnappedDeltas[0] = 0.0F;
            outSnappedDeltas[1] = 0.0F;
            return;
        }
        double gcd = computeMouseSensitivityGcd(mc);
        if (gcd <= 1.0E-7D) {
            outSnappedDeltas[0] = deltaYaw;
            outSnappedDeltas[1] = deltaPitch;
            return;
        }
        float g = (float) gcd;
        outSnappedDeltas[0] = Math.round(deltaYaw / g) * g;
        outSnappedDeltas[1] = Math.round(deltaPitch / g) * g;
    }

    public static double computeMouseSensitivityGcd(Minecraft mc) {
        try {
            if (mc != null && mc.options != null && mc.options.sensitivity() != null) {
                double sens = mc.options.sensitivity().get();
                double f = sens * 0.6D + 0.2D;
                return f * f * f * 8.0D * 0.15D;
            }
        } catch (Throwable ignored) {
        }
        return MathUtils.computeGcd();
    }

    public static void apply2DRotationMatrixToInputs(
            float rawForward,
            float rawSideways,
            float visualYaw,
            float spoofedServerYaw,
            float[] outForwardAndSideways
    ) {
        float inputMag = (float) Math.hypot(rawForward, rawSideways);
        if (inputMag < 1.0E-4F || Float.isNaN(visualYaw) || Float.isNaN(spoofedServerYaw)) {
            outForwardAndSideways[0] = 0.0F;
            outForwardAndSideways[1] = 0.0F;
            return;
        }

        double deltaRad = Math.toRadians(Mth.wrapDegrees(visualYaw - spoofedServerYaw));
        float cos = (float) Math.cos(deltaRad);
        float sin = (float) Math.sin(deltaRad);

        float rotForward = rawForward * cos - rawSideways * sin;
        float rotSideways = rawForward * sin + rawSideways * cos;

        float maxAxis = Math.max(Math.abs(rotForward), Math.abs(rotSideways));
        if (maxAxis > 1.0E-4F) {
            float scale = Math.min(1.0F, inputMag) / maxAxis;
            rotForward = Math.round(rotForward * scale) * Math.min(1.0F, inputMag);
            rotSideways = Math.round(rotSideways * scale) * Math.min(1.0F, inputMag);
        }

        outForwardAndSideways[0] = Mth.clamp(rotForward, -1.0F, 1.0F);
        outForwardAndSideways[1] = Mth.clamp(rotSideways, -1.0F, 1.0F);
    }

    public void applyMoveFixMatrixToPlayer(LocalPlayer player) {
    }

    private void syncWithAngleConnection() {
        if (!spoofingActive) {
            return;
        }
        if (Float.isNaN(serverSpoofedYaw) || Float.isNaN(serverSpoofedPitch)
                || Float.isInfinite(serverSpoofedYaw) || Float.isInfinite(serverSpoofedPitch)) {
            return;
        }
        try {
            Angle silentAngle = new Angle(serverSpoofedYaw, serverSpoofedPitch).adjustSensitivity();
            TaskPriority taskPrio = TaskPriority.HIGH_IMPORTANCE_1;
            AngleConnection.INSTANCE.rotateTo(
                    new Angle.VecRotation(silentAngle, silentAngle.toVector()),
                    requestedTargetEntity,
                    2,
                    requestedMoveFix ? cachedMoveFixOnConfig : cachedMoveFixOffConfig,
                    taskPrio,
                    winningModuleOwner
            );
        } catch (Throwable ignored) {
        }
    }

    public void onOutgoingPacket(Packet<?> packet) {
        if (!spoofingActive || !(packet instanceof ServerboundMovePlayerPacket movePacket)) {
            return;
        }
        injectSpoofedRotationIntoMovePacket(movePacket, serverSpoofedYaw, serverSpoofedPitch);
    }

    public static void injectSpoofedRotationIntoMovePacket(
            ServerboundMovePlayerPacket packet,
            float spoofYaw,
            float spoofPitch
    ) {
        if (packet == null || Float.isNaN(spoofYaw) || Float.isNaN(spoofPitch)
                || Float.isInfinite(spoofYaw) || Float.isInfinite(spoofPitch)) {
            return;
        }
        if ((Object) packet instanceof MovePlayerPacketAccessor accessor) {
            accessor.attrium$setYRot(spoofYaw);
            accessor.attrium$setXRot(spoofPitch);
            accessor.attrium$setHasRot(true);
            return;
        }
        initPacketReflectionOnce();
        try {
            if (movePacketYRotField != null) {
                movePacketYRotField.setFloat(packet, spoofYaw);
            }
            if (movePacketXRotField != null) {
                movePacketXRotField.setFloat(packet, spoofPitch);
            }
            if (movePacketHasRotField != null) {
                movePacketHasRotField.setBoolean(packet, true);
            }
        } catch (Throwable ignored) {
        }
    }

    public static ServerboundMovePlayerPacket createSpoofedLookPacket(
            float spoofYaw,
            float spoofPitch,
            boolean onGround,
            boolean horizontalCollision
    ) {
        if (Float.isNaN(spoofYaw) || Float.isNaN(spoofPitch)
                || Float.isInfinite(spoofYaw) || Float.isInfinite(spoofPitch)) {
            return null;
        }
        if (reusableLookPacketSingleton != null
                && (Object) reusableLookPacketSingleton instanceof MovePlayerPacketAccessor accessor) {
            accessor.attrium$setYRot(spoofYaw);
            accessor.attrium$setXRot(spoofPitch);
            accessor.attrium$setOnGround(onGround);
            accessor.attrium$setHasRot(true);
            return reusableLookPacketSingleton;
        }
        initPacketReflectionOnce();
        try {
            if (rotPacketCtor4 != null) {
                reusableLookPacketSingleton = (ServerboundMovePlayerPacket) rotPacketCtor4.newInstance(
                        spoofYaw, spoofPitch, onGround, horizontalCollision
                );
                return reusableLookPacketSingleton;
            }
            if (rotPacketCtor3 != null) {
                reusableLookPacketSingleton = (ServerboundMovePlayerPacket) rotPacketCtor3.newInstance(
                        spoofYaw, spoofPitch, onGround
                );
                return reusableLookPacketSingleton;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void initPacketReflectionOnce() {
        if (packetReflectionInitialized) {
            return;
        }
        packetReflectionInitialized = true;
        try {
            Field[] fields = ServerboundMovePlayerPacket.class.getDeclaredFields();
            int floatIdx = 0;
            int boolIdx = 0;
            for (Field f : fields) {
                f.setAccessible(true);
                if (f.getType() == float.class) {
                    if (floatIdx == 0) {
                        movePacketYRotField = f;
                    } else if (floatIdx == 1) {
                        movePacketXRotField = f;
                    }
                    floatIdx++;
                } else if (f.getType() == boolean.class) {
                    String fn = f.getName().toLowerCase();
                    if (fn.contains("rot") || boolIdx == 2) {
                        movePacketHasRotField = f;
                    }
                    boolIdx++;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            for (Constructor<?> c : ServerboundMovePlayerPacket.Rot.class.getDeclaredConstructors()) {
                c.setAccessible(true);
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 4
                        && pt[0] == float.class
                        && pt[1] == float.class
                        && pt[2] == boolean.class
                        && pt[3] == boolean.class) {
                    rotPacketCtor4 = c;
                } else if (pt.length == 3
                        && pt[0] == float.class
                        && pt[1] == float.class
                        && pt[2] == boolean.class) {
                    rotPacketCtor3 = c;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
