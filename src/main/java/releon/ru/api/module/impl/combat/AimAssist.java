package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConfig;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.util.MathUtils;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.concurrent.ThreadLocalRandom;

public class AimAssist extends Module {
    private static AimAssist instance;

    private final ModeSetting modeSetting = register(new ModeSetting("Mode", "Aim assist rotation mode.", "Smooth", "Smooth", "Silent"));
    private final MultiModeSetting targetType = register(new MultiModeSetting("Targets", "Target type filter.",
            new String[]{"Players", "Naked Players", "Mobs", "Animals", "Friends", "Armor Stands"},
            "Players", "Mobs", "Animals"));
    private final NumberSetting distanceSetting = register(new NumberSetting("Distance", "Maximum target distance.", 6.0, 1.0, 6.0, 0.1));
    private final NumberSetting fovSetting = register(new NumberSetting("FOV", "Target capture field of view.", 360.0, 20.0, 360.0, 1.0));
    private final BooleanSetting drawFovRadiusSetting = register(new BooleanSetting("Draw FOV", "Draws aim assist FOV circle.", false));
    private final NumberSetting speedSetting = register(new NumberSetting("Speed", "Base aiming speed.", 4.0, 0.5, 10.0, 0.1));
    private final NumberSetting smoothnessSetting = register(new NumberSetting("Smoothness", "Aiming smoothing amount.", 6.0, 1.0, 10.0, 0.1));
    private final NumberSetting aimHeightSetting = register(new NumberSetting("Aim Height", "Aim point height on target.", 0.85, 0.0, 1.0, 0.01));
    private final ModeSetting prioritySetting = register(new ModeSetting("Priority", "Target priority.", "Distance", "Distance", "Health"));
    private final BooleanSetting onlyWithWeaponSetting = register(new BooleanSetting("Only Weapon", "Aim only while holding a weapon.", false));
    private final BooleanSetting onlyPlayersSetting = register(new BooleanSetting("Only Players", "Aim only at players.", false));
    private final BooleanSetting noAimInInventorySetting = register(new BooleanSetting("No Inventory Aim", "Do not aim while a container screen is open.", true));
    private final BooleanSetting hitInvisibleSetting = register(new BooleanSetting("Invisible", "Aim at invisible entities.", false));
    private final BooleanSetting onlyArmoredSetting = register(new BooleanSetting("Only Armored", "Aim only at armored players.", false));
    private final BooleanSetting ignoreNakedSetting = register(new BooleanSetting("Ignore Naked", "Ignore players without armor.", false));
    private final BooleanSetting onlyYawSetting = register(new BooleanSetting("Only Yaw", "Only change horizontal rotation.", false));
    private final BooleanSetting multipointSetting = register(new BooleanSetting("Multipoint", "Use dynamic aim points inside the hitbox.", true));
    private final BooleanSetting wallCheckSetting = register(new BooleanSetting("Wall Check", "Skip targets behind full blocks.", true));
    private final BooleanSetting disableOnWorldChangeSetting = register(new BooleanSetting("Disable On World", "Disable module after world change.", false));
    private final BooleanSetting microMovementsSetting = register(new BooleanSetting("Micro Movements", "Adds subtle aiming noise and profile variation.", true));

    private final SilentAimRotateConstructor silentRotateConstructor = new SilentAimRotateConstructor();

    private Entity target;
    private long targetAcquiredAtMs;
    private long lastFrameNs;
    private long lastRenderAimNs;
    private double previousYawStep;
    private double previousPitchStep;
    private double aimYawVelocity;
    private double aimPitchVelocity;
    private float prevSilentYawStep;
    private float prevSilentPitchStep;
    private boolean silentActive;
    private double yawWavePhase;
    private double pitchWavePhase;
    private double yawWaveSpeed;
    private double pitchWaveSpeed;
    private double speedMultiplier;
    private double heightNoise;
    private long heightNoiseUpdatedAtMs;
    private double currentPointX;
    private double currentPointY;
    private double currentPointZ;
    private double nextPointX;
    private double nextPointY;
    private double nextPointZ;
    private long nextPointChangeAtMs;
    private double pointLerpSpeed;
    private ResourceKey<Level> worldKey;

