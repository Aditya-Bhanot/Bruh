package releon.ru.utils.move;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.aura.AngleConnection;

import java.util.Objects;

public final class MoveUtil {
    private static final Minecraft MC = Minecraft.getInstance();

    private MoveUtil() {
    }

    public static boolean hasPlayerMovement() {
        if (MC.player == null || MC.player.input == null) {
            return false;
        }
        ClientInput input = MC.player.input;
        if (input.hasForwardImpulse()) {
            return true;
        }
        Vec2 movement = input.getMoveVector();
        return movement.x != 0.0F || movement.y != 0.0F;
    }

    public static double getDistanceToGround() {
        if (MC.player == null || MC.level == null) {
            return 256.0D;
        }
        for (double y = MC.player.getY(); y > MC.level.getMinY(); y -= 0.1D) {
            if (!MC.level.getBlockState(MC.player.blockPosition().below((int) ((MC.player.getY() - y) + 1))).isAir()) {
                return MC.player.getY() - y;
            }
        }
        return 256.0D;
    }

    public static double getDegreesRelativeToView(Vec3 positionRelativeToPlayer, float yaw) {
        float optimalYaw = (float) Math.atan2(-positionRelativeToPlayer.x, positionRelativeToPlayer.z);
        double currentYaw = Math.toRadians(Mth.wrapDegrees(yaw));
        return Math.toDegrees(Mth.wrapDegrees((float) (optimalYaw - currentYaw)));
    }

    public static void setVelocity(double velocity) {
        if (MC.player == null) {
            return;
        }
        double[] direction = calculateDirection(velocity);
        MC.player.setDeltaMovement(direction[0], MC.player.getDeltaMovement().y(), direction[1]);
    }

    public static double[] forward(double distance) {
        if (MC.player == null || MC.player.input == null) {
            return new double[]{0.0D, 0.0D};
        }
        Vec2 movement = MC.player.input.getMoveVector();
        float forward = movement.y;
        float sideways = movement.x;
        float yaw = AngleConnection.INSTANCE.getRotation().getYaw();
        if (forward != 0.0F) {
            if (sideways > 0.0F) {
                yaw += forward > 0.0F ? -45.0F : 45.0F;
            } else if (sideways < 0.0F) {
                yaw += forward > 0.0F ? 45.0F : -45.0F;
            }
            sideways = 0.0F;
            forward = forward > 0.0F ? 1.0F : -1.0F;
        }
        double sin = Math.sin(Math.toRadians(yaw + 90.0F));
        double cos = Math.cos(Math.toRadians(yaw + 90.0F));
        return new double[]{
                forward * distance * cos + sideways * distance * sin,
                forward * distance * sin - sideways * distance * cos
        };
    }

    public static double[] calculateDirection(double distance) {
        if (MC.player == null || MC.player.input == null) {
            return new double[]{0.0D, 0.0D};
        }
        Vec2 movement = MC.player.input.getMoveVector();
        return calculateDirection(movement.y, movement.x, distance);
    }

    public static double[] calculateDirection(float forward, float sideways, double distance) {
        float yaw = AngleConnection.INSTANCE.getRotation().getYaw();
        if (forward != 0.0F) {
            if (sideways > 0.0F) {
                yaw += forward > 0.0F ? -45.0F : 45.0F;
            } else if (sideways < 0.0F) {
                yaw += forward > 0.0F ? 45.0F : -45.0F;
            }
            sideways = 0.0F;
            forward = forward > 0.0F ? 1.0F : -1.0F;
        }

        double sin = Math.sin(Math.toRadians(yaw + 90.0F));
        double cos = Math.cos(Math.toRadians(yaw + 90.0F));
        double x = forward * distance * cos + sideways * distance * sin;
        double z = forward * distance * sin - sideways * distance * cos;
        return new double[]{x, z};
    }

