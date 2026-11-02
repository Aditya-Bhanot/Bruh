package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.util.Mth;
import net.minecraft.util.Tuple;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.events.types.EventPriority;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConfig;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.attack.StrikeManager;
import releon.ru.api.module.impl.combat.aura.attack.StrikerConstructor;
import releon.ru.api.module.impl.combat.aura.context.AutoRegressionContext;
import releon.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.rotations.AimAssistAngle;
import releon.ru.api.module.impl.combat.aura.rotations.FunTimeSnapAngle;
import releon.ru.api.module.impl.combat.aura.rotations.LegitAngle;
import releon.ru.api.module.impl.combat.aura.rotations.MatrixAngle;
import releon.ru.api.module.impl.combat.aura.rotations.SPAngle;
import releon.ru.api.module.impl.combat.aura.rotations.SnapAngle;
import releon.ru.api.module.impl.combat.aura.rotations.SpookyTimeNewAngle;
import releon.ru.api.module.impl.combat.aura.target.MultiPoint;
import releon.ru.api.module.impl.combat.aura.target.TargetFinder;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.module.impl.movement.ElytraTarget;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.player.BaritoneMovementHelper;

public class AuraModule extends Module {
    private static AuraModule instance;
    public static LivingEntity target;

    private final TargetFinder targetSelector = new TargetFinder();
    private final MultiPoint pointFinder = new MultiPoint();
    private final StrikerConstructor attackPerpetrator = new StrikerConstructor();

    public final ModeSetting mode = register(new ModeSetting(
            "Mode",
            "Aura rotation mode.",
            "Matrix",
            "Matrix", "Snap", "FunTime", "SpookyTime", "SPAngle", "Legit", "AimAssist"
    ));

    public final NumberSetting timeOnTick = register(new NumberSetting("Snap Hold", "How long Snap keeps target after hit.", 50.0, 1.0, 300.0, 1.0));
    public final NumberSetting speedValue = register(new NumberSetting("Snap Speed", "Snap enter/return speed.", 50.0, 15.0, 180.0, 1.0));
    public final NumberSetting legitSpeed = register(new NumberSetting("Legit Speed", "Legit aim speed.", 15.0, 5.0, 100.0, 5.0));
    public final NumberSetting aimAssistSpeed = register(new NumberSetting("AimAssist Speed", "AimAssist rotation speed.", 14.0, 0.5, 20.0, 0.1));
    public final NumberSetting aimAssistSmoothness = register(new NumberSetting("AimAssist Smoothness", "AimAssist rotation smoothing.", 6.0, 1.0, 10.0, 0.1));
    public final NumberSetting attackRange = register(new NumberSetting("Range", "Maximum attack distance.", 3.0, 2.5, 6.0, 0.1));
    public final NumberSetting lookRange = register(new NumberSetting("Look Range", "Extra target search distance.", 1.5, 0.5, 10.0, 0.1));
    public final MultiModeSetting targetType = register(new MultiModeSetting("Targets", "Target type filter.", new String[]{"Players", "Friends", "Mobs", "Animals", "Invisible", "Armor Stands"}, "Players"));
    public final ModeSetting targetPriority = register(new ModeSetting("Priority", "Target sort priority.", "Distance", "Distance", "Health", "Armor", "FOV", "Combined"));
    public final MultiModeSetting options = register(new MultiModeSetting("Options", "Aura options.", new String[]{"Pause While Using", "Ignore Walls", "Release Shield", "Sync TPS"}, "Pause While Using"));
    public final ModeSetting moveFix = register(new ModeSetting("Move Fix", "Movement correction mode.", "Focused", "Focused", "Free", "Chase", "Target"));
    public final BooleanSetting onlyCriticals = register(new BooleanSetting("Only Crits", "Attack only with critical hits.", true));
    public final BooleanSetting smartCriticals = register(new BooleanSetting("Smart Crits", "Only force smart criticals while jumping.", false));
    public final BooleanSetting legitPitchCorrecting = register(new BooleanSetting("Legit Pitch", "Aim pitch in Legit mode.", false));

