package releon.ru.api.module.impl.combat.aura;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.PlayerVelocityStrafeEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.module.Module;
import releon.ru.api.module.impl.combat.aura.back.BackAngle;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.module.impl.combat.aura.util.TaskProcessor;
import releon.ru.manager.Manager;
import releon.ru.utils.player.BaritoneMovementHelper;

public class AngleConnection {
    public static final AngleConnection INSTANCE = new AngleConnection();
    private static final RotateConstructor USE_ITEM_RETURN_SMOOTH = new BackAngle(0.95F);

    private final TaskProcessor<AngleConstructor> rotationPlanTaskProcessor = new TaskProcessor<>();
    private AngleConstructor lastRotationPlan;
    private Angle currentAngle;
    private Angle previousAngle;
    private Angle serverAngle = Angle.DEFAULT;
    private Angle fakeAngle;
    private Angle previousFakeAngle;
    private Float fakeBodyYaw;
    private Float previousFakeBodyYaw;
    private boolean returning;
    private int forcePacketRotationTicks;
    private boolean useItemReturnPending;
    private int staleInactiveTicks;
    private boolean rotationRequestedThisTick;
    private boolean queuedRotationTaskThisTick;

    public void setRotation(Angle value) {
        if (value == null) {
            previousAngle = currentAngle != null ? currentAngle : MathAngle.cameraAngle();
        } else {
            previousAngle = currentAngle;
        }
        currentAngle = value;
    }

    public Angle getCurrentAngle() {
        return currentAngle;
    }

    public Angle getRotation() {
        return currentAngle != null ? currentAngle : MathAngle.cameraAngle();
    }

    public Angle getServerAngle() {
        return serverAngle;
    }

    public float getPacketYaw() {
        return getPacketRotation().getYaw();
    }

    public float getPacketPitch() {
        return Mth.clamp(getPacketRotation().getPitch(), -90.0F, 90.0F);
    }

    public boolean shouldApplyPacketRotation() {
        if (BaritoneMovementHelper.isBaritoneActive(Minecraft.getInstance().player)) {
            return false;
        }
        return currentAngle != null
                || fakeAngle != null
                || (forcePacketRotationTicks > 0 && previousAngle != null);
    }

    public void forcePacketRotation(int ticks) {
        if (ticks > 0) {
            forcePacketRotationTicks = Math.max(forcePacketRotationTicks, ticks);
        }
    }

    public Angle getFakeRotation() {
        if (fakeAngle != null) {
            return fakeAngle;
        }
        return currentAngle != null ? currentAngle : previousAngle != null ? previousAngle : MathAngle.cameraAngle();
    }

    public Angle getFakeAngle() {
        return fakeAngle;
    }

    public Angle getPreviousFakeRotation() {
        if (previousFakeAngle != null) {
            return previousFakeAngle;
        }
        return getFakeRotation();
    }

    public float getFakeBodyYaw() {
        return fakeBodyYaw != null ? fakeBodyYaw : getFakeRotation().getYaw();
    }

    public float getPreviousFakeBodyYaw() {
        return previousFakeBodyYaw != null ? previousFakeBodyYaw : getFakeBodyYaw();
    }

    public void setFakeRotation(Angle angle) {
        angle = normalizeAngle(angle, fakeAngle);
        if (angle == null) {
            previousFakeAngle = null;
            fakeAngle = null;
            previousFakeBodyYaw = null;
            fakeBodyYaw = null;
            return;
        }
        previousFakeAngle = fakeAngle != null ? fakeAngle : angle;
        fakeAngle = angle;

        float targetYaw = angle.getYaw();
        if (fakeBodyYaw == null) {
            fakeBodyYaw = targetYaw;
            previousFakeBodyYaw = targetYaw;
            return;
        }
        previousFakeBodyYaw = fakeBodyYaw;
        float diff = Mth.wrapDegrees(targetYaw - fakeBodyYaw);
        fakeBodyYaw += Mth.clamp(diff, -8.0F, 8.0F);
    }

