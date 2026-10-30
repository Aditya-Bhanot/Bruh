package releon.ru.api.module.impl.combat.macetarget.prediction;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.macetarget.state.MaceState.Stage;

public class TargetPredictor {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int MIN_SAMPLES = 2;
    private static final double SMOOTHING = 0.6;

    private Vec3 lastPosition;
    private Vec3 smoothedVelocity = Vec3.ZERO;
    private long lastUpdateTime;
    private int sampleCount;

    public void update(LivingEntity target) {
        if (target == null) {
            reset();
            return;
        }
        Vec3 currentPos = target.position();
        long currentTime = System.currentTimeMillis();
        if (lastPosition != null && lastUpdateTime > 0) {
            long deltaTime = currentTime - lastUpdateTime;
            if (deltaTime > 0 && deltaTime < 500) {
                Vec3 velocity = currentPos.subtract(lastPosition);
                smoothedVelocity = smoothedVelocity.scale(SMOOTHING).add(velocity.scale(1.0 - SMOOTHING));
                sampleCount++;
            }
        }
        lastPosition = currentPos;
        lastUpdateTime = currentTime;
    }

    public Vec3 getPredictedPosition(LivingEntity target, Stage stage) {
        if (target == null || MC.player == null) {
            return Vec3.ZERO;
        }
        Vec3 currentPos = target.position();
        if (sampleCount < MIN_SAMPLES || smoothedVelocity.horizontalDistanceSqr() < 0.0001D) {
            return currentPos;
        }
        double distance = MC.player.distanceTo(target);
        double playerSpeed = MC.player.getDeltaMovement().length();
        double targetSpeed = smoothedVelocity.horizontalDistance();
        double ticksToReach = switch (stage) {
            case FLYING_UP -> (Math.abs(MC.player.getY() - target.getY()) + distance) / Math.max(playerSpeed * 20.0D, 1.0D) * 1.2D;
            case TARGETTING -> distance / Math.max(playerSpeed * 20.0D, 0.8D);
            case ATTACKING -> distance / Math.max(playerSpeed * 20.0D, 1.5D) * 0.8D;
            default -> distance / 2.0D;
        };
        ticksToReach = Math.min(ticksToReach, 40.0D);
        double leadMultiplier = targetSpeed > 0.4D ? 1.5D : targetSpeed > 0.2D ? 1.3D : 1.0D;
        return currentPos.add(smoothedVelocity.scale(ticksToReach * leadMultiplier));
    }

    public boolean isMoving() {
        return smoothedVelocity.horizontalDistanceSqr() > 0.001D;
    }

    public void reset() {
        lastPosition = null;
        smoothedVelocity = Vec3.ZERO;
        lastUpdateTime = 0L;
        sampleCount = 0;
    }
}
