package releon.ru.api.settings;

@FunctionalInterface
public interface SettingChangeListener<T> {
    void onChanged(Setting<T> setting, T oldValue, T newValue);
}
