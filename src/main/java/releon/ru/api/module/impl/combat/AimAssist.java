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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
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
    private final BooleanSetting multipointSetting = register(new BooleanSetting("Multipoint", "Use randomized aim points inside the hitbox.", true));
    private final BooleanSetting wallCheckSetting = register(new BooleanSetting("Wall Check", "Skip targets behind full blocks.", false));
    private final BooleanSetting disableOnWorldChangeSetting = register(new BooleanSetting("Disable On World", "Disable module after world change.", false));
    private final BooleanSetting microMovementsSetting = register(new BooleanSetting("Micro Movements", "Adds small aiming noise and profile variation.", true));

    private Entity target;
    private long targetAcquiredAtMs;
    private long lastFrameNs;
    private long lastRenderAimNs;
    private double previousYawStep;
    private double previousPitchStep;
    private double aimYawVelocity;
    private double aimPitchVelocity;
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
        resetState();
    }

    @Override
    public void onTick(Minecraft client) {
        long now = System.nanoTime();
        if (lastRenderAimNs == 0L || now - lastRenderAimNs > 75_000_000L) {
            updateAim(client);
        }
    }

    @SubscribeEvent
    private void onDraw(DrawEvent event) {
        if (event.getLayer() == DrawEvent.Layer.GAME) {
            lastRenderAimNs = System.nanoTime();
            updateAim(Minecraft.getInstance());
        }
        drawFovRadius();
    }

    private void updateAim(Minecraft client) {
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
            return;
        }

        if ((onlyWithWeaponSetting.getValue() && !isHoldingWeapon(client))
                || (noAimInInventorySetting.getValue() && client.screen instanceof AbstractContainerScreen<?>)) {
            resetMotionState();
            return;
        }

        long frameNs = System.nanoTime();
        double frameScale = lastFrameNs > 0L ? (frameNs - lastFrameNs) / 16_666_666.7D : 1.0D;
        frameScale = Mth.clamp(frameScale, 0.05D, 3.0D);
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

        rotateToTarget(client, selectedTarget, frameScale);
    }

    private void rotateToTarget(Minecraft client, Entity selectedTarget, double frameScale) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean humanized = microMovementsSetting.getValue();
        long now = System.currentTimeMillis();

        if (humanized && now - heightNoiseUpdatedAtMs > random.nextLong(180L, 450L)) {
            heightNoise = Mth.clamp(heightNoise + random.nextDouble(-0.006D, 0.006D), -0.025D, 0.025D);
            heightNoiseUpdatedAtMs = now;
        }

        if (multipointSetting.getValue() && now >= nextPointChangeAtMs) {
            randomizePoint(random);
        }

        if (multipointSetting.getValue()) {
            double lerpSpeed = pointLerpSpeed + (humanized ? random.nextDouble(-0.002D, 0.002D) : 0.0D);
            currentPointX += (nextPointX - currentPointX) * lerpSpeed;
            currentPointY += (nextPointY - currentPointY) * lerpSpeed;
            currentPointZ += (nextPointZ - currentPointZ) * lerpSpeed;
        }

        double[] rotations = calculateRotations(client, selectedTarget);
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        boolean yawOnly = onlyYawSetting.getValue();
        double yawDelta = Mth.wrapDegrees(rotations[0] - currentYaw);
        double pitchDelta = rotations[1] - currentPitch;
        double effectivePitchDelta;

        if (yawOnly) {
            double pitchDeadZone = humanized ? 8.0D + random.nextDouble(4.0D) : 10.0D;
            double absPitch = Math.abs(pitchDelta);
            if (absPitch > pitchDeadZone) {
                double pull = humanized ? 0.06D + random.nextDouble(0.06D) : 0.08D;
                effectivePitchDelta = (pitchDelta - Math.signum(pitchDelta) * pitchDeadZone) * pull;
            } else {
                effectivePitchDelta = 0.0D;
            }
        } else {
            effectivePitchDelta = pitchDelta;
        }

        double deltaLength = yawOnly
                ? Math.abs(yawDelta)
                : Math.sqrt(yawDelta * yawDelta + effectivePitchDelta * effectivePitchDelta);
        if (deltaLength < 0.05D) {
            aimYawVelocity *= 0.35D;
            aimPitchVelocity *= 0.35D;
            return;
        }

        double speed = speedSetting.getValue() * (humanized ? speedMultiplier : 1.0D);
        double smoothness = Mth.clamp((smoothnessSetting.getValue() - 1.0D) / 9.0D, 0.0D, 1.0D);
        double distanceResponse = Mth.clamp(deltaLength / 35.0D, 0.0D, 1.0D);
        double responseScale = 1.0D - smoothness * 0.18D + distanceResponse * 0.18D;
        double microScale = 1.0D - smoothness * 0.85D;
        double warmup = Mth.clamp((now - targetAcquiredAtMs) / 180.0D, 0.0D, 1.0D);
        double stiffness = (0.012D + speed * 0.0026D) * (0.45D + warmup * 0.55D) * responseScale;
        double damping = (humanized ? 0.74D : 0.78D) - smoothness * 0.035D;
        double maxYawSpeed = (0.55D + speed * 0.50D) * (1.0D + distanceResponse * 0.55D - smoothness * 0.04D);
        double maxPitchSpeed = (0.36D + speed * 0.36D) * (1.0D + distanceResponse * 0.42D - smoothness * 0.04D);

        if (deltaLength > 25.0D) {
            double boost = Mth.clamp((deltaLength - 25.0D) / 55.0D, 0.0D, 1.0D);
            stiffness *= 1.0D + boost * 0.55D;
            maxYawSpeed *= 1.0D + boost * 0.35D;
            maxPitchSpeed *= 1.0D + boost * 0.25D;
        }

        aimYawVelocity += yawDelta * stiffness * frameScale;
        aimPitchVelocity += effectivePitchDelta * stiffness * frameScale;
        aimYawVelocity *= damping;
        aimPitchVelocity *= damping;

        aimYawVelocity = Mth.clamp(aimYawVelocity, -maxYawSpeed, maxYawSpeed);
        aimPitchVelocity = Mth.clamp(aimPitchVelocity, -maxPitchSpeed, maxPitchSpeed);

        if (Math.abs(yawDelta) < 1.2D) {
            aimYawVelocity *= 0.55D;
        }
        if (Math.abs(effectivePitchDelta) < 1.2D) {
            aimPitchVelocity *= 0.55D;
        }

        double yawStep = aimYawVelocity;
        double pitchStep = aimPitchVelocity;

        if (humanized) {
            yawWavePhase += yawWaveSpeed * 0.45D;
            pitchWavePhase += pitchWaveSpeed * 0.45D;
            yawStep += Math.sin(yawWavePhase) * 0.012D * microScale;
            pitchStep += Math.cos(pitchWavePhase) * 0.008D * microScale;
        }

        double stepResponse = 0.62D - smoothness * 0.22D + distanceResponse * 0.22D;
        yawStep = smoothStep(previousYawStep, yawStep, stepResponse);
        pitchStep = smoothStep(previousPitchStep, pitchStep, stepResponse);

        double stepLimitScale = 1.0D - smoothness * 0.10D + distanceResponse * 0.30D;
        double yawStepLimit = (humanized ? 0.2D + speed * 0.22D : 0.2D + speed * 0.26D) * stepLimitScale;
        double pitchStepLimit = (humanized ? 0.1D + speed * 0.16D : 0.1D + speed * 0.20D) * stepLimitScale;
        yawStep = clampStep(yawStep, previousYawStep, yawStepLimit);
        pitchStep = clampStep(pitchStep, previousPitchStep, pitchStepLimit);
        yawStep = clampToRemaining(yawStep, yawDelta);
        pitchStep = clampToRemaining(pitchStep, effectivePitchDelta);

        previousYawStep = yawStep;
        previousPitchStep = pitchStep;

        if (Math.abs(yawStep) < 0.003D && Math.abs(pitchStep) < 0.003D) {
            return;
        }

        client.player.setYRot(currentYaw + (float) yawStep);
        client.player.setXRot(Mth.clamp(currentPitch + (float) pitchStep, -90.0F, 90.0F));
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
        Vec3 eyePos = client.player.getEyePosition();
        Vec3 lookVec = client.player.getLookAngle();

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity livingEntity) || !isValidTarget(entity, livingEntity)) {
                continue;
            }

            double distance = client.player.distanceTo(entity);
            if (distance > maxDistance || distance < 0.5D) {
                continue;
            }

            if (wallCheckSetting.getValue() && isBlockedByWall(client, eyePos, getAimPoint(entity))) {
                continue;
            }

            if (getAngleTo(eyePos, lookVec, entity) > halfFov) {
                continue;
            }

            double score = healthPriority ? livingEntity.getHealth() : distance;
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

        double distance = client.player.distanceTo(entity);
        if (distance > distanceSetting.getValue() || distance < 0.5D) {
            return false;
        }

        Vec3 eyePos = client.player.getEyePosition();
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
            if (FriendUtils.isFriend(player)) {
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

    private double[] calculateRotations(Minecraft client, Entity entity) {
        Vec3 eyePos = client.player.getEyePosition();
        boolean humanized = microMovementsSetting.getValue();
        double height = aimHeightSetting.getValue();
        double adjustedHeight = humanized ? height + heightNoise : height;
        double offsetX = 0.0D;
        double offsetY = 0.0D;
        double offsetZ = 0.0D;

        if (multipointSetting.getValue()) {
            double horizontalRadius = entity.getBbWidth() * 0.5D;
            offsetX = currentPointX * horizontalRadius;
            offsetY = currentPointY;
            offsetZ = currentPointZ * horizontalRadius;
        }

        double clampedHeight = Mth.clamp(adjustedHeight + offsetY, 0.05D, 0.95D);
        Vec3 targetPoint = entity.position().add(offsetX, entity.getBbHeight() * clampedHeight, offsetZ);
        double diffX = targetPoint.x - eyePos.x;
        double diffY = targetPoint.y - eyePos.y;
        double diffZ = targetPoint.z - eyePos.z;
        double horizontal = Math.sqrt(diffX * diffX + diffZ * diffZ);
        double yaw = horizontal < 0.001D ? client.player.getYRot() : Math.toDegrees(Math.atan2(-diffX, diffZ));
        double distance = Math.sqrt(diffX * diffX + diffY * diffY + diffZ * diffZ);
        double pitch = distance < 0.001D
                ? client.player.getXRot()
                : -Math.toDegrees(Math.asin(Mth.clamp(diffY / distance, -1.0D, 1.0D)));

        return new double[]{yaw, pitch};
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
        yawWaveSpeed = random.nextDouble(0.08D, 0.25D);
        pitchWaveSpeed = random.nextDouble(0.06D, 0.2D);
        speedMultiplier = random.nextDouble(0.75D, 1.25D);
        heightNoise = random.nextDouble(-0.02D, 0.02D);
        heightNoiseUpdatedAtMs = System.currentTimeMillis();
        pointLerpSpeed = random.nextDouble(0.025D, 0.065D);
        randomizePoint(random);
    }

    private void randomizePoint(ThreadLocalRandom random) {
        nextPointX = random.nextDouble(-0.35D, 0.35D);
        nextPointY = random.nextDouble(-0.12D, 0.12D);
        nextPointZ = random.nextDouble(-0.35D, 0.35D);
        nextPointChangeAtMs = System.currentTimeMillis() + random.nextLong(600L, 1900L);
        pointLerpSpeed = random.nextDouble(0.008D, 0.025D);
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

    private double clampStep(double value, double previousValue, double maxDelta) {
        double delta = value - previousValue;
        if (Math.abs(delta) > maxDelta) {
            return previousValue + Math.signum(delta) * maxDelta;
        }
        return value;
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
}
