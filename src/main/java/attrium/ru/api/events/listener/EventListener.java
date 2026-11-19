package attrium.ru.api.events.listener;

import attrium.ru.api.events.Event;

@FunctionalInterface
public interface EventListener<T extends Event> {
    void invoke(T event) throws Exception;
}
