package releon.ru.screens.modernui.settings;

import releon.ru.api.settings.Setting;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ButtonSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.api.settings.impl.StringSetting;

final class SettingCardFactory {
    private SettingCardFactory() {
    }

    static SettingCardComponent<?> create(Setting<?> setting) {
        if (setting instanceof ModeSetting modeSetting) {
            return new ModeSettingCard(modeSetting);
        }
        if (setting instanceof MultiModeSetting multiModeSetting) {
            return new MultiSettingCard(multiModeSetting);
        }
        if (setting instanceof NumberSetting numberSetting) {
            return new SliderSettingCard(numberSetting);
        }
        if (setting instanceof BooleanSetting booleanSetting) {
            return new BooleanSettingCard(booleanSetting);
        }
        if (setting instanceof ColorSetting colorSetting) {
            return new ColorSettingCard(colorSetting);
        }
        if (setting instanceof BindSetting bindSetting) {
            return new BindSettingCard(bindSetting);
        }
        if (setting instanceof StringSetting stringSetting) {
            return new TextSettingCard(stringSetting);
        }
        if (setting instanceof ButtonSetting buttonSetting) {
            return new ButtonSettingCard(buttonSetting);
        }
        return null;
    }
}
