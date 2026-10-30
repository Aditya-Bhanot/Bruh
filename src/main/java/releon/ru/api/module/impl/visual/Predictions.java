package releon.ru.api.module.impl.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.WorldVertex;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.pipeline.ClientPipelines;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class Predictions extends Module {
    private static final int MAX_PARTICLES = 650;
    private static final long PARTICLE_SPAWN_INTERVAL_MS = 42L;
    private static final String TEXTURE_GLOW = "Glow";
    private static final String TEXTURE_STAR = "Star";
    private static final String TEXTURE_HEART = "Heart";
    private static final String TEXTURE_LIGHTNING = "Lightning";
    private static final String TEXTURE_TRIANGLE = "Triangle";
    private static final String TEXTURE_CROSS = "Cross";
    private static final String TEXTURE_CROWN = "Crown";
    private static final String TEXTURE_DOLLAR = "Dollar";
    private static final String TEXTURE_RHOMBUS = "Rhombus";
    private static final String TEXTURE_SNOWFLAKE = "Snowflake";
    private static final String TEXTURE_SKULL = "Skull";
    private static final String TEXTURE_CUBE = "Cube";
    private static final String TEXTURE_SQUARE = "Square";
    private static final String TEXTURE_RANDOM = "Random";
    private static final Identifier PARTICLE_GLOW = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/glow.png");
    private static final Identifier PARTICLE_STAR = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/star.png");
    private static final Identifier PARTICLE_HEART = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/heart.png");
    private static final Identifier PARTICLE_LIGHTNING = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/lightning.png");
    private static final Identifier PARTICLE_TRIANGLE = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/triangle.png");
    private static final Identifier PARTICLE_CROSS = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/cross.png");
    private static final Identifier PARTICLE_CROWN = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/crown.png");
    private static final Identifier PARTICLE_DOLLAR = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/dollar.png");
    private static final Identifier PARTICLE_SQUARE = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/square.png");
    private static final Identifier PARTICLE_SNOWFLAKE = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/snowflake.png");
    private static final Identifier PARTICLE_SKULL = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/skull.png");
    private static final Identifier PARTICLE_CUBE = Identifier.fromNamespaceAndPath("releon", "textures/features/predictions/particles/cube.png");
    private static final ProjectileProfile PROFILE_PEARL = new ProjectileProfile(0.99D, 0.03D);
    private static final ProjectileProfile PROFILE_ITEM = new ProjectileProfile(0.98D, 0.04D);
    private static final ProjectileProfile PROFILE_ARROW = new ProjectileProfile(0.99D, 0.05D);
    private static final String[] PARTICLE_TEXTURE_MODES = new String[]{
            TEXTURE_GLOW, TEXTURE_STAR, TEXTURE_HEART, TEXTURE_LIGHTNING, TEXTURE_TRIANGLE,
            TEXTURE_CROSS, TEXTURE_CROWN, TEXTURE_DOLLAR, TEXTURE_RHOMBUS, TEXTURE_SNOWFLAKE,
            TEXTURE_SKULL, TEXTURE_CUBE, TEXTURE_SQUARE, TEXTURE_RANDOM
    };

    private final MultiModeSetting targets = register(new MultiModeSetting("Targets", "Objects rendered by predictions.",
            new String[]{"Held", "Pearls", "Arrows", "Tridents", "Items"}, "Held", "Pearls", "Arrows", "Tridents", "Items"));
    private final MultiModeSetting render = register(new MultiModeSetting("Render", "Prediction visuals.",
            new String[]{"Trajectory", "Landing", "Particles"}, "Trajectory", "Landing", "Particles"));
    private final NumberSetting range = register(new NumberSetting("Range", "Maximum prediction render distance.", 128.0, 16.0, 256.0, 1.0));
    private final NumberSetting lineWidth = register(new NumberSetting("Line Width", "Trajectory line width.", 2.0, 1, 2.5, 0.1));
    private final ModeSetting particleTexture = register(new ModeSetting("Particle Texture", "Texture used for projectile particles.", TEXTURE_GLOW, PARTICLE_TEXTURE_MODES));
    private final NumberSetting particleCount = register(new NumberSetting("Particle Count", "Particles spawned per moving projectile pulse.", 4.0, 1.0, 10.0, 1.0));
    private final NumberSetting particleSize = register(new NumberSetting("Particle Size", "Projectile particle quad size.", 0.23, 0.05, 0.75, 0.01));
    private final NumberSetting particleSpeed = register(new NumberSetting("Particle Speed", "Projectile particle scatter speed.", 0.30, 0.05, 1.5, 0.01));
    private final NumberSetting particleLifetime = register(new NumberSetting("Particle Lifetime", "Projectile particle lifetime in milliseconds.", 900.0, 250.0, 2500.0, 50.0));
    private final BooleanSetting randomParticleColor = register(new BooleanSetting("Random Particle Color", "Uses random particle colors.", false));
    private final BooleanSetting particleWhiteCenter = register(new BooleanSetting("White Center", "Draws a bright center inside projectile particles.", true));
    private final BooleanSetting particleSpin = register(new BooleanSetting("Particle Spin", "Rotates projectile particles.", true));
    private final ColorSetting lineColor = register(new ColorSetting("Line Color", "Prediction line color.", new Color(127, 242, 255, 230)));
    private final ColorSetting particleColor = register(new ColorSetting("Particle Color", "Thrown object trail color.", new Color(185, 125, 255, 215)));

    private final Random random = new Random();
    private final List<PredictionParticle> particles = new ArrayList<>();
    private final List<Vec3> simulationPoints = new ArrayList<>(181);
    private final Map<Integer, Long> lastParticleSpawnMs = new HashMap<>();
    private final Set<RenderType> usedParticleRenderTypes = new HashSet<>();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f inverseView = new Matrix4f();
    private final Vector4f unprojectScratch = new Vector4f();
    private long lastParticleUpdateMs;

    public Predictions() {
        super("Predictions", "Draws projectile and thrown item trajectories.", ModuleCategory.VISUAL);
        particleTexture.visibleWhen(() -> render.isSelected("Particles"));
        particleCount.visibleWhen(() -> render.isSelected("Particles"));
        particleSize.visibleWhen(() -> render.isSelected("Particles"));
        particleSpeed.visibleWhen(() -> render.isSelected("Particles"));
        particleLifetime.visibleWhen(() -> render.isSelected("Particles"));
        randomParticleColor.visibleWhen(() -> render.isSelected("Particles"));
        particleWhiteCenter.visibleWhen(() -> render.isSelected("Particles"));
        particleSpin.visibleWhen(() -> render.isSelected("Particles"));
        particleColor.visibleWhen(() -> render.isSelected("Particles") && !randomParticleColor.getValue());
    }

    @Override
    protected void onDisable() {
        particles.clear();
        lastParticleSpawnMs.clear();
        usedParticleRenderTypes.clear();
        lastParticleUpdateMs = 0L;
    }

    @SubscribeEvent
    private void onWorldRender(WorldRenderEvent event) {
        if (mc.player == null || mc.level == null) {
            particles.clear();
            lastParticleSpawnMs.clear();
            return;
        }
        boolean trajectoryEnabled = render.isSelected("Trajectory");
        boolean particlesEnabled = render.isSelected("Particles");
        boolean landingEnabled = render.isSelected("Landing");
        if (!trajectoryEnabled && !particlesEnabled) {
            particles.clear();
            lastParticleSpawnMs.clear();
            return;
        }

        long now = System.currentTimeMillis();
        float frameDelta = frameDelta(now);
        double simulationLimitSqr = simulationLimitSqr();
        float lineWidthValue = lineWidth.getFloat();

        if (trajectoryEnabled) {
            drawHeldPrediction(event.getPartialTicks(), simulationLimitSqr, lineWidthValue, landingEnabled);
        }

        for (Entity entity : mc.level.entitiesForRendering()) {
            ProjectileTarget target = targetFor(entity);
            if (target == null || !targets.isSelected(target.settingName())) {
                continue;
            }
            if (!isInRange(entity)) {
                continue;
            }

            Vec3 velocity = entity.getDeltaMovement();
            if (!shouldRenderLive(entity, velocity)) {
                continue;
            }

            ProjectileProfile profile = profileFor(target);
            Vec3 start = entity.getPosition(event.getPartialTicks());
            if (trajectoryEnabled) {
                boolean hit = simulate(start, velocity, profile, 180, entity, simulationLimitSqr, simulationPoints);
                drawPath(simulationPoints, hit, lineColor.getValue().getRGB(), lineWidthValue, landingEnabled);
            }
            if (particlesEnabled) {
                spawnProjectileParticles(entity, start, velocity, now);
            }
        }

        if (particlesEnabled) {
            updateParticles(frameDelta, now);
            drawParticles(event, now);
            lastParticleSpawnMs.entrySet().removeIf(entry -> now - entry.getValue() > 2000L);
        } else {
            particles.clear();
            lastParticleSpawnMs.clear();
        }
    }

    private void drawHeldPrediction(float partialTicks, double simulationLimitSqr, float lineWidthValue, boolean landingEnabled) {
        if (!targets.isSelected("Held") || mc.player == null) {
            return;
        }

        HeldProjectile held = heldProjectile(mc.player, mc.player.getMainHandItem());
        if (held == null) {
            held = heldProjectile(mc.player, mc.player.getOffhandItem());
        }
        if (held == null || !targets.isSelected(held.target().settingName())) {
            return;
        }

        AimRay ray = centerScreenRay(mc.gameRenderer.getMainCamera());
        if (ray == null || ray.direction().lengthSqr() < 1.0E-6D) {
            return;
        }

        Vec3 start = ray.origin().add(ray.direction().scale(0.05D));
        Vec3 velocity = ray.direction().scale(held.speed());
        boolean hit = simulate(start, velocity, profileFor(held.target()), 180, mc.player, simulationLimitSqr, simulationPoints);
        drawPath(simulationPoints, hit, lineColor.getValue().getRGB(), lineWidthValue, landingEnabled);
    }

    private AimRay centerScreenRay(Camera camera) {
        Vec3 cameraPos = Render3D.lastCameraPos;
        if (cameraPos == null || !isFinite(cameraPos)) {
            cameraPos = camera.position();
        }

        AimRay matrixRay = centerScreenRayFromMatrices(cameraPos);
        if (matrixRay != null) {
            return matrixRay;
        }

        Vec3 direction = directionFromRotation(camera.xRot(), camera.yRot());
        return isFinite(direction) && direction.lengthSqr() > 1.0E-6D ? new AimRay(cameraPos, direction) : null;
    }

    private AimRay centerScreenRayFromMatrices(Vec3 cameraPos) {
        try {
            inverseProjection.set(Render3D.lastProjMat).invert();
            inverseView.set(Render3D.lastModMat).invert();
            Vec3 near = unprojectCenter(inverseProjection, inverseView, -1.0F);
            Vec3 far = unprojectCenter(inverseProjection, inverseView, 1.0F);
            if (!isFinite(near) || !isFinite(far)) {
                return null;
            }

            Vec3 direction = far.subtract(near);
            if (direction.lengthSqr() < 1.0E-8D) {
                return null;
            }
            return new AimRay(cameraPos.add(near), direction.normalize());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Vec3 unprojectCenter(Matrix4f inverseProjection, Matrix4f inverseView, float z) {
        Vector4f point = unprojectScratch.set(0.0F, 0.0F, z, 1.0F);
        inverseProjection.transform(point);
        if (Math.abs(point.w) > 1.0E-6F) {
            point.div(point.w);
        }
        inverseView.transform(point);
        if (Math.abs(point.w) > 1.0E-6F) {
            point.div(point.w);
        }
        return new Vec3(point.x, point.y, point.z);
    }

    private Vec3 directionFromRotation(float pitch, float yaw) {
        float pitchRad = pitch * Mth.DEG_TO_RAD;
        float yawRad = -yaw * Mth.DEG_TO_RAD;
        float cosPitch = Mth.cos(pitchRad);
        return new Vec3(
                Mth.sin(yawRad) * cosPitch,
                -Mth.sin(pitchRad),
                Mth.cos(yawRad) * cosPitch
        ).normalize();
    }

    private HeldProjectile heldProjectile(Player player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() == Items.ENDER_PEARL) {
            return new HeldProjectile(ProjectileTarget.PEARLS, 1.5D);
        }
        if (stack.getItem() instanceof BowItem) {
            if (!isUsing(player, stack)) {
                return null;
            }
            float power = BowItem.getPowerForTime(player.getTicksUsingItem());
            return power > 0.1F ? new HeldProjectile(ProjectileTarget.ARROWS, power * 3.0D) : null;
        }
        if (stack.getItem() instanceof CrossbowItem) {
            if (CrossbowItem.isCharged(stack)) {
                return new HeldProjectile(ProjectileTarget.ARROWS, 3.15D);
            }
            if (!isUsing(player, stack)) {
                return null;
            }
            float charge = (float) player.getTicksUsingItem() / (float) CrossbowItem.getChargeDuration(stack, player);
            return charge > 0.1F ? new HeldProjectile(ProjectileTarget.ARROWS, 3.15D * Mth.clamp(charge, 0.0F, 1.0F)) : null;
        }
        if (stack.getItem() instanceof TridentItem) {
            if (!isUsing(player, stack)) {
                return null;
            }
            float charge = Mth.clamp(player.getTicksUsingItem() / 10.0F, 0.0F, 1.0F);
            return charge > 0.1F ? new HeldProjectile(ProjectileTarget.TRIDENTS, 2.5D * charge) : null;
        }
        return null;
    }

    private boolean isUsing(Player player, ItemStack stack) {
        return player.isUsingItem() && !player.getUseItem().isEmpty() && player.getUseItem().getItem() == stack.getItem();
    }

    private boolean simulate(Vec3 start, Vec3 velocity, ProjectileProfile profile, int maxTicks, Entity collider, double simulationLimitSqr, List<Vec3> points) {
        points.clear();
        points.add(start);

        Vec3 position = start;
        Vec3 motion = velocity;
        boolean hit = false;
        for (int i = 0; i < maxTicks; i++) {
            Vec3 next = position.add(motion);
            BlockHitResult blockHit = mc.level.clip(new ClipContext(position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, collider));
            if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
                points.add(blockHit.getLocation());
                hit = true;
                break;
            }

            points.add(next);
            position = next;
            motion = motion.scale(profile.drag()).add(0.0D, -profile.gravity(), 0.0D);
            if (position.y < -128.0D || position.distanceToSqr(start) > simulationLimitSqr) {
                break;
            }
        }
        return hit;
    }

    private void drawPath(List<Vec3> points, boolean hit, int color, float lineWidthValue, boolean landingEnabled) {
        if (points.size() < 2) {
            return;
        }

        int count = points.size() - 1;
        for (int i = 1; i < points.size(); i++) {
            float startAlpha = 1.0F - ((float) (i - 1) / (float) count) * 0.55F;
            float endAlpha = 1.0F - ((float) i / (float) count) * 0.55F;
            Render3D.drawLineGradient(
                    points.get(i - 1),
                    points.get(i),
                    ColorUtil.multAlpha(color, startAlpha),
                    ColorUtil.multAlpha(color, endAlpha),
                    lineWidthValue,
                    false
            );
        }

        if (landingEnabled && hit) {
            Vec3 end = points.get(points.size() - 1);
            double size = 0.11D;
            AABB marker = new AABB(end.x - size, end.y - 0.015D, end.z - size, end.x + size, end.y + 0.015D, end.z + size);
            Render3D.drawBox(marker, ColorUtil.multAlpha(color, 0.65F), 1.0F, true, true, false);
            Render3D.drawBoxOverlay(marker, ColorUtil.multAlpha(color, 0.85F), 1.25F);
        }
    }

    private double simulationLimitSqr() {
        double value = range.getFloat();
        return value * value * 4.0D;
    }

    private float frameDelta(long now) {
        if (lastParticleUpdateMs == 0L) {
            lastParticleUpdateMs = now;
            return 1.0F;
        }
        long elapsed = Math.max(1L, Math.min(50L, now - lastParticleUpdateMs));
        lastParticleUpdateMs = now;
        return Mth.clamp(elapsed / 16.6667F, 0.25F, 3.0F);
    }

    private void spawnProjectileParticles(Entity entity, Vec3 position, Vec3 velocity, long now) {
        long last = lastParticleSpawnMs.getOrDefault(entity.getId(), 0L);
        if (now - last < PARTICLE_SPAWN_INTERVAL_MS) {
            return;
        }
        lastParticleSpawnMs.put(entity.getId(), now);

        int count = Math.max(1, (int) Math.round(particleCount.getValue()));
        double spread = Math.max(0.12D, entity.getBbWidth() * 0.8D);
        double height = Math.max(0.12D, entity.getBbHeight());
        double motionRange = particleSpeed.getFloat() / 18.0D;
        for (int i = 0; i < count; i++) {
            Vec3 spawn = position.add(
                    randomRange(-spread, spread),
                    random.nextDouble() * height,
                    randomRange(-spread, spread)
            );
            Vec3 motion = new Vec3(
                    randomRange(-motionRange, motionRange),
                    randomRange(-motionRange, motionRange),
                    randomRange(-motionRange, motionRange)
            ).add(velocity.scale(-0.018D));
            particles.add(new PredictionParticle(
                    particleTexture(),
                    spawn,
                    motion,
                    now,
                    Math.round(particleLifetime.getFloat()),
                    particleBaseColor(),
                    random.nextFloat() * 360.0F,
                    randomRange(-5.0D, 5.0D),
                    0.78F + random.nextFloat() * 0.5F
            ));
        }
        int overflow = particles.size() - MAX_PARTICLES;
        if (overflow > 0) {
            particles.subList(0, overflow).clear();
        }
    }

    private void updateParticles(float frameDelta, long now) {
        if (particles.isEmpty()) {
            return;
        }
        particles.removeIf(particle -> particle.dead(now));
        for (PredictionParticle particle : particles) {
            particle.update(frameDelta);
        }
    }

    private void drawParticles(WorldRenderEvent event, long now) {
        if (particles.isEmpty() || mc.gameRenderer == null) {
            return;
        }

        PoseStack stack = event.getStack();
        MultiBufferSource.BufferSource provider = mc.renderBuffers().bufferSource();
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
        Quaternionf cameraRotation = mc.gameRenderer.getMainCamera().rotation();
        usedParticleRenderTypes.clear();
        for (PredictionParticle particle : particles) {
            renderParticle(stack, provider, cameraPos, cameraRotation, particle, now);
        }
        for (RenderType type : usedParticleRenderTypes) {
            provider.endBatch(type);
        }
        usedParticleRenderTypes.clear();
    }

    private void renderParticle(PoseStack stack, MultiBufferSource.BufferSource provider, Vec3 cameraPos, Quaternionf cameraRotation, PredictionParticle particle, long now) {
        float alpha = particle.alpha(now);
        if (alpha <= 0.01F) {
            return;
        }
        float size = particleSize.getFloat() * particle.scale() * (0.55F + alpha * 0.45F);
        int color = ColorUtil.multAlpha(particle.color(), alpha);
        float rotation = particleSpin.getValue() ? particle.rotation() : 3.15F;
        drawBillboard(stack, provider, particle.texture(), particle.position(), cameraPos, cameraRotation, size, rotation, color);
        if (particleWhiteCenter.getValue()) {
            drawBillboard(stack, provider, particle.texture(), particle.position(), cameraPos, cameraRotation, size * 0.42F, rotation, ColorUtil.rgba(255, 255, 255, Math.round(230.0F * alpha)));
        }
    }

    private void drawBillboard(PoseStack stack, MultiBufferSource.BufferSource provider, Identifier texture, Vec3 pos, Vec3 cameraPos, Quaternionf cameraRotation, float size, float rotation, int color) {
        if (texture == null || size <= 0.001F || ColorUtil.getAlpha(color) <= 0) {
            return;
        }
        RenderType renderType = ClientPipelines.WORLD_PARTICLES_GLOW.apply(texture);
        usedParticleRenderTypes.add(renderType);
        VertexConsumer consumer = provider.getBuffer(renderType);

        stack.pushPose();
        stack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
        stack.mulPose(cameraRotation);
        stack.mulPose(Axis.ZP.rotationDegrees(rotation));
        PoseStack.Pose pose = stack.last();
        float half = size * 0.5F;
        WorldVertex.textured(consumer, pose, -half, -half, 0.0F, 0.0F, 0.0F, color);
        WorldVertex.textured(consumer, pose, half, -half, 0.0F, 1.0F, 0.0F, color);
        WorldVertex.textured(consumer, pose, half, half, 0.0F, 1.0F, 1.0F, color);
        WorldVertex.textured(consumer, pose, -half, half, 0.0F, 0.0F, 1.0F, color);
        stack.popPose();
    }

    private Identifier particleTexture() {
        String mode = particleTexture.getValue();
        if (TEXTURE_RANDOM.equals(mode)) {
            mode = PARTICLE_TEXTURE_MODES[random.nextInt(PARTICLE_TEXTURE_MODES.length - 1)];
        }
        return switch (mode) {
            case TEXTURE_STAR -> PARTICLE_STAR;
            case TEXTURE_HEART -> PARTICLE_HEART;
            case TEXTURE_LIGHTNING -> PARTICLE_LIGHTNING;
            case TEXTURE_TRIANGLE -> PARTICLE_TRIANGLE;
            case TEXTURE_CROSS -> PARTICLE_CROSS;
            case TEXTURE_CROWN -> PARTICLE_CROWN;
            case TEXTURE_DOLLAR -> PARTICLE_DOLLAR;
            case TEXTURE_RHOMBUS, TEXTURE_SQUARE -> PARTICLE_SQUARE;
            case TEXTURE_SNOWFLAKE -> PARTICLE_SNOWFLAKE;
            case TEXTURE_SKULL -> PARTICLE_SKULL;
            case TEXTURE_CUBE -> PARTICLE_CUBE;
            default -> PARTICLE_GLOW;
        };
    }

    private int particleBaseColor() {
        if (randomParticleColor.getValue()) {
            return Color.HSBtoRGB(random.nextFloat(), 0.78F, 1.0F) | 0xFF000000;
        }
        return particleColor.getValue().getRGB();
    }

    private double randomRange(double min, double max) {
        return min + (max - min) * random.nextDouble();
    }

    private boolean isFinite(Vec3 pos) {
        return pos != null
                && Double.isFinite(pos.x)
                && Double.isFinite(pos.y)
                && Double.isFinite(pos.z);
    }

    private boolean shouldRenderLive(Entity entity, Vec3 velocity) {
        if (entity instanceof ItemEntity item) {
            return !isGroundedItem(item, velocity) && velocity.lengthSqr() > 1.0E-5D;
        }
        return velocity.lengthSqr() > 1.0E-5D;
    }

    private boolean isGroundedItem(ItemEntity item, Vec3 velocity) {
        if (item.onGround()) {
            return true;
        }
        if (mc.level == null) {
            return false;
        }

        AABB box = item.getBoundingBox();
        Vec3 from = new Vec3(item.getX(), box.minY + 0.08D, item.getZ());
        Vec3 to = new Vec3(item.getX(), box.minY - 0.16D, item.getZ());
        BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, item));
        boolean blockUnderItem = hit != null && hit.getType() != HitResult.Type.MISS;
        return blockUnderItem && velocity.y > -0.08D;
    }

    private boolean isInRange(Entity entity) {
        float maxRange = range.getFloat();
        return maxRange <= 0.0F || mc.player == null || mc.player.distanceToSqr(entity) <= maxRange * maxRange;
    }

    private ProjectileTarget targetFor(Entity entity) {
        if (entity instanceof ThrownEnderpearl) {
            return ProjectileTarget.PEARLS;
        }
        if (entity instanceof ThrownTrident) {
            return ProjectileTarget.TRIDENTS;
        }
        if (entity instanceof AbstractArrow) {
            return ProjectileTarget.ARROWS;
        }
        if (entity instanceof ItemEntity item && !item.getItem().isEmpty()) {
            return ProjectileTarget.ITEMS;
        }
        return null;
    }

    private ProjectileProfile profileFor(ProjectileTarget target) {
        return switch (target) {
            case PEARLS -> PROFILE_PEARL;
            case ITEMS -> PROFILE_ITEM;
            case ARROWS, TRIDENTS -> PROFILE_ARROW;
        };
    }

    private enum ProjectileTarget {
        PEARLS("Pearls"),
        ARROWS("Arrows"),
        TRIDENTS("Tridents"),
        ITEMS("Items");

        private final String settingName;

        ProjectileTarget(String settingName) {
            this.settingName = settingName;
        }

        private String settingName() {
            return settingName;
        }
    }

    private record HeldProjectile(ProjectileTarget target, double speed) {
    }

    private record ProjectileProfile(double drag, double gravity) {
    }

    private record AimRay(Vec3 origin, Vec3 direction) {
    }

    private static final class PredictionParticle {
        private final Identifier texture;
        private Vec3 position;
        private Vec3 motion;
        private final long spawnMs;
        private final long lifetimeMs;
        private final int color;
        private float rotation;
        private final float rotationSpeed;
        private final float scale;

        private PredictionParticle(Identifier texture, Vec3 position, Vec3 motion, long spawnMs, long lifetimeMs, int color, float rotation, double rotationSpeed, float scale) {
            this.texture = texture;
            this.position = position;
            this.motion = motion;
            this.spawnMs = spawnMs;
            this.lifetimeMs = Math.max(1L, lifetimeMs);
            this.color = color;
            this.rotation = rotation;
            this.rotationSpeed = (float) rotationSpeed;
            this.scale = scale;
        }

        private void update(float frameDelta) {
            position = position.add(motion.scale(frameDelta));
            motion = motion.scale(Math.pow(0.985D, frameDelta)).add(0.0D, -0.0018D * frameDelta, 0.0D);
            rotation += rotationSpeed * frameDelta;
        }

        private boolean dead(long now) {
            return now - spawnMs >= lifetimeMs;
        }

        private float alpha(long now) {
            float progress = Mth.clamp((float) (now - spawnMs) / (float) lifetimeMs, 0.0F, 1.0F);
            if (progress < 0.18F) {
                return progress / 0.18F;
            }
            if (progress > 0.62F) {
                float out = (progress - 0.62F) / 0.38F;
                return 1.0F - out * out * (3.0F - 2.0F * out);
            }
            return 1.0F;
        }

        private Identifier texture() {
            return texture;
        }

        private Vec3 position() {
            return position;
        }

        private int color() {
            return color;
        }

        private float rotation() {
            return rotation;
        }

        private float scale() {
            return scale;
        }
    }
}
