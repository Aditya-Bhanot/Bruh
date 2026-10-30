package releon.ru.api.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.SettingType;

public class NumberSetting extends Setting<Double> {
    private final double min;
    private final double max;
    private final double step;

    public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
        super(name, description, defaultValue, SettingType.NUMBER);
        this.min = min;
        this.max = max;
        this.step = step <= 0.0 ? 0.1 : step;
        setValue(defaultValue);
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getStep() {
        return step;
    }

    public float getFloat() {
        return getValue().floatValue();
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(getValue());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null) {
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("value")) {
                setValue(object.get("value").getAsDouble());
            }
            return;
        }
        if (element.isJsonPrimitive()) {
            setValue(element.getAsDouble());
        }
    }

    @Override
    protected Double normalize(Double value) {
        double safeValue = value == null ? min : value;
        double clamped = Math.max(min, Math.min(max, safeValue));
        return Math.round(clamped / step) * step;
    }
}
