package releon.ru.api.module.impl.visual.particles;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import releon.ru.utils.render.color.ColorUtil;

import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

final class TexturedParticle3D {
    private final Supplier<Vec3> nullableTargetPosSup;
    private final Runnable onDeath;
    private double posX;
    private double posY;
    private double posZ;
    private double prevX;
    private double prevY;
    private double prevZ;
    private double motionX;
    private double motionY;
    private double motionZ;
    private float rotationYaw;
    private float rotationPitch;
    private float prevRotationYaw;
    private float prevRotationPitch;
    private float angle2D;
    private float prevAngle2D;
    private final float renderScaleMultiplier;
    private final Identifier texture;
    private boolean isDead;
    private boolean holdIsCollideChecks;
    private final int timeAliveTicks;
    private int ticksExisted;
    private int prevTicksExisted;
    private final byte metaIntRange1Only;
    private final int baseColor;
    private final ParticleRules rules;
    private final float randomFloat01StaticValue;
    private final float[] avoidRotation = new float[2];
    private final float[] motionRotation = new float[2];

    TexturedParticle3D(
            Random random,
            ParticleMotion motionHelper,
            Identifier texture,
            Vec3 spawnPos,
            Supplier<Vec3> nullableTargetPosSup,
            float baseSpeed,
            float[] rotationMovementOnStart,
            float timeAlive,
            Runnable onDeath,
            float maxAngleDeviation,
            int baseColor,
            ParticleRules rules
    ) {
        this.texture = texture;
        this.posX = spawnPos.x;
        this.posY = spawnPos.y;
        this.posZ = spawnPos.z;
        this.prevX = spawnPos.x;
        this.prevY = spawnPos.y;
        this.prevZ = spawnPos.z;
        this.nullableTargetPosSup = nullableTargetPosSup;
        this.rotationYaw = rotationMovementOnStart[0];
        this.rotationPitch = rotationMovementOnStart[1];
        this.prevRotationYaw = this.rotationYaw;
        this.prevRotationPitch = this.rotationPitch;

        float randomizedSpeed = ParticleMath.randomFloat(random, baseSpeed / 1.5F, baseSpeed / (1.0F / 1.5F));
        double[] motion = motionHelper.syncMotionsXYZToAngles(randomizedSpeed, this.rotationYaw, this.rotationPitch, randomizedSpeed);
        this.motionX = motion[0];
        this.motionY = motion[1];
        this.motionZ = motion[2];

        float timeRandomize = 0.04F;
        float minTime = ParticleMath.interpolate(timeAlive, timeAlive * 0.5F, timeRandomize);
        float maxTime = ParticleMath.interpolate(timeAlive, timeAlive * 1.5F, timeRandomize);
        this.timeAliveTicks = Math.max(1, (int) (ParticleMath.interpolate(minTime, maxTime, random.nextFloat()) / 50.0F));
        this.onDeath = onDeath;
        this.angle2D = ParticleMath.randomFloat(random, -maxAngleDeviation / 2.0F, maxAngleDeviation / 2.0F);
        this.prevAngle2D = this.angle2D;
        this.metaIntRange1Only = (byte) (random.nextInt(2) == 1 ? 1 : -1);
        this.baseColor = baseColor;
        this.renderScaleMultiplier = random.nextFloat();
        this.rules = rules;
        this.randomFloat01StaticValue = ParticleMath.quadInOut(ParticleMath.valWave01(random.nextFloat()));
    }

