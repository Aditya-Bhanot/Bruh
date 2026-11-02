package releon.ru.api.module.exception;

public class ModuleException extends RuntimeException {
    public ModuleException(String message) {
        super(message);
    }

    public ModuleException(String message, Throwable cause) {
        super(message, cause);
    }
}
