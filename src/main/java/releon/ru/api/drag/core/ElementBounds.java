package releon.ru.api.drag.core;

public record ElementBounds(float x, float y, float width, float height) {
    public boolean contains(float pointX, float pointY) {
        return contains(pointX, pointY, 0.0f);
    }

    public boolean contains(float pointX, float pointY, float padding) {
        return pointX >= x - padding
                && pointX <= x + width + padding
                && pointY >= y - padding
                && pointY <= y + height + padding;
    }
}