    public Angle getPreviousRotation() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return currentAngle != null ? currentAngle : Angle.DEFAULT;
        }
        return currentAngle != null && previousAngle != null ? previousAngle : new Angle(client.player.yRotO, client.player.xRotO);
    }

    public Angle getMoveRotation() {
        Minecraft client = Minecraft.getInstance();
        if (BaritoneMovementHelper.isBaritoneActive(client.player)) {
            return MathAngle.cameraAngle();
        }
        AngleConstructor rotationPlan = getCurrentRotationPlan();
        return currentAngle != null && rotationPlan != null && rotationPlan.isMoveCorrection()
                ? currentAngle
                : MathAngle.cameraAngle();
    }

    public boolean isMoveCorrectionActive() {
        Minecraft client = Minecraft.getInstance();
        if (BaritoneMovementHelper.isBaritoneActive(client.player)) {
            return false;
        }
        AngleConstructor rotationPlan = getCurrentRotationPlan();
        return currentAngle != null && rotationPlan != null && rotationPlan.isMoveCorrection();
    }

    public boolean isFreeCorrectionActive() {
        AngleConstructor rotationPlan = getCurrentRotationPlan();
        return isMoveCorrectionActive() && rotationPlan != null && rotationPlan.isFreeCorrection();
    }

    public boolean isReturning() {
        return returning;
    }

    public boolean isUseItemReturnPending() {
        return useItemReturnPending;
    }

    public AngleConstructor getCurrentRotationPlan() {
        AngleConstructor activePlan = rotationPlanTaskProcessor.fetchActiveTaskValue();
        return activePlan != null ? activePlan : lastRotationPlan;
    }

    public void rotateTo(Angle.VecRotation vecRotation, LivingEntity entity, int reset, AngleConfig configurable, TaskPriority taskPriority, Module provider) {
        rotateTo(configurable.createRotationPlan(vecRotation.getAngle(), vecRotation.getVec(), entity, reset), taskPriority, provider);
    }

    public void rotateTo(Angle angle, int reset, AngleConfig configurable, TaskPriority taskPriority, Module provider) {
        rotateTo(configurable.createRotationPlan(angle, angle.toVector(), null, reset), taskPriority, provider);
    }

    public void rotateTo(Angle angle, AngleConfig configurable, TaskPriority taskPriority, Module provider) {
        rotateTo(configurable.createRotationPlan(angle, angle.toVector(), null, 1), taskPriority, provider);
    }

    public void rotateTo(AngleConstructor plan, TaskPriority taskPriority, Module provider) {
        Minecraft client = Minecraft.getInstance();
        if (BaritoneMovementHelper.isBaritoneActive(client.player)) {
            return;
        }
        returning = false;
        useItemReturnPending = false;
        rotationRequestedThisTick = true;
        rotationPlanTaskProcessor.addTask(new TaskProcessor.Task<>(1, taskPriority.getPriority(), provider, plan));
    }

    public void update(Minecraft client) {
        tick(client);
    }

    public void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            reset();
            return;
        }
        if (BaritoneMovementHelper.isBaritoneActive(client.player)) {
            if (hasAnyRotationState()) {
                reset();
            }
            return;
        }

        if (forcePacketRotationTicks > 0) {
            forcePacketRotationTicks--;
        }

        rotationRequestedThisTick = false;
        queuedRotationTaskThisTick = false;
        Manager.postEvent(new RotationUpdateEvent(EventPhase.PRE));
        queuedRotationTaskThisTick = rotationPlanTaskProcessor.fetchActiveTaskValue() != null;
        advanceRotation(client);
        Manager.postEvent(new RotationUpdateEvent(EventPhase.POST));

        if (useItemReturnPending && !shouldDelayUseAction()) {
            useItemReturnPending = false;
        }
        if (!hasActiveRotationOwner() && hasAnyRotationState()) {
            staleInactiveTicks++;
            if (staleInactiveTicks >= 6) {
                hardResetStaleRotationState();
                return;
            }
        } else {
            staleInactiveTicks = 0;
        }
    }

    private void advanceRotation(Minecraft client) {
        AngleConstructor activePlan = getCurrentRotationPlan();
        if (activePlan == null) {
            return;
        }

        Angle clientAngle = new Angle(client.player.getYRot(), client.player.getXRot());
        if (returning && currentAngle != null && computeRotationDifference(currentAngle, clientAngle) < 1.0F) {
            restoreVanillaLook();
            return;
        }
        if (lastRotationPlan != null) {
            double differenceFromCurrentToPlayer = computeRotationDifference(serverAngle, clientAngle);
            if (activePlan.getTicksUntilReset() <= rotationPlanTaskProcessor.tickCounter() && differenceFromCurrentToPlayer < 1.0F) {
                client.player.setYRot(getRotation().getYaw());
                client.player.setXRot(getRotation().getPitch());
                setRotation(null);
                setFakeRotation(null);
                lastRotationPlan = null;
                rotationPlanTaskProcessor.clear();
                returning = false;
                useItemReturnPending = false;
                return;
            }
        }

        Angle baseAngle = currentAngle != null ? currentAngle : clientAngle;
        Angle newAngle = activePlan.nextRotation(baseAngle, rotationPlanTaskProcessor.fetchActiveTaskValue() == null).adjustSensitivity();
        setRotation(newAngle);
        lastRotationPlan = activePlan;
        rotationPlanTaskProcessor.tick(1);
    }

    public void applyPlayerRotation(Minecraft client) {
        if (client == null || client.player == null || currentAngle == null) {
            return;
        }
        client.player.setYRot(currentAngle.getYaw());
        client.player.setXRot(currentAngle.getPitch());
        client.player.setYHeadRot(currentAngle.getYaw());
        client.player.setYBodyRot(currentAngle.getYaw());
    }

    public void clear() {
        rotationPlanTaskProcessor.clear();
        currentAngle = null;
        previousAngle = null;
        lastRotationPlan = null;
        useItemReturnPending = false;
    }

    public void startReturning() {
        startReturning(null);
    }

    public void startReturningForUse() {
        useItemReturnPending = true;
        startReturning(USE_ITEM_RETURN_SMOOTH);
    }

    public boolean requestUseRotationRestore() {
        if (!shouldDelayUseAction()) {
            useItemReturnPending = false;
            return false;
        }
        if (!useItemReturnPending) {
            startReturningForUse();
        }
        return true;
    }

    public boolean shouldDelayUseAction() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        Angle cameraAngle = MathAngle.cameraAngle();
        return returning
                || currentAngle != null && computeRotationDifference(currentAngle, cameraAngle) > 0.35D
                || fakeAngle != null && computeRotationDifference(fakeAngle, cameraAngle) > 0.35D
                || forcePacketRotationTicks > 0 && previousAngle != null && computeRotationDifference(previousAngle, cameraAngle) > 0.35D;
    }

    public void reset() {
        clear();
        fakeAngle = null;
        previousFakeAngle = null;
        fakeBodyYaw = null;
        previousFakeBodyYaw = null;
        serverAngle = Angle.DEFAULT;
        returning = false;
        forcePacketRotationTicks = 0;
        staleInactiveTicks = 0;
        rotationRequestedThisTick = false;
        queuedRotationTaskThisTick = false;
    }

    public void restoreVanillaLook() {
        reset();
        syncVanillaHeadAndBody();
    }

    public void syncAfterFirstPersonRestore() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            reset();
            return;
        }
        Angle playerAngle = MathAngle.cameraAngle();
        Angle syncedAngle = normalizeAngle(playerAngle, currentAngle != null ? currentAngle : playerAngle);
        currentAngle = syncedAngle;
        previousAngle = syncedAngle;
        fakeAngle = null;
        previousFakeAngle = null;
        fakeBodyYaw = null;
        previousFakeBodyYaw = null;
        returning = false;
        forcePacketRotationTicks = 0;
        useItemReturnPending = false;
    }

    public void onMovePacket(ServerboundMovePlayerPacket packet) {
        if (packet != null && packet.hasRotation()) {
            serverAngle = new Angle(packet.getYRot(1), packet.getXRot(1));
        }
    }

    public void onPlayerPosition(ClientboundPlayerPositionPacket packet) {
        if (packet != null) {
            serverAngle = new Angle(packet.change().yRot(), packet.change().xRot());
        }
    }

    public static double computeRotationDifference(Angle a, Angle b) {
        return Math.hypot(Math.abs(computeAngleDifference(a.getYaw(), b.getYaw())), Math.abs(a.getPitch() - b.getPitch()));
    }

    public static float computeAngleDifference(float a, float b) {
        return Mth.wrapDegrees(a - b);
    }

    private Vec3 fixVelocity(Vec3 currentVelocity, Vec3 movementInput, float speed) {
        if (currentAngle == null) {
            return currentVelocity;
        }
        double length = movementInput.lengthSqr();
        if (length < 1.0E-7D) {
            return Vec3.ZERO;
        }
        Vec3 scaled = (length > 1.0D ? movementInput.normalize() : movementInput).scale(speed);
        float sin = Mth.sin(currentAngle.getYaw() * Mth.DEG_TO_RAD);
        float cos = Mth.cos(currentAngle.getYaw() * Mth.DEG_TO_RAD);
        return new Vec3(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin);
    }

    private Angle getPacketRotation() {
        if (currentAngle != null) {
            return currentAngle;
        }
        if (fakeAngle != null) {
            return fakeAngle;
        }
        if (forcePacketRotationTicks > 0 && previousAngle != null) {
            return previousAngle;
        }
        return MathAngle.cameraAngle();
    }

    private Angle normalizeAngle(Angle angle, Angle reference) {
        if (angle == null) {
            return null;
        }
        float yaw = angle.getYaw();
        if (reference != null) {
            yaw = reference.getYaw() + Mth.wrapDegrees(yaw - reference.getYaw());
        } else {
            yaw = Mth.wrapDegrees(yaw);
        }
        return new Angle(yaw, Mth.clamp(angle.getPitch(), -90.0F, 90.0F));
    }

    private void startReturning(RotateConstructor returnSmooth) {
        AngleConstructor sourcePlan = rotationPlanTaskProcessor.fetchActiveTaskValue();
        AngleConstructor returnPlan = sourcePlan != null ? sourcePlan : lastRotationPlan;
        if (returnPlan != null && returnSmooth != null) {
            returnPlan = cloneRotationPlan(returnPlan, returnSmooth);
        } else if (returnPlan == null && returnSmooth != null && currentAngle != null) {
            returnPlan = new AngleConstructor(currentAngle, currentAngle.toVector(), null, returnSmooth, 1, 1.0F, false, false);
        }
        lastRotationPlan = returnPlan;
        rotationPlanTaskProcessor.clear();
        returning = currentAngle != null && lastRotationPlan != null;
        if (!returning && !shouldDelayUseAction()) {
            useItemReturnPending = false;
        }
    }

    private AngleConstructor cloneRotationPlan(AngleConstructor sourcePlan, RotateConstructor returnSmooth) {
        return new AngleConstructor(
                sourcePlan.getAngle(),
                sourcePlan.getVec3d(),
                sourcePlan.getEntity(),
                returnSmooth,
                sourcePlan.getTicksUntilReset(),
                sourcePlan.getResetThreshold(),
                sourcePlan.isMoveCorrection(),
                sourcePlan.isFreeCorrection()
        );
    }

    private boolean hasAnyRotationState() {
        return currentAngle != null
                || fakeAngle != null
                || previousAngle != null
                || previousFakeAngle != null
                || lastRotationPlan != null
                || returning
                || useItemReturnPending;
    }

    private boolean hasActiveRotationOwner() {
        return rotationRequestedThisTick
                || queuedRotationTaskThisTick
                || returning
                || useItemReturnPending
                || forcePacketRotationTicks > 0;
    }

    private void hardResetStaleRotationState() {
        restoreVanillaLook();
    }

    private void syncVanillaHeadAndBody() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            float yaw = client.player.getYRot();
            client.player.setYHeadRot(yaw);
            client.player.setYBodyRot(yaw);
        }
    }

    @SubscribeEvent
    private void onPlayerVelocityStrafe(PlayerVelocityStrafeEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (BaritoneMovementHelper.isBaritoneActive(client.player)) {
            return;
        }
        if (isMoveCorrectionActive()) {
            event.setVelocity(fixVelocity(event.getVelocity(), event.getMovementInput(), event.getSpeed()));
        }
    }

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        tick(event.getClient());
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (event.isSend()) {
            if (event.getPacket() instanceof ServerboundMovePlayerPacket movePacket) {
                onMovePacket(movePacket);
            }
            return;
        }
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket positionPacket) {
            onPlayerPosition(positionPacket);
        }
    }
}
