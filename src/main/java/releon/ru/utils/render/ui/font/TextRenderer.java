package releon.ru.utils.render.ui.font;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import releon.ru.access.GuiRenderStateLayerAccessor;
import releon.ru.mixin.accessor.GuiGraphicsExtractorAccessor;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ScissorUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TextRenderer implements AutoCloseable {
    private static volatile TextRenderer instance;

    public static final RenderPipeline TEXT_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/text_atlas"))
            .withVertexShader(id("ui/shared/text_atlas"))
            .withFragmentShader(id("ui/text_atlas/text_atlas"))
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withSampler("Sampler0")
            .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    public static final RenderPipeline TEXT_FADE_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/text_atlas_fade"))
            .withVertexShader(id("ui/shared/text_atlas"))
            .withFragmentShader(id("ui/text_atlas_fade/text_atlas_fade"))
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withSampler("Sampler0")
            .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    private final FontManager fontManager = new FontManager();
    private final Map<FrameBatchKey, TextRenderState> frameBatches = new LinkedHashMap<>(64);
    private GuiGraphics activeGraphics;

    private TextRenderer() {
    }

    public static TextRenderer getInstance() {
        TextRenderer local = instance;
        if (local == null) {
            synchronized (TextRenderer.class) {
                local = instance;
                if (local == null) {
                    local = new TextRenderer();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void closeInstance() {
        TextRenderer local = instance;
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

    public void enqueue(BuiltText text) {
        submit(activeGraphics, text);
    }

    public void draw(GuiGraphics graphics, BuiltText text) {
        beginFrame(graphics);
        enqueue(text);
        flush();
    }

    public void flush() {
        activeGraphics = null;
        frameBatches.clear();
    }

    public float width(String fontName, String text, float size) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }

        return fontManager.strike(fontName, size).width(text);
    }

    public GlyphLayout glyphLayout(String fontName, String text, float size) {
        if (text == null || text.isEmpty() || size <= 0.0f) {
            return GlyphLayout.EMPTY;
        }

        try {
            FontStrike strike = fontManager.strike(fontName, size);
            TextLayout layout = strike.layout(text);
            if (layout.empty()) {
                return GlyphLayout.EMPTY;
            }

            strike.uploadDirtyPages();
            List<GlyphPage> pages = new ArrayList<>(layout.pages().size());
            for (TextLayout.Page page : layout.pages()) {
                List<Glyph> glyphs = new ArrayList<>(page.glyphs().size());
                for (LayoutGlyph glyph : page.glyphs()) {
                    glyphs.add(new Glyph(
                            glyph.x0(),
                            glyph.y0(),
                            glyph.x1(),
                            glyph.y1(),
                            glyph.u0(),
                            glyph.v0(),
                            glyph.u1(),
                            glyph.v1()
                    ));
                }
                pages.add(new GlyphPage(page.page().textureSetup(), glyphs));
            }

            return new GlyphLayout(pages, layout.width(), layout.height());
        } catch (RuntimeException exception) {
            return GlyphLayout.EMPTY;
        }
    }

    private void submit(GuiGraphics graphics, BuiltText text) {
        if (graphics == null || text == null) {
            return;
        }

        BuiltText normalized = text;
        if (!normalized.visible()) {
            return;
        }

        try {
            FontStrike strike = fontManager.strike(normalized.fontName(), normalized.size());
            Matrix3x2f pose = Render2DCoordinateSpace.pose(graphics);
            TextLayout layout = strike.layout(normalized.text());
            if (layout.empty()) {
                return;
            }
            strike.uploadDirtyPages();

            GuiRenderState guiState = ((GuiGraphicsExtractorAccessor) graphics).releon$getGuiRenderState();
            int layerSerial = ((GuiRenderStateLayerAccessor) guiState).releon$getLayerSerial();
            PoseKey poseKey = PoseKey.of(pose);
            ScreenRectangle scissorArea = ScissorUtil.current();
            RenderPipeline pipeline = normalized.hasHorizontalFade() ? TEXT_FADE_PIPELINE : TEXT_PIPELINE;
            for (TextLayout.Page page : layout.pages()) {
                if (page.glyphs().isEmpty()) {
                    continue;
                }

                FrameBatchKey key = new FrameBatchKey(guiState, layerSerial, page.page(), poseKey, scissorArea, pipeline);
                TextRenderState state = frameBatches.get(key);
                if (state == null) {
                    state = new TextRenderState(pose, page.page(), scissorArea, pipeline);
                    frameBatches.put(key, state);
                    guiState.submitGlyphToCurrentLayer(state);
                }
                state.add(
                        page.glyphs(),
                        normalized.x(),
                        normalized.y(),
                        normalized.colorTopLeft(),
                        normalized.colorTopRight(),
                        normalized.colorBottomRight(),
                        normalized.colorBottomLeft(),
                        normalized.rotationDegrees(),
                        normalized.rotationOriginX(),
                        normalized.rotationOriginY(),
                        normalized.fadeLeft(),
                        normalized.fadeRight(),
                        normalized.fadeLeftX(),
                        normalized.fadeRightX(),
                        normalized.fadeWidth(),
                        normalized.fadeLeftStrength(),
                        normalized.fadeRightStrength()
                );
            }
        } catch (RuntimeException exception) {
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("releon", path);
    }

    @Override
    public void close() {
        activeGraphics = null;
        frameBatches.clear();
        fontManager.close();
    }

    private record FrameBatchKey(GuiRenderState state, int layerSerial, GlyphAtlasPage page, PoseKey pose, ScreenRectangle scissorArea, RenderPipeline pipeline) {
    }

    private record PoseKey(float m00, float m01, float m10, float m11, float m20, float m21) {
        static PoseKey of(Matrix3x2f matrix) {
            return new PoseKey(matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), matrix.m20(), matrix.m21());
        }
    }

    public record GlyphLayout(List<GlyphPage> pages, float width, float height) {
        public static final GlyphLayout EMPTY = new GlyphLayout(List.of(), 0.0f, 0.0f);

        public GlyphLayout {
            pages = List.copyOf(pages);
        }

        public boolean empty() {
            return pages.isEmpty();
        }
    }

    public record GlyphPage(TextureSetup textureSetup, List<Glyph> glyphs) {
        public GlyphPage {
            glyphs = List.copyOf(glyphs);
        }
    }

    public record Glyph(
            float x0,
            float y0,
            float x1,
            float y1,
            float u0,
            float v0,
            float u1,
            float v1
    ) {
    }
}
