package releon.ru.api.module.impl.visual.frageffect;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;
import releon.ru.utils.render.FullscreenQuadBuffer;
import releon.ru.utils.render.RenderSampler;
import releon.ru.utils.render.color.ColorUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.OptionalInt;

public final class FragEffectScanRenderer {
    private static final int MAX_ACTIVE_WAVES = 4;
    private static final int UNIFORM_SIZE = 256;
    private static final float INITIAL_RADIUS = 1.0F;
    private static final float BASE_GROWTH_DURATION = 6400.0F;
    private static final float FLICK_DURATION_MULTIPLIER = 0.5F;
    private static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath("releon", "pipeline/effects/frag_effect_scan");
    private static final Identifier VERTEX_SHADER = Identifier.fromNamespaceAndPath("releon", "effects/frag_effect_scan/frag_effect_scan");
    private static final Identifier FRAGMENT_SHADER = Identifier.fromNamespaceAndPath("releon", "effects/frag_effect_scan/frag_effect_scan");
    private static final Minecraft MINECRAFT = Minecraft.getInstance();

    private static RenderPipeline pipeline;
    private static GpuBuffer uniformBuffer;
    private static GpuTexture depthCopyTexture;
    private static GpuTextureView depthCopyTextureView;
    private static int depthCopyWidth = -1;
    private static int depthCopyHeight = -1;
    private static boolean disabledAfterError;
    private static final Matrix4f projectionScratch = new Matrix4f();
    private static final Matrix4f viewScratch = new Matrix4f();
    private static final Matrix4f inverseProjectionScratch = new Matrix4f();
    private static final Matrix4f inverseViewScratch = new Matrix4f();

    private static final ArrayDeque<WavePulse> activeWaves = new ArrayDeque<>();

    private FragEffectScanRenderer() {
    }

    public static void ping(Vec3 position, float speedMultiplier) {
        if (position == null) {
            return;
        }

        while (activeWaves.size() >= MAX_ACTIVE_WAVES) {
            activeWaves.removeFirst();
        }
        activeWaves.addLast(new WavePulse(position, System.currentTimeMillis(), Math.clamp(speedMultiplier, 0.25F, 2.0F)));
    }

    public static boolean isDisabledAfterError() {
        return disabledAfterError;
    }

    public static void clear() {
        activeWaves.clear();
        closeDepthCopyTexture();
    }

    public static void render(RenderTarget renderTarget, Matrix4f projectionMatrix, Matrix4f positionMatrix, Vec3 cameraPos, float speedMultiplier, int baseOuterColor, int baseMidColor, int baseInnerColor, int baseScanlineColor,
                              int flickOuterColor, int flickMidColor, int flickInnerColor, int flickScanlineColor) {
        if (disabledAfterError || activeWaves.isEmpty()) {
            return;
        }
        if (!cleanupExpiredWaves()) {
            return;
        }

        if (pipeline == null) {
            init();
        }
        if (pipeline == null || uniformBuffer == null) {
            return;
        }

        if (renderTarget == null || renderTarget.getColorTextureView() == null || renderTarget.getDepthTexture() == null) {
            return;
        }
        if (!ensureDepthCopyTexture(renderTarget.width, renderTarget.height)) {
            return;
        }

        try {
            Matrix4f projection = projectionScratch.set((Matrix4fc) projectionMatrix);
            Matrix4f view = viewScratch.set((Matrix4fc) positionMatrix);
            Matrix4f inverseProjection = inverseProjectionScratch.set((Matrix4fc) projection).invert();
            Matrix4f inverseView = inverseViewScratch.set((Matrix4fc) view).invert();

            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(renderTarget.getDepthTexture(), depthCopyTexture, 0, 0, 0, 0, 0, renderTarget.width, renderTarget.height);

            for (WavePulse wavePulse : activeWaves) {
                float growthDuration = scaleDuration(BASE_GROWTH_DURATION, wavePulse.speedMultiplier);
                float baseRadius = computeRadius(wavePulse.startedAt, growthDuration);
                float flickRadius = computeFlickRadius(wavePulse.startedAt, growthDuration, FLICK_DURATION_MULTIPLIER);

                float baseProgress = timeProgress(wavePulse.startedAt, growthDuration);
                float baseAlphaProgress = 1.0F - baseProgress;
                float baseWave = Math.min(wave(baseAlphaProgress) * 1.75F, 1.0F);
                renderWave(encoder, renderTarget.getColorTextureView(), depthCopyTextureView, inverseProjection, inverseView, cameraPos, wavePulse.center,
                        baseRadius, baseRadius, 30.0F, baseProgress, 0.0F,
                        scaleColor(baseOuterColor, baseWave),
                        scaleColor(baseMidColor, baseWave),
                        scaleColor(baseInnerColor, baseWave),
                        scaleColor(baseScanlineColor, baseWave));

                float flickProgress = timeProgress(wavePulse.startedAt, growthDuration * FLICK_DURATION_MULTIPLIER);
                float flickAlphaProgress = 1.0F - flickProgress;
                float flickWave = Math.min(wave(flickAlphaProgress) * 2.0F, 1.0F);
                renderWave(encoder, renderTarget.getColorTextureView(), depthCopyTextureView, inverseProjection, inverseView, cameraPos, wavePulse.center,
                        flickRadius, flickRadius / 1.5F, 40.0F, flickProgress, 1.0F,
                        scaleColor(flickOuterColor, flickAlphaProgress),
                        scaleColor(flickMidColor, flickWave),
                        scaleColor(flickInnerColor, flickWave),
                        scaleColor(flickScanlineColor, flickWave));
            }
        } catch (Throwable ignored) {
            disabledAfterError = true;
            ignored.printStackTrace();
            closeDepthCopyTexture();
        }
    }