    public AimAssist() {
        super("Aim Assist", "Smoothly assists aiming at nearby targets.", ModuleCategory.COMBAT);
        ignoreNakedSetting.visibleWhen(() -> !onlyArmoredSetting.getValue());
        instance = this;
    }

    public static AimAssist getInstance() {
        return instance;
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        releaseSilentRotation();
        resetState();
    }

    @Override
    public void onTick(Minecraft client) {
        if ("Silent".equalsIgnoreCase(modeSetting.getValue())) {
            return;
        }
        long now = System.nanoTime();
        if (lastRenderAimNs == 0L || now - lastRenderAimNs > 75_000_000L) {
            updateSmoothAim(client);
        }
    }

    @SubscribeEvent
    private void onDraw(DrawEvent event) {
        if (event.getLayer() == DrawEvent.Layer.GAME && !"Silent".equalsIgnoreCase(modeSetting.getValue())) {
            releaseSilentRotation();
            lastRenderAimNs = System.nanoTime();
            updateSmoothAim(Minecraft.getInstance());
        }
        drawFovRadius();
    }

    @SubscribeEvent
    private void onRotationUpdate(RotationUpdateEvent event) {
        if (event.getPhase() != EventPhase.PRE || !"Silent".equalsIgnoreCase(modeSetting.getValue())) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            releaseSilentRotation();
            resetState();
            return;
        }
        if ((onlyWithWeaponSetting.getValue() && !isHoldingWeapon(client))
                || (noAimInInventorySetting.getValue() && client.screen instanceof AbstractContainerScreen<?>)) {
            releaseSilentRotation();
            resetMotionState();
            return;
        }

        Entity selectedTarget = findTarget(client);
        if (!(selectedTarget instanceof LivingEntity livingTarget)) {
            releaseSilentRotation();
            resetMotionState();
            return;
        }

        target = livingTarget;
        double[] rotations = calculateAccurateRotations(client, livingTarget);
        float desiredYaw = (float) rotations[0];
        float desiredPitch = onlyYawSetting.getValue() ? client.player.getXRot() : (float) rotations[1];
        Angle desiredAngle = new Angle(desiredYaw, desiredPitch).adjustSensitivity();

