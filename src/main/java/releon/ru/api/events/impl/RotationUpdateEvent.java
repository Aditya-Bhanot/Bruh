package releon.ru.api.events.impl;

import releon.ru.api.events.Event;
import releon.ru.api.events.types.EventPhase;

public final class RotationUpdateEvent implements Event {
    private final EventPhase phase;

    public RotationUpdateEvent(EventPhase phase) {
        this.phase = phase;
    }

    public EventPhase getPhase() {
        return phase;
    }

    public boolean isPre() {
        return phase == EventPhase.PRE;
    }

    public boolean isPost() {
        return phase == EventPhase.POST;
    }
}