    private LivingEntity lastTarget;
    private long activationTimeMs;
    private float tps = 20.0F;
    private float adjustTicks;
    private long timestamp;
    private int reducedHitboxAttackCounter;
    private boolean wasForwardPressed;
    private boolean wasBackPressed;
    private boolean wasLeftPressed;
    private boolean wasRightPressed;
    private boolean wasJumpPressed;
    private boolean keysOverridden;
    private boolean inventoryOpened;
    private boolean packetsHeld;

    public AuraModule() {
        super("Aura", "Automatically attacks nearby targets.", ModuleCategory.COMBAT);
        timeOnTick.visibleWhen(() -> mode.is("Snap"));
        speedValue.visibleWhen(() -> mode.is("Snap"));
        legitSpeed.visibleWhen(() -> mode.is("Legit"));
        aimAssistSpeed.visibleWhen(() -> mode.is("AimAssist"));
        aimAssistSmoothness.visibleWhen(() -> mode.is("AimAssist"));
        legitPitchCorrecting.visibleWhen(() -> mode.is("Legit"));
        smartCriticals.visibleWhen(onlyCriticals::getValue);
        instance = this;
    }

    public static AuraModule getInstance() {
        return instance;
    }

    public static float activeTps() {
        return instance == null ? 20.0F : instance.tps;
    }

    @Override
    protected void onEnable() {
        activationTimeMs = System.currentTimeMillis();
        timestamp = System.nanoTime();
        tps = 20.0F;
        adjustTicks = 0.0F;
        reducedHitboxAttackCounter = 0;
        AutoRegressionContext context = AutoRegressionContext.getInstance();
        context.setCdMinecraft(1000L / 12L);
        context.hitContentClear();
    }

    @Override
    protected void onDisable() {
        activationTimeMs = 0L;
        timestamp = 0L;
        tps = 20.0F;
        adjustTicks = 0.0F;
        reducedHitboxAttackCounter = 0;
        targetSelector.releaseTarget();
        target = null;
        lastTarget = null;
        attackPerpetrator.getAttackHandler().resetPendingState();
        AutoRegressionContext.getInstance().hitContentClear();
        lastTarget = null;
        finishRotationOnRelease(Minecraft.getInstance());
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            targetSelector.releaseTarget();
            target = null;
            AngleConnection.INSTANCE.restoreVanillaLook();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    private void onPreTick(TickEvent.Pre event) {
        Minecraft client = event.getClient();
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        attackPerpetrator.tick();
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        attackPerpetrator.onPacket(event);
        if (!isSyncWithTpsEnabled() || !event.isReceive() || !(event.getPacket() instanceof ClientboundSetTimePacket)) {
            return;
        }
        long currentTime = System.nanoTime();
        if (timestamp == 0L) {
            timestamp = currentTime;
            return;
        }
        long delay = currentTime - timestamp;
        if (delay <= 0L) {
            timestamp = currentTime;
            return;
        }
        float boundedTPS = Mth.clamp(20.0F * (1e9f / delay), 0.0F, 20.0F);
        tps = (float) limitDecimals(boundedTPS, 2);
        adjustTicks = boundedTPS - 20.0F;
        timestamp = currentTime;
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || client.player == null || client.level == null || event.getInput() == null) {
            return;
        }
        if (BaritoneMovementHelper.isBaritoneActive(client.player)) {
            return;
        }

        Input input = event.getInput();
        if (target == null || !target.isAlive()) {
            return;
        }

        boolean inWater = client.player.isInWater() || client.player.isUnderWater();
        StrikeManager attackHandler = getAttackHandlerRaw();
        attackHandler.syncShieldState();

        boolean targetFix = moveFix.is("Target");
        boolean chaseFix = moveFix.is("Chase");
        boolean focusedFixSelected = moveFix.is("Focused");

        StrikerConstructor.AttackPerpetratorConfigurable config = getConfig();
        float targetDistance = client.player.distanceTo(target);
        boolean shouldPrepareAttack = attackHandler.canAttack(config, 1)
                && targetDistance <= attackDistance()
                && !inWater;

        if (focusedFixSelected && shouldPrepareAttack) {
            event.setDirectionalLow(false, false, false, false);
        }
        if (!focusedFixSelected && shouldPrepareAttack) {
            event.setSprinting(false);
            client.player.setSprinting(false);
        }
        if (target != null
                && attackHandler.canAttack(config, 1)
                && client.player.distanceTo(target) <= attackDistance()
                && !client.player.isSwimming()) {
            event.setDirectionalLow(false, false, false, false);
        }
        if (!focusedFixSelected && !targetFix && !chaseFix) {
            Input patchedInput = event.getInput();
            event.setInput(new Input(
                    input.forward(),
                    input.backward(),
                    input.left(),
                    input.right(),
                    input.jump(),
                    input.shift(),
                    patchedInput.sprint()
            ));
        }
        if (focusedFixSelected && shouldPrepareAttack) {
            event.setDirectionalLow(false, false, false, false);
        }

        boolean w = client.options.keyUp.isDown();
        boolean s = client.options.keyDown.isDown();
        boolean a = client.options.keyLeft.isDown();
        boolean d = client.options.keyRight.isDown();

        if (inWater) {
            return;
        }

        if (targetFix && (
                client.options.keyUp.isDown() ||
                        client.options.keyDown.isDown() ||
                        client.options.keyLeft.isDown() ||
                        client.options.keyRight.isDown()
        )) {

            moveToward(event, client.player.position(), target.position(), AngleConnection.INSTANCE.getRotation().getYaw());
            return;
        }
        if (!chaseFix) {
            return;
        }
        if (!w && !s && !a && !d) {
            return;
        }

        Vec3 playerPos = client.player.position();
        AABB targetBox = target.getBoundingBox();
        Vec3 center = targetBox.getCenter();
        float targetYaw = target.getYRot();
        double rad = Math.toRadians(targetYaw);
        Vec3 forwardDir = new Vec3(-Math.sin(rad), 0, Math.cos(rad)).normalize();
        Vec3 rightDir = new Vec3(-forwardDir.z, 0, forwardDir.x).normalize();
        Vec3 leftDir = rightDir.scale(-1);
        double offset = target.getBbWidth() / 2.0 + 0.1;
        Vec3 offsetVec = Vec3.ZERO;

        if (w) {
            offsetVec = offsetVec.add(forwardDir);
        }
        if (s) {
            offsetVec = offsetVec.add(forwardDir.scale(-1.0));
        }
        if (a) {
            offsetVec = offsetVec.add(leftDir);
        }
        if (d) {
            offsetVec = offsetVec.add(rightDir);
        }

        Vec3 moveTargetVec = center;
        if (offsetVec.lengthSqr() > 0) {
            moveTargetVec = center.add(offsetVec.normalize().scale(offset));
        }
        moveToward(event, playerPos, moveTargetVec, AngleConnection.INSTANCE.getRotation().getYaw());
    }

