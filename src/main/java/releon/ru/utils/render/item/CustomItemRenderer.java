package releon.ru.utils.render.item;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import releon.ru.access.GuiRenderStateLayerAccessor;
import releon.ru.mixin.accessor.GuiGraphicsExtractorAccessor;
import releon.ru.mixin.accessor.ItemLayerRenderStateAccessor;
import releon.ru.mixin.accessor.ItemStackRenderStateAccessor;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CustomItemRenderer implements AutoCloseable {
    private static final int MAX_ITEM_QUADS = 4096;
    private static final int MAX_GEOMETRY_CACHE_ENTRIES = 512;
    private static final int FLOATS_PER_PARAM = 4;
    private static final int UNIFORM_BYTES = MAX_ITEM_QUADS * FLOATS_PER_PARAM * Float.BYTES;
    private static final float MIN_VISIBLE_AREA = 0.000002f;
    private static final float MIN_BOUNDS_SPAN = 0.0001f;
    private static final float PRESERVED_MODEL_SCALE_CAP = 1.08f;
    private static final float PRESERVED_MODEL_DEPTH_EPSILON = 0.02f;
    private static final float SHADE_UP = 0.98f;
    private static final float SHADE_SIDE_LIGHT = 0.90f;
    private static final float SHADE_SIDE = 0.84f;
    private static final float SHADE_SIDE_DARK = 0.78f;
    private static final float SHADE_DOWN = 0.72f;

    private static volatile CustomItemRenderer instance;

    private static final VertexFormat ITEM_VERTEX_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .add("LineWidth", VertexFormatElement.LINE_WIDTH)
            .build();

    public static final RenderPipeline ITEM_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/item"))
            .withVertexShader(id("ui/item/item"))
            .withFragmentShader(id("ui/item/item"))
            .withVertexFormat(ITEM_VERTEX_FORMAT, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withSampler("Sampler0")
            .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("ItemParamsArray", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    private final Map<Identifier, CachedTexture> textures = new HashMap<>();
    private final Map<GeometryCacheKey, CachedItemGeometry> geometryCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<GeometryCacheKey, CachedItemGeometry> eldest) {
            return size() > MAX_GEOMETRY_CACHE_ENTRIES;
        }
    };
    private final Map<FrameBatchKey, ItemRenderState> frameBatches = new LinkedHashMap<>(32);
    private final float[] preparedGlints = new float[MAX_ITEM_QUADS];
    private int preparedQuadCount;
    private GuiGraphics activeGraphics;
    private GpuBuffer paramsBuffer;
    private boolean paramsDirty;
    private ServerCacheKey lastServerCacheKey;

    private CustomItemRenderer() {
    }

    public static CustomItemRenderer getInstance() {
        CustomItemRenderer local = instance;
        if (local == null) {
            synchronized (CustomItemRenderer.class) {
                local = instance;
                if (local == null) {
                    local = new CustomItemRenderer();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void closeInstance() {
        CustomItemRenderer local = instance;
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

    public void enqueue(BuiltRenderItem item) {
        submit(activeGraphics, item);
    }

    public void flush() {
        activeGraphics = null;
        frameBatches.clear();
    }

    public void beginGuiFrame() {
        preparedQuadCount = 0;
        paramsDirty = false;
    }

    public boolean isItemPipeline(RenderPipeline pipeline) {
        return pipeline == ITEM_PIPELINE;
    }

    public void bindParams(RenderPass renderPass) {
        if (renderPass == null || preparedQuadCount == 0) {
            return;
        }

        GpuBuffer buffer = ensureParamsBuffer();
        if (buffer != null) {
            renderPass.setUniform("ItemParamsArray", buffer);
        }
    }

    public void prepareBuffers() {
        if (preparedQuadCount == 0 || !paramsDirty) {
            return;
        }

        GpuBuffer buffer = ensureWritableParamsBuffer();
        if (buffer == null) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uniformData = buildUniformData(stack);
            RenderSystem.getDevice()
                    .createCommandEncoder()
                    .writeToBuffer(buffer.slice(0, uniformData.remaining()), uniformData);
            paramsDirty = false;
        } catch (RuntimeException ignored) {
            paramsDirty = true;
        }
    }

    int reserve(float glintStrength) {
        int index = preparedQuadCount;
        if (index == MAX_ITEM_QUADS) {
            return -1;
        }

        preparedGlints[index] = glintStrength;
        preparedQuadCount++;
        paramsDirty = true;
        return index;
    }

    private void submit(GuiGraphics graphics, BuiltRenderItem item) {
        if (graphics == null || item == null || !item.visible()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getItemModelResolver() == null) {
            return;
        }
        refreshServerCacheKey(minecraft);

        try {
            submitSpecialItem(graphics, item);
        } catch (RuntimeException exception) {
        }
    }

    private CachedItemGeometry resolveGeometry(Minecraft minecraft, BuiltRenderItem item) {
        TrackingItemStackRenderState renderState = buildRenderState(minecraft, item.stack(), item.seed());
        GeometryCacheKey key = GeometryCacheKey.of(item.stack(), item.seed(), renderState.getModelIdentity());
        CachedItemGeometry cached = geometryCache.get(key);
        if (cached != null) {
            return cached;
        }

        CachedItemGeometry geometry = buildGeometry(item.stack(), renderState);
        if (!geometry.animated()) {
            geometryCache.put(key, geometry);
        }
        return geometry;
    }

    private TrackingItemStackRenderState buildRenderState(Minecraft minecraft, ItemStack stack, int seed) {
        TrackingItemStackRenderState renderState = new TrackingItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(
                renderState,
                stack,
                ItemDisplayContext.GUI,
                minecraft.level,
                minecraft.player,
                seed
        );
        return renderState;
    }

    private CachedItemGeometry buildGeometry(ItemStack stack, ItemStackRenderState renderState) {
        ItemStackRenderStateAccessor stateAccessor = (ItemStackRenderStateAccessor) renderState;
        ItemStackRenderState.LayerRenderState[] layers = stateAccessor.releon$getLayers();
        int activeLayerCount = Math.min(stateAccessor.releon$getActiveLayerCount(), layers.length);

        for (int i = 0; i < activeLayerCount; i++) {
            if (hasSpecialRenderer(layers[i])) {
                return new CachedItemGeometry(List.of(), renderState.isAnimated(), true);
            }
        }

        List<RawItemQuad> rawQuads = new ArrayList<>(Math.max(8, activeLayerCount * 8));
        Bounds bounds = new Bounds();

        for (int i = 0; i < activeLayerCount; i++) {
            collectLayerGeometry(rawQuads, bounds, layers[i], stack);
        }

        rawQuads.sort(Comparator.comparingDouble(RawItemQuad::depth));
        NormalizedBounds normalizedBounds = NormalizedBounds.of(bounds);
        List<CachedItemQuad> quads = new ArrayList<>(rawQuads.size());

        for (RawItemQuad rawQuad : rawQuads) {
            quads.add(buildCachedQuad(rawQuad, normalizedBounds));
        }

        return new CachedItemGeometry(List.copyOf(quads), renderState.isAnimated(), false);
    }

    private boolean hasSpecialRenderer(ItemStackRenderState.LayerRenderState layer) {
        if (layer == null) {
            return false;
        }

        ItemLayerRenderStateAccessor accessor = (ItemLayerRenderStateAccessor) layer;
        return accessor.releon$getSpecialRenderer() != null;
    }

    private void collectLayerGeometry(List<RawItemQuad> output, Bounds bounds, ItemStackRenderState.LayerRenderState layer, ItemStack stack) {
        if (layer == null) {
            return;
        }

        List<BakedQuad> quads = layer.prepareQuadList();
        if (quads.isEmpty()) {
            return;
        }

        ItemLayerRenderStateAccessor accessor = (ItemLayerRenderStateAccessor) layer;
        ItemStackRenderState.FoilType foilType = accessor.releon$getFoilType();
        boolean foil = foilType != ItemStackRenderState.FoilType.NONE;
        int[] tints = preparedTints(layer, accessor, maxTintLayer(quads));
        Matrix4f transform = layerTransform(accessor);
        boolean preserveModelScale = accessor.releon$getUsesBlockLight();
        if (preserveModelScale) {
            bounds.markPreserveModelScale();
        }

        for (BakedQuad quad : quads) {
            RawItemQuad rawQuad = buildRawQuad(quad, transform, tintColor(quad, tints, stack), foil, preserveModelScale);
            if (rawQuad.area() < MIN_VISIBLE_AREA) {
                continue;
            }

            bounds.include(rawQuad.x0(), rawQuad.y0(), rawQuad.z0());
            bounds.include(rawQuad.x1(), rawQuad.y1(), rawQuad.z1());
            bounds.include(rawQuad.x2(), rawQuad.y2(), rawQuad.z2());
            bounds.include(rawQuad.x3(), rawQuad.y3(), rawQuad.z3());
            output.add(rawQuad);
        }
    }

    private void submitQuad(
            GuiRenderState guiState,
            int layerSerial,
            Matrix3x2f pose,
            PoseKey poseKey,
            ItemTexture texture,
            CachedItemQuad quad,
            float x,
            float y,
            float size,
            int color,
            float glintStrength
    ) {
        FrameBatchKey key = new FrameBatchKey(guiState, layerSerial, texture.id(), poseKey);
        ItemRenderState state = frameBatches.get(key);
        if (state == null) {
            state = new ItemRenderState(pose, texture);
            state.add(quad, x, y, size, color, glintStrength);
            frameBatches.put(key, state);
            guiState.submitGuiElement(state);
        } else {
            state.add(quad, x, y, size, color, glintStrength);
        }
    }

    private void submitSpecialItem(GuiGraphics graphics, BuiltRenderItem item) {
        float scale = item.size() / 16.0f;
        if (scale <= 0.0f) {
            return;
        }

        graphics.pose().pushMatrix();
        try {
            Render2DCoordinateSpace.applyGuiScaleIndependence(graphics.pose());
            graphics.pose().translate(item.x(), item.y());
            graphics.pose().scale(scale);
            graphics.renderItem(item.stack(), 0, 0, item.seed());
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private RawItemQuad buildRawQuad(BakedQuad quad, Matrix4f transform, int tint, boolean foil, boolean shadeModel) {
        Vector3fc p0 = quad.position0();
        Vector3fc p1 = quad.position1();
        Vector3fc p2 = quad.position2();
        Vector3fc p3 = quad.position3();
        Vector3f t0 = transformPosition(p0, transform);
        Vector3f t1 = transformPosition(p1, transform);
        Vector3f t2 = transformPosition(p2, transform);
        Vector3f t3 = transformPosition(p3, transform);

        long uv0 = quad.packedUV0();
        long uv1 = quad.packedUV1();
        long uv2 = quad.packedUV2();
        long uv3 = quad.packedUV3();
        float depth = (t0.z + t1.z + t2.z + t3.z) * 0.25f;
        int shadedTint = shadeModel ? shadeColor(tint, faceShade(quad.direction())) : tint;

        return new RawItemQuad(
                quad.sprite().atlasLocation(),
                t0.x, t0.y, UVPair.unpackU(uv0), UVPair.unpackV(uv0),
                t1.x, t1.y, UVPair.unpackU(uv1), UVPair.unpackV(uv1),
                t2.x, t2.y, UVPair.unpackU(uv2), UVPair.unpackV(uv2),
                t3.x, t3.y, UVPair.unpackU(uv3), UVPair.unpackV(uv3),
                shadedTint,
                foil,
                depth,
                projectedArea(t0.x, t0.y, t1.x, t1.y, t2.x, t2.y, t3.x, t3.y),
                t0.z,
                t1.z,
                t2.z,
                t3.z
        );
    }

    private CachedItemQuad buildCachedQuad(RawItemQuad quad, NormalizedBounds bounds) {
        return new CachedItemQuad(
                quad.atlas(),
                bounds.x(quad.x0()), bounds.y(quad.y0()), quad.u0(), quad.v0(),
                bounds.x(quad.x1()), bounds.y(quad.y1()), quad.u1(), quad.v1(),
                bounds.x(quad.x2()), bounds.y(quad.y2()), quad.u2(), quad.v2(),
                bounds.x(quad.x3()), bounds.y(quad.y3()), quad.u3(), quad.v3(),
                quad.tint(),
                quad.foil()
        );
    }

    private Matrix4f layerTransform(ItemLayerRenderStateAccessor accessor) {
        PoseStack.Pose pose = new PoseStack.Pose();
        ItemTransform transform = accessor.releon$getItemTransform();
        if (transform == null) {
            transform = ItemTransform.NO_TRANSFORM;
        }

        transform.apply(false, pose);
        return new Matrix4f(pose.pose());
    }

    private static Vector3f transformPosition(Vector3fc position, Matrix4f transform) {
        return new Vector3f(position).mulPosition(transform);
    }

    private ItemTexture resolveTexture(Identifier atlas) {
        if (atlas == null) {
            return null;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }

        TextureManager textureManager = minecraft.getTextureManager();
        if (textureManager == null) {
            return null;
        }

        AbstractTexture texture = textureManager.getTexture(atlas);
        if (texture == null || texture.getTextureView() == null) {
            return null;
        }

        CachedTexture cached = textures.get(atlas);
        if (cached != null && cached.texture() == texture) {
            return cached.value();
        }

        TextureSetup setup = TextureSetup.singleTexture(
                texture.getTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        );
        ItemTexture value = new ItemTexture(atlas, setup);
        textures.put(atlas, new CachedTexture(texture, value));
        return value;
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
            ByteBuffer uniformData = buildUniformData(stack);
            paramsBuffer = RenderSystem.getDevice().createBuffer(() -> "RELEON_item_params", GpuBuffer.USAGE_UNIFORM, uniformData);
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
                    () -> "RELEON_item_params",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    UNIFORM_BYTES
            );
            return paramsBuffer;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ByteBuffer buildUniformData(MemoryStack stack) {
        int usedBytes = Math.max(1, preparedQuadCount) * FLOATS_PER_PARAM * Float.BYTES;
        ByteBuffer data = stack.calloc(usedBytes);
        float time = (System.nanoTime() % 30_000_000_000L) / 1_000_000_000.0f;
        for (int i = 0; i < preparedQuadCount; i++) {
            int offset = i * FLOATS_PER_PARAM * Float.BYTES;
            data.putFloat(offset, preparedGlints[i]);
            data.putFloat(offset + 4, time);
            data.putFloat(offset + 8, 0.0f);
            data.putFloat(offset + 12, 0.0f);
        }
        data.position(0);
        return data;
    }

    private static int maxTintLayer(List<BakedQuad> quads) {
        int max = 0;
        for (BakedQuad quad : quads) {
            if (quad.isTinted()) {
                max = Math.max(max, quad.tintIndex() + 1);
            }
        }
        return max;
    }

    private static int[] preparedTints(ItemStackRenderState.LayerRenderState layer, ItemLayerRenderStateAccessor accessor, int tintLayerCount) {
        int[] tints = accessor.releon$getTintLayers();
        if (tints == null || tints.length < tintLayerCount) {
            tints = layer.prepareTintLayers(tintLayerCount);
        }
        return tints;
    }

    private static int tintColor(BakedQuad quad, int[] tints, ItemStack stack) {
        int tint = 0xFFFFFFFF;
        if (quad.isTinted()) {
            int tintIndex = quad.tintIndex();
            if (tintIndex >= 0 && tintIndex < tints.length) {
                tint = normalizeColor(tints[tintIndex]);
            }
            if (isPotionStack(stack) && !isPotionOverlayQuad(quad)) {
                return 0xFFFFFFFF;
            }
            if (isWhiteTint(tint) && isPotionOverlayQuad(quad)) {
                tint = potionTint(stack, tint);
            }
        }
        return tint;
    }

    private static boolean isPotionStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.has(DataComponents.POTION_CONTENTS);
    }

    private static boolean isPotionOverlayQuad(BakedQuad quad) {
        if (quad == null || quad.sprite() == null || quad.sprite().contents() == null || quad.sprite().contents().name() == null) {
            return false;
        }
        return "minecraft:item/potion_overlay".equals(quad.sprite().contents().name().toString());
    }

    private static int potionTint(ItemStack stack, int fallback) {
        if (stack == null || stack.isEmpty()) {
            return fallback;
        }

        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents == null ? fallback : 0xFF000000 | (contents.getColor() & 0x00FFFFFF);
    }

    private static boolean isWhiteTint(int color) {
        return normalizeColor(color) == 0xFFFFFFFF;
    }

    private static int multiplyColor(int base, int tint, float alphaMultiplier) {
        base = normalizeColor(base);
        tint = normalizeColor(tint);
        int a = Math.round(((base >>> 24) & 0xFF) * (((tint >>> 24) & 0xFF) / 255.0f) * alphaMultiplier);
        int r = ((base >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF) / 255;
        int g = ((base >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF) / 255;
        int b = (base & 0xFF) * (tint & 0xFF) / 255;
        return (clampInt(a, 0, 255) << 24) | (r << 16) | (g << 8) | b;
    }

    private static int normalizeColor(int color) {
        if ((color & 0xFF000000) == 0 && (color & 0x00FFFFFF) != 0) {
            return color | 0xFF000000;
        }
        return color;
    }

    private static float projectedArea(
            float x0,
            float y0,
            float x1,
            float y1,
            float x2,
            float y2,
            float x3,
            float y3
    ) {
        return Math.abs(
                cross(x0, y0, x1, y1)
                        + cross(x1, y1, x2, y2)
                        + cross(x2, y2, x3, y3)
                        + cross(x3, y3, x0, y0)
        ) * 0.5f;
    }

    private static float cross(float ax, float ay, float bx, float by) {
        return ax * by - bx * ay;
    }

    private static float faceShade(Direction direction) {
        if (direction == null) {
            return SHADE_SIDE;
        }

        return switch (direction) {
            case UP -> SHADE_UP;
            case DOWN -> SHADE_DOWN;
            case NORTH -> SHADE_SIDE_LIGHT;
            case SOUTH -> SHADE_SIDE;
            case EAST, WEST -> SHADE_SIDE_DARK;
        };
    }

    private static int shadeColor(int color, float shade) {
        color = normalizeColor(color);
        int a = (color >>> 24) & 0xFF;
        int r = clampInt(Math.round(((color >>> 16) & 0xFF) * shade), 0, 255);
        int g = clampInt(Math.round(((color >>> 8) & 0xFF) * shade), 0, 255);
        int b = clampInt(Math.round((color & 0xFF) * shade), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
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

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("releon", path);
    }

    @Override
    public void close() {
        frameBatches.clear();
        preparedQuadCount = 0;
        geometryCache.clear();
        textures.clear();
        activeGraphics = null;
        closeParamsBuffer();
    }

    public void clearCaches() {
        geometryCache.clear();
        textures.clear();
    }

    private void refreshServerCacheKey(Minecraft minecraft) {
        ServerCacheKey key = ServerCacheKey.of(minecraft);
        if (key.equals(lastServerCacheKey)) {
            return;
        }

        geometryCache.clear();
        textures.clear();
        lastServerCacheKey = key;
    }

    private record FrameBatchKey(GuiRenderState state, int layerSerial, Identifier texture, PoseKey pose) {
    }

    private record PoseKey(float m00, float m01, float m10, float m11, float m20, float m21) {
        static PoseKey of(Matrix3x2f matrix) {
            return new PoseKey(matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), matrix.m20(), matrix.m21());
        }
    }

    private record CachedTexture(AbstractTexture texture, ItemTexture value) {
    }

    private record GeometryCacheKey(Item item, DataComponentMap components, int count, int seed, Object modelIdentity) {
        static GeometryCacheKey of(ItemStack stack, int seed, Object modelIdentity) {
            return new GeometryCacheKey(stack.getItem(), stack.immutableComponents(), stack.getCount(), seed, stableModelIdentity(modelIdentity));
        }

        private static Object stableModelIdentity(Object modelIdentity) {
            if (modelIdentity instanceof List<?> list) {
                return List.copyOf(list);
            }
            return modelIdentity;
        }
    }

    private record ServerCacheKey(String address, int protocol) {
        static ServerCacheKey of(Minecraft minecraft) {
            if (minecraft == null || minecraft.getConnection() == null || minecraft.getConnection().getServerData() == null) {
                return new ServerCacheKey("", 0);
            }
            return new ServerCacheKey(minecraft.getConnection().getServerData().ip, minecraft.getConnection().getServerData().protocol);
        }
    }

    private record RawItemQuad(
            Identifier atlas,
            float x0,
            float y0,
            float u0,
            float v0,
            float x1,
            float y1,
            float u1,
            float v1,
            float x2,
            float y2,
            float u2,
            float v2,
            float x3,
            float y3,
            float u3,
            float v3,
            int tint,
            boolean foil,
            float depth,
            float area,
            float z0,
            float z1,
            float z2,
            float z3
    ) {
    }

    private static final class Bounds {
        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        private float maxZ = -Float.MAX_VALUE;
        private boolean preserveModelScale;

        private void include(float x, float y, float z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        private boolean defined() {
            return minX <= maxX && minY <= maxY;
        }

        private void markPreserveModelScale() {
            preserveModelScale = true;
        }

        private boolean preserveModelScale() {
            return preserveModelScale || maxZ - minZ > PRESERVED_MODEL_DEPTH_EPSILON;
        }
    }

    private record NormalizedBounds(float minX, float maxY, float scale, float offsetX, float offsetY) {
        private static NormalizedBounds of(Bounds bounds) {
            if (!bounds.defined()) {
                return new NormalizedBounds(0.0f, 1.0f, 1.0f, 0.0f, 0.0f);
            }

            float width = Math.max(MIN_BOUNDS_SPAN, bounds.maxX - bounds.minX);
            float height = Math.max(MIN_BOUNDS_SPAN, bounds.maxY - bounds.minY);
            float span = Math.max(width, height);
            if (bounds.preserveModelScale()) {
                float scale = Math.min(1.0f / span, PRESERVED_MODEL_SCALE_CAP);
                return new NormalizedBounds(
                        (bounds.minX + bounds.maxX) * 0.5f,
                        (bounds.minY + bounds.maxY) * 0.5f,
                        scale,
                        0.5f,
                        0.5f
                );
            }

            float scale = 1.0f / span;
            return new NormalizedBounds(
                    bounds.minX,
                    bounds.maxY,
                    scale,
                    (1.0f - width * scale) * 0.5f,
                    (1.0f - height * scale) * 0.5f
            );
        }

        private float x(float value) {
            return offsetX + (value - minX) * scale;
        }

        private float y(float value) {
            return offsetY + (maxY - value) * scale;
        }
    }
}
