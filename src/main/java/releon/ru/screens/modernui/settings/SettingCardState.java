package releon.ru.screens.modernui.settings;

import releon.ru.api.settings.Setting;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.api.settings.impl.StringSetting;
import releon.ru.utils.math.MathUtils;
import releon.ru.utils.render.animation.modernfx.Decelerate;
import releon.ru.utils.render.animation.modernfx.Direction;

final class SettingCardState {
    final Setting<?> setting;
    final Decelerate visibilityAnimation;
    final Decelerate expandAnimation = MathUtils.createAnimation(180, Direction.BACKWARDS);
    final Decelerate booleanAnimation = MathUtils.createAnimation(180, Direction.BACKWARDS);
    final Decelerate colorPickerAnimation = MathUtils.createAnimation(180, Direction.BACKWARDS);
    boolean visible;
    boolean expanded;
    boolean selectedAll;
    boolean colorSynced;
    String text = "";
    int cursor;
    float rowX;
    float rowY;
    float rowWidth;
    float rowHeight;
    float boxX;
    float boxY;
    float boxWidth;
    float boxHeight;
    float optionX;
    float optionY;
    float optionWidth;
    float optionHeight;
    int optionCount;
    float sliderX;
    float sliderY;
    float sliderWidth;
    float sliderHeight;
    float sliderProgress;
    float colorX;
    float colorY;
    float colorWidth;
    float colorHeight;
    float popupX;
    float popupY;
    float popupWidth;
    float popupHeight;
    float paletteX;
    float paletteY;
    float paletteSize;
    float hueX;
    float hueY;
    float hueWidth;
    float hueHeight;
    float hue;
    float saturation = 1.0F;
    float brightness = 1.0F;
    float visibilityProgress;

    SettingCardState(Setting<?> setting) {
        this.setting = setting;
        visibilityAnimation = MathUtils.createAnimation(190, setting.isVisible() ? Direction.FORWARDS : Direction.BACKWARDS);
        visibilityProgress = setting.isVisible() ? 1.0F : 0.0F;
        if (setting instanceof StringSetting stringSetting) {
            text = stringSetting.getValue();
            cursor = text.length();
        }
        if (setting instanceof NumberSetting numberSetting) {
            double min = numberSetting.getMin();
            double max = numberSetting.getMax();
            sliderProgress = max == min ? 0.0F : (float) ((numberSetting.getValue() - min) / (max - min));
        }
        if (setting instanceof BooleanSetting booleanSetting && booleanSetting.getValue()) {
            booleanAnimation.setDirection(Direction.FORWARDS);
            booleanAnimation.reset();
        }
    }

    void row(float x, float y, float width, float height) {
        rowX = x;
        rowY = y;
        rowWidth = width;
        rowHeight = height;
        visible = true;
    }

    void box(float x, float y, float width, float height) {
        boxX = x;
        boxY = y;
        boxWidth = width;
        boxHeight = height;
    }

    void slider(float x, float y, float width, float height) {
        sliderX = x;
        sliderY = y;
        sliderWidth = width;
        sliderHeight = height;
    }

    void options(float x, float y, float width, float height, int count) {
        optionX = x;
        optionY = y;
        optionWidth = width;
        optionHeight = height;
        optionCount = count;
    }
}
