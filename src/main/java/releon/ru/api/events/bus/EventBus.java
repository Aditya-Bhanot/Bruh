package releon.ru.api.events.bus;

import releon.ru.api.events.Cancellable;
import releon.ru.api.events.Event;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.exception.EventDispatchException;
import releon.ru.api.events.exception.EventRegistrationException;
import releon.ru.api.events.listener.EventListener;
import releon.ru.api.events.listener.RegisteredListener;
import releon.ru.api.events.types.EventPriority;

import java.lang.reflect.Method;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class EventBus {
    private final Map<Class<? extends Event>, CopyOnWriteArrayList<RegisteredListener<?>>> listeners = new ConcurrentHashMap<>();
    private final Map<Class<? extends Event>, RegisteredListener<?>[]> listenerCache = new ConcurrentHashMap<>();
    private final Map<Object, List<EventSubscription>> ownerSubscriptions = Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicLong orderCounter = new AtomicLong();

    public <T extends Event> EventSubscription subscribe(Class<T> eventType, EventListener<? super T> listener) {
        return subscribe(eventType, EventPriority.NORMAL, false, listener, listener);
    }

    public <T extends Event> EventSubscription subscribe(Class<T> eventType,
                                                        EventPriority priority,
                                                        EventListener<? super T> listener) {
        return subscribe(eventType, priority, false, listener, listener);
    }

    public <T extends Event> EventSubscription subscribe(Class<T> eventType,
                                                        EventPriority priority,
                                                        boolean receiveCancelled,
                                                        EventListener<? super T> listener) {
        return subscribe(eventType, priority, receiveCancelled, listener, listener);
    }

    public void register(Object owner) {
        if (owner == null) {
            throw new EventRegistrationException("Event owner cannot be null");
        }

        List<EventSubscription> subscriptions = new ArrayList<>();
        for (Method method : owner.getClass().getDeclaredMethods()) {
            SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);
            if (annotation == null) {
                continue;
            }
            subscriptions.add(registerMethod(owner, method, annotation));
        }

        if (!subscriptions.isEmpty()) {
            ownerSubscriptions.put(owner, subscriptions);
        }
    }

    public void unregister(Object owner) {
        if (owner == null) {
            return;
        }

        List<EventSubscription> subscriptions = ownerSubscriptions.remove(owner);
        if (subscriptions == null) {
            return;
        }
        for (EventSubscription subscription : subscriptions) {
            subscription.unsubscribe();
        }
    }

    public <T extends Event> T post(T event) {
        if (event == null) {
            throw new EventDispatchException(null, new NullPointerException("event"));
        }

        RegisteredListener<?>[] eventListeners = getListeners(event.getClass());
        for (RegisteredListener<?> listener : eventListeners) {
            if (event instanceof Cancellable cancellable && cancellable.isCancelled() && !listener.isReceiveCancelled()) {
                continue;
            }
            invoke(listener, event);
        }
        return event;
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> EventSubscription subscribe(Class<T> eventType,
                                                         EventPriority priority,
                                                         boolean receiveCancelled,
                                                         EventListener<? super T> listener,
                                                         Object owner) {
        if (eventType == null) {
            throw new EventRegistrationException("Event type cannot be null");
        }
        if (listener == null) {
            throw new EventRegistrationException("Event listener cannot be null");
        }

        RegisteredListener<T> registered = new RegisteredListener<>(
                eventType,
                listener,
                priority == null ? EventPriority.NORMAL : priority,
                receiveCancelled,
                owner,
                orderCounter.incrementAndGet()
        );
        listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(registered);
        listenerCache.clear();
        return new EventSubscription(this, eventType, registered);
    }

    void unsubscribe(Class<? extends Event> eventType, RegisteredListener<?> listener) {
        CopyOnWriteArrayList<RegisteredListener<?>> registeredListeners = listeners.get(eventType);
        if (registeredListeners == null) {
            return;
        }
        registeredListeners.remove(listener);
        if (registeredListeners.isEmpty()) {
            listeners.remove(eventType);
        }
        listenerCache.clear();
    }

    @SuppressWarnings("unchecked")
    private EventSubscription registerMethod(Object owner, Method method, SubscribeEvent annotation) {
        if (method.getParameterCount() != 1) {
            throw new EventRegistrationException("@SubscribeEvent method must have exactly one event parameter: " + method);
        }

        Class<?> parameterType = method.getParameterTypes()[0];
        if (!Event.class.isAssignableFrom(parameterType)) {
            throw new EventRegistrationException("@SubscribeEvent parameter must implement Event: " + method);
        }

        method.setAccessible(true);
        Class<? extends Event> eventType = (Class<? extends Event>) parameterType;
        MethodHandle handle;
        try {
            handle = MethodHandles.privateLookupIn(owner.getClass(), MethodHandles.lookup()).unreflect(method).bindTo(owner);
        } catch (IllegalAccessException exception) {
            throw new EventRegistrationException("Cannot access @SubscribeEvent method: " + method, exception);
        }
        EventListener<Event> reflectedListener = event -> {
            try {
                handle.invoke(event);
            } catch (Throwable throwable) {
                if (throwable instanceof Exception exception) {
                    throw exception;
                }
                if (throwable instanceof Error error) {
                    throw error;
                }
                throw new EventDispatchException(event, throwable);
            }
        };

        return subscribe((Class<Event>) eventType, annotation.priority(), annotation.receiveCancelled(), reflectedListener, owner);
    }

    private RegisteredListener<?>[] getListeners(Class<? extends Event> eventType) {
        return listenerCache.computeIfAbsent(eventType, this::resolveListeners);
    }

    private RegisteredListener<?>[] resolveListeners(Class<? extends Event> eventType) {
        List<RegisteredListener<?>> resolved = new ArrayList<>();
        for (Map.Entry<Class<? extends Event>, CopyOnWriteArrayList<RegisteredListener<?>>> entry : listeners.entrySet()) {
            if (entry.getKey().isAssignableFrom(eventType)) {
                resolved.addAll(entry.getValue());
            }
        }
        resolved.sort(Comparator
                .comparingInt((RegisteredListener<?> listener) -> listener.getPriority().getOrder())
                .thenComparingLong(RegisteredListener::getOrder));
        return resolved.toArray(RegisteredListener<?>[]::new);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invoke(RegisteredListener<?> listener, Event event) {
        try {
            ((RegisteredListener) listener).getListener().invoke(event);
        } catch (Throwable throwable) {
            throw new EventDispatchException(event, throwable);
        }
    }
}
