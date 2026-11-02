package releon.ru.api.module.impl.visual.esp;

import releon.ru.utils.render.ui.font.FontType;

record TagPart(FontType font, String text, int color, float yOffset) {
    TagPart(FontType font, String text, int color) {
        this(font, text, color, 0.0F);
    }

    TagPart withYOffset(float yOffset) {
        return new TagPart(font, text, color, yOffset);
    }
}
