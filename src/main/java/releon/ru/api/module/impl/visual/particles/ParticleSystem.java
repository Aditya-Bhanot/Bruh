package releon.ru.api.module.impl.visual.particles;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import releon.ru.api.events.impl.WorldRenderEvent;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static releon.ru.api.module.impl.visual.particles.ParticleConstants.*;

public final class ParticleSystem {
    private final ParticleSettings settings;
    private final Random random = new Random();
    private final List<TexturedParticle3D> particles = new ArrayList<>();
    private final List<ParticleTexture> enabledTextures = new ArrayList<>(TEXTURES.length);
    private final ParticleMotion motion;
    private final ParticleRenderer renderer = new ParticleRenderer();

    public ParticleSystem(ParticleSettings settings) {
        this.settings = settings;
        this.motion = new ParticleMotion(settings, random);
    }

    public void clear() {
        particles.clear();
        renderer.clear();
    }

    public void tick(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            clear();
            return;
        }

        updateParticlesList(false, client);
        if (settings.spawnWorld()) {
            addParticlesToWorld(client, client.player);
        }
    }

    public void spawnAttack(Minecraft client, Entity target) {
        if (target == null || client == null || client.level == null || client.player == null || !settings.spawnAttacks()) {
            return;
        }
        addParticlesToList(client, target, Math.round(settings.spawnCountOnAttack.getFloat()), Math.round(settings.aliveTimeBaseOnAttack.getFloat()));
    }

    public void render(Minecraft client, WorldRenderEvent event) {
        if (client == null || client.level == null || client.player == null || particles.isEmpty() || client.gameRenderer == null) {
            return;
        }

        PoseStack stack = event.getStack();
        MultiBufferSource.BufferSource provider = client.renderBuffers().bufferSource();
        Vec3 cameraPos = client.gameRenderer.getMainCamera().position();
        Quaternionf cameraRotation = client.gameRenderer.getMainCamera().rotation();
        float partialTicks = 1.0F - Mth.clamp(event.getPartialTicks(), 0.0F, 1.0F);
        boolean scaleOfAlpha = settings.scaleAsAlphaEnabled.getValue();
        boolean bloom = settings.bloomTextureEnabled.getValue();

        renderer.clear();
        for (TexturedParticle3D particle : particles) {
            particle.render(renderer, stack, provider, partialTicks, 1.0F, scaleOfAlpha, bloom, cameraPos, cameraRotation);
        }
        renderer.flush(provider);
    }

    private void addParticlesToWorld(Minecraft client, Entity entityIn) {
        addParticlesToList(client, entityIn, Math.round(settings.spawnCountPerTickInWorld.getFloat()), Math.round(settings.aliveTimeBaseInWorld.getFloat()));
    }

    private void addParticlesToList(Minecraft client, Entity entityIn, int countAdd, long aliveTime) {
        Entity entity = entityIn == null ? client.player : entityIn;
        if (entity == null || countAdd <= 0 || client.level == null || client.player == null || !settings.hasEnabledTextures()) {
            return;
        }

        Vec3 targetVelocityPos = ParticleWorld.entityVecForParticle(client, entity, 0.5F);
        Vec3 spawnCenter = entity.getEyePosition().add(0.0D, -entity.getEyeHeight() / 2.0F, 0.0D);
        refreshEnabledTextures();
        if (enabledTextures.isEmpty()) {
            return;
        }
        for (int i = 0; i < countAdd; i++) {
            Vec3 pos = ParticleWorld.randomSpawnPosition(client, random, settings, spawnCenter, entity);
            if (pos == null) {
                continue;
            }
            ParticleTexture texture = randomParticleTexture();
            if (texture == null) {
                continue;
            }
            particles.add(new TexturedParticle3D(
                    random,
                    motion,
                    texture.id(),
                    pos,
                    null,
                    settings.speedBase.getFloat(),
                    motion.startMotionRotations(client, targetVelocityPos, pos, entity),
                    aliveTime,
                    null,
                    30.0F,
                    particleColor(),
                    new ParticleRules(client, settings, random, entity)
            ));
        }
        trimParticles();
    }

    private void updateParticlesList(boolean clear, Minecraft client) {
        if (clear) {
            particles.clear();
            return;
        }
        if (particles.isEmpty()) {
            return;
        }

        particles.removeIf(TexturedParticle3D::removeIfDeadInWorld);
        if (particles.isEmpty()) {
            return;
        }
        for (TexturedParticle3D particle : particles) {
            particle.updateOnTickWorld(client, motion, particles);
        }
        trimParticles();
    }

    private void trimParticles() {
        int overflow = particles.size() - MAX_PARTICLES;
        if (overflow > 0) {
            particles.subList(0, overflow).clear();
        }
    }

    private ParticleTexture randomParticleTexture() {
        if (enabledTextures.isEmpty()) {
            return null;
        }
        return enabledTextures.get(random.nextInt(enabledTextures.size()));
    }

    private void refreshEnabledTextures() {
        enabledTextures.clear();
        for (ParticleTexture texture : TEXTURES) {
            if (settings.texturesEnabled.isSelected(texture.mode())) {
                enabledTextures.add(texture);
            }
        }
    }

    private int particleColor() {
        if (settings.rainbowColorEnabled.getValue()) {
            return Color.HSBtoRGB(random.nextFloat(), 0.75F, 1.0F);
        }
        return settings.color.getValue().getRGB();
    }
}
