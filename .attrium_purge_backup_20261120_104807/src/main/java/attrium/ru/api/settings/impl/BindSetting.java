package attrium.ru.api.settings.impl;

import com.google.gson.JsonElement;
import attrium.ru.api.settings.Setting;
import attrium.ru.api.settings.SettingType;
import attrium.ru.api.settings.bind.KeyBind;

public class BindSetting extends Setting<KeyBind> {
    public BindSetting(String name, String description, KeyBind defaultValue) {
        this(name, description, defaultValue, true);
    }

    public BindSetting(String name, String description, KeyBind defaultValue, boolean persistent) {
        super(name, description, defaultValue, SettingType.BIND, persistent);
    }

    @Override
    public JsonElement toJson() {
        return getValue().toJson();
    }

    @Override
    public void fromJson(JsonElement element) {
        setValue(KeyBind.fromJson(element));
    }

    @Override
    protected KeyBind normalize(KeyBind value) {
        return value == null ? KeyBind.NONE : value;
    }
}
