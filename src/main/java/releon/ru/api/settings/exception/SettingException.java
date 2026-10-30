package releon.ru.api.settings.exception;

public class SettingException extends RuntimeException {
    public SettingException(String message) {
        super(message);
    }

    public SettingException(String message, Throwable cause) {
        super(message, cause);
    }
}
