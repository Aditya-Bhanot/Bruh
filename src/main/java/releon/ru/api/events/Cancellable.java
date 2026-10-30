package releon.ru.api.events;

public interface Cancellable {
    boolean isCancelled();

    void setCancelled(boolean cancelled);

    default void cancel() {
        setCancelled(true);
    }
}
