package releon.ru.api.events.exception;

public class EventRegistrationException extends EventException {
    public EventRegistrationException(String message) {
        super(message);
    }

    public EventRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
