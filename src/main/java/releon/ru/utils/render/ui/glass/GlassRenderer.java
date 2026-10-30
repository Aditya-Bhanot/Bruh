package releon.ru.utils.render.ui.glass;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import releon.ru.mixin.accessor.GuiGraphicsExtractorAccessor;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ui.blur.BlurFramebuffer;
import releon.ru.utils.render.ui.blur.BuiltBlur;
import releon.ru.utils.render.ScissorUtil;
import releon.ru.utils.render.color.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class GlassRenderer implements AutoCloseable {
    private static final int MAX_GLASSES = 512;
    private static final int PARAMS_PER_GLASS = 5;
    private static final int FLOATS_PER_PARAM = 4;
    private static final int UNIFORM_BYTES = MAX_GLASSES * PARAMS_PER_GLASS * FLOATS_PER_PARAM * Float.BYTES;

    private static volatile GlassRenderer instance;

    public static final RenderPipeline GLASS_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/glass"))
            .withVertexShader(id("ui/glass/glass"))
            .withFragmentShader(id("ui/glass/glass"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withSampler("Sampler0")
            .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("GlassParamsArray", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("BlurRegion", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    private final List<BuiltGlass> preparedGlasses = new ArrayList<>(64);
    private GuiGraphics activeGraphics;
    private GpuBuffer paramsBuffer;
    private boolean paramsDirty = true;

    private GlassRenderer() {
    }

    public static GlassRenderer getInstance() {
        GlassRenderer local = instance;
        if (local == null) {
            synchronized (GlassRenderer.class) {
                local = instance;
                if (local == null) {
                    local = new GlassRenderer();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void closeInstance() {
        GlassRenderer local = instance;
        if (local != null) {
            local.close();
            instance = null;
        }
    }

    public void beginFrame(GuiGraphics graphics) {
        activeGraphics = graphics;
    }

    public void draw(GuiGraphics graphics, BuiltGlass glass) {
        beginFrame(graphics);
        enqueue(glass);
        flush();
    }

    public void enqueue(BuiltGlass glass) {
        submit(activeGraphics, glass);
    }

    public void flush() {
        activeGraphics = null;
    }

    public void beginGuiFrame() {
        preparedGlasses.clear();
        paramsDirty = false;
    }

    public boolean isGlassPipeline(RenderPipeline pipeline) {
        return pipeline == GLASS_PIPELINE;
    }

    public void bindParams(RenderPass renderPass) {
        if (renderPass == null || preparedGlasses.isEmpty()) {
            return;
        }

        GpuBuffer buffer = ensureParamsBuffer();
        if (buffer != null) {
            renderPass.setUniform("GlassParamsArray", buffer);
        }
        BlurFramebuffer.getInstance().bindBlurRegion(renderPass);
    }

    public void prepareBuffers() {
        if (preparedGlasses.isEmpty() || !paramsDirty) {
            return;
        }

        GpuBuffer buffer = ensureWritableParamsBuffer();
        if (buffer == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uniformData = buildUniformData(stack, preparedGlasses);
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(buffer.slice(0, uniformData.remaining()), uniformData);
            paramsDirty = false;
        } catch (RuntimeException ignored) {
            paramsDirty = true;
        }
    }

    int reserve(BuiltGlass glass) {
        int index = preparedGlasses.size();
        if (index == MAX_GLASSES) {
            return -1;
        }

        preparedGlasses.add(glass);
        paramsDirty = true;
        return index;
    }

    private void submit(GuiGraphics graphics, BuiltGlass glass) {
        if (graphics == null || glass == null || !glass.visible()) {
            return;
        }

        try {
            BuiltGlass normalized = normalize(glass);
            Matrix3x2f pose = Render2DCoordinateSpace.pose(graphics);
            BlurFramebuffer.getInstance().requestCapture(
                    graphics,
                    new BuiltBlur(
                            normalized.x(),
                            normalized.y(),
                            normalized.width(),
                            normalized.height(),
                            normalized.radiusTopLeft(),
                            normalized.radiusTopRight(),
                            normalized.radiusBottomRight(),
                            normalized.radiusBottomLeft(),
                            1.0f,
                            30.0f,
                            ColorUtil.WHITE
                    )
            );
            ((GuiGraphicsExtractorAccessor) graphics)
                    .releon$getGuiRenderState()
                    .submitGuiElement(new GlassRenderState(pose, normalized, ScissorUtil.current()));
        } catch (RuntimeException ignored) {
        }
    }

    private GpuBuffer ensureParamsBuffer() {
        if (!paramsDirty && paramsBuffer != null) {
            return paramsBuffer;
        }

        prepareBuffers();
        if (!paramsDirty && paramsBuffer != null) {
            return paramsBuffer;
        }

        closeParamsBuffer();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uniformData = buildUniformData(stack, preparedGlasses);
            paramsBuffer = RenderSystem.getDevice().createBuffer(() -> "RELEON_glass_params", GpuBuffer.USAGE_UNIFORM, uniformData);
            paramsDirty = false;
            return paramsBuffer;
        }
    }

    private GpuBuffer ensureWritableParamsBuffer() {
        if (paramsBuffer != null && !paramsBuffer.isClosed() && paramsBuffer.size() >= UNIFORM_BYTES) {
            return paramsBuffer;
        }

        closeParamsBuffer();

        try {
            paramsBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "RELEON_glass_params",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UNIFORM_BYTES
            );
            return paramsBuffer;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ByteBuffer buildUniformData(MemoryStack stack, List<BuiltGlass> batch) {
        int usedBytes = Math.max(1, batch.size()) * PARAMS_PER_GLASS * FLOATS_PER_PARAM * Float.BYTES;
        ByteBuffer data = stack.calloc(usedBytes);

        for (int i = 0; i < batch.size(); i++) {
            BuiltGlass glass = batch.get(i);
            int offset = i * PARAMS_PER_GLASS * FLOATS_PER_PARAM * Float.BYTES;

            data.putFloat(offset, glass.radiusTopLeft());
            data.putFloat(offset + 4, glass.radiusTopRight());
            data.putFloat(offset + 8, glass.radiusBottomRight());
            data.putFloat(offset + 12, glass.radiusBottomLeft());

            data.putFloat(offset + 16, glass.width());
            data.putFloat(offset + 20, glass.height());
            data.putFloat(offset + 24, 0.5f);
            data.putFloat(offset + 28, Math.max(glass.squirt(), 0.001f));

            data.putFloat(offset + 32, glass.globalAlpha());
            data.putFloat(offset + 36, glass.fresnelPower());
            data.putFloat(offset + 40, glass.baseAlpha());
            data.putFloat(offset + 44, glass.fresnelMix());

            putColor(data, offset + 48, glass.fresnelColor());

            data.putFloat(offset + 64, glass.fresnelInvert() ? 1.0f : 0.0f);
            data.putFloat(offset + 68, glass.distortStrength());
            data.putFloat(offset + 72, glass.z());
            data.putFloat(offset + 76, 0.0f);
        }

        data.position(0);
        return data;
    }

    private BuiltGlass normalize(BuiltGlass glass) {
        float maxRadius = Math.max(0.0f, Math.min(glass.width(), glass.height()) * 0.5f);
        float radiusTopLeft = clamp(glass.radiusTopLeft(), 0.0f, maxRadius);
        float radiusTopRight = clamp(glass.radiusTopRight(), 0.0f, maxRadius);
        float radiusBottomRight = clamp(glass.radiusBottomRight(), 0.0f, maxRadius);
        float radiusBottomLeft = clamp(glass.radiusBottomLeft(), 0.0f, maxRadius);
        float globalAlpha = clamp(glass.globalAlpha(), 0.0f, 1.0f);
        float fresnelPower = Math.max(glass.fresnelPower(), 0.001f);
        float baseAlpha = clamp(glass.baseAlpha(), 0.0f, 1.0f);
        float fresnelMix = clamp(glass.fresnelMix(), 0.0f, 1.0f);
        float squirt = Math.max(glass.squirt(), 0.001f);

        if (radiusTopLeft == glass.radiusTopLeft()
                && radiusTopRight == glass.radiusTopRight()
                && radiusBottomRight == glass.radiusBottomRight()
                && radiusBottomLeft == glass.radiusBottomLeft()
                && globalAlpha == glass.globalAlpha()
                && fresnelPower == glass.fresnelPower()
                && baseAlpha == glass.baseAlpha()
                && fresnelMix == glass.fresnelMix()
                && squirt == glass.squirt()) {
            return glass;
        }

        return new BuiltGlass(
                glass.x(),
                glass.y(),
                glass.width(),
                glass.height(),
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                glass.color(),
                globalAlpha,
                fresnelPower,
                glass.fresnelColor(),
                baseAlpha,
                glass.fresnelInvert(),
                fresnelMix,
                glass.distortStrength(),
                squirt,
                glass.z()
        );
    }

    private static void putColor(ByteBuffer data, int offset, int color) {
        data.putFloat(offset, ((color >>> 16) & 0xFF) / 255.0f);
        data.putFloat(offset + 4, ((color >>> 8) & 0xFF) / 255.0f);
        data.putFloat(offset + 8, (color & 0xFF) / 255.0f);
        data.putFloat(offset + 12, ((color >>> 24) & 0xFF) / 255.0f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void closeParamsBuffer() {
        if (paramsBuffer != null) {
            paramsBuffer.close();
            paramsBuffer = null;
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("releon", path);
    }

    @Override
    public void close() {
        preparedGlasses.clear();
        activeGraphics = null;
        closeParamsBuffer();
    }
}
