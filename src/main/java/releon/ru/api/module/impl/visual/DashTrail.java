package releon.ru.api.module.impl.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.WorldVertex;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.pipeline.ClientPipelines;
import releon.ru.utils.repository.friend.FriendUtils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class DashTrail extends Module {
    private static final String ROOT = "textures/features/dashtrail/";
    private static final Identifier BLOOM_TEXTURE = texture("dashbloomsample.png");
    private static final Identifier DOT_TEXTURE = texture("dashbloom.png");
    private static final float BASE_PIXEL_SCALE = 0.0033f;
    private static final double MIN_DASH_SPEED = 0.08D;
    private static final int MAX_CUBICS = 420;
    private static final int CLIENT_COLOR_FIRST = ColorUtil.rgba(127, 242, 255, 255);
    private static final int CLIENT_COLOR_SECOND = ColorUtil.rgba(255, 50, 150, 255);

    private static final TextureInfo[] STATIC_TEXTURES = new TextureInfo[]{
            cubic(1, 48, 48),
            cubic(2, 48, 48),
            cubic(3, 48, 48),
            cubic(4, 96, 48),
            cubic(5, 96, 48),
            cubic(6, 96, 48),
            cubic(7, 96, 48),
            cubic(8, 96, 48),
            cubic(9, 96, 48),
            cubic(10, 96, 48),
            cubic(11, 96, 48),
            cubic(12, 96, 48),
            cubic(13, 96, 96),
            cubic(14, 96, 96),
            cubic(15, 96, 96),
            cubic(16, 96, 96),
            cubic(17, 44, 48),
            cubic(18, 44, 48),
            cubic(19, 44, 48),
            cubic(20, 44, 48),
            cubic(21, 44, 48)
    };

    private static final TextureInfo[][] ANIMATED_TEXTURES = new TextureInfo[][]{
            group(1, 11, 144, 48),
            group(2, 23, 48, 48),
            group(3, 32, 96, 48),
            group(4, 16, 48, 48),
            group(5, 32, 48, 48)
    };

    private final Random random = new Random(1234567891L);
    private final List<DashCubic> dashCubics = new ArrayList<>();
    private final Map<Integer, Vec3> lastPositions = new HashMap<>();
    private final Set<RenderType> usedRenderTypes = new HashSet<>();
    private final Set<Integer> seenPlayerIds = new HashSet<>();
    private final BooleanSetting self = register(new BooleanSetting("Self", "Draw dash trail for you.", true));
    private final BooleanSetting players = register(new BooleanSetting("Players", "Draw dash trail for other players.", false));
    private final BooleanSetting friends = register(new BooleanSetting("Friends", "Draw dash trail for friends.", true));
    private final ModeSetting colorMode = register(new ModeSetting("Color Mode", "Dash trail color mode.", "Random Palette", "Random Palette", "Custom", "Client", "Rainbow"));
    private final ColorSetting pickColor = register(new ColorSetting("Pick Color", "Custom dash trail color.", new Color(170, 40, 255, 255)));
    private final NumberSetting maxDistance = register(new NumberSetting("Max Distance", "Maximum distance to render other players.", 25.0, 15.0, 100.0, 1.0));
    private final BooleanSetting motionSmoothing = register(new BooleanSetting("Motion Smoothing", "Smooths dash pieces through the chain.", false));
    private final BooleanSetting dashSegments = register(new BooleanSetting("Dash Segments", "Adds small glowing line segments.", false));
    private final BooleanSetting dashDots = register(new BooleanSetting("Dash Dots", "Adds spark dots around dash pieces.", true));
    private final BooleanSetting lighting = register(new BooleanSetting("Lighting", "Adds bloom lighting around dash pieces.", true));
    private final NumberSetting dashLength = register(new NumberSetting("Dash Length", "Dash piece lifetime multiplier.", 0.75, 0.5, 1.5, 0.05));

    public DashTrail() {
        super("Dash Trail", "Draws Vega-style dash trail particles behind moving players.", ModuleCategory.VISUAL);
        colorMode.visibleWhen(this::hasAnyTargetEnabled);
        pickColor.visibleWhen(() -> colorMode.is("Custom") && hasAnyTargetEnabled());
        maxDistance.visibleWhen(() -> players.getValue() || friends.getValue());
        motionSmoothing.visibleWhen(this::hasAnyTargetEnabled);
        dashSegments.visibleWhen(this::hasAnyTargetEnabled);
        dashDots.visibleWhen(this::hasAnyTargetEnabled);
        lighting.visibleWhen(this::hasAnyTargetEnabled);
        dashLength.visibleWhen(this::hasAnyTargetEnabled);
    }

    @Override
    protected void onDisable() {
        dashCubics.clear();
        lastPositions.clear();
        usedRenderTypes.clear();
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            dashCubics.clear();
            lastPositions.clear();
            return;
        }

        if (hasAnyTargetEnabled()) {
            seenPlayerIds.clear();
            for (Player player : client.level.players()) {
                if (!isTarget(player, client)) {
                    continue;
                }
                seenPlayerIds.add(player.getId());
                trackPlayer(player);
            }
            lastPositions.keySet().removeIf(id -> !seenPlayerIds.contains(id));
        } else {
            lastPositions.clear();
        }

        tickCubics();
    }

    @SubscribeEvent
    private void onWorldRender(WorldRenderEvent event) {
        long now = System.currentTimeMillis();
        dashCubics.removeIf(cubic -> cubic.isDead(now));
        if (dashCubics.isEmpty() || mc.gameRenderer == null) {
            return;
        }

        PoseStack stack = event.getStack();
        MultiBufferSource.BufferSource provider = mc.renderBuffers().bufferSource();
        Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
        Quaternionf cameraRotation = mc.gameRenderer.getMainCamera().rotation();
        float tickDelta = event.getPartialTicks();
        boolean renderDots = dashDots.getValue();
        boolean renderSegments = dashSegments.getValue();
        usedRenderTypes.clear();

        for (DashCubic cubic : dashCubics) {
            float progress = cubic.progress(now);
            float alpha = dashAlpha(progress);
            if (alpha <= 0.02f) {
                continue;
            }

            Vec3 pos = cubic.renderPos(tickDelta);
            TextureInfo texture = cubic.texture.get(now);
            float sizeAlpha = easeOutQuad(alpha);
            float width = texture.width() * BASE_PIXEL_SCALE * cubic.scale * sizeAlpha;
            float height = texture.height() * BASE_PIXEL_SCALE * cubic.scale * sizeAlpha;
            int mainColor = ColorUtil.multAlpha(ColorUtil.lerpColor(cubic.color, ColorUtil.WHITE, 0.34f), alpha * 0.92f);
            renderBillboard(stack, provider, ClientPipelines.GHOSTS_ESP.apply(texture.id()), pos, cameraPos, cameraRotation, width, height, cubic.rotation, mainColor);

            renderBloom(stack, provider, pos, cameraPos, cameraRotation, width, height, cubic.color, alpha, progress);
            if (renderDots || renderSegments) {
                renderSparks(stack, provider, cubic, pos, cameraPos, cameraRotation, tickDelta, alpha, renderDots, renderSegments);
            }
        }

        for (RenderType type : usedRenderTypes) {
            provider.endBatch(type);
        }
        usedRenderTypes.clear();
    }

    private void trackPlayer(Player player) {
        Vec3 current = player.position();
        Vec3 previous = lastPositions.put(player.getId(), current);
        if (previous == null) {
            return;
        }

        Vec3 delta = current.subtract(previous);
        double speedXZ = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (speedXZ < MIN_DASH_SPEED || delta.lengthSqr() > 36.0D) {
            return;
        }

        int count = Mth.clamp((int) (delta.length() / MIN_DASH_SPEED), 1, 16);
        boolean addSparks = dashSegments.getValue() || dashDots.getValue();
        for (int i = 0; i < count; i++) {
            float offset = count == 1 ? 0.0f : (float) i / (float) count;
            spawnCubic(player, current, delta, offset, addSparks);
        }
        trimCubics();
    }

    private void spawnCubic(Player player, Vec3 current, Vec3 delta, float offset, boolean addSparks) {
        double height = player.getBbHeight();
        double x = current.x - delta.x * offset + randomRange(0.175D) - 0.0875D;
        double y = current.y - delta.y * offset + height / 3.0D + height / 4.0D * random.nextDouble() * 0.7D;
        double z = current.z - delta.z * offset + randomRange(0.175D) - 0.0875D;
        Vec3 position = new Vec3(x, y, z);
        Vec3 motion = delta.scale(0.04D);
        DashTexture texture = DashTexture.random(random, getRandomTimeAnimation());
        int lifetime = getRandomTimeAnimation();
        int color = nextDashColor();
        float scale = 0.86f + random.nextFloat() * 0.34f;
        dashCubics.add(new DashCubic(player.getId(), position, motion, texture, lifetime, color, scale, addSparks, random));
    }

    private void tickCubics() {
        long now = System.currentTimeMillis();
        boolean addSegments = dashSegments.getValue();
        boolean addDots = dashDots.getValue();
        for (int i = 0; i < dashCubics.size(); i++) {
            DashCubic current = dashCubics.get(i);
            DashCubic next = motionSmoothing.getValue() && i + 1 < dashCubics.size() && dashCubics.get(i + 1).entityId == current.entityId
                    ? dashCubics.get(i + 1)
                    : null;
            current.tick(next, random, addSegments, addDots, now);
        }
        dashCubics.removeIf(cubic -> cubic.isDead(now));
        trimCubics();
    }

    private void renderBloom(PoseStack stack, MultiBufferSource.BufferSource provider, Vec3 pos, Vec3 cameraPos, Quaternionf cameraRotation, float width, float height, int color, float alpha, float progress) {
        float diagonal = (float) Math.sqrt(width * width + height * height);
        int softColor = ColorUtil.multAlpha(ColorUtil.lerpColor(color, ColorUtil.WHITE, 0.18f), alpha * 0.13f);
        renderBillboard(stack, provider, ClientPipelines.BLOOM_ESP.apply(BLOOM_TEXTURE), pos, cameraPos, cameraRotation, diagonal * 0.58f, diagonal * 0.58f, 0.0f, softColor);

        if (!lighting.getValue()) {
            return;
        }
        float fadeOut = 1.0f - progress;
        float glowScale = 1.05f + 1.85f * fadeOut * alpha;
        int glowColor = ColorUtil.multAlpha(ColorUtil.lerpColor(color, ColorUtil.WHITE, 0.10f), alpha * 0.18f);
        renderBillboard(stack, provider, ClientPipelines.BLOOM_ESP.apply(BLOOM_TEXTURE), pos, cameraPos, cameraRotation, diagonal * glowScale, diagonal * glowScale, 0.0f, glowColor);
    }

    private void renderSparks(PoseStack stack, MultiBufferSource.BufferSource provider, DashCubic cubic, Vec3 center, Vec3 cameraPos, Quaternionf cameraRotation, float tickDelta, float cubicAlpha, boolean renderDots, boolean renderSegments) {
        for (DashSpark spark : cubic.sparks) {
            float sparkAlpha = easeInOutWave((float) spark.alpha(nowMs()) * cubicAlpha);
            if (sparkAlpha <= 0.01f) {
                continue;
            }
            Vec3 offset = spark.renderPos(tickDelta);
            Vec3 first = center.add(offset);
            Vec3 second = center.subtract(offset);
            int sparkColor = ColorUtil.multAlpha(ColorUtil.lerpColor(cubic.color, ColorUtil.WHITE, 0.45f), sparkAlpha * 0.72f);

            if (renderDots) {
                float size = 0.052f + 0.07f * sparkAlpha;
                renderBillboard(stack, provider, ClientPipelines.WORLD_PARTICLES_GLOW.apply(DOT_TEXTURE), first, cameraPos, cameraRotation, size, size, 0.0f, sparkColor);
                renderBillboard(stack, provider, ClientPipelines.WORLD_PARTICLES_GLOW.apply(DOT_TEXTURE), second, cameraPos, cameraRotation, size, size, 0.0f, sparkColor);
            }
            if (renderSegments) {
                int endColor = ColorUtil.withAlpha(sparkColor, 0);
                Render3D.drawLineGradient(first, second, sparkColor, endColor, 2.0f, false);
            }
        }
    }

    private void renderBillboard(PoseStack stack, MultiBufferSource.BufferSource provider, RenderType renderType, Vec3 pos, Vec3 cameraPos, Quaternionf cameraRotation, float width, float height, float rotation, int color) {
        if (width <= 0.001f || height <= 0.001f || ColorUtil.getAlpha(color) <= 0) {
            return;
        }
        usedRenderTypes.add(renderType);
        VertexConsumer consumer = provider.getBuffer(renderType);

        stack.pushPose();
        stack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
        stack.mulPose(cameraRotation);
        stack.mulPose(Axis.ZP.rotationDegrees(rotation));
        PoseStack.Pose pose = stack.last();
        float halfWidth = width / 2.0f;
        float halfHeight = height / 2.0f;
        WorldVertex.textured(consumer, pose, -halfWidth, -halfHeight, 0.0f, 0.0f, 0.0f, color);
        WorldVertex.textured(consumer, pose, halfWidth, -halfHeight, 0.0f, 1.0f, 0.0f, color);
        WorldVertex.textured(consumer, pose, halfWidth, halfHeight, 0.0f, 1.0f, 1.0f, color);
        WorldVertex.textured(consumer, pose, -halfWidth, halfHeight, 0.0f, 0.0f, 1.0f, color);
        stack.popPose();
    }

    private boolean isTarget(Player player, Minecraft client) {
        if (player == null || !player.isAlive()) {
            return false;
        }
        if (player == client.player) {
            return self.getValue();
        }
        double maxDistanceValue = maxDistance.getValue();
        if (client.player.distanceToSqr(player) > maxDistanceValue * maxDistanceValue) {
            return false;
        }
        boolean friend = FriendUtils.isFriend(player);
        return friend ? friends.getValue() : players.getValue();
    }

    private boolean hasAnyTargetEnabled() {
        return self.getValue() || players.getValue() || friends.getValue();
    }

    private int getRandomTimeAnimation() {
        return Math.round((550 + random.nextInt(300)) * dashLength.getFloat());
    }

    private int nextDashColor() {
        if (colorMode.is("Random Palette")) {
            return Color.getHSBColor(random.nextFloat(), 0.95f, 1.0f).getRGB();
        }
        if (colorMode.is("Custom")) {
            return pickColor.getValue().getRGB();
        }
        if (colorMode.is("Client")) {
            float wave = (Mth.sin(System.currentTimeMillis() / 520.0f) + 1.0f) / 2.0f;
            return ColorUtil.lerpColor(CLIENT_COLOR_FIRST, CLIENT_COLOR_SECOND, wave);
        }
        float hue = (System.currentTimeMillis() % 1600L) / 1600.0f;
        return Color.getHSBColor(hue, 0.82f, 1.0f).getRGB();
    }

    private void trimCubics() {
        int overflow = dashCubics.size() - MAX_CUBICS;
        if (overflow > 0) {
            dashCubics.subList(0, overflow).clear();
        }
    }

    private double randomRange(double range) {
        return random.nextDouble() * range;
    }

    private static float dashAlpha(float progress) {
        float clamped = Mth.clamp(progress, 0.0f, 1.0f);
        if (clamped < 0.08f) {
            return clamped / 0.08f;
        }
        if (clamped > 0.72f) {
            return 1.0f - (clamped - 0.72f) / 0.28f;
        }
        return 1.0f;
    }

    private static float easeOutQuad(float value) {
        float clamped = Mth.clamp(value, 0.0f, 1.0f);
        return 1.0f - (1.0f - clamped) * (1.0f - clamped);
    }

    private static float easeInOutWave(float value) {
        float clamped = Mth.clamp(value, 0.0f, 1.0f);
        return clamped <= 0.5f ? 2.0f * clamped * clamped : 1.0f - (float) Math.pow(-2.0f * clamped + 2.0f, 2.0f) / 2.0f;
    }

    private static long nowMs() {
        return System.currentTimeMillis();
    }

    private static Identifier texture(String path) {
        return Identifier.fromNamespaceAndPath("releon", ROOT + path);
    }

    private static TextureInfo cubic(int number, int width, int height) {
        return new TextureInfo(texture("dashcubics/dashcubic" + number + ".png"), width, height);
    }

    private static TextureInfo[] group(int group, int count, int width, int height) {
        TextureInfo[] textures = new TextureInfo[count];
        for (int i = 0; i < count; i++) {
            textures[i] = new TextureInfo(texture("dashcubics/group_dashs/group" + group + "/dashcubic" + (i + 1) + ".png"), width, height);
        }
        return textures;
    }

    private record TextureInfo(Identifier id, int width, int height) {
    }

    private static final class DashTexture {
        private final TextureInfo[] textures;
        private final long startTime;
        private final long animationTime;

        private DashTexture(TextureInfo[] textures, long startTime, long animationTime) {
            this.textures = textures;
            this.startTime = startTime;
            this.animationTime = Math.max(1L, animationTime);
        }

        private static DashTexture random(Random random, long animationTime) {
            if (random.nextInt(100) > 40) {
                return new DashTexture(ANIMATED_TEXTURES[random.nextInt(ANIMATED_TEXTURES.length)], System.currentTimeMillis(), animationTime);
            }
            return new DashTexture(new TextureInfo[]{STATIC_TEXTURES[random.nextInt(STATIC_TEXTURES.length)]}, System.currentTimeMillis(), animationTime);
        }

        private TextureInfo get(long now) {
            if (textures.length <= 1) {
                return textures[0];
            }
            long elapsed = Math.floorMod(now - startTime, animationTime);
            int index = Mth.clamp((int) ((elapsed / (float) animationTime) * textures.length), 0, textures.length - 1);
            return textures[index];
        }
    }

    private static final class DashCubic {
        private final int entityId;
        private final DashTexture texture;
        private final long startTime = System.currentTimeMillis();
        private final int lifetime;
        private final int color;
        private final float scale;
        private final boolean addSparks;
        private final List<DashSpark> sparks = new ArrayList<>();
        private Vec3 pos;
        private Vec3 prevPos;
        private Vec3 motion;
        private float rotation;

        private DashCubic(int entityId, Vec3 pos, Vec3 motion, DashTexture texture, int lifetime, int color, float scale, boolean addSparks, Random random) {
            this.entityId = entityId;
            this.pos = pos;
            this.prevPos = pos;
            this.motion = motion;
            this.texture = texture;
            this.lifetime = Math.max(1, lifetime);
            this.color = color;
            this.scale = scale;
            this.addSparks = addSparks;
            updateRotation(random);
        }

        private void tick(DashCubic next, Random random, boolean addSegments, boolean addDots, long now) {
            prevPos = pos;
            Vec3 sourceMotion = next != null ? next.motion : motion;
            motion = sourceMotion.scale(1.0D / 1.05D);
            double yDivider = motion.y < 0.0D ? 1.0D : 3.5D;
            pos = pos.add(motion.x * 5.0D, motion.y * 5.0D / yDivider, motion.z * 5.0D);
            updateRotation(random);

            if (addSparks && (addSegments || addDots) && progress(now) < 0.3f && random.nextInt(12) > 5) {
                int amount = addSegments ? 1 : 2;
                for (int i = 0; i < amount; i++) {
                    sparks.add(new DashSpark(random));
                }
            }
            if (addSparks && (addSegments || addDots)) {
                for (DashSpark spark : sparks) {
                    spark.tick();
                }
                sparks.removeIf(DashSpark::isDead);
            } else {
                sparks.clear();
            }
        }

        private Vec3 renderPos(float tickDelta) {
            return prevPos.lerp(pos, Mth.clamp(tickDelta, 0.0f, 1.0f));
        }

        private float progress(long now) {
            return Mth.clamp((now - startTime) / (float) lifetime, 0.0f, 1.0f);
        }

        private boolean isDead(long now) {
            if (progress(now) < 1.0f) {
                return false;
            }
            for (DashSpark spark : sparks) {
                if (!spark.isDead()) {
                    return false;
                }
            }
            return true;
        }

        private void updateRotation(Random random) {
            double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            if (horizontal < 5.0E-4D) {
                rotation = random.nextFloat() * 360.0f;
                return;
            }
            float motionYaw = (float) Math.toDegrees(Math.atan2(motion.z, motion.x) - Math.PI / 2.0D);
            if (motionYaw < 0.0f) {
                motionYaw += 360.0f;
            }
            rotation = motionYaw - 60.0f;
        }
    }

    private static final class DashSpark {
        private final double speed;
        private final double yaw;
        private final double pitch;
        private final long startTime = System.currentTimeMillis();
        private Vec3 pos = Vec3.ZERO;
        private Vec3 prevPos = Vec3.ZERO;

        private DashSpark(Random random) {
            speed = random.nextDouble() / 50.0D;
            yaw = Math.toRadians(random.nextDouble() * 360.0D);
            pitch = Math.toRadians(-180.0D + random.nextDouble() * 180.0D);
        }

        private void tick() {
            prevPos = pos;
            pos = pos.add(Math.sin(yaw) * speed, Math.cos(pitch) * speed, Math.cos(yaw) * speed);
        }

        private Vec3 renderPos(float tickDelta) {
            return prevPos.lerp(pos, Mth.clamp(tickDelta, 0.0f, 1.0f));
        }

        private double alpha(long now) {
            return 1.0D - Mth.clamp((now - startTime) / 1000.0D, 0.0D, 1.0D);
        }

        private boolean isDead() {
            return alpha(System.currentTimeMillis()) <= 0.0D;
        }
    }
}
