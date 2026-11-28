package attrium.ru.api.module.impl.misc;

import net.minecraft.util.Mth;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.utils.sound.SoundUtil;

import java.util.Locale;

/**
 * UISounds Module — Full customization for Attrium's 6 custom UI sounds
 * (click, toggle, hover, scroll, collapse, keypress).
 * Automatically persists across all 4 config slots (acc.json - aan.json).
 */
public class UISoundsModule extends Module {
    private static volatile UISoundsModule instance;

    private final ModeSetting soundPack = register(new ModeSetting(
            "Sound Pack",
            "Switch between custom Attrium OGG sounds, vanilla clicks, or muted.",
            "Custom",
            "Custom", "Vanilla", "Muted"
    ));

    private final NumberSetting masterVolume = register(new NumberSetting(
            "Master Volume",
            "Overall volume for all ClickGUI & HUD UI sounds (0.0 - 1.5).",
            0.85D, 0.0D, 1.5D, 0.05D
    ));

    private final NumberSetting pitchScale = register(new NumberSetting(
            "Pitch Scale",
            "Global pitch multiplier for UI sounds (0.6 - 1.5).",
            1.0D, 0.6D, 1.5D, 0.05D
    ));

    private final BooleanSetting pitchJitter = register(new BooleanSetting(
            "Pitch Jitter",
            "Adds subtle +/-3.5% organic pitch variation so rapid clicks feel natural.",
            true
    ));

    private final BooleanSetting clickEnabled = register(new BooleanSetting(
            "Click Sound",
            "Plays click.ogg on tabs, buttons, and menu actions.",
            true
    ));

    private final BooleanSetting toggleEnabled = register(new BooleanSetting(
            "Toggle Sound",
            "Plays toggle.ogg when enabling or disabling modules and switches.",
            true
    ));

    private final BooleanSetting hoverEnabled = register(new BooleanSetting(
            "Hover Sound",
            "Plays hover.ogg when moving across module rows.",
            true
    ));

    private final BooleanSetting scrollEnabled = register(new BooleanSetting(
            "Scroll Sound",
            "Plays scroll.ogg when scrolling ClickGUI lists.",
            true
    ));

    private final BooleanSetting collapseEnabled = register(new BooleanSetting(
            "Collapse Sound",
            "Plays collapse.ogg when expanding or collapsing module drawers.",
            true
    ));

    private final BooleanSetting keypressEnabled = register(new BooleanSetting(
            "Keypress Sound",
            "Plays keypress.ogg while typing in search, config, or friend inputs.",
            true
    ));

    private final NumberSetting hoverVolume = register(new NumberSetting(
            "Hover Volume",
            "Relative volume for hover.ogg (0.0 - 1.0).",
            0.45D, 0.0D, 1.0D, 0.05D
    ));

    private final NumberSetting scrollVolume = register(new NumberSetting(
            "Scroll Volume",
            "Relative volume for scroll.ogg (0.0 - 1.0).",
            0.50D, 0.0D, 1.0D, 0.05D
    ));

    private final NumberSetting keypressVolume = register(new NumberSetting(
            "Keypress Volume",
            "Relative volume for keypress.ogg (0.0 - 1.0).",
            0.65D, 0.0D, 1.0D, 0.05D
    ));

    public UISoundsModule() {
        super("UISounds", "Fully customizable tactile UI sound pack (click, toggle, hover, scroll, collapse, keypress).", resolveMiscCategory());
        instance = this;
        this.hoverVolume.visibleWhen(() -> Boolean.TRUE.equals(this.hoverEnabled.getValue()));
        this.scrollVolume.visibleWhen(() -> Boolean.TRUE.equals(this.scrollEnabled.getValue()));
        this.keypressVolume.visibleWhen(() -> Boolean.TRUE.equals(this.keypressEnabled.getValue()));
        if (!isEnabled()) {
            try {
                toggle();
            } catch (Throwable ignored) {
            }
        }
    }

    private static ModuleCategory resolveMiscCategory() {
        for (ModuleCategory cat : ModuleCategory.values()) {
            String n = cat.name().toUpperCase(Locale.ROOT);
            if (n.contains("MISC") || n.contains("CLIENT") || n.contains("VISUAL")) {
                return cat;
            }
        }
        return ModuleCategory.values()[0];
    }

    public static UISoundsModule getInstance() {
        return instance;
    }

    public boolean isAudioActive() {
        return isEnabled() && !"Muted".equalsIgnoreCase(soundPack.getValue());
    }

    public boolean useCustomPack() {
        return !"Vanilla".equalsIgnoreCase(soundPack.getValue());
    }

    public float getMasterVolume() {
        Double v = masterVolume.getValue();
        float f = v != null ? v.floatValue() : SoundUtil.getUiVolume();
        return Mth.clamp(f, 0.0F, 1.5F);
    }

    public float getPitchScale() {
        Double v = pitchScale.getValue();
        return Mth.clamp(v != null ? v.floatValue() : 1.0F, 0.6F, 1.5F);
    }

    public boolean isPitchJitterEnabled() {
        return Boolean.TRUE.equals(pitchJitter.getValue());
    }

    public boolean isClickEnabled() { return Boolean.TRUE.equals(clickEnabled.getValue()); }
    public boolean isToggleEnabled() { return Boolean.TRUE.equals(toggleEnabled.getValue()); }
    public boolean isHoverEnabled() { return Boolean.TRUE.equals(hoverEnabled.getValue()); }
    public boolean isScrollEnabled() { return Boolean.TRUE.equals(scrollEnabled.getValue()); }
    public boolean isCollapseEnabled() { return Boolean.TRUE.equals(collapseEnabled.getValue()); }
    public boolean isKeypressEnabled() { return Boolean.TRUE.equals(keypressEnabled.getValue()); }

    public float getHoverVolume() {
        Double v = hoverVolume.getValue();
        return Mth.clamp(v != null ? v.floatValue() : 0.45F, 0.0F, 1.0F);
    }

    public float getScrollVolume() {
        Double v = scrollVolume.getValue();
        return Mth.clamp(v != null ? v.floatValue() : 0.50F, 0.0F, 1.0F);
    }

    public float getKeypressVolume() {
        Double v = keypressVolume.getValue();
        return Mth.clamp(v != null ? v.floatValue() : 0.65F, 0.0F, 1.0F);
    }
}
