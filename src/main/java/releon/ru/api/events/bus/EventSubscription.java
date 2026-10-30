package releon.ru.api.events.bus;

import releon.ru.api.events.Event;
import releon.ru.api.events.listener.RegisteredListener;

import java.util.concurrent.atomic.AtomicBoolean;

public final class EventSubscription implements AutoCloseable {
    private final EventBus eventBus;
    private final Class<? extends Event> eventType;
    private final RegisteredListener<?> listener;
    private final AtomicBoolean active = new AtomicBoolean(true);

    EventSubscription(EventBus eventBus, Class<? extends Event> eventType, RegisteredListener<?> listener) {
        this.eventBus = eventBus;
        this.eventType = eventType;
        this.listener = listener;
    }

    public boolean isActive() {
        return active.get();
    }

    public void unsubscribe() {
        if (active.compareAndSet(true, false)) {
            eventBus.unsubscribe(eventType, listener);
        }
    }

    @Override
    public void close() {
        unsubscribe();
    }
}
