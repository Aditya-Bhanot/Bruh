package attrium.ru.api.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import attrium.ru.api.settings.Setting;
import attrium.ru.api.settings.SettingType;

import java.util.Locale;

/**
 * Editable String/Text setting for ClickGUI inline text inputs (e.g., NameProtect Custom Alias).
 * Persists cleanly to/from JSON in attrium_config.json and notifies optional change listeners.
 */
public class StringSetting extends Setting<String> {
    private final int maxLength;
    private Runnable onChangeListener;

    public StringSetting(String name, String description, String defaultValue) {
        this(name, description, defaultValue, 24);
    }

    public StringSetting(String name, String description, String defaultValue, int maxLength) {
        super(name, description, defaultValue != null ? defaultValue : "", resolveStringSettingType());
        this.maxLength = Math.max(1, maxLength);
    }

    private static SettingType resolveStringSettingType() {
        SettingType[] vals = SettingType.values();
        for (SettingType t : vals) {
            String n = t.name().toUpperCase(Locale.ROOT);
            if (n.contains("STRING") || n.contains("TEXT")) {
                return t;
            }
        }
        for (SettingType t : vals) {
            String n = t.name().toUpperCase(Locale.ROOT);
            if (n.contains("MODE")) {
                return t;
            }
        }
        return vals.length > 0 ? vals[0] : null;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public StringSetting onChange(Runnable listener) {
        this.onChangeListener = listener;
        return this;
    }

    @Override
    public void setValue(String value) {
        String safe = value != null ? value : "";
        if (safe.length() > maxLength) {
            safe = safe.substring(0, maxLength);
        }
        String prev = getValue();
        super.setValue(safe);
        if (onChangeListener != null && !safe.equals(prev)) {
            try {
                onChangeListener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public JsonElement toJson() {
        String val = getValue();
        return new JsonPrimitive(val != null ? val : "");
    }

    @Override
    public void fromJson(JsonElement element) {
        try {
            if (element != null && element.isJsonPrimitive()) {
                setValue(element.getAsString());
            }
        } catch (Throwable ignored) {
        }
    }
}