    public static boolean moveKeyPressed(int keyNumber) {
        if (MC.options == null) {
            return false;
        }
        boolean w = MC.options.keyUp.isDown();
        boolean a = MC.options.keyLeft.isDown();
        boolean s = MC.options.keyDown.isDown();
        boolean d = MC.options.keyRight.isDown();
        return keyNumber == 0 ? w : keyNumber == 1 ? a : keyNumber == 2 ? s : keyNumber == 3 && d;
    }

    public static boolean w() {
        return moveKeyPressed(0);
    }

    public static boolean a() {
        return moveKeyPressed(1);
    }

    public static boolean s() {
        return moveKeyPressed(2);
    }

    public static boolean d() {
        return moveKeyPressed(3);
    }

    public static float moveYaw(float entityYaw) {
        return entityYaw + (float) (!a() || !d() || w() && s() || !w() && !s()
                ? (w() && s() && (!a() || !d()) && (a() || d())
                ? (a() ? -90 : d() ? 90 : 0)
                : (a() && d() && (!w() || !s()) || w() && s() && (!a() || !d())
                ? 0
                : (!a() && !d() && !s()
                ? 0
                : (w() && !s()
                ? 45
                : (s() && !w() ? (!a() && !d() ? 180 : 135) : ((w() || s()) && (!w() || !s()) ? 0 : 90))) * (a() ? -1 : 1))))
                : (w() ? 0 : s() ? 180 : 0));
    }

    public static float calculateBodyYaw(float yaw, float prevBodyYaw, double prevX, double prevZ, double currentX, double currentZ, float handSwingProgress) {
        double motionX = currentX - prevX;
        double motionZ = currentZ - prevZ;
        float motionSquared = (float) (motionX * motionX + motionZ * motionZ);
        float bodyYaw = prevBodyYaw;

        if (motionSquared > 0.0025000002F) {
            float movementYaw = (float) Mth.atan2(motionZ, motionX) * (180.0F / (float) Math.PI) - 90.0F;
            float yawDiff = Mth.abs(Mth.wrapDegrees(yaw) - movementYaw);
            if (95.0F < yawDiff && yawDiff < 265.0F) {
                bodyYaw = movementYaw - 180.0F;
            } else {
                bodyYaw = movementYaw;
            }
        }

        if (MC.player != null && MC.player.attackAnim - 0.2F > 0.0F) {
            bodyYaw = yaw;
        }

        float deltaYaw = Mth.wrapDegrees(bodyYaw - prevBodyYaw);
        bodyYaw = prevBodyYaw + deltaYaw * 0.3F;

        float yawOffsetDiff = Mth.wrapDegrees(yaw - bodyYaw);
        float maxHeadRotation = 52.0F;
        if (Math.abs(yawOffsetDiff) > maxHeadRotation) {
            bodyYaw += yawOffsetDiff - (float) Mth.sign(yawOffsetDiff) * maxHeadRotation;
        }

        return bodyYaw;
    }

    public static Input getDirectionalInputForDegrees(Input input, double degrees, float deadAngle) {
        Objects.requireNonNull(input, "input");
        boolean forward = input.forward();
        boolean backward = input.backward();
        boolean left = input.left();
        boolean right = input.right();

        if (degrees >= -90.0F + deadAngle && degrees <= 90.0F - deadAngle) {
            forward = true;
        } else if (degrees < -90.0F - deadAngle || degrees > 90.0F + deadAngle) {
            backward = true;
        }

        if (degrees >= 0.0F + deadAngle && degrees <= 180.0F - deadAngle) {
            right = true;
        } else if (degrees >= -180.0F + deadAngle && degrees <= 0.0F - deadAngle) {
            left = true;
        }

        return new Input(forward, backward, left, right, input.jump(), input.shift(), input.sprint());
    }

    public static Input getDirectionalInputForDegrees(Input input, double degrees) {
        return getDirectionalInputForDegrees(input, degrees, 20.0F);
    }
}
