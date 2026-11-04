package releon.ru.api.settings;

import com.google.gson.JsonElement;
import releon.ru.api.settings.exception.SettingException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    private final String name;
    private final String description;
    private final T defaultValue;
    private final SettingType type;
    private final boolean persistent;
    private final List<SettingChangeListener<T>> listeners = new ArrayList<>();
    private BooleanSupplier visible = () -> true;
    private T value;

    protected Setting(String name, String description, T defaultValue, SettingType type) {
        this(name, description, defaultValue, type, true);
    }

    protected Setting(String name, String description, T defaultValue, SettingType type, boolean persistent) {
        if (name == null || name.isBlank()) {
            throw new SettingException("Setting name cannot be empty");
        }
        this.name = name;
        this.description = description == null ? "" : description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.type = type;
        this.persistent = persistent;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public T getValue() {
        return value;
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public SettingType getType() {
        return type;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public boolean isVisible() {
        return visible.getAsBoolean();
    }

    public Setting<T> visibleWhen(BooleanSupplier visible) {
        this.visible = visible == null ? () -> true : visible;
        return this;
    }

    public Setting<T> visible(BooleanSupplier visible) {
        return visibleWhen(visible);
    }

    public Setting<T> setVisible(BooleanSupplier visible) {
        return visibleWhen(visible);
    }

    public Setting<T> setVisible(boolean visible) {
        return visibleWhen(() -> visible);
    }

    public void setValue(T value) {
        T normalized = normalize(value);
        T oldValue = this.value;
        if (Objects.equals(oldValue, normalized)) {
            return;
        }
        this.value = normalized;
        notifyListeners(oldValue, normalized);
    }

    public void reset() {
        setValue(defaultValue);
    }

    public void addListener(SettingChangeListener<T> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement element);

    protected T normalize(T value) {
        return value;
    }

    private void notifyListeners(T oldValue, T newValue) {
        for (SettingChangeListener<T> listener : listeners) {
            listener.onChanged(this, oldValue, newValue);
        }
    }
}
