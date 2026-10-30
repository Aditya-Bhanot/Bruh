package releon.ru.utils.render.ui.effecticon;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import releon.ru.access.GuiRenderStateLayerAccessor;
import releon.ru.mixin.accessor.GuiGraphicsExtractorAccessor;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EffectIconRenderer implements AutoCloseable {
    private static volatile EffectIconRenderer instance;

    private static final VertexFormat EFFECT_ICON_VERTEX_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .build();

    public static final RenderPipeline EFFECT_ICON_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/effect_icon"))
            .withVertexShader(id("ui/effect_icon/effect_icon"))
            .withFragmentShader(id("ui/effect_icon/effect_icon"))
            .withVertexFormat(EFFECT_ICON_VERTEX_FORMAT, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withSampler("Sampler0")
            .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    private final Map<Identifier, CachedTexture> textures = new HashMap<>();
    private final Map<FrameBatchKey, EffectIconRenderState> frameBatches = new LinkedHashMap<>(16);
    private GuiGraphics activeGraphics;

    private EffectIconRenderer() {
    }

    public static EffectIconRenderer getInstance() {
        EffectIconRenderer local = instance;
        if (local == null) {
            synchronized (EffectIconRenderer.class) {
                local = instance;
                if (local == null) {
                    local = new EffectIconRenderer();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void closeInstance() {
        EffectIconRenderer local = instance;
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

    public void enqueue(BuiltEffectIcon icon) {
        submit(activeGraphics, icon);
    }

    public void flush() {
        activeGraphics = null;
        frameBatches.clear();
    }

    public void barrier() {
        frameBatches.clear();
    }

    private void submit(GuiGraphics graphics, BuiltEffectIcon icon) {
        if (graphics == null || icon == null || !icon.visible()) {
            return;
        }

        TextureAtlasSprite sprite = resolveSprite(icon);
        if (sprite == null) {
            return;
        }

        EffectIconTexture texture = resolveTexture(sprite.atlasLocation());
        if (texture == null) {
            return;
        }

        EffectIconQuad quad = new EffectIconQuad(
                icon.x(),
                icon.y(),
                icon.size(),
                sprite.getU0(),
                sprite.getV0(),
                sprite.getU1(),
                sprite.getV1(),
                normalizeColor(icon.color())
        );

        try {
            GuiRenderState guiState = ((GuiGraphicsExtractorAccessor) graphics).releon$getGuiRenderState();
            int layerSerial = ((GuiRenderStateLayerAccessor) guiState).releon$getLayerSerial();
            Matrix3x2f pose = Render2DCoordinateSpace.pose(graphics);
            FrameBatchKey key = new FrameBatchKey(guiState, layerSerial, texture.id(), PoseKey.of(pose));
            EffectIconRenderState state = frameBatches.get(key);
            if (state == null) {
                state = new EffectIconRenderState(pose, texture);
                state.add(quad);
                frameBatches.put(key, state);
                guiState.submitGuiElement(state);
            } else {
                state.add(quad);
            }
        } catch (RuntimeException exception) {
        }
    }

    private TextureAtlasSprite resolveSprite(BuiltEffectIcon icon) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || icon.effect() == null) {
            return null;
        }

        TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.GUI);
        Identifier spriteId = Gui.getMobEffectSprite(icon.effect());
        return atlas.getSprite(spriteId);
    }

    private EffectIconTexture resolveTexture(Identifier atlasLocation) {
        if (atlasLocation == null) {
            return null;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return null;
        }

        AbstractTexture texture = minecraft.getTextureManager().getTexture(atlasLocation);
        if (texture == null || texture.getTextureView() == null) {
            return null;
        }

        CachedTexture cached = textures.get(atlasLocation);
        if (cached != null && cached.texture() == texture) {
            return cached.value();
        }

        TextureSetup setup = TextureSetup.singleTexture(
                texture.getTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
        );
        EffectIconTexture value = new EffectIconTexture(atlasLocation, setup);
        textures.put(atlasLocation, new CachedTexture(texture, value));
        return value;
    }

    private static int normalizeColor(int color) {
        if ((color & 0xFF000000) == 0 && (color & 0x00FFFFFF) != 0) {
            return color | 0xFF000000;
        }
        return color;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("releon", path);
    }

    @Override
    public void close() {
        frameBatches.clear();
        textures.clear();
        activeGraphics = null;
    }

    private record FrameBatchKey(GuiRenderState state, int layerSerial, Identifier texture, PoseKey pose) {
    }

    private record PoseKey(float m00, float m01, float m10, float m11, float m20, float m21) {
        static PoseKey of(Matrix3x2f matrix) {
            return new PoseKey(matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), matrix.m20(), matrix.m21());
        }
    }

    private record CachedTexture(AbstractTexture texture, EffectIconTexture value) {
    }
}