    @SubscribeEvent
    private void onRotationUpdate(RotationUpdateEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || client.player == null || client.level == null) {
            return;
        }
        if (BaritoneMovementHelper.isBaritoneActive(client.player)) {
            targetSelector.releaseTarget();
            target = null;
            lastTarget = null;
            return;
        }
        if (event.getPhase() == EventPhase.PRE) {
            target = updateTarget();
            if (target != null) {
                rotateToTarget(getConfig());
                lastTarget = target;
            } else if (lastTarget != null) {
                lastTarget = null;
                AngleConnection.INSTANCE.startReturning();
            }
            return;
        }
        if (event.getPhase() == EventPhase.POST && target != null) {
            performAuraAttack(getConfig());
        }
    }

    public float finalDistance() {
        ElytraTarget elytraTarget = ElytraTarget.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null
                && client.player.isFallFlying()
                && elytraTarget != null
                && elytraTarget.isEnabled()) {
            return elytraTarget.getRange();
        }
        if (mode.is("Legit")) {
            return attackRange.getFloat() + 0.4F;
        }
        return attackRange.getFloat() + lookRange.getFloat();
    }

    public float attackDistance() {
        return attackRange.getFloat();
    }

    public StrikerConstructor.AttackPerpetratorConfigurable getConfig() {
        Minecraft client = Minecraft.getInstance();
        if (target == null || client.player == null) {
            AABB fallbackBox = client.player != null ? client.player.getBoundingBox() : new AABB(0, 0, 0, 0, 0, 0);
            return new StrikerConstructor.AttackPerpetratorConfigurable(
                    client.player,
                    MathAngle.cameraAngle(),
                    attackDistance(),
                    options.getValue(),
                    mode,
                    fallbackBox,
                    onlyCriticals.getValue()
            );
        }

        Tuple<Vec3, AABB> point = pointFinder.computeVector(target, attackDistance(), AngleConnection.INSTANCE.getRotation(), getSmoothMode().randomValue(), options.isSelected("Ignore Walls"));
        Vec3 computedPoint = point.getA();
        AABB box = adjustAttackBox(point.getB());

        if (client.player.isFallFlying() && target.isFallFlying()) {
            Vec3 targetVelocity = target.getDeltaMovement();
            double targetSpeed = targetVelocity.horizontalDistance();
            float leadTicks = 0.0F;
            ElytraTarget elytraTarget = ElytraTarget.getInstance();
            if (ElytraTarget.shouldElytraTarget && elytraTarget != null && elytraTarget.isEnabled()) {
                leadTicks = elytraTarget.getForward();
            }
            if (targetSpeed > 0.35D) {
                Vec3 predictedPos = target.position().add(targetVelocity.scale(leadTicks));
                computedPoint = predictedPos.add(0.0D, target.getBbHeight() / 2.0D, 0.0D);
                box = adjustAttackBox(new AABB(
                        predictedPos.x - target.getBbWidth() / 2.0D,
                        predictedPos.y,
                        predictedPos.z - target.getBbWidth() / 2.0D,
                        predictedPos.x + target.getBbWidth() / 2.0D,
                        predictedPos.y + target.getBbHeight(),
                        predictedPos.z + target.getBbWidth() / 2.0D
                ));
            }
        }

        Angle angle = MathAngle.fromVec3d(computedPoint.subtract(client.player.getEyePosition()));

        return new StrikerConstructor.AttackPerpetratorConfigurable(target, angle, attackDistance(), options.getValue(), mode, box, onlyCriticals.getValue());
    }

    public AngleConfig getRotationConfig() {
        if (mode.is("Legit")) {
            return new AngleConfig(getSmoothMode(), true, false);
        }
        return new AngleConfig(getSmoothMode(), true, moveFix.is("Free"));
    }

    public RotateConstructor getSmoothMode() {
        Minecraft client = Minecraft.getInstance();
        ElytraTarget elytraTarget = ElytraTarget.getInstance();
        if (client.player != null && client.player.isFallFlying() && elytraTarget != null && elytraTarget.isEnabled()) {
            return new LinearConstructor();
        }
        return switch (mode.getValue()) {
            case "Matrix" -> new MatrixAngle();
            case "Snap" -> new SnapAngle();
            case "FunTime" -> FunTimeSnapAngle.INSTANCE;
            case "SpookyTime" -> SpookyTimeNewAngle.INSTANCE;
            case "SPAngle" -> SPAngle.INSTANCE;
            case "Legit" -> new LegitAngle();
            case "AimAssist" -> AimAssistAngle.INSTANCE;
            default -> new LinearConstructor();
        };
    }

    private void applyTargetOrChaseCorrection(InputEvent event, Minecraft client) {
        LivingEntity currentTarget = target;
        if (currentTarget == null || !currentTarget.isAlive() || client.player.isInWater() || client.player.isUnderWater()) {
            return;
        }

        if (!moveFix.is("Target") && !moveFix.is("Chase") && !moveFix.is("Focused")) {
            return;
        }

        StrikeManager attackHandler = getAttackHandlerRaw();
        if (attackHandler == null) {
            return;
        }
        boolean attackReady = attackHandler.canAttack(getConfig(), 1) && client.player.distanceTo(currentTarget) <= attackDistance();
        if (moveFix.is("Focused")) {
            if (attackReady) {
                event.setDirectionalLow(false, false, false, false);
            }
            return;
        }

        if (moveFix.is("Target")) {
            moveToward(event, client.player.position(), currentTarget.position(), AngleConnection.INSTANCE.getMoveRotation().getYaw());
            return;
        }

        var input = event.getInput();
        if (!input.forward() && !input.backward() && !input.left() && !input.right()) {
            return;
        }

        Vec3 playerPos = client.player.position();
        Vec3 center = currentTarget.getBoundingBox().getCenter();
        float targetYaw = currentTarget.getYRot();
        double rad = Math.toRadians(targetYaw);
        Vec3 forwardDir = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad)).normalize();
        Vec3 rightDir = new Vec3(-forwardDir.z, 0.0, forwardDir.x).normalize();
        Vec3 offsetVec = Vec3.ZERO;

        if (input.forward()) {
            offsetVec = offsetVec.add(forwardDir);
        }
        if (input.backward()) {
            offsetVec = offsetVec.add(forwardDir.scale(-1.0));
        }
        if (input.left()) {
            offsetVec = offsetVec.add(rightDir.scale(-1.0));
        }
        if (input.right()) {
            offsetVec = offsetVec.add(rightDir);
        }

        double offset = currentTarget.getBbWidth() / 2.0 + 0.1;
        Vec3 moveTarget = offsetVec.lengthSqr() > 0.0 ? center.add(offsetVec.normalize().scale(offset)) : center;
        moveToward(event, playerPos, moveTarget, AngleConnection.INSTANCE.getMoveRotation().getYaw());
    }

    private void moveToward(InputEvent event, Vec3 playerPos, Vec3 targetPos, float yaw) {
        Vec3 targetFlat = new Vec3(targetPos.x, playerPos.y, targetPos.z);
        Vec3 dir = targetFlat.subtract(playerPos);
        if (dir.lengthSqr() < 1.0E-7) {
            event.setDirectionalLow(false, false, false, false);
            return;
        }

        float moveAngle = (float) Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0F;
        float angleDiff = Mth.wrapDegrees(moveAngle - yaw);
        boolean forward = false;
        boolean back = false;
        boolean left = false;
        boolean right = false;

        if (angleDiff >= -22.5F && angleDiff < 22.5F) {
            forward = true;
        } else if (angleDiff >= 22.5F && angleDiff < 67.5F) {
            forward = true;
            right = true;
        } else if (angleDiff >= 67.5F && angleDiff < 112.5F) {
            right = true;
        } else if (angleDiff >= 112.5F && angleDiff < 157.5F) {
            back = true;
            right = true;
        } else if (angleDiff >= -67.5F && angleDiff < -22.5F) {
            forward = true;
            left = true;
        } else if (angleDiff >= -112.5F && angleDiff < -67.5F) {
            left = true;
        } else if (angleDiff >= -157.5F && angleDiff < -112.5F) {
            back = true;
            left = true;
        } else {
            back = true;
        }

        event.setDirectionalLow(forward, back, left, right);
    }

    public boolean shouldCancelInteractItem(InteractionHand hand) {
        if (shouldCancelUseInteractions()) {
            return true;
        }
        StrikeManager attackHandler = getAttackHandler();
        return attackHandler != null && attackHandler.shouldCancelShieldUse(hand);
    }

    public boolean shouldSuppressAirUsePacket(InteractionHand hand) {
        return shouldCancelInteractItem(hand);
    }

    public boolean shouldCancelInteractBlock() {
        if (shouldCancelUseInteractions()) {
            return true;
        }
        StrikeManager attackHandler = getAttackHandler();
        return attackHandler != null && attackHandler.shouldCancelUseItemOn();
    }

    public boolean shouldSuppressBlockUsePacket() {
        return shouldCancelInteractBlock();
    }

    public boolean shouldCancelEntityInteraction() {
        if (shouldCancelUseInteractions()) {
            return true;
        }
        StrikeManager attackHandler = getAttackHandler();
        return attackHandler != null && attackHandler.shouldCancelEntityInteraction();
    }

    public boolean shouldSuppressEntityUsePacket() {
        return shouldCancelEntityInteraction();
    }

    public boolean shouldBlockUseInteractions() {
        return false;
    }

    public boolean shouldPauseForUse() {
        return false;
    }

    public boolean shouldCancelUseInteractions() {
        return false;
    }

    public boolean isSyncWithTpsEnabled() {
        return options.isSelected("Sync TPS");
    }

    public StrikeManager getAttackHandler() {
        return isEnabled() ? attackPerpetrator.getAttackHandler() : null;
    }

    public StrikeManager getAttackHandlerRaw() {
        return attackPerpetrator.getAttackHandler();
    }

    public boolean hasQueuedAttack() {
        return false;
    }

    public void flushQueuedAttack() {
        // RELEON-Modern performs Aura attacks during RotationUpdateEvent.POST.
    }

    public ModeSetting getMode() {
        return mode;
    }

    public NumberSetting getTimeOnTick() {
        return timeOnTick;
    }

    public NumberSetting getSpeedValue() {
        return speedValue;
    }

    public NumberSetting getLegitSpeed() {
        return legitSpeed;
    }

    public NumberSetting getAimAssistSpeed() {
        return aimAssistSpeed;
    }

    public NumberSetting getAimAssistSmoothness() {
        return aimAssistSmoothness;
    }

    public NumberSetting getRange() {
        return attackRange;
    }

    public NumberSetting getLookRange() {
        return lookRange;
    }

    public MultiModeSetting getTargets() {
        return targetType;
    }

    public ModeSetting getTargetPriority() {
        return targetPriority;
    }

    public MultiModeSetting getOptions() {
        return options;
    }

    public ModeSetting getMoveFix() {
        return moveFix;
    }

    public BooleanSetting getOnlyCrits() {
        return onlyCriticals;
    }

    public BooleanSetting getOnlyCriticals() {
        return onlyCriticals;
    }

    public BooleanSetting getSmartCrits() {
        return smartCriticals;
    }

    public BooleanSetting getSmartCriticals() {
        return smartCriticals;
    }

    public boolean isLegitMode() {
        return mode.is("Legit");
    }

    public boolean isLegitPitchCorrecting() {
        return legitPitchCorrecting.getValue();
    }

    public long getActivationTimeMs() {
        return activationTimeMs;
    }

    public float getTps() {
        return tps;
    }

    public float getAdjustTicks() {
        return adjustTicks;
    }

    private LivingEntity updateTarget() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            targetSelector.releaseTarget();
            return null;
        }
        TargetFinder.EntityFilter filter = new TargetFinder.EntityFilter(targetType.getValue());
        targetSelector.searchTargets(client.level.entitiesForRendering(), finalDistance(), 360.0F, options.isSelected("Ignore Walls"), filter::isValid);
        targetSelector.validateTarget(filter::isValid);
        return targetSelector.getCurrentTarget();
    }
    private void finishRotationOnRelease(Minecraft client) {
        AngleConnection controller = AngleConnection.INSTANCE;

        if (mode.is("Matrix") && client != null && client.player != null && controller.getCurrentAngle() != null) {
            controller.applyPlayerRotation(client);
            controller.restoreVanillaLook();
            return;
        }

        controller.startReturning();
    }
    private void rotateToTarget(StrikerConstructor.AttackPerpetratorConfigurable config) {
        AngleConnection controller = AngleConnection.INSTANCE;
        Angle.VecRotation rotation = new Angle.VecRotation(config.getAngle(), config.getAngle().toVector());
        controller.rotateTo(rotation, target, 1, getRotationConfig(), TaskPriority.HIGH_IMPORTANCE_1, this);
    }

    private void performAuraAttack(StrikerConstructor.AttackPerpetratorConfigurable config) {
        StrikeManager attackHandler = attackPerpetrator.getAttackHandler();
        int before = attackHandler.getCount();
        attackPerpetrator.performAttack(config);
        int performed = attackHandler.getCount() - before;
        if (performed > 0) {
            reducedHitboxAttackCounter += performed;
        }
    }

    private AABB adjustAttackBox(AABB box) {
        if (box == null || !shouldUseReducedHitbox()) {
            return box;
        }
        Vec3 center = box.getCenter();
        double halfX = Math.max(box.getXsize() * 0.4, 0.01);
        double halfZ = Math.max(box.getZsize() * 0.4, 0.01);
        return new AABB(center.x - halfX, box.minY, center.z - halfZ, center.x + halfX, box.maxY, center.z + halfZ);
    }

    private boolean shouldUseReducedHitbox() {
        return reducedHitboxAttackCounter % 4 < 2;
    }

    private double limitDecimals(double value, int decimalPlaces) {
        return Math.round(value * Math.pow(10, decimalPlaces)) / Math.pow(10, decimalPlaces);
    }
}
