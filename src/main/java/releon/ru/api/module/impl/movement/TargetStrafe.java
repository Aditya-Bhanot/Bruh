package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.InventoryFlowManager;

public final class TargetStrafe extends Module {
    private final ModeSetting mode = register(new ModeSetting("Mode", "Target strafe mode.", "Matrix", "Matrix", "Grim"));
    private final ModeSetting type = register(new ModeSetting("Walk Point", "Target strafe walk point.", "Cube", "Cube", "Center", "Circle"));
    private final ModeSetting typeMatrix = register(new ModeSetting("Bypass Point", "Target strafe bypass point.", "Circle", "Cube", "Circle"));
    private final NumberSetting grimRadius = register(new NumberSetting("Walk Radius", "Target strafe walk radius.", 0.87, 0.1, 1.5, 0.01));
    private final NumberSetting radius = register(new NumberSetting("Radius", "Matrix strafe radius.", 2.5, 0.1, 7.0, 0.1));
    private final NumberSetting speed = register(new NumberSetting("Speed", "Matrix strafe speed.", 0.3, 0.1, 1.0, 0.01));
    private final MultiModeSetting options = register(new MultiModeSetting("Options", "Target strafe options.",
            new String[]{"Auto Jump", "Only While Pressing", "Front", "Direction Mode"}, "Auto Jump"));
    private final ModeSetting directionMode = register(new ModeSetting("Direction", "Strafe direction.", "Clockwise", "Clockwise", "Counter Clockwise", "Random"));
    private int grimPointIndex;

    public TargetStrafe() {
        super("Target Strafe", "Strafes around Aura target.", ModuleCategory.MOVEMENT);
        type.visibleWhen(() -> mode.is("Grim"));
        typeMatrix.visibleWhen(() -> mode.is("Matrix"));
        grimRadius.visibleWhen(() -> mode.is("Grim") && (type.is("Cube") || type.is("Circle")));
        radius.visibleWhen(() -> mode.is("Matrix"));
        speed.visibleWhen(() -> mode.is("Matrix"));
        directionMode.visibleWhen(() -> options.isSelected("Direction Mode"));
    }

