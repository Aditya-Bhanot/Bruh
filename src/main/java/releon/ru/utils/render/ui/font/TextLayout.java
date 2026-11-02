package releon.ru.utils.render.ui.font;

import java.util.List;

final class TextLayout {
    static final TextLayout EMPTY = new TextLayout(List.of(), 0.0f, 0.0f);

    private final List<Page> pages;
    private final float width;
    private final float height;

    TextLayout(List<Page> pages, float width, float height) {
        this.pages = List.copyOf(pages);
        this.width = width;
        this.height = height;
    }

    List<Page> pages() {
        return pages;
    }

    float width() {
        return width;
    }

    float height() {
        return height;
    }

    boolean empty() {
        return pages.isEmpty();
    }

    record Page(GlyphAtlasPage page, List<LayoutGlyph> glyphs) {
        Page {
            glyphs = List.copyOf(glyphs);
        }
    }
}
