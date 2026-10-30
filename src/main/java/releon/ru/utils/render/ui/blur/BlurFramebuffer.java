package releon.ru.utils.render.ui.blur;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import releon.ru.mixin.accessor.GuiGraphicsExtractorAccessor;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ScissorUtil;
import releon.ru.utils.render.post.fogblur.FogBlurRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.lwjgl.system.MemoryStack;
import releon.ru.screens.modernui.ClickGuiScreen;
import releon.ru.screens.modernui.impl.WorldAnimation;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class BlurFramebuffer implements AutoCloseable {
    private static final int MAX_BLUR_RECTS = 1024;
    private static final int MAX_BLUR_ITERATIONS = 4;
    private static final int PARAMS_PER_RECT = 2;
    private static final int FLOATS_PER_PARAM = 4;
    private static final int UNIFORM_BYTES = MAX_BLUR_RECTS * PARAMS_PER_RECT * FLOATS_PER_PARAM * Float.BYTES;
    private static final int REGION_UNIFORM_BYTES = 4 * Float.BYTES;
    private static final int KAWASE_UNIFORM_BYTES = 12 * Float.BYTES;
    private static final float MAX_BLUR_RENDER_SCALE = 0.5f;
    private static final float MEDIUM_BLUR_RENDER_SCALE = 0.44f;
    private static final float MIN_BLUR_RENDER_SCALE = 0.4f;

    private static volatile BlurFramebuffer instance;
    private static float skyFallbackRed = 0.55f;
    private static float skyFallbackGreen = 0.65f;
    private static float skyFallbackBlue = 0.78f;

    public static final RenderPipeline BATCHED_BLUR_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/batched_blur"))
            .withVertexShader(id("ui/batched_blur/batched_blur"))
            .withFragmentShader(id("ui/batched_blur/batched_blur"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_LINE_WIDTH, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withSampler("Sampler0")
            .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("BlurParamsArray", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("BlurRegion", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    public static final RenderPipeline KAWASE_DOWN_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/kawase_down"))
            .withVertexShader(id("ui/kawase/down"))
            .withFragmentShader(id("ui/kawase/down"))
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withSampler("Sampler0")
            .withUniform("KawaseParams", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    public static final RenderPipeline KAWASE_UP_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/kawase_up"))
            .withVertexShader(id("ui/kawase/down"))
            .withFragmentShader(id("ui/kawase/up"))
            .withVertexFormat(DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withSampler("Sampler0")
            .withUniform("KawaseParams", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    private final List<PendingBlur> pendingBlurs = new ArrayList<>(32);
    private final List<BuiltBlur> preparedBlurs = new ArrayList<>(32);
    private GuiGraphics activeGraphics;
    private TextureTarget blurTarget;
    private final TextureTarget[] downTargets = new TextureTarget[MAX_BLUR_ITERATIONS];
    private final TextureTarget[] upTargets = new TextureTarget[MAX_BLUR_ITERATIONS];
    private GpuBuffer fullscreenQuadBuffer;
    private GpuBuffer paramsBuffer;
    private GpuBuffer regionBuffer;
    private GpuBuffer kawaseParamsBuffer;
    private TextureSetup blurTextureSetup = TextureSetup.noTexture();
    private boolean captureRequested;
    private boolean paramsDirty = true;
    private boolean regionDirty = true;
    private int regionX;
    private int regionY;
    private int regionWidth;
    private int regionHeight;
    private int preparedIterations;
    private float maxBlurRadius;
    private BlurFramebuffer() {
    }

    public static BlurFramebuffer getInstance() {
        BlurFramebuffer local = instance;
        if (local == null) {
            synchronized (BlurFramebuffer.class) {
                local = instance;
                if (local == null) {
                    local = new BlurFramebuffer();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void closeInstance() {
        BlurFramebuffer local = instance;
        if (local != null) {
            local.close();
            instance = null;
        }
    }

    public static void setSkyFallbackColor(float red, float green, float blue) {
        if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)) {
            return;
        }

        skyFallbackRed = clamp(red, 0.0f, 1.0f);
        skyFallbackGreen = clamp(green, 0.0f, 1.0f);
        skyFallbackBlue = clamp(blue, 0.0f, 1.0f);
    }

    public void beginFrame(GuiGraphics graphics) {
        activeGraphics = graphics;
    }

    public void draw(GuiGraphics graphics, BuiltBlur blur) {
        beginFrame(graphics);
        enqueue(blur);
        flush();
    }

    public void enqueue(BuiltBlur blur) {
        submit(activeGraphics, blur);
    }

    public void flush() {
        activeGraphics = null;
    }

    public void beginGuiFrame() {
        preparedBlurs.clear();
        captureRequested = false;
        paramsDirty = false;
        regionDirty = false;
        regionWidth = 0;
        regionHeight = 0;
        preparedIterations = 0;
        maxBlurRadius = 0.0f;
        blurTextureSetup = TextureSetup.noTexture();
    }

    public void preparePending() {
        if (pendingBlurs.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            pendingBlurs.clear();
            return;
        }

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTexture() == null || !computeBlurRegion(minecraft, mainTarget)) {
            pendingBlurs.clear();
            return;
        }

        preparedIterations = computeIterations(maxBlurRadius);
        ensureBlurTargets(regionWidth, regionHeight, computeRenderScale(maxBlurRadius, regionWidth, regionHeight), preparedIterations);
        if (blurTarget == null || blurTarget.getColorTextureView() == null) {
            pendingBlurs.clear();
            return;
        }

        TextureSetup textureSetup = TextureSetup.singleTexture(
                blurTarget.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        );

        blurTextureSetup = textureSetup;
        captureRequested = true;
        regionDirty = true;
        uploadRegionBuffer();
        pendingBlurs.clear();
    }

    public TextureSetup textureSetup() {
        return blurTextureSetup;
    }

    public void requestCapture(GuiGraphics graphics, BuiltBlur blur) {
        if (graphics == null || blur == null || !blur.visible()) {
            return;
        }

        try {
            BuiltBlur normalized = normalize(blur);
            Matrix3x2f pose = Render2DCoordinateSpace.pose(graphics);
            pendingBlurs.add(new PendingBlur(normalized, pose, ScissorUtil.current()));
        } catch (RuntimeException ignored) {
        }
    }

    public void prepareGuiDraw() {
        if (!captureRequested || regionWidth <= 0 || regionHeight <= 0) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        if (mainTarget == null || mainTarget.getColorTextureView() == null) {
            return;
        }

        GpuTextureView guiSource = shouldUseMainTargetSource(minecraft)
                ? null
                : FogBlurRenderer.getGuiSourceTextureView(mainTarget.width, mainTarget.height);
        renderBlurChain(mainTarget, guiSource);
    }

    private boolean shouldUseMainTargetSource(Minecraft minecraft) {
        return minecraft.screen instanceof ClickGuiScreen || WorldAnimation.isActive();
    }

    public boolean isBlurPipeline(RenderPipeline pipeline) {
        return pipeline == BATCHED_BLUR_PIPELINE;
    }

    public void bindBlurParams(RenderPass renderPass) {
        if (renderPass == null || preparedBlurs.isEmpty()) {
            return;
        }

        GpuBuffer buffer = ensureParamsBuffer();
        if (buffer != null) {
            renderPass.setUniform("BlurParamsArray", buffer);
        }
        GpuBuffer region = ensureRegionBuffer();
        if (region != null) {
            renderPass.setUniform("BlurRegion", region);
        }
    }

    public void bindBlurRegion(RenderPass renderPass) {
        if (renderPass == null) {
            return;
        }

        GpuBuffer region = ensureRegionBuffer();
        if (region != null) {
            renderPass.setUniform("BlurRegion", region);
        }
    }

    int reserve(BuiltBlur blur) {
        int index = preparedBlurs.size();
        if (index == MAX_BLUR_RECTS) {
            return -1;
        }

        preparedBlurs.add(blur);
        paramsDirty = true;
        maxBlurRadius = Math.max(maxBlurRadius, blur.blurRadius());
        return index;
    }

    private void submit(GuiGraphics graphics, BuiltBlur blur) {
        if (blur == null || !blur.visible()) {
            return;
        }

        if (graphics == null) {
            return;
        }

        try {
            BuiltBlur normalized = normalize(blur);
            Matrix3x2f pose = Render2DCoordinateSpace.pose(graphics);
            ScreenRectangle scissorArea = ScissorUtil.current();
            pendingBlurs.add(new PendingBlur(normalized, pose, scissorArea));
            ((GuiGraphicsExtractorAccessor) graphics).releon$getGuiRenderState().submitGuiElement(new BlurRenderState(pose, normalized, scissorArea));
        } catch (RuntimeException ignored) {
        }
    }

    private GpuBuffer ensureParamsBuffer() {
        if (paramsDirty) {
            prepareBuffers();
        }

        if (!paramsDirty && paramsBuffer != null) {
            return paramsBuffer;
        }

        if (paramsDirty) {
            closeParamsBuffer();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer uniformData = buildUniformData(stack, preparedBlurs);
                paramsBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "RELEON_blur_params",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                        uniformData
                );
            }
            paramsDirty = false;
            return paramsBuffer;
        }

        if (paramsBuffer == null || paramsBuffer.isClosed() || paramsBuffer.size() < UNIFORM_BYTES) {
            closeParamsBuffer();
            paramsBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "RELEON_blur_params",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UNIFORM_BYTES
            );
        }

        return paramsBuffer;
    }

    public void prepareBuffers() {
        if (preparedBlurs.isEmpty() || !paramsDirty) {
            return;
        }

        GpuBuffer buffer = ensureWritableParamsBuffer();
        if (buffer == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uniformData = buildUniformData(stack, preparedBlurs);
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(buffer.slice(0, uniformData.remaining()), uniformData);
            paramsDirty = false;
        } catch (RuntimeException ignored) {
            paramsDirty = true;
        }
    }

    private GpuBuffer ensureWritableParamsBuffer() {
        if (paramsBuffer != null && !paramsBuffer.isClosed() && paramsBuffer.size() >= UNIFORM_BYTES) {
            return paramsBuffer;
        }

        closeParamsBuffer();

        try {
            paramsBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "RELEON_blur_params",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UNIFORM_BYTES
            );
            return paramsBuffer;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ByteBuffer buildUniformData(MemoryStack stack, List<BuiltBlur> batch) {
        int usedBytes = Math.max(1, batch.size()) * PARAMS_PER_RECT * FLOATS_PER_PARAM * Float.BYTES;
        ByteBuffer data = stack.calloc(usedBytes);

        for (int i = 0; i < batch.size(); i++) {
            BuiltBlur blur = batch.get(i);
            int offset = i * PARAMS_PER_RECT * FLOATS_PER_PARAM * Float.BYTES;

            data.putFloat(offset, blur.radiusTopLeft());
            data.putFloat(offset + 4, blur.radiusTopRight());
            data.putFloat(offset + 8, blur.radiusBottomRight());
            data.putFloat(offset + 12, blur.radiusBottomLeft());
            data.putFloat(offset + 16, blur.width());
            data.putFloat(offset + 20, blur.height());
            data.putFloat(offset + 24, blur.smoothness());
            data.putFloat(offset + 28, blur.blurRadius());
        }

        data.position(0);
        return data;
    }

    private void ensureBlurTargets(int width, int height, float renderScale, int iterations) {
        if (width <= 0 || height <= 0) {
            return;
        }

        int resultWidth = Math.max(Math.round(width * renderScale), 1);
        int resultHeight = Math.max(Math.round(height * renderScale), 1);

        if (blurTarget == null) {
            blurTarget = new TextureTarget("RELEON_blur_result", resultWidth, resultHeight, false);
        } else if (blurTarget.width != resultWidth || blurTarget.height != resultHeight) {
            blurTarget.resize(resultWidth, resultHeight);
        }

        int targetWidth = resultWidth;
        int targetHeight = resultHeight;
        for (int i = 0; i < iterations; i++) {
            downTargets[i] = ensureTarget(downTargets[i], "RELEON_blur_down_" + i, targetWidth, targetHeight);
            if (i < iterations - 1) {
                upTargets[i] = ensureTarget(upTargets[i], "RELEON_blur_up_" + i, targetWidth, targetHeight);
            }
            targetWidth = Math.max(targetWidth / 2, 1);
            targetHeight = Math.max(targetHeight / 2, 1);
        }
    }

    private TextureTarget ensureTarget(TextureTarget target, String name, int width, int height) {
        if (target == null) {
            return new TextureTarget(name, width, height, false);
        }

        if (target.width != width || target.height != height) {
            target.resize(width, height);
        }

        return target;
    }

    private boolean computeBlurRegion(Minecraft minecraft, RenderTarget mainTarget) {
        Window window = minecraft.getWindow();
        int guiWidth = Math.max(window.getGuiScaledWidth(), 1);
        int guiHeight = Math.max(window.getGuiScaledHeight(), 1);
        float scaleX = (float) mainTarget.width / (float) guiWidth;
        float scaleY = (float) mainTarget.height / (float) guiHeight;

        float minX = guiWidth;
        float minY = guiHeight;
        float maxX = 0.0f;
        float maxY = 0.0f;
        maxBlurRadius = 0.0f;
        boolean hasBounds = false;

        for (PendingBlur pendingBlur : pendingBlurs) {
            BuiltBlur blur = pendingBlur.blur();
            float padding = Render2DCoordinateSpace.toGui(Math.max(16.0f, blur.blurRadius() * 2.0f));
            ScreenRectangle bounds = new ScreenRectangle(
                    Math.round(blur.x()),
                    Math.round(blur.y()),
                    Math.round(blur.width()),
                    Math.round(blur.height())
            ).transformMaxBounds(pendingBlur.pose());
            if (pendingBlur.scissorArea() != null) {
                bounds = pendingBlur.scissorArea().intersection(bounds);
                if (bounds == null) {
                    continue;
                }
            }

            minX = Math.min(minX, bounds.left() - padding);
            minY = Math.min(minY, bounds.top() - padding);
            maxX = Math.max(maxX, bounds.right() + padding);
            maxY = Math.max(maxY, bounds.bottom() + padding);
            maxBlurRadius = Math.max(maxBlurRadius, blur.blurRadius());
            hasBounds = true;
        }
        if (!hasBounds) {
            return false;
        }

        minX = clamp(minX, 0.0f, guiWidth);
        minY = clamp(minY, 0.0f, guiHeight);
        maxX = clamp(maxX, minX + 1.0f, guiWidth);
        maxY = clamp(maxY, minY + 1.0f, guiHeight);

        int x0 = clampInt((int) Math.floor(minX * scaleX), 0, mainTarget.width - 1);
        int x1 = clampInt((int) Math.ceil(maxX * scaleX), x0 + 1, mainTarget.width);
        int y0 = clampInt((int) Math.floor((guiHeight - maxY) * scaleY), 0, mainTarget.height - 1);
        int y1 = clampInt((int) Math.ceil((guiHeight - minY) * scaleY), y0 + 1, mainTarget.height);

        regionX = x0;
        regionY = y0;
        regionWidth = Math.max(x1 - x0, 1);
        regionHeight = Math.max(y1 - y0, 1);
        return regionWidth > 0 && regionHeight > 0;
    }

    private void renderBlurChain(RenderTarget mainTarget, GpuTextureView sourceOverride) {
        if (blurTarget == null) {
            return;
        }

        int iterations = preparedIterations > 0 ? preparedIterations : computeIterations(maxBlurRadius);
        float offsetScale = clamp(maxBlurRadius / 18.0f, 0.85f, 2.0f);
        GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuTextureView source = sourceOverride != null ? sourceOverride : mainTarget.getColorTextureView();
        int sourceWidth = mainTarget.width;
        int sourceHeight = mainTarget.height;
        float sourceX = (float) regionX / Math.max(mainTarget.width, 1);
        float sourceY = (float) regionY / Math.max(mainTarget.height, 1);
        float sourceW = (float) regionWidth / Math.max(mainTarget.width, 1);
        float sourceH = (float) regionHeight / Math.max(mainTarget.height, 1);

        for (int i = 0; i < iterations; i++) {
            TextureTarget target = downTargets[i];
            renderKawasePass(KAWASE_DOWN_PIPELINE, source, sourceWidth, sourceHeight, target, offsetScale, sourceX, sourceY, sourceW, sourceH, sampler);
            source = target.getColorTextureView();
            sourceWidth = target.width;
            sourceHeight = target.height;
            sourceX = 0.0f;
            sourceY = 0.0f;
            sourceW = 1.0f;
            sourceH = 1.0f;
        }

        for (int i = iterations - 2; i >= 0; i--) {
            TextureTarget target = upTargets[i];
            renderKawasePass(KAWASE_UP_PIPELINE, source, sourceWidth, sourceHeight, target, offsetScale, 0.0f, 0.0f, 1.0f, 1.0f, sampler);
            source = target.getColorTextureView();
            sourceWidth = target.width;
            sourceHeight = target.height;
        }

        renderKawasePass(KAWASE_UP_PIPELINE, source, sourceWidth, sourceHeight, blurTarget, offsetScale, 0.0f, 0.0f, 1.0f, 1.0f, sampler);
    }

    private int computeIterations(float blurRadius) {
        if (blurRadius >= 52.0f) {
            return 4;
        }
        if (blurRadius >= 36.0f) {
            return 3;
        }
        return 2;
    }

    private float computeRenderScale(float blurRadius, int width, int height) {
        int area = Math.max(width, 1) * Math.max(height, 1);
        if (blurRadius >= 30.0f || area >= 220_000) {
            return MIN_BLUR_RENDER_SCALE;
        }
        if (blurRadius >= 18.0f || area >= 120_000) {
            return MEDIUM_BLUR_RENDER_SCALE;
        }
        return MAX_BLUR_RENDER_SCALE;
    }

    private void renderKawasePass(
            RenderPipeline pipeline,
            GpuTextureView source,
            int sourceWidth,
            int sourceHeight,
            TextureTarget target,
            float offsetScale,
            float sourceX,
            float sourceY,
            float sourceW,
            float sourceH,
            GpuSampler sampler
    ) {
        if (source == null || target == null) {
            return;
        }

        GpuBuffer fullscreenQuad = ensureFullscreenQuadBuffer();
        if (fullscreenQuad == null) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        RenderSystem.AutoStorageIndexBuffer indexBuffer = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GpuBuffer indices = indexBuffer.getBuffer(6);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uniformData = stack.calloc(KAWASE_UNIFORM_BYTES);
            uniformData.putFloat(0, sourceX);
            uniformData.putFloat(4, sourceY);
            uniformData.putFloat(8, sourceW);
            uniformData.putFloat(12, sourceH);
            uniformData.putFloat(16, offsetScale / Math.max(sourceWidth, 1));
            uniformData.putFloat(20, offsetScale / Math.max(sourceHeight, 1));
            uniformData.putFloat(32, skyFallbackRed);
            uniformData.putFloat(36, skyFallbackGreen);
            uniformData.putFloat(40, skyFallbackBlue);
            uniformData.putFloat(44, 1.0f);
            uniformData.position(0);

            GpuBuffer uniformBuffer = ensureKawaseParamsBuffer();
            if (uniformBuffer == null) {
                return;
            }

            encoder.writeToBuffer(uniformBuffer.slice(0, KAWASE_UNIFORM_BYTES), uniformData);

            try (RenderPass renderPass = encoder.createRenderPass(
                         () -> "RELEON_kawase_blur",
                         target.getColorTextureView(),
                         OptionalInt.of(ColorUtil.TRANSPARENT),
                         null,
                         OptionalDouble.empty()
                 )) {
                renderPass.setPipeline(pipeline);
                renderPass.bindTexture("Sampler0", source, sampler);
                renderPass.setUniform("KawaseParams", uniformBuffer);
                renderPass.setVertexBuffer(0, fullscreenQuad);
                renderPass.setIndexBuffer(indices, indexBuffer.type());
                renderPass.drawIndexed(0, 0, 6, 1);
            }
        }
    }

    private GpuBuffer ensureRegionBuffer() {
        if (regionDirty) {
            closeRegionBuffer();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer data = buildRegionData(stack);
                regionBuffer = RenderSystem.getDevice().createBuffer(
                        () -> "RELEON_blur_region",
                        GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                        data
                );
            }
            regionDirty = false;
            return regionBuffer;
        }

        if (regionBuffer == null || regionBuffer.isClosed() || regionBuffer.size() < REGION_UNIFORM_BYTES) {
            closeRegionBuffer();
            regionBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "RELEON_blur_region",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    REGION_UNIFORM_BYTES
            );
        }

        return regionBuffer;
    }

    private void uploadRegionBuffer() {
        GpuBuffer buffer = ensureWritableRegionBuffer();
        if (buffer == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = buildRegionData(stack);
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(buffer.slice(0, REGION_UNIFORM_BYTES), data);
            regionDirty = false;
        } catch (RuntimeException ignored) {
        }
    }

    private GpuBuffer ensureWritableRegionBuffer() {
        if (regionBuffer != null && !regionBuffer.isClosed() && regionBuffer.size() >= REGION_UNIFORM_BYTES) {
            return regionBuffer;
        }

        closeRegionBuffer();

        try {
            regionBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "RELEON_blur_region",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    REGION_UNIFORM_BYTES
            );
            return regionBuffer;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ByteBuffer buildRegionData(MemoryStack stack) {
        ByteBuffer data = stack.calloc(REGION_UNIFORM_BYTES);
        data.putFloat(0, regionX);
        data.putFloat(4, regionY);
        data.putFloat(8, regionWidth);
        data.putFloat(12, regionHeight);
        data.position(0);
        return data;
    }

    private GpuBuffer ensureKawaseParamsBuffer() {
        if (kawaseParamsBuffer == null || kawaseParamsBuffer.isClosed() || kawaseParamsBuffer.size() < KAWASE_UNIFORM_BYTES) {
            closeKawaseParamsBuffer();
            kawaseParamsBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "RELEON_kawase_params",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    KAWASE_UNIFORM_BYTES
            );
        }

        return kawaseParamsBuffer;
    }

    private GpuBuffer ensureFullscreenQuadBuffer() {
        if (fullscreenQuadBuffer != null) {
            return fullscreenQuadBuffer;
        }

        try (ByteBufferBuilder allocator = new ByteBufferBuilder(4 * DefaultVertexFormat.POSITION.getVertexSize());
             MeshData meshData = buildFullscreenQuad(allocator)) {
            fullscreenQuadBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "RELEON_blur_fullscreen_quad",
                    GpuBuffer.USAGE_VERTEX,
                    meshData.vertexBuffer()
            );
            return fullscreenQuadBuffer;
        }
    }

    private MeshData buildFullscreenQuad(ByteBufferBuilder allocator) {
        BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        builder.addVertex(-1.0f, -1.0f, 0.0f);
        builder.addVertex(-1.0f, 1.0f, 0.0f);
        builder.addVertex(1.0f, 1.0f, 0.0f);
        builder.addVertex(1.0f, -1.0f, 0.0f);
        return builder.buildOrThrow();
    }

    private BuiltBlur normalize(BuiltBlur blur) {
        float maxRadius = Math.max(0.0f, Math.min(blur.width(), blur.height()) * 0.5f);
        float radiusTopLeft = clamp(blur.radiusTopLeft(), 0.0f, maxRadius);
        float radiusTopRight = clamp(blur.radiusTopRight(), 0.0f, maxRadius);
        float radiusBottomRight = clamp(blur.radiusBottomRight(), 0.0f, maxRadius);
        float radiusBottomLeft = clamp(blur.radiusBottomLeft(), 0.0f, maxRadius);
        float smoothness = Math.max(0.0f, blur.smoothness());
        float blurRadius = Math.max(0.0f, blur.blurRadius());

        if (radiusTopLeft == blur.radiusTopLeft()
                && radiusTopRight == blur.radiusTopRight()
                && radiusBottomRight == blur.radiusBottomRight()
                && radiusBottomLeft == blur.radiusBottomLeft()
                && smoothness == blur.smoothness()
                && blurRadius == blur.blurRadius()) {
            return blur;
        }

        return new BuiltBlur(
                blur.x(),
                blur.y(),
                blur.width(),
                blur.height(),
                radiusTopLeft,
                radiusTopRight,
                radiusBottomRight,
                radiusBottomLeft,
                smoothness,
                blurRadius,
                blur.colorTopLeft(),
                blur.colorTopRight(),
                blur.colorBottomRight(),
                blur.colorBottomLeft()
        );
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void closeParamsBuffer() {
        if (paramsBuffer != null) {
            paramsBuffer.close();
            paramsBuffer = null;
        }
    }

    private void closeRegionBuffer() {
        if (regionBuffer != null) {
            regionBuffer.close();
            regionBuffer = null;
        }
    }

    private void closeKawaseParamsBuffer() {
        if (kawaseParamsBuffer != null) {
            kawaseParamsBuffer.close();
            kawaseParamsBuffer = null;
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("releon", path);
    }

    @Override
    public void close() {
        pendingBlurs.clear();
        preparedBlurs.clear();
        activeGraphics = null;
        captureRequested = false;
        blurTextureSetup = TextureSetup.noTexture();
        closeParamsBuffer();
        closeRegionBuffer();
        closeKawaseParamsBuffer();
        if (blurTarget != null) {
            blurTarget.destroyBuffers();
            blurTarget = null;
        }
        for (int i = 0; i < MAX_BLUR_ITERATIONS; i++) {
            if (downTargets[i] != null) {
                downTargets[i].destroyBuffers();
                downTargets[i] = null;
            }
            if (upTargets[i] != null) {
                upTargets[i].destroyBuffers();
                upTargets[i] = null;
            }
        }
        if (fullscreenQuadBuffer != null) {
            fullscreenQuadBuffer.close();
            fullscreenQuadBuffer = null;
        }
    }

    private record PendingBlur(BuiltBlur blur, Matrix3x2f pose, ScreenRectangle scissorArea) {
    }
}
