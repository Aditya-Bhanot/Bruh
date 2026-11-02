package releon.ru.api.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.SettingType;

import java.awt.Color;

public class ColorSetting extends Setting<Color> {
    public ColorSetting(String name, String description, Color defaultValue) {
        super(name, description, defaultValue, SettingType.COLOR);
    }

    @Override
    public JsonElement toJson() {
        Color color = getValue();
        JsonObject object = new JsonObject();
        object.addProperty("red", color.getRed());
        object.addProperty("green", color.getGreen());
        object.addProperty("blue", color.getBlue());
        object.addProperty("alpha", color.getAlpha());
        object.addProperty("rgba", color.getRGB());
        return object;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        if (object.has("rgba")) {
            setValue(new Color(object.get("rgba").getAsInt(), true));
            return;
        }
        int red = object.has("red") ? object.get("red").getAsInt() : getValue().getRed();
        int green = object.has("green") ? object.get("green").getAsInt() : getValue().getGreen();
        int blue = object.has("blue") ? object.get("blue").getAsInt() : getValue().getBlue();
        int alpha = object.has("alpha") ? object.get("alpha").getAsInt() : getValue().getAlpha();
        setValue(new Color(clamp(red), clamp(green), clamp(blue), clamp(alpha)));
    }

    @Override
    protected Color normalize(Color value) {
        return value == null ? new Color(255, 255, 255, 255) : value;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
