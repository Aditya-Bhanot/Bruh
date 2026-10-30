package releon.ru.utils.render.ui.arc;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.lwjgl.system.MemoryStack;
import releon.ru.mixin.accessor.GuiGraphicsExtractorAccessor;
import releon.ru.utils.render.ScissorUtil;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class ArcRenderer implements AutoCloseable {
    private static final int MAX_ARCS = 256;
    private static final int PARAMS_PER_ARC = 10;
    private static final int FLOATS_PER_PARAM = 4;
    private static final int UNIFORM_BYTES = MAX_ARCS * PARAMS_PER_ARC * FLOATS_PER_PARAM * Float.BYTES;

    private static volatile ArcRenderer instance;

    public static final RenderPipeline ARC_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/arc"))
            .withVertexShader(id("ui/shared/outline_quad"))
            .withFragmentShader(id("ui/arc/arc"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("ArcParamsArray", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    private final List<BuiltArc> preparedArcs = new ArrayList<>(64);
    private GuiGraphics activeGraphics;
    private GpuBuffer paramsBuffer;
    private boolean paramsDirty = true;

    private ArcRenderer() {
    }

    public static ArcRenderer getInstance() {
        ArcRenderer local = instance;
        if (local == null) {
            synchronized (ArcRenderer.class) {
                local = instance;
                if (local == null) {
                    local = new ArcRenderer();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void closeInstance() {
        ArcRenderer local = instance;
        if (local != null) {
            local.close();
            instance = null;
        }
    }

    public void beginFrame(GuiGraphics graphics) {
        activeGraphics = graphics;
    }

    public void draw(GuiGraphics graphics, BuiltArc arc) {
        beginFrame(graphics);
        enqueue(arc);
        flush();
    }

    public void enqueue(BuiltArc arc) {
        submit(activeGraphics, arc);
    }

    public void flush() {
        activeGraphics = null;
    }

    public void beginGuiFrame() {
        preparedArcs.clear();
        paramsDirty = false;
    }

    public boolean isArcPipeline(RenderPipeline pipeline) {
        return pipeline == ARC_PIPELINE;
    }

    public void bindParams(RenderPass renderPass) {
        if (renderPass == null || preparedArcs.isEmpty()) {
            return;
        }

        GpuBuffer buffer = ensureParamsBuffer();
        if (buffer != null) {
            renderPass.setUniform("ArcParamsArray", buffer);
        }
    }

    public void prepareBuffers() {
        if (preparedArcs.isEmpty() || !paramsDirty) {
            return;
        }

        GpuBuffer buffer = ensureWritableParamsBuffer();
        if (buffer == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uniformData = buildUniformData(stack, preparedArcs);
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(buffer.slice(0, uniformData.remaining()), uniformData);
            paramsDirty = false;
        } catch (RuntimeException ignored) {
            paramsDirty = true;
        }
    }

    int reserve(BuiltArc arc) {
        int index = preparedArcs.size();
        if (index == MAX_ARCS) {
            return -1;
        }

        preparedArcs.add(arc);
        paramsDirty = true;
        return index;
    }

    private void submit(GuiGraphics graphics, BuiltArc arc) {
        if (graphics == null || arc == null || !arc.visible()) {
            return;
        }

        try {
            BuiltArc normalized = normalize(arc);
            Matrix3x2f pose = Render2DCoordinateSpace.pose(graphics);
            ((GuiGraphicsExtractorAccessor) graphics)
                    .releon$getGuiRenderState()
                    .submitGuiElement(new ArcRenderState(pose, normalized, ScissorUtil.current()));
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
            ByteBuffer uniformData = buildUniformData(stack, preparedArcs);
            paramsBuffer = RenderSystem.getDevice().createBuffer(() -> "RELEON_arc_params", GpuBuffer.USAGE_UNIFORM, uniformData);
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
                    () -> "RELEON_arc_params",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UNIFORM_BYTES
            );
            return paramsBuffer;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ByteBuffer buildUniformData(MemoryStack stack, List<BuiltArc> batch) {
        int usedBytes = Math.max(1, batch.size()) * PARAMS_PER_ARC * FLOATS_PER_PARAM * Float.BYTES;
        ByteBuffer data = stack.calloc(usedBytes);

        for (int i = 0; i < batch.size(); i++) {
            BuiltArc arc = batch.get(i);
            int offset = i * PARAMS_PER_ARC * FLOATS_PER_PARAM * Float.BYTES;

            data.putFloat(offset, arc.size());
            data.putFloat(offset + 4, arc.thickness());
            data.putFloat(offset + 8, arc.degree());
            data.putFloat(offset + 12, arc.rotation());

            int[] colors = arc.colors();
            for (int colorIndex = 0; colorIndex < 9; colorIndex++) {
                putColor(data, offset + (colorIndex + 1) * 16, colors[colorIndex]);
            }
        }

        data.position(0);
        return data;
    }

    private BuiltArc normalize(BuiltArc arc) {
        float size = Math.max(0.0f, arc.size());
        float thickness = Math.max(0.0f, Math.min(arc.thickness(), size));
        float degree = Math.max(0.0f, Math.min(arc.degree(), 360.0f));
        return new BuiltArc(arc.x(), arc.y(), size, thickness, degree, arc.rotation(), arc.colors());
    }

    private void putColor(ByteBuffer data, int offset, int color) {
        data.putFloat(offset, ((color >>> 16) & 0xFF) / 255.0f);
        data.putFloat(offset + 4, ((color >>> 8) & 0xFF) / 255.0f);
        data.putFloat(offset + 8, (color & 0xFF) / 255.0f);
        data.putFloat(offset + 12, ((color >>> 24) & 0xFF) / 255.0f);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("releon", path);
    }

    @Override
    public void close() {
        closeParamsBuffer();
    }

    private void closeParamsBuffer() {
        if (paramsBuffer != null) {
            paramsBuffer.close();
            paramsBuffer = null;
        }
    }
}
