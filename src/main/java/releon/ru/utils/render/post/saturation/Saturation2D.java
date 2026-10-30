package releon.ru.utils.render.post.saturation;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import releon.ru.utils.render.RenderSampler;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

public class Saturation2D {

    private static final Identifier PIPELINE_ID = Identifier.fromNamespaceAndPath("releon", "pipeline/post/saturation");
    private static final Identifier VERTEX_SHADER = Identifier.fromNamespaceAndPath("releon", "post/saturation/saturation");
    private static final Identifier FRAGMENT_SHADER = Identifier.fromNamespaceAndPath("releon", "post/saturation/saturation");

    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                    .withLocation(PIPELINE_ID)
                    .withVertexShader(VERTEX_SHADER)
                    .withFragmentShader(FRAGMENT_SHADER)
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                    .withUniform("SaturationData", UniformType.UNIFORM_BUFFER)
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .build()
    );

    private static final ByteBuffer DATA_BUFFER = MemoryUtil.memAlloc(16);

    private static final GpuBuffer DUMMY_VERTEX_BUFFER;
    private static final GpuBuffer UNIFORM_BUFFER;

    private static GpuTexture tempTexture;
    private static GpuTextureView tempTextureView;
    private static int lastWidth;
    private static int lastHeight;
    private static boolean disabledAfterError;

    static {
        ByteBuffer dummyData = MemoryUtil.memAlloc(4);
        dummyData.putInt(0);
        dummyData.flip();
        DUMMY_VERTEX_BUFFER = RenderSystem.getDevice().createBuffer(
                () -> "releon:saturation_dummy_vertex",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                dummyData
        );
        MemoryUtil.memFree(dummyData);

        UNIFORM_BUFFER = RenderSystem.getDevice().createBuffer(
                () -> "releon:saturation_uniform",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                16
        );
    }

    private static void ensureTextures(int width, int height) {
        if (tempTexture != null && width == lastWidth && height == lastHeight) {
            return;
        }

        if (tempTextureView != null) {
            tempTextureView.close();
        }
        if (tempTexture != null) {
            tempTexture.close();
        }

        tempTexture = RenderSystem.getDevice().createTexture(
                () -> "releon:saturation_temp",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                TextureFormat.RGBA8,
                width,
                height,
                1,
                1
        );
        tempTextureView = RenderSystem.getDevice().createTextureView(tempTexture);
        lastWidth = width;
        lastHeight = height;
    }

    public static void applyWithCopy(float saturation) {
        if (disabledAfterError) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getMainRenderTarget() == null) {
            return;
        }
        if (!Float.isFinite(saturation)) {
            return;
        }
        float clampedSaturation = Math.clamp(saturation, 0.0f, 2.0f);
        if (Math.abs(clampedSaturation - 1.0f) <= 0.0005f) {
            return;
        }

        int width = client.getMainRenderTarget().width;
        int height = client.getMainRenderTarget().height;
        if (width <= 0 || height <= 0) {
            return;
        }
        ensureTextures(width, height);

        try {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.copyTextureToTexture(
                    client.getMainRenderTarget().getColorTexture(),
                    tempTexture,
                    0,
                    0,
                    0,
                    0,
                    0,
                    width,
                    height
            );

            apply(client.getMainRenderTarget().getColorTextureView(), tempTextureView, clampedSaturation);
        } catch (Throwable ignored) {
            disabledAfterError = true;
        }
    }

    private static void apply(GpuTextureView targetView, GpuTextureView sourceView, float saturation) {
        DATA_BUFFER.clear();
        DATA_BUFFER.putFloat(saturation);
        DATA_BUFFER.flip();

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToBuffer(UNIFORM_BUFFER.slice(), DATA_BUFFER);

        try (RenderPass renderPass = encoder.createRenderPass(() -> "releon:saturation_pass", targetView, OptionalInt.empty())) {
            renderPass.setPipeline(PIPELINE);
            renderPass.setVertexBuffer(0, DUMMY_VERTEX_BUFFER);
            renderPass.bindTexture("Sampler0", sourceView, RenderSampler.linear());
            renderPass.setUniform("SaturationData", UNIFORM_BUFFER.slice());
            renderPass.draw(0, 6);
        }
    }
}