        silentActive = true;
        AngleConnection.INSTANCE.rotateTo(
                new Angle.VecRotation(desiredAngle, desiredAngle.toVector()),
                livingTarget,
                2,
                new AngleConfig(silentRotateConstructor, true, true),
                TaskPriority.HIGH_IMPORTANCE_2,
                this
        );
    }

    private void releaseSilentRotation() {
        if (silentActive) {
            AngleConnection.INSTANCE.startReturning();
            silentActive = false;
        }
        prevSilentYawStep = 0.0F;
        prevSilentPitchStep = 0.0F;
    }

    private void updateSmoothAim(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            resetState();
            return;
        }

        ResourceKey<Level> currentWorldKey = client.level.dimension();
        if (worldKey == null) {
            worldKey = currentWorldKey;
        } else if (!worldKey.equals(currentWorldKey)) {
            if (disableOnWorldChangeSetting.getValue()) {
                setEnabled(false);
                return;
            }
            resetMotionState();
            worldKey = currentWorldKey;
        }

        if ((onlyWithWeaponSetting.getValue() && !isHoldingWeapon(client))
                || (noAimInInventorySetting.getValue() && client.screen instanceof AbstractContainerScreen<?>)) {
            resetMotionState();
            return;
        }

        long frameNs = System.nanoTime();
        double frameScale = lastFrameNs > 0L ? (frameNs - lastFrameNs) / 16_666_666.7D : 1.0D;
        frameScale = Mth.clamp(frameScale, 0.05D, 2.5D);
        lastFrameNs = frameNs;

        Entity selectedTarget = findTarget(client);
        if (selectedTarget == null) {
            if (target != null) {
                resetMotionState();
            }
            return;
        }

        if (selectedTarget != target) {
            target = selectedTarget;
            targetAcquiredAtMs = System.currentTimeMillis();
            randomizeProfile();
        }

        rotateSmoothToTarget(client, selectedTarget, frameScale);
    }

    private void rotateSmoothToTarget(Minecraft client, Entity selectedTarget, double frameScale) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean humanized = microMovementsSetting.getValue();
        long now = System.currentTimeMillis();

        if (humanized && now - heightNoiseUpdatedAtMs > random.nextLong(220L, 500L)) {
            heightNoise = Mth.clamp(heightNoise + random.nextDouble(-0.004D, 0.004D), -0.015D, 0.015D);
            heightNoiseUpdatedAtMs = now;
        }

        if (multipointSetting.getValue() && now >= nextPointChangeAtMs) {
            randomizePoint(random);
        }

        if (multipointSetting.getValue()) {
            double lerpSpeed = pointLerpSpeed + (humanized ? random.nextDouble(-0.001D, 0.001D) : 0.0D);
            currentPointX += (nextPointX - currentPointX) * lerpSpeed;
            currentPointY += (nextPointY - currentPointY) * lerpSpeed;
            currentPointZ += (nextPointZ - currentPointZ) * lerpSpeed;
        }

        double[] rotations = calculateAccurateRotations(client, selectedTarget);
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        boolean yawOnly = onlyYawSetting.getValue();
        double yawDelta = Mth.wrapDegrees(rotations[0] - currentYaw);
        double pitchDelta = rotations[1] - currentPitch;
        double effectivePitchDelta = yawOnly ? 0.0D : pitchDelta;

        double deltaLength = yawOnly
                ? Math.abs(yawDelta)
                : Math.hypot(yawDelta, effectivePitchDelta);
        if (deltaLength < 0.08D) {
            aimYawVelocity *= 0.30D;
            aimPitchVelocity *= 0.30D;
            return;
        }

        double speed = speedSetting.getValue() * (humanized ? speedMultiplier : 1.0D);
        double smoothness = Mth.clamp((smoothnessSetting.getValue() - 1.0D) / 9.0D, 0.0D, 1.0D);
        double distanceResponse = Mth.clamp(deltaLength / 26.0D, 0.12D, 1.0D);
        double hermite = distanceResponse * distanceResponse * (3.0D - 2.0D * distanceResponse);
        double warmup = Mth.clamp((now - targetAcquiredAtMs) / 120.0D, 0.35D, 1.0D);

        double stiffness = (0.022D + speed * 0.0052D) * warmup * (0.45D + 0.55D * hermite) * (1.0D - smoothness * 0.25D);
        double damping = (humanized ? 0.72D : 0.76D) - smoothness * 0.04D;
        double maxYawSpeed = (0.85D + speed * 0.72D) * (0.50D + 0.50D * hermite) * (1.0D - smoothness * 0.08D);
        double maxPitchSpeed = (0.60D + speed * 0.54D) * (0.50D + 0.50D * hermite) * (1.0D - smoothness * 0.08D);

        aimYawVelocity = (aimYawVelocity + yawDelta * stiffness * frameScale) * damping;
        aimPitchVelocity = (aimPitchVelocity + effectivePitchDelta * stiffness * frameScale) * damping;

        aimYawVelocity = Mth.clamp(aimYawVelocity, -maxYawSpeed, maxYawSpeed);
        aimPitchVelocity = Mth.clamp(aimPitchVelocity, -maxPitchSpeed, maxPitchSpeed);

        if (Math.abs(yawDelta) < 0.9D) {
            aimYawVelocity *= 0.50D;
        }
        if (Math.abs(effectivePitchDelta) < 0.9D) {
            aimPitchVelocity *= 0.50D;
        }

        double yawStep = aimYawVelocity;
        double pitchStep = aimPitchVelocity;

        if (humanized && deltaLength > 1.5D) {
            yawWavePhase += yawWaveSpeed * 0.35D;
            pitchWavePhase += pitchWaveSpeed * 0.35D;
            double microScale = (1.0D - smoothness * 0.85D) * 0.65D;
            yawStep += Math.sin(yawWavePhase) * 0.008D * microScale;
            pitchStep += Math.cos(pitchWavePhase) * 0.005D * microScale;
        }

        double stepResponse = Mth.clamp(0.70D - smoothness * 0.22D + distanceResponse * 0.20D, 0.25D, 0.90D);
        yawStep = smoothStep(previousYawStep, yawStep, stepResponse);
        pitchStep = smoothStep(previousPitchStep, pitchStep, stepResponse);

        yawStep = clampToRemaining(yawStep, yawDelta);
        pitchStep = clampToRemaining(pitchStep, effectivePitchDelta);

        previousYawStep = yawStep;
        previousPitchStep = pitchStep;

        if (Math.abs(yawStep) < 0.002D && Math.abs(pitchStep) < 0.002D) {
            return;
        }

        double gcd = MathUtils.computeGcd();
        float nextYaw = currentYaw + (float) yawStep;
        float nextPitch = Mth.clamp(currentPitch + (float) pitchStep, -89.5F, 89.5F);
        if (gcd > 0.0D) {
            nextYaw = currentYaw + Math.round((nextYaw - currentYaw) / (float) gcd) * (float) gcd;
            nextPitch = Mth.clamp(currentPitch + Math.round((nextPitch - currentPitch) / (float) gcd) * (float) gcd, -89.5F, 89.5F);
        }

        client.player.setYRot(nextYaw);
        client.player.setXRot(nextPitch);
    }

    private Entity findTarget(Minecraft client) {
        if (target instanceof LivingEntity livingEntity && isStillValidLockedTarget(client, target, livingEntity)) {
            return target;
        }

        double maxDistance = distanceSetting.getValue();
        double halfFov = fovSetting.getValue() / 2.0D;
        boolean healthPriority = prioritySetting.isSelected("Health");
        Entity bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 lookVec = client.player.getLookAngle();

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity livingEntity) || !isValidTarget(entity, livingEntity)) {
                continue;
            }

            double distance = eyePos.distanceTo(clampToBox(eyePos, entity.getBoundingBox()));
            if (distance > maxDistance) {
                continue;
            }

            if (wallCheckSetting.getValue() && isBlockedByWall(client, eyePos, getAimPoint(entity))) {
                continue;
            }

            double angleTo = getAngleTo(eyePos, lookVec, entity);
            if (angleTo > halfFov) {
                continue;
            }

            double score = healthPriority ? livingEntity.getHealth() : (distance * 0.75D + angleTo * 0.05D);
            if (score < bestScore) {
                bestScore = score;
                bestTarget = entity;
            }
        }

        return bestTarget;
    }

    private boolean isStillValidLockedTarget(Minecraft client, Entity entity, LivingEntity livingEntity) {
        if (!entity.isAlive() || !isValidTarget(entity, livingEntity)) {
            return false;
        }

        Vec3 eyePos = client.player.getEyePosition(1.0F);
        double distance = eyePos.distanceTo(clampToBox(eyePos, entity.getBoundingBox()));
        if (distance > distanceSetting.getValue()) {
            return false;
        }

        Vec3 lookVec = client.player.getLookAngle();
        if (getAngleTo(eyePos, lookVec, entity) > fovSetting.getValue() / 2.0D) {
            return false;
        }

        return !wallCheckSetting.getValue() || !isBlockedByWall(client, eyePos, getAimPoint(entity));
    }

    private boolean isValidTarget(Entity entity, LivingEntity livingEntity) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || entity == client.player || !entity.isAlive() || livingEntity.getHealth() <= 0.0F) {
            return false;
        }
        if (onlyPlayersSetting.getValue() && !(entity instanceof Player)) {
            return false;
        }
        if (!hitInvisibleSetting.getValue() && livingEntity.isInvisible()) {
            return false;
        }
        if (entity instanceof Player player) {
            if (AntiBot.shouldIgnore(player)) {
                return false;
            }
            boolean hasArmor = hasArmor(livingEntity);
            if (onlyArmoredSetting.getValue() && !hasArmor) {
                return false;
            }
            if (ignoreNakedSetting.getValue() && !hasArmor) {
                return false;
            }
            if (FriendUtils.isFriend(player.getName().getString())) {
                return targetType.isSelected("Friends");
            }
            return targetType.isSelected("Players") || (!hasArmor && targetType.isSelected("Naked Players"));
        }
        if (entity instanceof Animal) {
            return targetType.isSelected("Animals");
        }
        if (entity instanceof Mob) {
            return targetType.isSelected("Mobs");
        }
        if (entity instanceof ArmorStand) {
            return targetType.isSelected("Armor Stands");
        }
        return false;
    }

    private Vec3 getAimPoint(Entity entity) {
        return entity.position().add(0.0D, entity.getBbHeight() * aimHeightSetting.getValue(), 0.0D);
    }

    private double getAngleTo(Vec3 eyePos, Vec3 lookVec, Entity entity) {
        Vec3 targetVec = getAimPoint(entity).subtract(eyePos);
        double length = targetVec.length();
        if (length < 0.001D) {
            return 0.0D;
        }

        targetVec = targetVec.scale(1.0D / length);
        double dot = lookVec.x * targetVec.x + lookVec.y * targetVec.y + lookVec.z * targetVec.z;
        return Math.toDegrees(Math.acos(Mth.clamp(dot, -1.0D, 1.0D)));
    }

    /**
     * Velocity-Lead & Clamped Hitbox Aim Calculation:
     * Predicts sub-tick target movement and blends between the closest point on the contracted
     * hitbox and the configured Aim Height center for superior accuracy.
     */
    private double[] calculateAccurateRotations(Minecraft client, Entity entity) {
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 targetVel = entity.getDeltaMovement();
        AABB ledBox = entity.getBoundingBox().move(targetVel.x * 0.55D, targetVel.y * 0.25D, targetVel.z * 0.55D);

        boolean humanized = microMovementsSetting.getValue();
        double height = aimHeightSetting.getValue();
        double adjustedHeight = Mth.clamp(humanized ? height + heightNoise : height, 0.10D, 0.92D);

        double offsetX = 0.0D;
        double offsetY = 0.0D;
        double offsetZ = 0.0D;

        if (multipointSetting.getValue()) {
            double horizontalRadius = entity.getBbWidth() * 0.28D;
            offsetX = currentPointX * horizontalRadius;
            offsetY = currentPointY * 0.5D;
            offsetZ = currentPointZ * horizontalRadius;
        }

        Vec3 centerAtHeight = new Vec3(
                (ledBox.minX + ledBox.maxX) * 0.5D + offsetX,
                Mth.lerp(Mth.clamp(adjustedHeight + offsetY, 0.08D, 0.94D), ledBox.minY, ledBox.maxY),
                (ledBox.minZ + ledBox.maxZ) * 0.5D + offsetZ
        );

        AABB coreBox = ledBox.deflate(ledBox.getXsize() * 0.20D, ledBox.getYsize() * 0.15D, ledBox.getZsize() * 0.20D);
        Vec3 closestOnCore = clampToBox(eyePos, coreBox);
        Vec3 targetPoint = closestOnCore.lerp(centerAtHeight, 0.65D);

        double diffX = targetPoint.x - eyePos.x;
        double diffY = targetPoint.y - eyePos.y;
        double diffZ = targetPoint.z - eyePos.z;
        double horizontal = Math.hypot(diffX, diffZ);
        double yaw = horizontal < 0.001D ? client.player.getYRot() : Math.toDegrees(Math.atan2(-diffX, diffZ));
        double distance = Math.sqrt(diffX * diffX + diffY * diffY + diffZ * diffZ);
        double pitch = distance < 0.001D
                ? client.player.getXRot()
                : -Math.toDegrees(Math.asin(Mth.clamp(diffY / distance, -1.0D, 1.0D)));

        return new double[]{yaw, pitch};
    }

    private Vec3 clampToBox(Vec3 point, AABB box) {
        return new Vec3(
                Mth.clamp(point.x, box.minX, box.maxX),
                Mth.clamp(point.y, box.minY, box.maxY),
                Mth.clamp(point.z, box.minZ, box.maxZ)
        );
    }

    private void drawFovRadius() {
        if (!drawFovRadiusSetting.getValue() || mc.player == null || mc.level == null || mc.screen != null || mc.getWindow() == null) {
            return;
        }

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        float radius = calculateFovRadius(width, height);
        if (radius < 1.0F) {
            return;
        }

        float diameter = radius * 2.0F;
        int color = target != null ? ColorUtil.rgba(127, 242, 255, 165) : ColorUtil.rgba(255, 255, 255, 95);
        Render2D.outline360(
                width * 0.5F - radius,
                height * 0.5F - radius,
                diameter,
                diameter,
                radius,
                1.2F,
                color,
                Render2D.outline360Range(0.0F, 360.0F, color)
        );
    }

    private float calculateFovRadius(int width, int height) {
        float maxRadius = Math.max(0.0F, Math.min(width, height) * 0.5F - 2.0F);
        if (maxRadius <= 0.0F) {
            return 0.0F;
        }

        double aimFov = fovSetting.getValue();
        double gameFov = Mth.clamp(mc.options.fov().get(), 30.0D, 170.0D);
        if (aimFov >= gameFov) {
            return maxRadius;
        }

        double aimHalf = Mth.clamp(aimFov * 0.5D, 0.1D, 89.9D);
        double gameHalf = Mth.clamp(gameFov * 0.5D, 1.0D, 89.9D);
        double radius = Math.tan(Math.toRadians(aimHalf)) / Math.tan(Math.toRadians(gameHalf)) * (Math.min(width, height) * 0.5D);
        return (float) Mth.clamp(radius, 0.0D, maxRadius);
    }

    private void resetState() {
        worldKey = null;
        resetMotionState();
    }

    private void resetMotionState() {
        target = null;
        targetAcquiredAtMs = 0L;
        lastFrameNs = 0L;
        lastRenderAimNs = 0L;
        previousYawStep = 0.0D;
        previousPitchStep = 0.0D;
        aimYawVelocity = 0.0D;
        aimPitchVelocity = 0.0D;
        currentPointX = 0.0D;
        currentPointY = 0.0D;
        currentPointZ = 0.0D;
        nextPointX = 0.0D;
        nextPointY = 0.0D;
        nextPointZ = 0.0D;
        nextPointChangeAtMs = 0L;
        randomizeProfile();
    }

    private void randomizeProfile() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        yawWavePhase = random.nextDouble(Math.PI * 2.0D);
        pitchWavePhase = random.nextDouble(Math.PI * 2.0D);
        yawWaveSpeed = random.nextDouble(0.08D, 0.22D);
        pitchWaveSpeed = random.nextDouble(0.06D, 0.18D);
        speedMultiplier = random.nextDouble(0.90D, 1.12D);
        heightNoise = random.nextDouble(-0.012D, 0.012D);
        heightNoiseUpdatedAtMs = System.currentTimeMillis();
        pointLerpSpeed = random.nextDouble(0.035D, 0.075D);
        randomizePoint(random);
    }

    private void randomizePoint(ThreadLocalRandom random) {
        nextPointX = random.nextDouble(-0.22D, 0.22D);
        nextPointY = random.nextDouble(-0.08D, 0.08D);
        nextPointZ = random.nextDouble(-0.22D, 0.22D);
        nextPointChangeAtMs = System.currentTimeMillis() + random.nextLong(650L, 1600L);
        pointLerpSpeed = random.nextDouble(0.015D, 0.035D);
    }

    private boolean isHoldingWeapon(Minecraft client) {
        if (client.player == null) {
            return false;
        }

        Item item = client.player.getMainHandItem().getItem();
        return item == Items.WOODEN_SWORD
                || item == Items.STONE_SWORD
                || item == Items.IRON_SWORD
                || item == Items.GOLDEN_SWORD
                || item == Items.DIAMOND_SWORD
                || item == Items.NETHERITE_SWORD
                || item instanceof AxeItem
                || item instanceof TridentItem
                || item instanceof MaceItem;
    }

    private boolean hasArmor(LivingEntity entity) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlockedByWall(Minecraft client, Vec3 from, Vec3 to) {
        if (client.level == null) {
            return false;
        }

        BlockHitResult hitResult = client.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        if (hitResult.getType() == HitResult.Type.MISS) {
            return false;
        }

        BlockState state = client.level.getBlockState(hitResult.getBlockPos());
        return state.isCollisionShapeFullBlock(client.level, hitResult.getBlockPos())
                && state.isSolidRender();
    }

    private double smoothStep(double previousValue, double targetValue, double response) {
        double clampedResponse = Mth.clamp(response, 0.05D, 1.0D);
        return previousValue + (targetValue - previousValue) * clampedResponse;
    }

    private double clampToRemaining(double step, double remaining) {
        if (Math.abs(step) > Math.abs(remaining)) {
            return remaining;
        }
        return step;
    }

    private final class SilentAimRotateConstructor extends RotateConstructor {
        private SilentAimRotateConstructor() {
            super("AimAssistSilent");
        }

        @Override
        public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
            Angle delta = MathAngle.calculateDelta(currentAngle, targetAngle);
            double yawDelta = delta.getYaw();
            double pitchDelta = delta.getPitch();
            double dist = Math.hypot(yawDelta, pitchDelta);

            if (dist <= 0.35D) {
                prevSilentYawStep *= 0.30F;
                prevSilentPitchStep *= 0.30F;
                return targetAngle.adjustSensitivity();
            }

            double speedNorm = Mth.clamp(speedSetting.getValue() / 5.0D, 0.25D, 2.0D);
            double smoothNorm = Mth.clamp((smoothnessSetting.getValue() - 1.0D) / 9.0D, 0.0D, 1.0D);
            double t = Mth.clamp(dist / 22.0D, 0.25D, 1.0D);
            double hermite = t * t * (3.0D - 2.0D * t);

            double maxYaw = (36.0D * speedNorm * hermite) / (1.0D + smoothNorm * 1.15D);
            double maxPitch = (26.0D * speedNorm * hermite) / (1.0D + smoothNorm * 1.15D);

            double stepYaw = Math.max(0.8D, Math.abs(yawDelta / dist) * maxYaw);
            double stepPitch = Math.max(0.6D, Math.abs(pitchDelta / dist) * maxPitch);

            float rawYaw = (float) Mth.clamp(yawDelta, -stepYaw, stepYaw);
            float rawPitch = (float) Mth.clamp(pitchDelta, -stepPitch, stepPitch);

            if (microMovementsSetting.getValue() && dist > 2.5D) {
                rawYaw += MathUtils.getRandom(-0.15F, 0.15F);
                rawPitch += MathUtils.getRandom(-0.12F, 0.12F);
            }

            float blendedYaw = Mth.lerp(0.82F, prevSilentYawStep, rawYaw);
            float blendedPitch = Mth.lerp(0.82F, prevSilentPitchStep, rawPitch);
            prevSilentYawStep = blendedYaw;
            prevSilentPitchStep = blendedPitch;

            return new Angle(
                    currentAngle.getYaw() + blendedYaw,
                    Mth.clamp(currentAngle.getPitch() + blendedPitch, -89.5F, 89.5F)
            ).adjustSensitivity();
        }

        @Override
        public Vec3 randomValue() {
            return Vec3.ZERO;
        }
    }
}