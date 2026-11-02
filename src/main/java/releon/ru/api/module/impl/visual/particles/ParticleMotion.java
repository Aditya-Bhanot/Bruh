package releon.ru.api.module.impl.visual.particles;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

import static releon.ru.api.module.impl.visual.particles.ParticleConstants.*;

final class ParticleMotion {
    private final ParticleSettings settings;
    private final Random random;
    private final float[] rotationOut = new float[2];
    private final float[] speedOut = new float[2];
    private final double[] motionOut = new double[3];

    ParticleMotion(ParticleSettings settings, Random random) {
        this.settings = settings;
        this.random = random;
    }

    float[] startMotionRotations(Minecraft client, Vec3 sourcePos, Vec3 objPos, Entity entityFrom) {
        String rule = entityFrom == null || entityFrom == client.player
                ? settings.spawnPulseMotionSideRuleInWorld.getValue()
                : settings.spawnPulseMotionSideRuleOnAttack.getValue();
        float pitch = 0.0F;
        return switch (rule) {
            case RULE_NO_XZ -> setRotation(LOCK_XZ_TRIGGER_YAW, pitch);
            case RULE_RANDOM -> setRotation(ParticleMath.randomFloat(random, 0.0F, 360.0F), ParticleMath.randomFloat(random, -90.0F, 90.0F));
            case RULE_TO_SOURCE -> ParticleMath.vecNeeded(sourcePos, objPos, rotationOut);
            case RULE_FROM_SOURCE -> ParticleMath.vecNeeded(objPos, sourcePos, rotationOut);
            case RULE_CAMERA_LEFT -> setRotation(cameraYaw(client) - 90.0F, pitch);
            case RULE_CAMERA_RIGHT -> setRotation(cameraYaw(client) + 90.0F, pitch);
            case RULE_CAMERA_FORWARD -> setRotation(cameraYaw(client), pitch);
            case RULE_CAMERA_BACK -> setRotation(cameraYaw(client) + 180.0F, pitch);
            default -> setRotation(0.0F, pitch);
        };
    }

    float[] changeRotationUpdateIfRule(
            Minecraft client,
            String motionSideChangeRuleMode,
            double objPosX,
            double objPosY,
            double objPosZ,
            Vec3 tentativeTargetVec,
            float yawIn,
            float pitchIn,
            float[] speedYawPitch,
            byte metaDataRange1Only,
            ParticleRules rules,
            float randomFloat01StaticValue
    ) {
        float preYaw = yawIn;
        float prePitch = pitchIn;
        switch (motionSideChangeRuleMode) {
            case RULE_CAMERA_LEFT -> {
                yawIn = cameraYaw(client) - 90.0F;
                pitchIn += speedYawPitch[1] / 5.0F;
            }
            case RULE_CAMERA_RIGHT -> {
                yawIn = cameraYaw(client) + 90.0F;
                pitchIn += speedYawPitch[1] / 5.0F;
            }
            case RULE_CAMERA_FORWARD -> {
                yawIn = cameraYaw(client);
                pitchIn += speedYawPitch[1] / 5.0F;
            }
            case RULE_CAMERA_BACK -> {
                yawIn = cameraYaw(client) + 180.0F;
                pitchIn += speedYawPitch[1] / 5.0F;
            }
            case RULE_TWIST_LEFT -> {
                yawIn -= speedYawPitch[0];
                pitchIn += speedYawPitch[1] / 5.0F;
            }
            case RULE_TWIST_RIGHT -> {
                yawIn += speedYawPitch[0];
                pitchIn += speedYawPitch[1] / 5.0F;
            }
            case RULE_TWIST_RANDOM -> {
                yawIn += speedYawPitch[0] * metaDataRange1Only;
                pitchIn += speedYawPitch[1] / 5.0F;
            }
            case RULE_TO_SOURCE, RULE_FROM_SOURCE -> {
                Vec3 target = tentativeTargetVec == null
                        ? ParticleWorld.entityVecForParticle(client, rules.entityFor, randomFloat01StaticValue)
                        : tentativeTargetVec;
                float[] targetRotation = RULE_FROM_SOURCE.equals(motionSideChangeRuleMode)
                        ? ParticleMath.vecNeeded(objPosX, objPosY, objPosZ, target.x, target.y, target.z, rotationOut)
                        : ParticleMath.vecNeeded(target.x, target.y, target.z, objPosX, objPosY, objPosZ, rotationOut);
                yawIn = targetRotation[0];
                pitchIn = targetRotation[1];
            }
            default -> {
            }
        }
        return setRotation(ParticleMath.stepRotationSpeed(preYaw, yawIn, speedYawPitch[0]), ParticleMath.stepRotationSpeed(prePitch, pitchIn, speedYawPitch[1]));
    }

    double[] syncMotionsXYZToAngles(double xyzSqrtDelta, float yaw, float pitch, double speedIfZero) {
        double speed = xyzSqrtDelta > 0.0D || speedIfZero == 0.0D ? xyzSqrtDelta : speedIfZero;
        double[] out = ParticleMath.vec3FromAngles(yaw, pitch, speed, motionOut);
        if (yaw == LOCK_XZ_TRIGGER_YAW) {
            out[0] = 0.0D;
            out[2] = 0.0D;
        }
        return out;
    }

    float[] rotationSpeeds(float speedMultiplier) {
        speedOut[0] = speedMultiplier * 180.0F;
        speedOut[1] = speedMultiplier * 90.0F;
        return speedOut;
    }

    private float[] setRotation(float yaw, float pitch) {
        rotationOut[0] = yaw;
        rotationOut[1] = pitch;
        return rotationOut;
    }

    private float cameraYaw(Minecraft client) {
        if (client.player == null) {
            return 0.0F;
        }
        float yaw = client.player.getYRot();
        if (client.options.getCameraType() == CameraType.THIRD_PERSON_FRONT) {
            yaw += 180.0F;
        }
        return yaw;
    }
}