    @Override
    protected void onEnable() {
        grimPointIndex = 0;
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || InventoryFlowManager.isIdle() == false) {
            return;
        }
        LivingEntity target = AuraModule.target;
        if (target == null || !target.isAlive() || !mode.is("Grim")) {
            return;
        }
        if (options.isSelected("Only While Pressing") && !isAnyMoveKeyDown(client)) {
            return;
        }
        Vec3 nextPoint = calculateGrimNextPoint(client, target);
        applyGrimMovement(client, event, nextPoint);
    }

    @SubscribeEvent
    private void onTick(TickEvent event) {
        Minecraft client = event.getClient();
        if (client.player == null || client.level == null || !InventoryFlowManager.isIdle()) {
            return;
        }
        LivingEntity target = AuraModule.target;
        if (target == null || !target.isAlive() || !mode.is("Matrix")) {
            return;
        }
        if (options.isSelected("Only While Pressing") && !isAnyMoveKeyDown(client)) {
            return;
        }
        if (options.isSelected("Auto Jump") && client.player.onGround()) {
            client.player.jumpFromGround();
        }
        processMatrixStrafe(client, target);
    }

    private Vec3 calculateGrimNextPoint(Minecraft client, LivingEntity target) {
        Vec3 playerPos = client.player.position();
        Vec3 targetPos = target.position();
        double r = grimRadius.getValue();
        int directionMultiplier = getDirectionMultiplier();
        if (options.isSelected("Front")) {
            return calculateFrontPoint(target, targetPos, r, directionMultiplier);
        }
        return calculateNormalPoint(playerPos, targetPos, r, directionMultiplier, type);
    }

    private Vec3 calculateFrontPoint(LivingEntity target, Vec3 targetPos, double r, int directionMultiplier) {
        float targetYaw = target.getYRot();
        if (type.is("Center")) {
            return targetPos.add(
                    -Math.sin(Math.toRadians(targetYaw)) * r * directionMultiplier,
                    0.0D,
                    Math.cos(Math.toRadians(targetYaw)) * r * directionMultiplier
            );
        }
        double offset = Math.cos(System.currentTimeMillis() / 500.0D) * r * directionMultiplier;
        return targetPos.add(
                -Math.sin(Math.toRadians(targetYaw)) * r + Math.cos(Math.toRadians(targetYaw)) * offset,
                0.0D,
                Math.cos(Math.toRadians(targetYaw)) * r + Math.sin(Math.toRadians(targetYaw)) * offset
        );
    }

    private Vec3 calculateNormalPoint(Vec3 playerPos, Vec3 targetPos, double r, int directionMultiplier, ModeSetting selectedType) {
        if (selectedType.is("Cube")) {
            Vec3[] points = new Vec3[]{
                    new Vec3(targetPos.x - r, playerPos.y, targetPos.z - r),
                    new Vec3(targetPos.x - r, playerPos.y, targetPos.z + r),
                    new Vec3(targetPos.x + r, playerPos.y, targetPos.z + r),
                    new Vec3(targetPos.x + r, playerPos.y, targetPos.z - r)
            };
            if (playerPos.distanceTo(points[grimPointIndex]) < 0.5D) {
                grimPointIndex = (grimPointIndex + directionMultiplier + points.length) % points.length;
            }
            return points[grimPointIndex];
        }
        if (selectedType.is("Circle")) {
            double baseAngle = (System.currentTimeMillis() % 3600L) / 3600.0D * 4.0D * Math.PI;
            double angle = directionMultiplier > 0 ? baseAngle : 2.0D * Math.PI - baseAngle;
            return new Vec3(targetPos.x + Math.cos(angle) * r, playerPos.y, targetPos.z + Math.sin(angle) * r);
        }
        return new Vec3(targetPos.x, playerPos.y, targetPos.z);
    }

    private void applyGrimMovement(Minecraft client, InputEvent event, Vec3 nextPoint) {
        Vec3 direction = nextPoint.subtract(client.player.position()).normalize();
        float yaw = AngleConnection.INSTANCE.getRotation().getYaw();
        float movementAngle = (float) Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0F;
        float angleDiff = Mth.wrapDegrees(movementAngle - yaw);

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
        if (options.isSelected("Auto Jump") && client.player.onGround()) {
            event.setJumping(true);
        }
    }

    private void processMatrixStrafe(Minecraft client, LivingEntity target) {
        Vec3 playerPos = client.player.position();
        Vec3 targetPos = target.position();
        double r = radius.getValue();
        int directionMultiplier = getDirectionMultiplier();
        if (options.isSelected("Front")) {
            processMatrixFrontStrafe(client, target, playerPos, targetPos, r, directionMultiplier);
            return;
        }
        if (typeMatrix.is("Cube")) {
            processMatrixCubeStrafe(client, playerPos, targetPos, r, directionMultiplier);
        } else if (typeMatrix.is("Circle")) {
            processMatrixCircleStrafe(client, playerPos, targetPos, r, directionMultiplier);
        }
    }

    private void processMatrixFrontStrafe(Minecraft client, LivingEntity target, Vec3 playerPos, Vec3 targetPos, double r, int directionMultiplier) {
        float targetYaw = target.getYRot();
        double x = targetPos.x - Math.sin(Math.toRadians(targetYaw)) * r * directionMultiplier;
        double z = targetPos.z + Math.cos(Math.toRadians(targetYaw)) * r * directionMultiplier;
        setMotionTo(client, playerPos, x, z);
    }

    private void processMatrixCubeStrafe(Minecraft client, Vec3 playerPos, Vec3 targetPos, double r, int directionMultiplier) {
        Vec3 nextPoint = calculateNormalPoint(playerPos, targetPos, r, directionMultiplier, typeMatrix);
        setMotionTo(client, playerPos, nextPoint.x, nextPoint.z);
    }

    private void processMatrixCircleStrafe(Minecraft client, Vec3 playerPos, Vec3 targetPos, double r, int directionMultiplier) {
        double angle = Math.atan2(playerPos.z - targetPos.z, playerPos.x - targetPos.x);
        angle += directionMultiplier * speed.getValue() / Math.max(playerPos.distanceTo(targetPos), r);
        double x = targetPos.x + r * Math.cos(angle);
        double z = targetPos.z + r * Math.sin(angle);
        setMotionTo(client, playerPos, x, z);
    }

    private void setMotionTo(Minecraft client, Vec3 playerPos, double x, double z) {
        float yaw = (float) Math.toDegrees(Math.atan2(z - playerPos.z, x - playerPos.x)) - 90.0F;
        double motionSpeed = speed.getValue();
        client.player.setDeltaMovement(
                -Math.sin(Math.toRadians(yaw)) * motionSpeed,
                client.player.getDeltaMovement().y,
                Math.cos(Math.toRadians(yaw)) * motionSpeed
        );
    }

    private int getDirectionMultiplier() {
        if (options.isSelected("Direction Mode")) {
            if (directionMode.is("Counter Clockwise")) {
                return -1;
            }
            if (directionMode.is("Random")) {
                return (System.currentTimeMillis() / 3000L) % 2L == 0L ? 1 : -1;
            }
        }
        return 1;
    }

    private boolean isAnyMoveKeyDown(Minecraft client) {
        return client.options.keyUp.isDown()
                || client.options.keyDown.isDown()
                || client.options.keyLeft.isDown()
                || client.options.keyRight.isDown();
    }
}
