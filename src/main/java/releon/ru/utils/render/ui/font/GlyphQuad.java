package releon.ru.utils.render.ui.font;

record GlyphQuad(
        float x0,
        float y0,
        float x1,
        float y1,
        float x2,
        float y2,
        float x3,
        float y3,
        float u0,
        float v0,
        float u1,
        float v1,
        int colorTopLeft,
        int colorTopRight,
        int colorBottomRight,
        int colorBottomLeft
) {
}