    private static void init() {
        try {
            pipeline = RenderPipeline.builder()
                    .withLocation(PIPELINE_ID)
                    .withVertexShader(VERTEX_SHADER)
                    .withFragmentShader(FRAGMENT_SHADER)
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    .withUniform("Uniforms", UniformType.UNIFORM_BUFFER)
                    .withSampler("DepthSampler")
                    .withBlend(BlendFunction.ADDITIVE)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withCull(false)
                    .withDepthWrite(false)
                    .build();

            uniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "releon:frag_effect_scan_uniforms",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UNIFORM_SIZE
            );
        } catch (Throwable ignored) {
            disabledAfterError = true;
            ignored.printStackTrace();
            pipeline = null;
            uniformBuffer = null;
        }
    }

    private static void renderWave(CommandEncoder encoder, GpuTextureView colorTexture, GpuTextureView depthTexture, Matrix4f inverseProjection, Matrix4f inverseView,
                                   Vec3 cameraPos, Vec3 center, float radius, float width, float sharpness,
                                   float progress, float flick,
                                   int outerColor, int midColor, int innerColor, int scanlineColor) {
        GpuBuffer quadBuffer = FullscreenQuadBuffer.getOrCreate();
        if (colorTexture == null || depthTexture == null || quadBuffer == null) {
            return;
        }

        ByteBuffer buffer = MemoryUtil.memAlloc(UNIFORM_SIZE);
        try {
            putMatrix(buffer, inverseProjection);
            putMatrix(buffer, inverseView);
            putVec4(buffer, (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z, radius);
            putVec4(buffer, (float) center.x, (float) center.y, (float) center.z, Math.max(width, 0.0001F));
            putColor(buffer, outerColor);
            putColor(buffer, midColor);
            putColor(buffer, innerColor);
            putColor(buffer, scanlineColor);
            putVec4(buffer, sharpness, progress, flick, 0.0F);
            while (buffer.position() < UNIFORM_SIZE) {
                buffer.put((byte) 0);
            }
            buffer.flip();

            encoder.writeToBuffer(uniformBuffer.slice(), buffer);
            try (RenderPass pass = encoder.createRenderPass(() -> "releon:frag_effect_scan", colorTexture, OptionalInt.empty())) {
                pass.setPipeline(pipeline);
                pass.setVertexBuffer(0, quadBuffer);
                pass.setUniform("Uniforms", uniformBuffer);
                pass.bindTexture("DepthSampler", depthTexture, RenderSampler.nearest());
                pass.draw(0, 6);
            }
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    private static boolean ensureDepthCopyTexture(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (depthCopyTexture != null && depthCopyTextureView != null && depthCopyWidth == width && depthCopyHeight == height) {
            return true;
        }

        closeDepthCopyTexture();
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return false;
        }

        depthCopyTexture = device.createTexture(
                () -> "releon:frag_effect_depth_copy",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                TextureFormat.DEPTH32,
                width,
                height,
                1,
                1
        );
        depthCopyTextureView = device.createTextureView(depthCopyTexture);
        depthCopyWidth = width;
        depthCopyHeight = height;
        return true;
    }

    private static boolean cleanupExpiredWaves() {
        Iterator<WavePulse> iterator = activeWaves.iterator();
        long now = System.currentTimeMillis();
        while (iterator.hasNext()) {
            WavePulse wavePulse = iterator.next();
            float growthDuration = scaleDuration(BASE_GROWTH_DURATION, wavePulse.speedMultiplier);
            if ((float) (now - wavePulse.startedAt) >= growthDuration) {
                iterator.remove();
            }
        }
        return !activeWaves.isEmpty();
    }

    private static void closeDepthCopyTexture() {
        if (depthCopyTextureView != null) {
            depthCopyTextureView.close();
            depthCopyTextureView = null;
        }
        if (depthCopyTexture != null) {
            depthCopyTexture.close();
            depthCopyTexture = null;
        }
        depthCopyWidth = -1;
        depthCopyHeight = -1;
    }

    private static void putMatrix(ByteBuffer buffer, Matrix4f matrix) {
        buffer.putFloat(matrix.m00()).putFloat(matrix.m01()).putFloat(matrix.m02()).putFloat(matrix.m03());
        buffer.putFloat(matrix.m10()).putFloat(matrix.m11()).putFloat(matrix.m12()).putFloat(matrix.m13());
        buffer.putFloat(matrix.m20()).putFloat(matrix.m21()).putFloat(matrix.m22()).putFloat(matrix.m23());
        buffer.putFloat(matrix.m30()).putFloat(matrix.m31()).putFloat(matrix.m32()).putFloat(matrix.m33());
    }

    private static void putVec4(ByteBuffer buffer, float x, float y, float z, float w) {
        buffer.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
    }

    private static void putColor(ByteBuffer buffer, int color) {
        buffer.putFloat(ColorUtil.getRed(color) / 255.0F);
        buffer.putFloat(ColorUtil.getGreen(color) / 255.0F);
        buffer.putFloat(ColorUtil.getBlue(color) / 255.0F);
        buffer.putFloat(ColorUtil.getAlpha(color) / 255.0F);
    }

    private static int scaleColor(int color, float factor) {
        float clamped = Math.clamp(factor, 0.0F, 1.0F);
        return ColorUtil.rgba(
                Math.round(ColorUtil.getRed(color) * clamped),
                Math.round(ColorUtil.getGreen(color) * clamped),
                Math.round(ColorUtil.getBlue(color) * clamped),
                Math.round(255.0F * clamped)
        );
    }

    private static float wave(float value) {
        float clamped = Math.clamp(value, 0.0F, 1.0F);
        return clamped > 0.5F ? (1.0F - clamped) * 2.0F : clamped * 2.0F;
    }

    private static float scaleDuration(float duration, float speedMultiplier) {
        float speed = Math.clamp(speedMultiplier, 0.25F, 2.0F);
        return Math.max(280.0F, duration / speed);
    }

    private static float computeRadius(long start, float duration) {
        float progress = FragEffectEasing.quintOut(timeProgress(start, duration));
        return INITIAL_RADIUS + (viewDistance() - INITIAL_RADIUS) * progress;
    }

    private static float computeFlickRadius(long start, float duration, float multiplier) {
        float progress = FragEffectEasing.expoInOut(timeProgress(start, duration * multiplier));
        return INITIAL_RADIUS + (viewDistance() - INITIAL_RADIUS) * progress;
    }

    private static float timeProgress(long start, float duration) {
        return Math.clamp((float) (System.currentTimeMillis() - start) / duration, 0.0F, 1.0F);
    }

    private static float viewDistance() {
        return Math.max(96.0F, (MINECRAFT.options.renderDistance().get() + 1) * 16.0F);
    }

    private record WavePulse(Vec3 center, long startedAt, float speedMultiplier) {
    }
}
