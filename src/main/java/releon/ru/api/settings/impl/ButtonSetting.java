package releon.ru.api.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.SettingType;

public class ButtonSetting extends Setting<Runnable> {
    public ButtonSetting(String name, String description, Runnable action) {
        super(name, description, action, SettingType.BUTTON, false);
    }

    public void press() {
        if (getValue() != null) {
            getValue().run();
        }
    }

    @Override
    public JsonElement toJson() {
        return JsonNull.INSTANCE;
    }

    @Override
    public void fromJson(JsonElement element) {
    }
}
