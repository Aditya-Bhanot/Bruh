package releon.ru.api.events.listener;

import releon.ru.api.events.Event;

@FunctionalInterface
public interface EventListener<T extends Event> {
    void invoke(T event) throws Exception;
}
