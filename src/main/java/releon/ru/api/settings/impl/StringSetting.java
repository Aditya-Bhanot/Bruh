package releon.ru.api.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.SettingType;

public class StringSetting extends Setting<String> {
    private final int maxLength;

    public StringSetting(String name, String description, String defaultValue, int maxLength) {
        super(name, description, defaultValue, SettingType.STRING);
        this.maxLength = Math.max(1, maxLength);
        setValue(defaultValue);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(getValue());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            setValue(element.getAsString());
        }
    }

    @Override
    protected String normalize(String value) {
        String safeValue = value == null ? "" : value;
        return safeValue.length() > maxLength ? safeValue.substring(0, maxLength) : safeValue;
    }
}
