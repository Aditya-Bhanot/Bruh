package releon.ru.api.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.SettingType;

public class BooleanSetting extends Setting<Boolean> {
    public BooleanSetting(String name, String description, boolean defaultValue) {
        super(name, description, defaultValue, SettingType.BOOLEAN);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(getValue());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            setValue(element.getAsBoolean());
        }
    }
}