    void updateOnTickWorld(Minecraft client, ParticleMotion motionHelper, List<TexturedParticle3D> allParticles) {
        this.prevTicksExisted = this.ticksExisted;
        ++this.ticksExisted;

        this.rules.updateScalePreUpdate();
        this.rules.updateMotionRulePreUpdate(this.ticksExisted, this.timeAliveTicks);
        this.rules.updateRotationSpeedMul();

        float speedLimit = 5.0F;
        float prevSpeedXYZ = (float) Math.sqrt(this.motionX * this.motionX + this.motionY * this.motionY + this.motionZ * this.motionZ);
        this.prevX = this.posX;
        this.prevY = this.posY;
        this.prevZ = this.posZ;
        this.prevRotationYaw = this.rotationYaw;
        this.prevRotationPitch = this.rotationPitch;
        this.prevAngle2D = this.angle2D;
        this.angle2D -= 60.0F * this.metaIntRange1Only * prevSpeedXYZ;

        boolean cancelUpdatePosition = false;
        float speedRotationMul = prevSpeedXYZ / speedLimit * this.rules.rotationSpeedMul;
        float[] nextRotation;
        boolean anyOtherParticleMeet = false;

        if (this.rules.collisionAtOtherParticles && (nextRotation = checkAnyMeetAnotherParticleAwayAngles(allParticles, 60, this.getScaleUnique() / 6.0F)) != null) {
            this.rotationYaw = nextRotation[0];
            this.rotationPitch = nextRotation[1];
            anyOtherParticleMeet = true;
        } else {
            float[] angleSpeeds = motionHelper.rotationSpeeds(speedRotationMul);
            float[] rotateTo = motionHelper.changeRotationUpdateIfRule(
                    client,
                    this.rules.motionSideChangeRuleMode,
                    this.posX,
                    this.posY,
                    this.posZ,
                    this.nullableTargetPosSup == null ? null : this.nullableTargetPosSup.get(),
                    this.rotationYaw,
                    this.rotationPitch,
                    angleSpeeds,
                    this.metaIntRange1Only,
                    this.rules,
                    this.randomFloat01StaticValue
            );
            this.rotationYaw = rotateTo[0];
            this.rotationPitch = rotateTo[1];
        }

        double[] motion = motionHelper.syncMotionsXYZToAngles(prevSpeedXYZ, this.rotationYaw, this.rotationPitch, 0.0D);
        this.motionX = motion[0];
        this.motionY = motion[1];
        this.motionZ = motion[2];

        float motionSpeedMul = this.rules.motionMulFactorAlways * (anyOtherParticleMeet ? 0.8F : 1.0F);
        this.motionX *= motionSpeedMul;
        this.motionY *= motionSpeedMul;
        this.motionZ *= motionSpeedMul;

        float gravity = this.rules.gravityPowers;
        this.motionY -= gravity / 17.5F;
        if (gravity > 0.0F) {
            if (this.motionY > 0.0D) {
                this.motionY /= 1.0F + gravity * 0.05F;
            } else if (this.motionY < 0.0D && this.motionY > speedLimit / 3.333333F) {
                this.motionY *= 1.0F + gravity * 0.15F;
            }
        }

        if (!this.rules.ignoreCollisionsWithBlocks) {
            cancelUpdatePosition = handleBlockCollision(client, gravity);
        }

        this.motionX = Mth.clamp(this.motionX, -speedLimit, speedLimit);
        this.motionY = Mth.clamp(this.motionY, -speedLimit, speedLimit);
        this.motionZ = Mth.clamp(this.motionZ, -speedLimit, speedLimit);

        if (this.nullableTargetPosSup != null && this.nullableTargetPosSup.get() != null) {
            Vec3 target = this.nullableTargetPosSup.get();
            float toSup01 = Math.min(this.getTimePC(1.0F) * 1.3333333F, 1.0F);
            float lerpValue = toSup01 / this.timeAliveTicks;
            this.posX = ParticleMath.interpolate(this.posX, target.x, lerpValue);
            this.posY = ParticleMath.interpolate(this.posY, target.y, lerpValue);
            this.posZ = ParticleMath.interpolate(this.posZ, target.z, lerpValue);
        }

        if (!cancelUpdatePosition) {
            float[] motionAngles = ParticleMath.yawPitchFromDelta(this.motionX, this.motionY, this.motionZ, motionRotation);
            this.rotationYaw = motionAngles[0];
            this.rotationPitch = motionAngles[1];
            this.posX += this.motionX;
            this.posY += this.motionY;
            this.posZ += this.motionZ;
        }
    }

    void render(ParticleRenderer renderer, PoseStack stack, MultiBufferSource.BufferSource provider, float partialTicks, float alphaPC, boolean scaleOfAlpha, boolean bloom, Vec3 cameraPos, Quaternionf cameraRotation) {
        float timePC = this.getTimePC(partialTicks);
        if (timePC >= 1.0F) {
            return;
        }
        int color = this.getColor(timePC);
        if (color == 0) {
            return;
        }
        if (alphaPC < 1.0F) {
            color = ColorUtil.multAlpha(color, alphaPC);
            if (color == 0) {
                return;
            }
        }
        if (this.texture == null) {
            return;
        }

        this.rules.updateRenderRulesPreRender();
        float trueAlpha = ColorUtil.getAlpha(color) / 255.0F;
        float angle = this.getRenderAngle2D(partialTicks);
        float scale = scaleOfAlpha ? this.getScaleUnique() * trueAlpha : this.getScaleUnique();
        Vec3 renderPos = this.getRenderPosition(partialTicks).add(0.0D, scale / 5.0F, 0.0D);

        if (this.rules.renderTexture) {
            renderer.drawTexture(stack, provider, this.texture, renderPos, cameraPos, cameraRotation, scale, angle, color, bloom);
        }
        if (this.rules.renderBox) {
            renderer.drawBox(renderPos, scale, color);
        }
    }

    boolean removeIfDeadInWorld() {
        if (this.isDead) {
            if (this.onDeath != null) {
                this.onDeath.run();
            }
            return true;
        }
        if (this.texture == null || this.baseColor == 0) {
            return true;
        }
        return this.getTimePC(1.0F) >= 1.0F;
    }

