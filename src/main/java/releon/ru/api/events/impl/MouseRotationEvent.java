package releon.ru.api.events.impl;

import releon.ru.api.events.CancellableEvent;

public final class MouseRotationEvent extends CancellableEvent {
    private float cursorDeltaX;
    private float cursorDeltaY;

    public MouseRotationEvent(float cursorDeltaX, float cursorDeltaY) {
        this.cursorDeltaX = cursorDeltaX;
        this.cursorDeltaY = cursorDeltaY;
    }

    public float getCursorDeltaX() {
        return cursorDeltaX;
    }

    public void setCursorDeltaX(float cursorDeltaX) {
        this.cursorDeltaX = cursorDeltaX;
    }

    public float getCursorDeltaY() {
        return cursorDeltaY;
    }

    public void setCursorDeltaY(float cursorDeltaY) {
        this.cursorDeltaY = cursorDeltaY;
    }
}
