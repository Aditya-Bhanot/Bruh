package releon.ru.api.module.impl.combat.macetarget.flight;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.macetarget.prediction.TargetPredictor;
import releon.ru.api.module.impl.combat.macetarget.state.MaceState.Stage;

public class FlightController {
    private static final Minecraft MC = Minecraft.getInstance();
    private final TargetPredictor predictor;
    private boolean predictionEnabled;
    private float height = 30.0F;

    public FlightController(TargetPredictor predictor) {
        this.predictor = predictor;
    }

    public void setPredictionEnabled(boolean predictionEnabled) {
        this.predictionEnabled = predictionEnabled;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public Vec3 getTargetPosition(LivingEntity target, Stage stage) {
        if (target == null) {
            return Vec3.ZERO;
        }
        if (predictionEnabled && predictor.isMoving()) {
            return predictor.getPredictedPosition(target, stage);
        }
        return target.getEyePosition();
    }

    public Angle calculateAngle(LivingEntity target, Stage stage) {
        if (target == null || MC.player == null) {
            return MathAngle.cameraAngle();
        }
        Vec3 targetPos = getTargetPosition(target, stage);
        if (stage == Stage.FLYING_UP) {
            return MathAngle.fromVec3d(targetPos.add(0.0D, height, 0.0D).subtract(MC.player.getEyePosition()));
        }
        if (stage == Stage.TARGETTING || stage == Stage.ATTACKING) {
            return MathAngle.fromVec3d(targetPos.subtract(MC.player.getEyePosition()));
        }
        return MathAngle.cameraAngle();
    }
}
