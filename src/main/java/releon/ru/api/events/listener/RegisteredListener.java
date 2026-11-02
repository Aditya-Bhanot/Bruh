package releon.ru.api.events.listener;

import releon.ru.api.events.Event;
import releon.ru.api.events.types.EventPriority;

public final class RegisteredListener<T extends Event> {
    private final Class<T> eventType;
    private final EventListener<? super T> listener;
    private final EventPriority priority;
    private final boolean receiveCancelled;
    private final Object owner;
    private final long order;

    public RegisteredListener(Class<T> eventType,
                              EventListener<? super T> listener,
                              EventPriority priority,
                              boolean receiveCancelled,
                              Object owner,
                              long order) {
        this.eventType = eventType;
        this.listener = listener;
        this.priority = priority;
        this.receiveCancelled = receiveCancelled;
        this.owner = owner;
        this.order = order;
    }

    public Class<T> getEventType() {
        return eventType;
    }

    public EventListener<? super T> getListener() {
        return listener;
    }

    public EventPriority getPriority() {
        return priority;
    }

    public boolean isReceiveCancelled() {
        return receiveCancelled;
    }

    public Object getOwner() {
        return owner;
    }

    public long getOrder() {
        return order;
    }
}
