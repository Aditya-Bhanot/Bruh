package releon.ru.utils.render.ui.rectangle.recthalficon;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import releon.ru.access.GuiRenderStateLayerAccessor;
import releon.ru.mixin.accessor.GuiGraphicsExtractorAccessor;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ui.font.TextRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HalfIconRectangleRenderer implements AutoCloseable {
    private static final int MAX_RECTANGLES = 256;
    private static final int PARAMS_PER_RECTANGLE = 3;
    private static final int FLOATS_PER_PARAM = 4;
    private static final int UNIFORM_BYTES = MAX_RECTANGLES * PARAMS_PER_RECTANGLE * FLOATS_PER_PARAM * Float.BYTES;
    private static final int MAX_ICON_COLUMNS = 96;
    private static final int MAX_ICON_ROWS = 96;

    private static final VertexFormat POSITION_TEX_COLOR_LINE_WIDTH = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .add("LineWidth", VertexFormatElement.LINE_WIDTH)
            .build();

    private static volatile HalfIconRectangleRenderer instance;

    public static final RenderPipeline HALF_ICON_RECTANGLE_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/rect_half_icon"))
            .withVertexShader(id("ui/rect_half_icon/rect_half_icon"))
            .withFragmentShader(id("ui/rect_half_icon/rect_half_icon"))
            .withVertexFormat(POSITION_TEX_COLOR_LINE_WIDTH, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withSampler("Sampler0")
            .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("RectHalfIconParamsArray", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    private final List<BuiltHalfIconRectangle> preparedRectangles = new ArrayList<>(32);
    private final Map<FrameBatchKey, HalfIconRectangleRenderState> frameBatches = new LinkedHashMap<>(32);
    private GuiGraphics activeGraphics;
    private GpuBuffer paramsBuffer;
    private boolean paramsDirty = true;

    private HalfIconRectangleRenderer() {
    }

    public static HalfIconRectangleRenderer getInstance() {
        HalfIconRectangleRenderer local = instance;
        if (local == null) {
            synchronized (HalfIconRectangleRenderer.class) {
                local = instance;
                if (local == null) {
                    local = new HalfIconRectangleRenderer();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void closeInstance() {
        HalfIconRectangleRenderer local = instance;
        if (local != null) {
            local.close();
            instance = null;
        }
    }

    public void beginFrame(GuiGraphics graphics) {
        if (activeGraphics != graphics) {
            frameBatches.clear();
        }
        activeGraphics = graphics;
    }

    public void draw(GuiGraphics graphics, BuiltHalfIconRectangle rectangle) {
        beginFrame(graphics);
        enqueue(rectangle);
        flush();
    }

    public void enqueue(BuiltHalfIconRectangle rectangle) {
        submit(activeGraphics, rectangle);
    }

    public void flush() {
        activeGraphics = null;
        frameBatches.clear();
    }

    public void beginGuiFrame() {
        preparedRectangles.clear();
        paramsDirty = false;
    }

    public boolean isHalfIconRectanglePipeline(RenderPipeline pipeline) {
        return pipeline == HALF_ICON_RECTANGLE_PIPELINE;
    }

    public void bindParams(RenderPass renderPass) {
        if (renderPass == null || preparedRectangles.isEmpty()) {
            return;
        }

        GpuBuffer buffer = ensureParamsBuffer();
        if (buffer != null) {
            renderPass.setUniform("RectHalfIconParamsArray", buffer);
        }
    }

    public void prepareBuffers() {
        if (preparedRectangles.isEmpty() || !paramsDirty) {
            return;
        }

        GpuBuffer buffer = ensureWritableParamsBuffer();
        if (buffer == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uniformData = buildUniformData(stack, preparedRectangles);
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(buffer.slice(0, uniformData.remaining()), uniformData);
            paramsDirty = false;
        } catch (RuntimeException ignored) {
            paramsDirty = true;
        }
    }

    int reserve(BuiltHalfIconRectangle rectangle) {
        int index = preparedRectangles.size();
        if (index == MAX_RECTANGLES) {
            return -1;
        }

        preparedRectangles.add(rectangle);
        paramsDirty = true;
        return index;
    }

    private void submit(GuiGraphics graphics, BuiltHalfIconRectangle rectangle) {
        if (graphics == null || rectangle == null || !rectangle.iconsVisible()) {
            return;
        }

        try {
            BuiltHalfIconRectangle normalized = normalize(rectangle);
            TextRenderer.GlyphLayout layout = TextRenderer.getInstance().glyphLayout(
                    normalized.fontName(),
                    normalized.icon(),
                    normalized.iconSize()
            );
            if (layout.empty()) {
                return;
            }

            Matrix3x2f pose = Render2DCoordinateSpace.pose(graphics);
            GuiRenderState guiState = ((GuiGraphicsExtractorAccessor) graphics).releon$getGuiRenderState();
            int layerSerial = ((GuiRenderStateLayerAccessor) guiState).releon$getLayerSerial();
            PoseKey poseKey = PoseKey.of(pose);

            for (TextRenderer.GlyphPage page : layout.pages()) {
                if (page.glyphs().isEmpty()) {
                    continue;
                }

                List<HalfIconRectangleRenderState.IconQuad> quads = buildIconQuads(normalized, layout, page);
                if (quads.isEmpty()) {
                    continue;
                }

                FrameBatchKey key = new FrameBatchKey(guiState, layerSerial, page.textureSetup(), poseKey);
                HalfIconRectangleRenderState state = frameBatches.get(key);
                if (state == null) {
                    state = new HalfIconRectangleRenderState(pose, page.textureSetup());
                    state.add(normalized, quads);
                    frameBatches.put(key, state);
                    guiState.submitGuiElement(state);
                } else {
                    state.add(normalized, quads);
                }
            }
        } catch (RuntimeException exception) {
        }
    }

    private List<HalfIconRectangleRenderState.IconQuad> buildIconQuads(
            BuiltHalfIconRectangle rectangle,
            TextRenderer.GlyphLayout layout,
            TextRenderer.GlyphPage page
    ) {
        List<HalfIconRectangleRenderState.IconQuad> quads = new ArrayList<>(64);
        float spacingX = Math.max(rectangle.spacingX(), Math.max(layout.width(), 1.0f));
        float spacingY = Math.max(rectangle.spacingY(), Math.max(layout.height(), 1.0f));
        float startX = rectangle.x() + rectangle.paddingX() + layout.width() * 0.5f;
        float startY = rectangle.y() + rectangle.paddingY() + layout.height() * 0.5f;
        float endX = rectangle.x() + rectangle.width() - rectangle.paddingX() - layout.width() * 0.5f;
        float endY = rectangle.y() + rectangle.height() - rectangle.paddingY() - layout.height() * 0.5f;

        if (endX < startX || endY < startY) {
            return List.of();
        }

        int rows = Math.min(MAX_ICON_ROWS, Math.max(1, (int) Math.floor((endY - startY) / spacingY) + 1));
        int columns = Math.min(MAX_ICON_COLUMNS, Math.max(1, (int) Math.floor((endX - startX) / spacingX) + 1));

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                float centerX = startX + column * spacingX + randomSigned(rectangle.seed(), column, row, 1) * rectangle.jitterX();
                float centerY = startY + row * spacingY + randomSigned(rectangle.seed(), column, row, 2) * rectangle.jitterY();
                float angle = (float) Math.toRadians(randomSigned(rectangle.seed(), column, row, 3) * rectangle.rotationMaxDegrees());
                float sin = (float) Math.sin(angle);
                float cos = (float) Math.cos(angle);
                float originX = centerX - layout.width() * 0.5f;
                float originY = centerY - layout.height() * 0.5f;
                int iconColor = withAlphaMultiplier(
                        rectangle.iconColor(),
                        1.0f - clamp((centerY - rectangle.y()) / Math.max(rectangle.height(), 1.0f), 0.0f, 1.0f) * 0.92f
                );

                for (TextRenderer.Glyph glyph : page.glyphs()) {
                    float x0 = originX + glyph.x0();
                    float y0 = originY + glyph.y0();
                    float x1 = originX + glyph.x1();
                    float y1 = originY + glyph.y1();

                    RotatedPoint topLeft = rotate(x0, y0, centerX, centerY, sin, cos);
                    RotatedPoint bottomLeft = rotate(x0, y1, centerX, centerY, sin, cos);
                    RotatedPoint bottomRight = rotate(x1, y1, centerX, centerY, sin, cos);
                    RotatedPoint topRight = rotate(x1, y0, centerX, centerY, sin, cos);

                    quads.add(new HalfIconRectangleRenderState.IconQuad(
                            topLeft.x(),
                            topLeft.y(),
                            glyph.u0(),
                            glyph.v0(),
                            bottomLeft.x(),
                            bottomLeft.y(),
                            glyph.u0(),
                            glyph.v1(),
                            bottomRight.x(),
                            bottomRight.y(),
                            glyph.u1(),
                            glyph.v1(),
                            topRight.x(),
                            topRight.y(),
                            glyph.u1(),
                            glyph.v0(),
                            iconColor
                    ));
                }
            }
        }

        return quads;
    }

    private ByteBuffer buildUniformData(MemoryStack stack, List<BuiltHalfIconRectangle> batch) {
        int usedBytes = Math.max(1, batch.size()) * PARAMS_PER_RECTANGLE * FLOATS_PER_PARAM * Float.BYTES;
        ByteBuffer data = stack.calloc(usedBytes);

        for (int i = 0; i < batch.size(); i++) {
            BuiltHalfIconRectangle rectangle = batch.get(i);
            int offset = i * PARAMS_PER_RECTANGLE * FLOATS_PER_PARAM * Float.BYTES;

            data.putFloat(offset, rectangle.x());
            data.putFloat(offset + 4, rectangle.y());
            data.putFloat(offset + 8, rectangle.width());
            data.putFloat(offset + 12, rectangle.height());

            data.putFloat(offset + 16, rectangle.radiusTopLeft());
            data.putFloat(offset + 20, rectangle.radiusTopRight());
            data.putFloat(offset + 24, rectangle.radiusBottomRight());
            data.putFloat(offset + 28, rectangle.radiusBottomLeft());

            data.putFloat(offset + 32, rectangle.smoothness());
            data.putFloat(offset + 36, 0.0f);
            data.putFloat(offset + 40, 0.0f);
            data.putFloat(offset + 44, 0.0f);
        }

        data.position(0);
        return data;
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
            ByteBuffer uniformData = buildUniformData(stack, preparedRectangles);
            paramsBuffer = RenderSystem.getDevice().createBuffer(() -> "RELEON_rect_half_icon_params", GpuBuffer.USAGE_UNIFORM, uniformData);
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
                    () -> "RELEON_rect_half_icon_params",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UNIFORM_BYTES
            );
            return paramsBuffer;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private BuiltHalfIconRectangle normalize(BuiltHalfIconRectangle rectangle) {
        float maxRadius = Math.max(0.0f, Math.min(rectangle.width(), rectangle.height()) * 0.5f);
        float iconSize = Math.max(rectangle.iconSize(), 1.0f);
        float radiusTopLeft = clamp(rectangle.radiusTopLeft(), 0.0f, maxRadius);
        float radiusTopRight = clamp(rectangle.radiusTopRight(), 0.0f, maxRadius);
        float radiusBottomRight = clamp(rectangle.radiusBottomRight(), 0.0f, maxRadius);
        float radiusBottomLeft = clamp(rectangle.radiusBottomLeft(), 0.0f, maxRadius);
        float smoothness = Math.max(rectangle.smoothness(), 0.5f);
        float spacingX = Math.max(rectangle.spacingX(), iconSize);
        float spacingY = Math.max(rectangle.spacingY(), iconSize);
        float paddingX = Math.max(rectangle.paddingX(), 0.0f);
        float paddingY = Math.max(rectangle.paddingY(), 0.0f);
        float jitterX = Math.max(rectangle.jitterX(), 0.0f);
        float jitterY = Math.max(rectangle.jitterY(), 0.0f);
        float rotationMaxDegrees = clamp(rectangle.rotationMaxDegrees(), 0.0f, 90.0f);

        if (radiusTopLeft == rectangle.radiusTopLeft()
                && radiusTopRight == rectangle.radiusTopRight()
                && radiusBottomRight == rectangle.radiusBottomRight()
                && radiusBottomLeft == rectangle.radiusBottomLeft()
                && smoothness == rectangle.smoothness()
                && iconSize == rectangle.iconSize()
                && spacingX == rectangle.spacingX()
                && spacingY == rectangle.spacingY()
                && paddingX == rectangle.paddingX()
                && paddingY == rectangle.paddingY()
                && jitterX == rectangle.jitterX()
                && jitterY == rectangle.jitterY()
                && rotationMaxDegrees == rectangle.rotationMaxDegrees()) {
            return rectangle;
        }

        return new BuiltHalfIconRectangle(
                rectangle.x(),
                rectangle.y(),
                rectangle.width(),
                rectangle.height(),
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                rectangle.colorTopLeft(),
                rectangle.colorTopRight(),
                rectangle.colorBottomRight(),
                rectangle.colorBottomLeft(),
                smoothness,
                rectangle.fontName(),
                rectangle.icon(),
                iconSize,
                rectangle.iconColor(),
                spacingX,
                spacingY,
                paddingX,
                paddingY,
                jitterX,
                jitterY,
                rotationMaxDegrees,
                rectangle.seed()
        );
    }

    private static RotatedPoint rotate(float x, float y, float centerX, float centerY, float sin, float cos) {
        float localX = x - centerX;
        float localY = y - centerY;
        return new RotatedPoint(
                centerX + localX * cos - localY * sin,
                centerY + localX * sin + localY * cos
        );
    }

    private static float randomSigned(long seed, int column, int row, int salt) {
        long value = seed
                ^ ((long) column * 0x9E3779B97F4A7C15L)
                ^ ((long) row * 0xBF58476D1CE4E5B9L)
                ^ ((long) salt * 0x94D049BB133111EBL);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (((value & 0xFFFFFFL) / (float) 0xFFFFFFL) * 2.0f) - 1.0f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int withAlphaMultiplier(int color, float multiplier) {
        int alpha = Math.round(((color >>> 24) & 0xFF) * clamp(multiplier, 0.0f, 1.0f));
        return (alpha << 24) | (color & 0x00FFFFFF);
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
        preparedRectangles.clear();
        frameBatches.clear();
        activeGraphics = null;
        closeParamsBuffer();
    }

    private record FrameBatchKey(GuiRenderState state, int layerSerial, TextureSetup textureSetup, PoseKey pose) {
    }

    private record PoseKey(float m00, float m01, float m10, float m11, float m20, float m21) {
        static PoseKey of(Matrix3x2f matrix) {
            return new PoseKey(matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), matrix.m20(), matrix.m21());
        }
    }

    private record RotatedPoint(float x, float y) {
    }
}
