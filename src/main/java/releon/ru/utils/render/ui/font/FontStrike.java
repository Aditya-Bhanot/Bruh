package releon.ru.utils.render.ui.font;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphMetrics;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FontStrike implements AutoCloseable {
    private final FontFamily family;
    private final float size;
    private final int oversample;
    private final int glyphPadding;
    private final FontRenderContext renderContext;
    private final Map<GlyphKey, GlyphInfo> glyphs = new HashMap<>();
    private final Map<String, Font> physicalFonts = new HashMap<>();
    private final Map<String, TextLayout> layouts = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TextLayout> eldest) {
            return size() > FontQuality.MAX_LAYOUT_CACHE;
        }
    };
    private final Map<String, Float> widths = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Float> eldest) {
            return size() > FontQuality.MAX_LAYOUT_CACHE;
        }
    };
    private final List<GlyphAtlasPage> pages = new ArrayList<>();
    private final float ascent;
    private final float lineHeight;
    private final float spaceAdvance;

    FontStrike(FontFamily family, float size) {
        this.family = family;
        this.size = size;
        this.oversample = FontQuality.oversampleFor(size);
        this.glyphPadding = FontQuality.glyphPadding(oversample);
        this.renderContext = new FontRenderContext(
                new AffineTransform(),
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON
        );

        Font metricsFont = family.primary().deriveFont(Font.PLAIN, size * oversample);
        LineMetrics metrics = metricsFont.getLineMetrics("Mg", renderContext);
        this.ascent = metrics.getAscent() / oversample;
        this.lineHeight = Math.max(size, metrics.getHeight() / oversample);
        this.spaceAdvance = Math.max(metricsFont.createGlyphVector(renderContext, " ").getGlyphMetrics(0).getAdvance() / oversample, size * 0.25f);
    }

    float ascent() {
        return ascent;
    }

    float lineHeight() {
        return lineHeight;
    }

    GlyphInfo glyph(int codePoint) {
        if (codePoint == '\t') {
            GlyphInfo space = glyph(' ');
            return space.withAdvance(space.advance() * 4.0f);
        }

        Font sourceFont = family.resolve(codePoint);
        Font font = physicalFont(sourceFont);
        GlyphVector vector = font.createGlyphVector(renderContext, new String(Character.toChars(codePoint)));
        int glyphCode = vector.getGlyphCode(0);
        float advance = vector.getGlyphMetrics(0).getAdvance() / oversample;
        return glyph(sourceFont, glyphCode, advance);
    }

    Font fontFor(int codePoint) {
        return family.resolve(codePoint);
    }

    TextLayout layout(String text) {
        if (text == null || text.isEmpty()) {
            return TextLayout.EMPTY;
        }
        return layouts.computeIfAbsent(text, this::buildLayout);
    }

    float width(String text) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }
        TextLayout layout = layouts.get(text);
        if (layout != null) {
            return layout.width();
        }
        return widths.computeIfAbsent(text, this::measureWidth);
    }

    RunLayout layoutRun(Font sourceFont, String text) {
        if (text == null || text.isEmpty()) {
            return RunLayout.EMPTY;
        }

        Font font = physicalFont(sourceFont);
        char[] chars = text.toCharArray();
        int flags = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).isRightToLeft()
                ? Font.LAYOUT_RIGHT_TO_LEFT
                : Font.LAYOUT_LEFT_TO_RIGHT;
        GlyphVector vector = font.layoutGlyphVector(renderContext, chars, 0, chars.length, flags);
        int glyphCount = vector.getNumGlyphs();
        if (glyphCount == 0) {
            return RunLayout.EMPTY;
        }

        List<ShapedGlyph> shapedGlyphs = new ArrayList<>(glyphCount);
        float minX = 0.0f;
        float maxX = 0.0f;
        for (int glyphIndex = 0; glyphIndex < glyphCount; glyphIndex++) {
            Point2D position = vector.getGlyphPosition(glyphIndex);
            Point2D next = vector.getGlyphPosition(glyphIndex + 1);
            float glyphX = (float) position.getX() / oversample;
            float glyphY = (float) position.getY() / oversample;
            float advance = Math.abs((float) (next.getX() - position.getX()) / oversample);
            GlyphInfo glyph = glyph(sourceFont, vector.getGlyphCode(glyphIndex), advance);
            if (glyph.drawable()) {
                minX = Math.min(minX, glyphX + glyph.xOffset());
                maxX = Math.max(maxX, glyphX + glyph.xOffset() + glyph.width());
            } else {
                maxX = Math.max(maxX, glyphX + glyph.advance());
            }
            shapedGlyphs.add(new ShapedGlyph(glyph, glyphX, glyphY));
        }

        float runAdvance = Math.max(Math.abs((float) vector.getGlyphPosition(glyphCount).getX() / oversample), maxX - minX);
        float shiftX = minX < 0.0f ? -minX : 0.0f;
        if (shiftX > 0.0f) {
            List<ShapedGlyph> shifted = new ArrayList<>(shapedGlyphs.size());
            for (ShapedGlyph shapedGlyph : shapedGlyphs) {
                shifted.add(new ShapedGlyph(shapedGlyph.glyph(), shapedGlyph.x() + shiftX, shapedGlyph.y()));
            }
            shapedGlyphs = shifted;
            runAdvance += shiftX;
        }

        return new RunLayout(shapedGlyphs, runAdvance);
    }

    private float measureWidth(String text) {
        float cursorX = 0.0f;
        float maxWidth = 0.0f;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);

            if (codePoint == '\n') {
                i += Character.charCount(codePoint);
                maxWidth = Math.max(maxWidth, cursorX);
                cursorX = 0.0f;
                continue;
            }

            if (codePoint == '\t') {
                i += Character.charCount(codePoint);
                cursorX += spaceAdvance * 4.0f;
                continue;
            }

            if (Character.isISOControl(codePoint)) {
                i += Character.charCount(codePoint);
                continue;
            }

            Font font = fontFor(codePoint);
            int runStart = i;
            i += Character.charCount(codePoint);
            while (i < text.length()) {
                int nextCodePoint = text.codePointAt(i);
                if (nextCodePoint == '\n' || nextCodePoint == '\t' || Character.isISOControl(nextCodePoint)) {
                    break;
                }
                Font nextFont = fontFor(nextCodePoint);
                if (!sameFont(font, nextFont)) {
                    break;
                }
                i += Character.charCount(nextCodePoint);
            }

            cursorX += measureRun(font, text.substring(runStart, i));
        }

        return Math.max(maxWidth, cursorX);
    }

    private float measureRun(Font sourceFont, String text) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }

        Font font = physicalFont(sourceFont);
        char[] chars = text.toCharArray();
        int flags = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).isRightToLeft()
                ? Font.LAYOUT_RIGHT_TO_LEFT
                : Font.LAYOUT_LEFT_TO_RIGHT;
        GlyphVector vector = font.layoutGlyphVector(renderContext, chars, 0, chars.length, flags);
        int glyphCount = vector.getNumGlyphs();
        if (glyphCount == 0) {
            return 0.0f;
        }

        float advance = Math.abs((float) vector.getGlyphPosition(glyphCount).getX() / oversample);
        if (advance > 0.0f) {
            return advance;
        }
        return (float) font.getStringBounds(text, renderContext).getWidth() / oversample;
    }

    void uploadDirtyPages() {
        for (GlyphAtlasPage page : pages) {
            page.uploadIfDirty();
        }
    }

    private GlyphInfo glyph(Font sourceFont, int glyphCode, float fallbackAdvance) {
        GlyphKey key = new GlyphKey(FontFamily.fontKey(sourceFont), glyphCode);
        return glyphs.computeIfAbsent(key, ignored -> bakeGlyph(sourceFont, glyphCode, fallbackAdvance));
    }

    private static boolean sameFont(Font left, Font right) {
        return left == right || left != null && left.equals(right);
    }

    private TextLayout buildLayout(String text) {
        Map<GlyphAtlasPage, List<LayoutGlyph>> batches = new java.util.LinkedHashMap<>();
        float cursorX = 0.0f;
        float cursorY = 0.0f;
        float maxWidth = 0.0f;
        float baselineY = ascent;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);

            if (codePoint == '\n') {
                i += Character.charCount(codePoint);
                maxWidth = Math.max(maxWidth, cursorX);
                cursorX = 0.0f;
                cursorY += lineHeight;
                continue;
            }

            if (codePoint == '\t') {
                i += Character.charCount(codePoint);
                cursorX += glyph('\t').advance();
                continue;
            }

            if (Character.isISOControl(codePoint)) {
                i += Character.charCount(codePoint);
                continue;
            }

            Font font = fontFor(codePoint);
            int runStart = i;
            i += Character.charCount(codePoint);
            while (i < text.length()) {
                int nextCodePoint = text.codePointAt(i);
                if (nextCodePoint == '\n' || nextCodePoint == '\t' || Character.isISOControl(nextCodePoint)) {
                    break;
                }
                Font nextFont = fontFor(nextCodePoint);
                if (!sameFont(font, nextFont)) {
                    break;
                }
                i += Character.charCount(nextCodePoint);
            }

            RunLayout run = layoutRun(font, text.substring(runStart, i));
            for (ShapedGlyph shapedGlyph : run.glyphs()) {
                GlyphInfo glyph = shapedGlyph.glyph();
                if (!glyph.drawable()) {
                    continue;
                }

                float x0 = cursorX + shapedGlyph.x() + glyph.xOffset();
                float y0 = baselineY + cursorY + shapedGlyph.y() + glyph.yOffset();
                float x1 = x0 + glyph.width();
                float y1 = y0 + glyph.height();

                batches.computeIfAbsent(glyph.page(), ignored -> new ArrayList<>())
                        .add(new LayoutGlyph(glyph.page(), x0, y0, x1, y1, glyph.u0(), glyph.v0(), glyph.u1(), glyph.v1()));
            }
            cursorX += run.advance();
        }

        maxWidth = Math.max(maxWidth, cursorX);
        List<TextLayout.Page> pages = new ArrayList<>(batches.size());
        for (Map.Entry<GlyphAtlasPage, List<LayoutGlyph>> entry : batches.entrySet()) {
            pages.add(new TextLayout.Page(entry.getKey(), entry.getValue()));
        }
        return new TextLayout(pages, maxWidth, cursorY + lineHeight);
    }

    private GlyphInfo bakeGlyph(Font sourceFont, int glyphCode, float fallbackAdvance) {
        Font font = physicalFont(sourceFont);
        GlyphVector vector = font.createGlyphVector(renderContext, new int[]{glyphCode});
        GlyphMetrics metrics = vector.getGlyphMetrics(0);
        float advance = Math.max(fallbackAdvance > 0.0f ? fallbackAdvance : metrics.getAdvance() / oversample, 0.0f);

        java.awt.Rectangle bounds = vector.getPixelBounds(renderContext, 0.0f, 0.0f);
        if (bounds.width <= 0 || bounds.height <= 0) {
            return GlyphInfo.empty(advance);
        }

        int imageWidth = bounds.width + glyphPadding * 2;
        int imageHeight = bounds.height + glyphPadding * 2;
        if (imageWidth <= 0 || imageHeight <= 0 || imageWidth > FontQuality.ATLAS_SIZE || imageHeight > FontQuality.ATLAS_SIZE) {
            return GlyphInfo.empty(advance);
        }

        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setColor(Color.WHITE);
        graphics.drawGlyphVector(vector, glyphPadding - bounds.x, glyphPadding - bounds.y);
        graphics.dispose();
        strengthenSmallGlyph(image);

        GlyphAtlasPage.Allocation allocation = allocate(imageWidth, imageHeight);
        if (allocation == null) {
            return GlyphInfo.empty(advance);
        }

        allocation.page().copy(image, allocation.x(), allocation.y());

        float xOffset = (bounds.x - glyphPadding) / (float) oversample;
        float yOffset = (bounds.y - glyphPadding) / (float) oversample;
        float width = imageWidth / (float) oversample;
        float height = imageHeight / (float) oversample;

        return new GlyphInfo(
                allocation.page(),
                allocation.u0(),
                allocation.v0(),
                allocation.u1(),
                allocation.v1(),
                xOffset,
                yOffset,
                width,
                height,
                advance
        );
    }

    private GlyphAtlasPage.Allocation allocate(int width, int height) {
        for (GlyphAtlasPage page : pages) {
            GlyphAtlasPage.Allocation allocation = page.allocate(width, height);
            if (allocation != null) {
                return allocation;
            }
        }

        GlyphAtlasPage page = new GlyphAtlasPage(FontQuality.ATLAS_SIZE);
        pages.add(page);
        return page.allocate(width, height);
    }

    private Font physicalFont(Font sourceFont) {
        String key = FontFamily.fontKey(sourceFont);
        return physicalFonts.computeIfAbsent(key, ignored -> sourceFont.deriveFont(Font.PLAIN, size * oversample));
    }

    private void strengthenSmallGlyph(BufferedImage image) {
        float weight = FontQuality.coverageWeight(size);
        if (weight <= 0.0f) {
            return;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int[] source = image.getRGB(0, 0, width, height, null, 0, width);
        int[] target = source.clone();
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int index = row + x;
                int alpha = (source[index] >>> 24) & 0xFF;
                int neighborAlpha = alpha;
                neighborAlpha = Math.max(neighborAlpha, (source[index - 1] >>> 24) & 0xFF);
                neighborAlpha = Math.max(neighborAlpha, (source[index + 1] >>> 24) & 0xFF);
                neighborAlpha = Math.max(neighborAlpha, (source[index - width] >>> 24) & 0xFF);
                neighborAlpha = Math.max(neighborAlpha, (source[index + width] >>> 24) & 0xFF);
                neighborAlpha = Math.max(neighborAlpha, (source[index - width - 1] >>> 24) & 0xFF);
                neighborAlpha = Math.max(neighborAlpha, (source[index - width + 1] >>> 24) & 0xFF);
                neighborAlpha = Math.max(neighborAlpha, (source[index + width - 1] >>> 24) & 0xFF);
                neighborAlpha = Math.max(neighborAlpha, (source[index + width + 1] >>> 24) & 0xFF);
                int boostedAlpha = Math.min(255, Math.round(alpha + (neighborAlpha - alpha) * weight));
                target[index] = (boostedAlpha << 24) | 0x00FFFFFF;
            }
        }
        image.setRGB(0, 0, width, height, target, 0, width);
    }

    @Override
    public void close() {
        glyphs.clear();
        physicalFonts.clear();
        layouts.clear();
        widths.clear();
        for (GlyphAtlasPage page : pages) {
            page.close();
        }
        pages.clear();
    }

    record ShapedGlyph(GlyphInfo glyph, float x, float y) {
    }

    record RunLayout(List<ShapedGlyph> glyphs, float advance) {
        static final RunLayout EMPTY = new RunLayout(List.of(), 0.0f);
    }

    private record GlyphKey(String fontKey, int glyphCode) {
    }
}
