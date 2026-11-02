package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.FireworkEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;

public final class SuperFireWork extends Module {
    private final ModeSetting modeSetting = register(new ModeSetting("Mode", "Firework boost mode.", "BravoHvH", "BravoHvH", "ReallyWorld", "PulseHVH", "Custom"));
    private final NumberSetting customSpeedSetting = register(new NumberSetting("Speed", "Custom firework speed.", 1.963, 1.5, 3.0, 0.001));
    private final BooleanSetting nearBoostSetting = register(new BooleanSetting("Near Boost", "Boosts when a player is close.", false));

    public SuperFireWork() {
        super("Super Fire Work", "Amplifies elytra firework boost.", ModuleCategory.MOVEMENT);
        customSpeedSetting.visibleWhen(() -> modeSetting.is("Custom"));
    }

    @SubscribeEvent
    private void onFirework(FireworkEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !client.player.isFallFlying()) {
            return;
        }

        float yaw = AngleConnection.INSTANCE.getRotation().getYaw() % 360.0F;
        if (yaw < 0.0F) {
            yaw += 360.0F;
        }

        if (modeSetting.is("ReallyWorld")) {
            handleReallyWorldMode(event, yaw);
        } else if (modeSetting.is("BravoHvH")) {
            handleBravoHvHMode(client, event, yaw);
        } else if (modeSetting.is("PulseHVH")) {
            handlePulseHVHMode(client, event, yaw);
        } else if (modeSetting.is("Custom")) {
            handleCustomMode(client, event, yaw);
        }
    }

    private void handleReallyWorldMode(FireworkEvent event, float yaw) {
        float closestDiff = 180.0F;
        for (float diagonal : new float[]{45.0F, 135.0F, 225.0F, 315.0F}) {
            float diff = Math.abs(yaw - diagonal);
            diff = Math.min(diff, 360.0F - diff);
            if (diff < closestDiff) {
                closestDiff = diff;
            }
        }

        double speedXZ = 1.5D;
        double speedY = 1.5D;
        if (closestDiff <= 4.0F) {
            speedXZ = 2.2D;
        } else if (closestDiff <= 8.0F) {
            speedXZ = 2.06D;
        } else if (closestDiff <= 12.0F) {
            speedXZ = 1.98D;
        } else if (closestDiff <= 16.0F) {
            speedXZ = 1.87D;
        } else if (closestDiff <= 20.0F) {
            speedXZ = 1.8D;
        } else if (closestDiff <= 24.0F) {
            speedXZ = 1.74D;
        } else if (closestDiff <= 28.0F) {
            speedXZ = 1.7D;
        } else if (closestDiff <= 32.0F) {
            speedXZ = 1.65D;
        } else if (closestDiff <= 36.0F) {
            speedXZ = 1.63D;
        } else {
            speedXZ = 1.61D;
            speedY = 1.61D;
        }
        applyFireworkVelocity(event, speedXZ, speedY);
    }

    private void handleBravoHvHMode(Minecraft client, FireworkEvent event, float yaw) {
        boolean diagonal = checkDiagonal(yaw, 16.0F);
        boolean nearPlayer = checkNearPlayer(client, 4.0F);
        double speedXZ;
        double speedY = 1.66D;
        if (diagonal) {
            speedXZ = 1.963D;
        } else if (nearBoostSetting.getValue() && nearPlayer) {
            speedXZ = 1.82D;
            speedY = 1.67D;
        } else {
            speedXZ = 1.675D;
        }
        applyFireworkVelocity(event, speedXZ, speedY);
    }

    private void handlePulseHVHMode(Minecraft client, FireworkEvent event, float yaw) {
        boolean diagonal = checkDiagonal(yaw, 16.0F);
        boolean nearPlayer = checkNearPlayer(client, 5.0F);
        double speedXZ;
        double speedY = 1.66D;
        if (diagonal) {
            speedXZ = 1.963D;
        } else if (nearBoostSetting.getValue() && nearPlayer) {
            speedXZ = 1.82D;
            speedY = 1.67D;
        } else {
            speedXZ = 1.675D;
        }
        applyFireworkVelocity(event, speedXZ, speedY);
    }

    private void handleCustomMode(Minecraft client, FireworkEvent event, float yaw) {
        boolean diagonal = checkDiagonal(yaw, 16.0F);
        boolean nearPlayer = checkNearPlayer(client, 5.0F);
        double speedXZ;
        double speedY = 1.66D;
        if (diagonal) {
            speedXZ = customSpeedSetting.getValue();
        } else if (nearBoostSetting.getValue() && nearPlayer) {
            speedXZ = customSpeedSetting.getValue() - 0.1D;
            speedY = 1.67D;
        } else {
            speedXZ = 1.675D;
        }
        applyFireworkVelocity(event, speedXZ, speedY);
    }

    private boolean checkDiagonal(float yaw, float threshold) {
        for (float diagonal : new float[]{45.0F, 135.0F, 225.0F, 315.0F}) {
            float diff = Math.abs(yaw - diagonal);
            diff = Math.min(diff, 360.0F - diff);
            if (diff <= threshold) {
                return true;
            }
        }
        return false;
    }

    private boolean checkNearPlayer(Minecraft client, float distance) {
        if (!nearBoostSetting.getValue() || client.level == null || client.player == null) {
            return false;
        }
        for (Player player : client.level.players()) {
            if (player != client.player && player.distanceTo(client.player) <= distance) {
                return true;
            }
        }
        return false;
    }

    private void applyFireworkVelocity(FireworkEvent event, double speedXZ, double speedY) {
        Vec3 rotationVector = AngleConnection.INSTANCE.getMoveRotation().toVector();
        Vec3 currentVelocity = event.getVector();
        event.setVector(currentVelocity.add(
                rotationVector.x * 0.1D + (rotationVector.x * speedXZ - currentVelocity.x) * 0.5D,
                rotationVector.y * 0.1D + (rotationVector.y * speedY - currentVelocity.y) * 0.5D,
                rotationVector.z * 0.1D + (rotationVector.z * speedXZ - currentVelocity.z) * 0.5D
        ));
    }
}
