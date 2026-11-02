package releon.ru.api.settings.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.SettingType;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MultiModeSetting extends Setting<Set<String>> {
    private final List<String> modes;
    private final boolean renderDescription;

    public MultiModeSetting(String name, String description, String[] modes, String... selected) {
        this(name, description, true, modes, selected);
    }

    public MultiModeSetting(String name, String description, boolean renderDescription, String[] modes, String... selected) {
        super(name, description, new LinkedHashSet<>(Arrays.asList(selected)), SettingType.MULTI_MODE);
        this.modes = List.of(modes);
        this.renderDescription = renderDescription;
        setValue(new LinkedHashSet<>(Arrays.asList(selected)));
    }

    public List<String> getModes() {
        return modes;
    }

    public boolean shouldRenderDescription() {
        return renderDescription;
    }

    public boolean isSelected(String mode) {
        return getValue().contains(normalizeMode(mode));
    }

    public void setSelected(String mode, boolean selected) {
        Set<String> next = new LinkedHashSet<>(getValue());
        String normalized = normalizeMode(mode);
        if (selected) {
            next.add(normalized);
        } else {
            next.remove(normalized);
        }
        setValue(next);
    }

    public int selectedCount() {
        return getValue().size();
    }

    @Override
    public JsonElement toJson() {
        JsonArray array = new JsonArray();
        for (String value : getValue()) {
            array.add(value);
        }
        return array;
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return;
        }
        Set<String> next = new LinkedHashSet<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                next.add(normalizeMode(item.getAsString()));
            }
        }
        setValue(next);
    }

    @Override
    protected Set<String> normalize(Set<String> value) {
        Set<String> normalized = new LinkedHashSet<>();
        if (value == null) {
            return normalized;
        }
        for (String mode : value) {
            String normalizedMode = normalizeMode(mode);
            if (!normalizedMode.isEmpty()) {
                normalized.add(normalizedMode);
            }
        }
        return normalized;
    }

    private String normalizeMode(String value) {
        if (value == null) {
            return "";
        }
        for (String mode : modes) {
            if (mode.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return "";
    }
}