    private boolean handleBlockCollision(Minecraft client, float gravity) {
        float predictCollideValue = 1.0F;
        if (this.rules.instantDeathOnCollide) {
            if (this.holdIsCollideChecks || hasCollideX(client, predictCollideValue) || hasCollideY(client, predictCollideValue) || hasCollideZ(client, predictCollideValue)) {
                this.isDead = true;
                this.motionX = 0.0D;
                this.motionY = 0.0D;
                this.motionZ = 0.0D;
                this.holdIsCollideChecks = true;
                return true;
            }
            return false;
        }

        if (this.rules.stopMotionOnCollide) {
            if (this.holdIsCollideChecks || hasCollideX(client, predictCollideValue) || hasCollideY(client, predictCollideValue) || hasCollideZ(client, predictCollideValue)) {
                this.motionX = 0.0D;
                this.motionY = 0.0D;
                this.motionZ = 0.0D;
                this.holdIsCollideChecks = true;
                return true;
            }
            return false;
        }

        boolean ricochet = this.rules.ricochetOnCollide;
        boolean anyCollide = false;
        float collideRicochetMul = this.rules.motionMulOnCollideJump;
        float collideSlideMul = ricochet ? 1.0F : this.rules.motionMulOnCollideSlide;

        if (this.holdIsCollideChecks || hasCollideY(client, predictCollideValue)) {
            if (this.motionY != 0.0D) {
                this.motionY = ricochet
                        ? -this.motionY * collideRicochetMul / (Math.max(gravity, 0.0F) + 1.0F) * Math.min(Math.abs(this.motionY) / 0.2F, 1.0F)
                        : 0.0D;
                if (ricochet && this.motionY > 0.0D && this.motionY < 0.02F) {
                    this.motionY = 0.0D;
                }
            }
            if (!ricochet) {
                this.motionX *= collideSlideMul;
                this.motionZ *= collideSlideMul;
            }
            anyCollide = true;
        }
        if (this.holdIsCollideChecks || hasCollideX(client, predictCollideValue)) {
            this.motionX = ricochet ? -this.motionX * collideRicochetMul : 0.0D;
            if (!ricochet) {
                this.motionZ *= collideSlideMul;
            }
            anyCollide = true;
        }
        if (this.holdIsCollideChecks || hasCollideZ(client, predictCollideValue)) {
            this.motionZ = ricochet ? -this.motionZ * collideRicochetMul : 0.0D;
            if (!ricochet) {
                this.motionX *= collideSlideMul;
            }
            anyCollide = true;
        }
        if (anyCollide) {
            this.holdIsCollideChecks = Math.sqrt(this.motionX * this.motionX + this.motionY * this.motionY + this.motionZ * this.motionZ) < 1.0E-3D * 8.0D;
        }
        return false;
    }

    private boolean hasCollideX(Minecraft client, float motionExtPC) {
        return ParticleWorld.isSolidCollisionBlock(client, this.posX + this.motionX * motionExtPC, this.posY, this.posZ);
    }

    private boolean hasCollideY(Minecraft client, float motionExtPC) {
        return ParticleWorld.isSolidCollisionBlock(client, this.posX, this.posY + this.motionY * motionExtPC, this.posZ);
    }

    private boolean hasCollideZ(Minecraft client, float motionExtPC) {
        return ParticleWorld.isSolidCollisionBlock(client, this.posX, this.posY, this.posZ + this.motionZ * motionExtPC);
    }

    private float[] checkAnyMeetAnotherParticleAwayAngles(List<TexturedParticle3D> allParticles, int maxCheckIterations, double minDistanceHandle) {
        if (minDistanceHandle == 0.0D) {
            return null;
        }
        int attempt = 0;
        Iterator<TexturedParticle3D> iterator = allParticles.iterator();
        while (iterator.hasNext() && attempt < maxCheckIterations) {
            ++attempt;
            TexturedParticle3D other = iterator.next();
            if (other == this) {
                ++attempt;
                continue;
            }
            double diffX = other.posX - this.posX;
            double diffY = other.posY - this.posY;
            double diffZ = other.posZ - this.posZ;
            double dot = diffX * diffX + diffY * diffY + diffZ * diffZ;
            if (dot < minDistanceHandle) {
                return ParticleMath.vecNeeded(this.posX, this.posY, this.posZ, other.posX, other.posY, other.posZ, avoidRotation);
            }
        }
        return null;
    }

    private float getTimePC(float partialTicks) {
        return Math.min(ParticleMath.interpolate(this.prevTicksExisted, this.ticksExisted, partialTicks) / (float) this.timeAliveTicks, 1.0F);
    }

    private int getColor(float timePC) {
        float alphaPC = ParticleMath.quintOut((timePC > 0.5F ? 1.0F - timePC : timePC) * 2.0F);
        return ColorUtil.multAlpha(this.baseColor, alphaPC);
    }

    private float getScaleUnique() {
        return ParticleMath.interpolate(this.rules.minScale, this.rules.maxScale, this.renderScaleMultiplier);
    }

    private Vec3 getRenderPosition(float partialTicks) {
        return new Vec3(
                ParticleMath.interpolate(this.prevX, this.posX, partialTicks),
                ParticleMath.interpolate(this.prevY, this.posY, partialTicks),
                ParticleMath.interpolate(this.prevZ, this.posZ, partialTicks)
        );
    }

    private float getRenderAngle2D(float partialTicks) {
        return ParticleMath.interpolate(this.prevAngle2D, this.angle2D, partialTicks);
    }
}
