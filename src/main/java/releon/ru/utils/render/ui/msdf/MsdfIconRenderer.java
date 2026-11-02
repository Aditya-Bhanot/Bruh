package releon.ru.utils.render.ui.msdf;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.joml.Matrix3x2f;
import releon.ru.access.GuiRenderStateLayerAccessor;
import releon.ru.mixin.accessor.GuiGraphicsExtractorAccessor;
import releon.ru.utils.render.ScissorUtil;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ui.font.FontType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class MsdfIconRenderer implements AutoCloseable {
    private static volatile MsdfIconRenderer instance;
    private static final int MAX_WIDTH_CACHE = 512;
    private static final VertexFormat MSDF_ICON_VERTEX_FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .add("LineWidth", VertexFormatElement.LINE_WIDTH)
            .build();

    public static final RenderPipeline MSDF_ICON_PIPELINE = RenderPipeline.builder()
            .withLocation(id("pipeline/msdf_icon"))
            .withVertexShader(id("ui/msdf_icon/msdf_icon"))
            .withFragmentShader(id("ui/msdf_icon/msdf_icon"))
            .withVertexFormat(MSDF_ICON_VERTEX_FORMAT, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withCull(false)
            .withSampler("Sampler0")
            .withUniform("DynamicTransforms", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
            .build();

    private final Map<String, Atlas> atlases = new HashMap<>();
    private final Map<FrameBatchKey, MsdfIconRenderState> frameBatches = new LinkedHashMap<>(32);
    private final Map<WidthKey, Float> widthCache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<WidthKey, Float> eldest) {
            return size() > MAX_WIDTH_CACHE;
        }
    };
    private GuiGraphics activeGraphics;

    private MsdfIconRenderer() {
    }

    public static MsdfIconRenderer getInstance() {
        MsdfIconRenderer local = instance;
        if (local == null) {
            synchronized (MsdfIconRenderer.class) {
                local = instance;
                if (local == null) {
                    local = new MsdfIconRenderer();
                    instance = local;
                }
            }
        }
        return local;
    }

    public static void closeInstance() {
        MsdfIconRenderer local = instance;
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

    public void draw(FontType font, String text, float x, float y, float size, int color) {
        draw(font, text, x, y, size, color, color, color, color, 0.0f, x, y, false, false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    public void draw(
            FontType font,
            String text,
            float x,
            float y,
            float size,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft,
            float rotationDegrees,
            float rotationOriginX,
            float rotationOriginY,
            boolean fadeLeft,
            boolean fadeRight,
            float fadeLeftX,
            float fadeRightX,
            float fadeWidth,
            float fadeLeftStrength,
            float fadeRightStrength
    ) {
        if (activeGraphics == null || font == null || text == null || text.isEmpty() || size <= 0.0f || maxAlpha(colorTopLeft, colorTopRight, colorBottomRight, colorBottomLeft) <= 0) {
            return;
        }
        Atlas atlas = atlas(font);
        TextureSetup setup = textureSetup(font, atlas);
        if (setup == null) {
            return;
        }

        try {
            GuiRenderState guiState = ((GuiGraphicsExtractorAccessor) activeGraphics).releon$getGuiRenderState();
            int layerSerial = ((GuiRenderStateLayerAccessor) guiState).releon$getLayerSerial();
            Matrix3x2f pose = Render2DCoordinateSpace.pose(activeGraphics);
            ScreenRectangle scissorArea = ScissorUtil.current();
            FrameBatchKey key = new FrameBatchKey(guiState, layerSerial, PoseKey.of(pose), scissorArea, setup);
            MsdfIconRenderState state = frameBatches.get(key);
            if (state == null) {
                state = new MsdfIconRenderState(pose, setup, scissorArea, atlas.distanceRange());
                frameBatches.put(key, state);
                guiState.submitGlyphToCurrentLayer(state);
            }
            float cursorX = x;
            float scale = size / atlas.fontSize();
            int index = 0;
            while (index < text.length()) {
                int codePoint = text.codePointAt(index);
                index += Character.charCount(codePoint);
                MsdfGlyph glyph = glyph(atlas, codePoint);
                if (glyph == null) {
                    continue;
                }

                if (glyph.drawable()) {
                    float glyphX = cursorX + glyph.xOffset() * scale;
                    float glyphY = y + glyph.yOffset() * scale;
                    float glyphWidth = glyph.width() * scale;
                    float glyphHeight = glyph.height() * scale;
                    state.add(
                            glyphX,
                            glyphY,
                            glyphWidth,
                            glyphHeight,
                            glyph,
                            normalizeColor(colorTopLeft),
                            normalizeColor(colorTopRight),
                            normalizeColor(colorBottomRight),
                            normalizeColor(colorBottomLeft),
                            rotationDegrees,
                            rotationOriginX,
                            rotationOriginY,
                            fadeLeft,
                            fadeRight,
                            fadeLeftX,
                            fadeRightX,
                            fadeWidth,
                            fadeLeftStrength,
                            fadeRightStrength
                    );
                }
                cursorX += glyph.advance() * scale;
            }
        } catch (RuntimeException ignored) {
        }
    }

    public boolean canRender(FontType font, String text) {
        if (font == null || text == null || text.isEmpty()) {
            return false;
        }
        Atlas atlas = atlas(font);
        if (!atlas.ready()) {
            return false;
        }
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) {
                return false;
            }
            if (glyph(atlas, codePoint) == null) {
                return false;
            }
        }
        return true;
    }

    public float width(FontType font, String text, float size) {
        if (font == null || text == null || text.isEmpty() || size <= 0.0f) {
            return 0.0f;
        }
        Atlas atlas = atlas(font);
        if (!atlas.ready()) {
            return 0.0f;
        }
        if (text.codePointCount(0, text.length()) == 1) {
            MsdfGlyph glyph = glyph(atlas, text.codePointAt(0));
            return glyph == null ? 0.0f : glyph.advance() * (size / atlas.fontSize());
        }
        WidthKey key = new WidthKey(font.fontName(), text, normalizeSize(size));
        Float cached = widthCache.get(key);
        if (cached != null) {
            return cached;
        }
        float scale = size / atlas.fontSize();
        float width = 0.0f;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            MsdfGlyph glyph = glyph(atlas, codePoint);
            if (glyph != null) {
                width += glyph.advance() * scale;
            }
        }
        widthCache.put(key, width);
        return width;
    }

    public void flush() {
        activeGraphics = null;
        frameBatches.clear();
    }

    private Atlas atlas(FontType font) {
        return atlases.computeIfAbsent(font.fontName(), ignored -> load(font));
    }

    private Atlas load(FontType font) {
        Map<Integer, MsdfGlyph> glyphs = new HashMap<>();
        MsdfGlyph[] asciiGlyphs = new MsdfGlyph[128];
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getResourceManager() == null) {
            return new Atlas(font.path(), glyphs, asciiGlyphs, null, 96.0f, 8.0f);
        }
        Identifier jsonId = Identifier.fromNamespaceAndPath("releon", "ui/msdf/" + font.path() + ".json");
        Optional<Resource> resource = minecraft.getResourceManager().getResource(jsonId);
        if (resource.isEmpty()) {
            return new Atlas(font.path(), glyphs, asciiGlyphs, null, 96.0f, 8.0f);
        }
        float fontSize = 96.0f;
        float distanceRange = 8.0f;
        try (InputStream stream = resource.get().open();
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject atlas = root.getAsJsonObject("atlas");
            float atlasWidth = getFloat(atlas, "width", 4096.0f);
            float atlasHeight = getFloat(atlas, "height", 4096.0f);
            fontSize = getFloat(atlas, "size", 96.0f);
            distanceRange = Math.max(1.0f, getFloat(atlas, "distanceRange", 8.0f));
            boolean originBottom = atlas.has("yOrigin") && "bottom".equalsIgnoreCase(atlas.get("yOrigin").getAsString());

            for (JsonElement element : root.getAsJsonArray("glyphs")) {
                JsonObject glyph = element.getAsJsonObject();
                if (!glyph.has("unicode")) {
                    continue;
                }
                int unicode = glyph.get("unicode").getAsInt();
                MsdfGlyph msdfGlyph;
                if (glyph.has("atlasBounds") && glyph.has("planeBounds")) {
                    JsonObject atlasBounds = glyph.getAsJsonObject("atlasBounds");
                    JsonObject planeBounds = glyph.getAsJsonObject("planeBounds");

                    float left = getFloat(atlasBounds, "left", 0.0f);
                    float bottom = getFloat(atlasBounds, "bottom", 0.0f);
                    float right = getFloat(atlasBounds, "right", 0.0f);
                    float top = getFloat(atlasBounds, "top", 0.0f);
                    float textureY = originBottom ? atlasHeight - top : bottom;

                    float planeLeft = getFloat(planeBounds, "left", 0.0f);
                    float planeBottom = getFloat(planeBounds, "bottom", 0.0f);
                    float planeRight = getFloat(planeBounds, "right", 0.0f);
                    float planeTop = getFloat(planeBounds, "top", 0.0f);
                    float ascender = 0.95f;

                    msdfGlyph = new MsdfGlyph(
                            planeLeft * fontSize,
                            (ascender - planeTop) * fontSize,
                            (planeRight - planeLeft) * fontSize,
                            (planeTop - planeBottom) * fontSize,
                            getFloat(glyph, "advance", 1.0f) * fontSize,
                            left / atlasWidth,
                            textureY / atlasHeight,
                            right / atlasWidth,
                            (textureY + top - bottom) / atlasHeight
                    );
                } else {
                    msdfGlyph = new MsdfGlyph(0.0f, 0.0f, 0.0f, 0.0f, getFloat(glyph, "advance", 1.0f) * fontSize, 0.0f, 0.0f, 0.0f, 0.0f);
                }
                glyphs.put(unicode, msdfGlyph);
                if (unicode >= 0 && unicode < asciiGlyphs.length) {
                    asciiGlyphs[unicode] = msdfGlyph;
                }
            }
        } catch (Exception ignored) {
            glyphs.clear();
            java.util.Arrays.fill(asciiGlyphs, null);
        }
        return new Atlas(font.path(), glyphs, asciiGlyphs, null, fontSize, distanceRange);
    }

    private TextureSetup textureSetup(FontType font, Atlas atlas) {
        if (atlas.textureSetup() != null) {
            return atlas.textureSetup();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return null;
        }
        Identifier textureId = Identifier.fromNamespaceAndPath("releon", "ui/msdf/" + font.path() + ".png");
        AbstractTexture texture = minecraft.getTextureManager().getTexture(textureId);
        if (texture == null || texture.getTextureView() == null) {
            return null;
        }
        TextureSetup setup = TextureSetup.singleTexture(
                texture.getTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        );
        atlases.put(font.fontName(), atlas.withTextureSetup(setup));
        return setup;
    }

    private static float getFloat(JsonObject object, String key, float fallback) {
        return object != null && object.has(key) ? object.get(key).getAsFloat() : fallback;
    }

    private static int normalizeColor(int color) {
        return color;
    }

    private static int effectiveAlpha(int color) {
        return (color >>> 24) & 0xFF;
    }

    private static int maxAlpha(int... colors) {
        int alpha = 0;
        for (int color : colors) {
            alpha = Math.max(alpha, effectiveAlpha(color));
        }
        return alpha;
    }

    private static MsdfGlyph glyph(Atlas atlas, int codePoint) {
        if (codePoint >= 0 && codePoint < atlas.asciiGlyphs().length) {
            return atlas.asciiGlyphs()[codePoint];
        }
        return atlas.glyphs().get(codePoint);
    }

    private static float normalizeSize(float size) {
        return Math.max(1.0f, Math.min(512.0f, Math.round(size * 4.0f) / 4.0f));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("releon", path);
    }

    @Override
    public void close() {
        atlases.clear();
        frameBatches.clear();
        widthCache.clear();
        activeGraphics = null;
    }

    private record Atlas(String path, Map<Integer, MsdfGlyph> glyphs, MsdfGlyph[] asciiGlyphs, TextureSetup textureSetup, float fontSize, float distanceRange) {
        private boolean ready() {
            return !glyphs.isEmpty();
        }

        private Atlas withTextureSetup(TextureSetup setup) {
            return new Atlas(path, glyphs, asciiGlyphs, setup, fontSize, distanceRange);
        }
    }

    private record FrameBatchKey(GuiRenderState state, int layerSerial, PoseKey pose, ScreenRectangle scissorArea, TextureSetup textureSetup) {
    }

    private record WidthKey(String fontName, String text, float size) {
    }

    private record PoseKey(float m00, float m01, float m10, float m11, float m20, float m21) {
        static PoseKey of(Matrix3x2f matrix) {
            return new PoseKey(matrix.m00(), matrix.m01(), matrix.m10(), matrix.m11(), matrix.m20(), matrix.m21());
        }
    }
}
