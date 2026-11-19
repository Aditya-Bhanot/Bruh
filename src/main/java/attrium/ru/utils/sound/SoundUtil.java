package attrium.ru.utils.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import attrium.ru.api.settings.impl.NumberSetting;

import java.util.Locale;

/**
 * Tactile UI Sound Mixer for Attrium ClickGUI (Minecraft 1.21.11 Official Mappings).
 *
 * Uses Minecraft's native {@link SimpleSoundInstance#forUI(SoundEvent, float, float)}
 * (Mojang mappings equivalent of PositionedSoundInstance.master) with a loud, punchy
 * base volume of 1.0f dynamically bound to the client's "UI Volume" (0.0 - 1.0) slider setting.
 */
public final class SoundUtil {
    private static SoundEvent cachedTickSound = null;
    private static SoundEvent cachedClickSound = null;
    private static SoundEvent cachedPopSound = null;
    private static boolean soundRegistryScanned = false;
    private static long lastHoverSoundTimeNs = 0L;

    // Shared UI Volume Slider Setting (Range: 0.0 - 1.0, Default: 1.0f)
    public static final NumberSetting UI_VOLUME_SETTING = new NumberSetting(
            "UI Volume",
            "Master volume for ClickGUI tactile interaction sounds (0.0 - 1.0).",
            1.0D,
            0.0D,
            1.0D,
            0.05D
    );

    private static volatile float fallbackUiVolume = 1.0F;

    private SoundUtil() {
    }

    public static float getUiVolume() {
        try {
            Double val = UI_VOLUME_SETTING.getValue();
            if (val != null) {
                return Mth.clamp(val.floatValue(), 0.0F, 1.0F);
            }
        } catch (Throwable ignored) {
        }
        return Mth.clamp(fallbackUiVolume, 0.0F, 1.0F);
    }

    public static void setUiVolume(float volume) {
        float clamped = Mth.clamp(volume, 0.0F, 1.0F);
        fallbackUiVolume = clamped;
        try {
            UI_VOLUME_SETTING.setValue((double) clamped);
        } catch (Throwable ignored) {
        }
    }

    private static void resolveCustomSoundEvents() {
        if (soundRegistryScanned && cachedClickSound != null) {
            return;
        }
        soundRegistryScanned = true;
        try {
            for (SoundEvent se : BuiltInRegistries.SOUND_EVENT) {
                if (se == null) {
                    continue;
                }
                Object keyObj = BuiltInRegistries.SOUND_EVENT.getKey(se);
                if (keyObj == null) {
                    continue;
                }
                String key = keyObj.toString().toLowerCase(Locale.ROOT);
                if (key.startsWith("attrium:") || key.startsWith("attrium:")) {
                    if (key.contains("tick") || key.contains("hover")) {
                        cachedTickSound = se;
                    } else if (key.contains("click") || key.contains("button")) {
                        cachedClickSound = se;
                    } else if (key.contains("pop") || key.contains("toggle") || key.contains("enable")) {
                        cachedPopSound = se;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Plays a UI sound using PositionedSoundInstance.master / SimpleSoundInstance.forUI
     * with volume dynamically fetched from the UI Volume (0.0 - 1.0) slider setting.
     */
    public static void playMasterSound(SoundEvent customEvent, float pitch, float volumeMultiplier) {
        float baseVolume = getUiVolume();
        if (baseVolume <= 0.001F) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSoundManager() == null) {
            return;
        }
        try {
            resolveCustomSoundEvents();
            SoundEvent targetEvent = customEvent != null ? customEvent : SoundEvents.UI_BUTTON_CLICK.value();
            float finalVolume = Mth.clamp(baseVolume * volumeMultiplier, 0.0F, 1.0F);
            if (finalVolume > 0.001F) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(targetEvent, pitch, finalVolume));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hover Trigger: Crisp tactile tick when the cursor enters a module row.
     */
    public static void playHoverTick() {
        long nowNs = System.nanoTime();
        if ((nowNs - lastHoverSoundTimeNs) < 35_000_000L) {
            return;
        }
        lastHoverSoundTimeNs = nowNs;
        resolveCustomSoundEvents();
        SoundEvent ev = cachedTickSound != null ? cachedTickSound : cachedClickSound;
        playMasterSound(ev, 1.55F, 0.55F);
    }

    /**
     * Click / Expand / Tab Switch Trigger: Medium pitch (1.0f), full UI Volume (1.0f base).
     */
    public static void playClick() {
        resolveCustomSoundEvents();
        playMasterSound(cachedClickSound, 1.00F, 1.00F);
    }

    /**
     * Toggle Trigger: Higher pitch (1.28f) for ON, lower pitch (0.76f) for OFF, full UI Volume (1.0f base).
     */
    public static void playTogglePop(boolean enabled) {
        resolveCustomSoundEvents();
        SoundEvent ev = cachedPopSound != null ? cachedPopSound : cachedClickSound;
        float pitch = enabled ? 1.28F : 0.76F;
        playMasterSound(ev, pitch, 1.00F);
    }
}
