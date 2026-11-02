package releon.ru.utils.render.ui.font;

record GlyphInfo(
        GlyphAtlasPage page,
        float u0,
        float v0,
        float u1,
        float v1,
        float xOffset,
        float yOffset,
        float width,
        float height,
        float advance
) {
    static GlyphInfo empty(float advance) {
        return new GlyphInfo(null, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, advance);
    }

    boolean drawable() {
        return page != null && width > 0.0f && height > 0.0f;
    }

    GlyphInfo withAdvance(float advance) {
        return new GlyphInfo(page, u0, v0, u1, v1, xOffset, yOffset, width, height, advance);
    }
}
