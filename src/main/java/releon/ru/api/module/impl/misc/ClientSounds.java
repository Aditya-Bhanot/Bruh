package releon.ru.api.module.impl.misc;

import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.ModuleToggleEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.sounds.SoundManager;

public final class ClientSounds extends Module {
    private static ClientSounds instance;

    private final ModeSetting soundType = register(new ModeSetting("Sound Type", "Client sound type.", "New", "New", "Old", "Alternative"));
    private final NumberSetting volume = register(new NumberSetting("Volume", "Client sound volume.", 1.0, 0.0, 1.0, 0.05));
    private final NumberSetting pitch = register(new NumberSetting("Pitch", "Client sound pitch.", 1.0, 0.5, 2.0, 0.05));
    private final BooleanSetting searchTypingSound = register(new BooleanSetting("Search Typing", "Search typing sound.", true));
    private final BooleanSetting sliderSound = register(new BooleanSetting("Slider", "Slider move sound.", true));
    private final BooleanSetting switchCategorySound = register(new BooleanSetting("Switch Category", "Category switch sound.", true));
    private final BooleanSetting noSettingsSound = register(new BooleanSetting("No Settings", "No settings sound.", true));
    private final BooleanSetting buttonClickSound = register(new BooleanSetting("Button", "Button click sound.", true));
    private final BooleanSetting unknownCommandSound = register(new BooleanSetting("Unknown Command", "Unknown command sound.", true));

    public ClientSounds() {
        super("Client Sounds", "Client sounds for module and GUI actions.", ModuleCategory.MISC);
        instance = this;
    }

    public static ClientSounds getInstance() {
        return instance;
    }

    @SubscribeEvent
    private void onModuleToggle(ModuleToggleEvent event) {
        if (mc.player == null || mc.level == null || event.getModule() == this) {
            return;
        }
        playToggleSound(event.isEnabled());
    }

    private void playToggleSound(boolean enabled) {
        float vol = volume.getFloat();
        float tone = pitch.getFloat();
        if (soundType.is("Old")) {
            SoundManager.playSoundDirect(enabled ? SoundManager.ON : SoundManager.OFF, vol, tone);
        } else if (soundType.is("Alternative")) {
            SoundManager.playSoundDirect(enabled ? SoundManager.MODULE_ENABLE_TYPE2 : SoundManager.MODULE_DISABLE_TYPE2, vol, tone);
        } else {
            SoundManager.playSoundDirect(enabled ? SoundManager.MODULE_ENABLE : SoundManager.MODULE_DISABLE, vol, tone);
        }
    }

    public float getVolume() {
        return volume.getFloat();
    }

    public float getPitch() {
        return pitch.getFloat();
    }

    public static boolean allowSearchTypingSound() {
        ClientSounds sounds = instance;
        return sounds != null && sounds.isEnabled() && sounds.searchTypingSound.getValue();
    }

    public static boolean allowSliderSound() {
        ClientSounds sounds = instance;
        return sounds != null && sounds.isEnabled() && sounds.sliderSound.getValue();
    }

    public static boolean allowSwitchCategorySound() {
        ClientSounds sounds = instance;
        return sounds != null && sounds.isEnabled() && sounds.switchCategorySound.getValue();
    }

    public static boolean allowNoSettingsSound() {
        ClientSounds sounds = instance;
        return sounds != null && sounds.isEnabled() && sounds.noSettingsSound.getValue();
    }

    public static boolean allowButtonClickSound() {
        ClientSounds sounds = instance;
        return sounds != null && sounds.isEnabled() && sounds.buttonClickSound.getValue();
    }

    public static boolean allowUnknownCommandSound() {
        ClientSounds sounds = instance;
        return sounds != null && sounds.isEnabled() && sounds.unknownCommandSound.getValue();
    }
}
