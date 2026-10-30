package releon.ru.api.drag.core;

public final class ElementComponent {
    private static final float DEFAULT_MIN_WIDTH = 12.0f;
    private static final float DEFAULT_MIN_HEIGHT = 12.0f;
    private static final float SCREEN_MARGIN = 4.0f;
    private static final float SNAP_DISTANCE = 6.0f;

    private final String id;
    private final String title;
    private final float defaultX;
    private final float defaultY;
    private float targetX;
    private float targetY;
    private float x;
    private float y;
    private float width = DEFAULT_MIN_WIDTH;
    private float height = DEFAULT_MIN_HEIGHT;
    private float minWidth = DEFAULT_MIN_WIDTH;
    private float minHeight = DEFAULT_MIN_HEIGHT;
    private float hitExpandLeft;
    private float hitExpandTop;
    private float hitExpandRight;
    private float hitExpandBottom;
    private boolean visible = true;
    private boolean locked;
    private boolean moving;
    private float pointerOffsetX;
    private float pointerOffsetY;
    private int order;

    ElementComponent(String id, String title, float defaultX, float defaultY, int order) {
        this.id = id;
        this.title = title;
        this.defaultX = defaultX;
        this.defaultY = defaultY;
        this.targetX = defaultX;
        this.targetY = defaultY;
        this.x = defaultX;
        this.y = defaultY;
        this.order = order;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float targetX() {
        return targetX;
    }

    public float targetY() {
        return targetY;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public boolean visible() {
        return visible;
    }

    public boolean locked() {
        return locked;
    }

    public boolean moving() {
        return moving;
    }

    public int order() {
        return order;
    }

    public ElementBounds bounds() {
        return new ElementBounds(x, y, width, height);
    }

    public ElementBounds dragBounds() {
        return new ElementBounds(x - hitExpandLeft, y - hitExpandTop, width + hitExpandLeft + hitExpandRight, height + hitExpandTop + hitExpandBottom);
    }

    public ElementComponent size(float width, float height) {
        if (Float.isFinite(width)) {
            this.width = Math.max(minWidth, width);
        }
        if (Float.isFinite(height)) {
            this.height = Math.max(minHeight, height);
        }
        return this;
    }

    public ElementComponent minimumSize(float minWidth, float minHeight) {
        if (Float.isFinite(minWidth)) {
            this.minWidth = Math.max(1.0f, minWidth);
        }
        if (Float.isFinite(minHeight)) {
            this.minHeight = Math.max(1.0f, minHeight);
        }
        return size(width, height);
    }

    public ElementComponent hitExpansion(float left, float top, float right, float bottom) {
        hitExpandLeft = safeExpansion(left);
        hitExpandTop = safeExpansion(top);
        hitExpandRight = safeExpansion(right);
        hitExpandBottom = safeExpansion(bottom);
        return this;
    }

    public ElementComponent position(float x, float y) {
        if (Float.isFinite(x)) {
            targetX = x;
            this.x = x;
        }
        if (Float.isFinite(y)) {
            targetY = y;
            this.y = y;
        }
        return this;
    }

    public ElementComponent visible(boolean visible) {
        this.visible = visible;
        return this;
    }

    public ElementComponent locked(boolean locked) {
        this.locked = locked;
        if (locked) {
            moving = false;
        }
        return this;
    }

    public ElementComponent resetPosition() {
        return position(defaultX, defaultY);
    }

    void order(int order) {
        this.order = order;
    }

    boolean hit(float mouseX, float mouseY, float padding) {
        return visible && !locked && dragBounds().contains(mouseX, mouseY, padding);
    }

    void beginMove(float mouseX, float mouseY) {
        moving = true;
        pointerOffsetX = mouseX - x;
        pointerOffsetY = mouseY - y;
    }

    void moveTo(float mouseX, float mouseY, ElementScreen screen) {
        if (!moving) {
            return;
        }

        targetX = mouseX - pointerOffsetX;
        targetY = mouseY - pointerOffsetY;
        x = targetX;
        y = targetY;
        snap(screen);
        commitClamp(screen);
    }

    void endMove() {
        moving = false;
    }

    void applyState(float x, float y, float width, float height, boolean visible, int order) {
        position(x, y);
        size(width, height);
        this.visible = visible;
        this.order = order;
    }

    public void clamp(ElementScreen screen) {
        if (screen == null || !screen.valid()) {
            return;
        }
        x = clampedX(screen, targetX);
        y = clampedY(screen, targetY);
    }

    public void commitClamp(ElementScreen screen) {
        if (screen == null || !screen.valid()) {
            return;
        }
        targetX = clampedX(screen, targetX);
        targetY = clampedY(screen, targetY);
        x = targetX;
        y = targetY;
    }

    private void snap(ElementScreen screen) {
        if (screen == null || !screen.valid()) {
            return;
        }

        float right = screen.width() - width - hitExpandRight - SCREEN_MARGIN;
        float bottom = screen.height() - height - hitExpandBottom - SCREEN_MARGIN;
        float centerX = (screen.width() - width - hitExpandRight + hitExpandLeft) * 0.5f;
        float centerY = (screen.height() - height - hitExpandBottom + hitExpandTop) * 0.5f;

        if (Math.abs(targetX - (SCREEN_MARGIN + hitExpandLeft)) <= SNAP_DISTANCE) {
            targetX = SCREEN_MARGIN + hitExpandLeft;
        } else if (Math.abs(targetX - right) <= SNAP_DISTANCE) {
            targetX = right;
        } else if (Math.abs(targetX - centerX) <= SNAP_DISTANCE) {
            targetX = centerX;
        }

        if (Math.abs(targetY - (SCREEN_MARGIN + hitExpandTop)) <= SNAP_DISTANCE) {
            targetY = SCREEN_MARGIN + hitExpandTop;
        } else if (Math.abs(targetY - bottom) <= SNAP_DISTANCE) {
            targetY = bottom;
        } else if (Math.abs(targetY - centerY) <= SNAP_DISTANCE) {
            targetY = centerY;
        }
    }

    private float clampedX(ElementScreen screen, float value) {
        return clamp(value, SCREEN_MARGIN + hitExpandLeft, Math.max(SCREEN_MARGIN + hitExpandLeft, screen.width() - width - hitExpandRight - SCREEN_MARGIN));
    }

    private float clampedY(ElementScreen screen, float value) {
        return clamp(value, SCREEN_MARGIN + hitExpandTop, Math.max(SCREEN_MARGIN + hitExpandTop, screen.height() - height - hitExpandBottom - SCREEN_MARGIN));
    }

    private static float safeExpansion(float value) {
        return Float.isFinite(value) ? Math.max(0.0f, value) : 0.0f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
