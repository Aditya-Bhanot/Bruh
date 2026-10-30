package releon.ru.utils.render.ui.font;

import releon.ru.utils.render.ui.Render2DCoordinateSpace;

final class FontQuality {
    static final int ATLAS_SIZE = 2048;
    static final int ATLAS_GAP = 2;
    static final int MAX_LAYOUT_CACHE = 2048;

    private FontQuality() {
    }

    static int oversampleFor(float size) {
        if (size <= 10.0f) {
            return 6;
        }
        if (size <= 14.0f) {
            return 5;
        }
        if (size <= 28.0f) {
            return 4;
        }
        if (size <= 64.0f) {
            return 3;
        }
        return 2;
    }

    static int glyphPadding(int oversample) {
        return Math.max(4, oversample + 2);
    }

    static float snapOrigin(float value) {
        float scale = Render2DCoordinateSpace.designGuiScale();
        return Math.round(value * scale) / scale;
    }

    static float coverageWeight(float size) {
        if (size <= 8.0f) {
            return 0.32f;
        }
        if (size <= 10.0f) {
            return 0.22f;
        }
        if (size <= 12.0f) {
            return 0.14f;
        }
        if (size <= 14.0f) {
            return 0.08f;
        }
        return 0.0f;
    }
}
