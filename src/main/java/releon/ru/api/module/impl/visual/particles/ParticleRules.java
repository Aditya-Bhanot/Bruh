package releon.ru.api.module.impl.visual.particles;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.Random;

import static releon.ru.api.module.impl.visual.particles.ParticleConstants.*;

final class ParticleRules {
    final Entity entityFor;
    final boolean ignoreCollisionsWithBlocks;
    final boolean instantDeathOnCollide;
    final boolean ricochetOnCollide;
    final boolean stopMotionOnCollide;
    final boolean collisionAtOtherParticles;
    private final ParticleSettings settings;
    private final Minecraft client;
    private final Random random;
    private final boolean renderModeIsRandom;
    boolean renderBox;
    boolean renderTexture;
    final float motionMulFactorAlways;
    final float motionMulOnCollideJump;
    final float motionMulOnCollideSlide;
    final float gravityPowers;
    float minScale;
    float maxScale;
    float rotationSpeedMul;
    String motionSideChangeRuleMode;

    ParticleRules(Minecraft client, ParticleSettings settings, Random random, Entity entityFor) {
        this.client = client;
        this.settings = settings;
        this.random = random;
        this.entityFor = entityFor;
        this.ignoreCollisionsWithBlocks = settings.jumpFromCollideMode.is(COLLIDE_IGNORE);
        this.instantDeathOnCollide = settings.jumpFromCollideMode.is(COLLIDE_FATAL);
        this.ricochetOnCollide = isRicochetMode(settings.jumpFromCollideMode.getValue());
        this.stopMotionOnCollide = settings.jumpFromCollideMode.is(COLLIDE_STUCK);
        this.collisionAtOtherParticles = settings.jumpFromAnotherParticle.getValue();

        String renderMode = settings.renderModeAs.getValue();
        this.renderModeIsRandom = RENDER_RANDOM.equals(renderMode);
        applyRenderMode(renderModeIsRandom ? randomRenderMode() : renderMode);

        float motionInfluence = settings.motionInfluenceMul.getFloat();
        this.motionMulFactorAlways = motionInfluence > 0.0F
                ? 1.0F + ParticleMath.cubicOut(motionInfluence) * 0.05F
                : 1.0F + ParticleMath.sineOut(motionInfluence) * 0.1F;
        this.motionMulOnCollideJump = collisionJumpMultiplier(settings.jumpFromCollideMode.getValue());
        this.motionMulOnCollideSlide = settings.jumpFromCollideMode.is(COLLIDE_SLIDE_SLOW) ? 0.875F : 1.0F;
        this.gravityPowers = settings.gravityPower.getFloat();
        updateScalePreUpdate();
        this.motionSideChangeRuleMode = sourceIsMe() ? settings.motionSideChangeRuleInWorld.getValue() : settings.motionSideChangeRuleOnAttack.getValue();
        if (RULE_ALTERNATE_SOURCE.equalsIgnoreCase(this.motionSideChangeRuleMode)) {
            this.motionSideChangeRuleMode = RULE_TO_SOURCE;
        }
        this.rotationSpeedMul = settings.magnetismPowerBase.getFloat();
    }

    void updateScalePreUpdate() {
        this.maxScale = settings.maxBaseScale.getFloat();
        this.minScale = this.maxScale / 5.0F;
    }

    void updateMotionRulePreUpdate(int ticksExisted, int timeAliveTicks) {
        String currentRule = sourceIsMe() ? settings.motionSideChangeRuleInWorld.getValue() : settings.motionSideChangeRuleOnAttack.getValue();
        if (RULE_ALTERNATE_SOURCE.equalsIgnoreCase(currentRule)) {
            this.motionSideChangeRuleMode = ticksExisted < timeAliveTicks / 2.0F ? RULE_TO_SOURCE : RULE_FROM_SOURCE;
        }
    }

    void updateRenderRulesPreRender() {
        if (!renderModeIsRandom) {
            applyRenderMode(settings.renderModeAs.getValue());
        }
    }

    void updateRotationSpeedMul() {
        float multiplier = switch (motionSideChangeRuleMode) {
            case RULE_TO_SOURCE, RULE_FROM_SOURCE -> 2.0F;
            case RULE_TWIST_LEFT, RULE_TWIST_RIGHT, RULE_TWIST_RANDOM -> 1.0F;
            case RULE_CAMERA_LEFT, RULE_CAMERA_RIGHT, RULE_CAMERA_FORWARD, RULE_CAMERA_BACK -> 0.5F;
            default -> 1.0F;
        };
        this.rotationSpeedMul = multiplier * settings.magnetismPowerBase.getFloat();
    }

    private boolean sourceIsMe() {
        return entityFor == null || client.player == null || entityFor.getId() == client.player.getId();
    }

    private void applyRenderMode(String renderMode) {
        this.renderBox = RENDER_CUBES.equals(renderMode) || RENDER_TEXTURES_AND_CUBES.equals(renderMode);
        this.renderTexture = RENDER_TEXTURES.equals(renderMode) || RENDER_TEXTURES_AND_CUBES.equals(renderMode);
    }

    private boolean isRicochetMode(String mode) {
        return COLLIDE_RICOCHET.equals(mode)
                || COLLIDE_STRONG_RICOCHET.equals(mode)
                || COLLIDE_VERY_STRONG_RICOCHET.equals(mode)
                || COLLIDE_WEAK_RICOCHET.equals(mode)
                || COLLIDE_VERY_WEAK_RICOCHET.equals(mode);
    }

    private float collisionJumpMultiplier(String mode) {
        return switch (mode) {
            case COLLIDE_VERY_WEAK_RICOCHET -> 0.5F;
            case COLLIDE_WEAK_RICOCHET -> 0.75F;
            case COLLIDE_STRONG_RICOCHET -> 1.5F;
            case COLLIDE_VERY_STRONG_RICOCHET -> 2.0F;
            default -> 1.0F;
        };
    }

    private String randomRenderMode() {
        return switch (random.nextInt(3)) {
            case 0 -> RENDER_TEXTURES;
            case 1 -> RENDER_CUBES;
            default -> RENDER_TEXTURES_AND_CUBES;
        };
    }
}
