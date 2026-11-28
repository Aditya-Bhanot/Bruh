package attrium.ru.utils.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import attrium.ru.api.module.impl.misc.UISoundsModule;
import attrium.ru.api.settings.impl.NumberSetting;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tactile UI Sound Mixer for Attrium ClickGUI (Minecraft 1.21.11 Official Mappings).
 *
 * Plays Attrium's 6 custom OGG sounds (click, toggle, hover, scroll, collapse, keypress)
 * via SoundEvent.createVariableRangeEvent + SimpleSoundInstance.forUI, fully customizable
 * through UISoundsModule with graceful vanilla fallback.
 */
public final class SoundUtil {
    private static boolean eventsResolved = false;
    private static SoundEvent evClick = null;
    private static SoundEvent evToggle = null;
    private static SoundEvent evHover = null;
    private static SoundEvent evScroll = null;
    private static SoundEvent evCollapse = null;
    private static SoundEvent evKeypress = null;

    private static long lastHoverNs = 0L;
    private static long lastScrollNs = 0L;
    private static long lastKeypressNs = 0L;

    // Shared UI Volume Slider Setting (kept for full compatibility with existing ClickGUI bindings)
    public static final NumberSetting UI_VOLUME_SETTING = new NumberSetting(
            "UI Volume",
            "Master volume for ClickGUI tactile interaction sounds (0.0 - 1.0).",
            0.85D,
            0.0D,
            1.0D,
            0.05D
    );

    private static volatile float fallbackUiVolume = 0.85F;

    private SoundUtil() {
    }

    public static float getUiVolume() {
        UISoundsModule mod = UISoundsModule.getInstance();
        if (mod != null) {
            return mod.getMasterVolume();
        }
        try {
            Double val = UI_VOLUME_SETTING.getValue();
            if (val != null) {
                return Mth.clamp(val.floatValue(), 0.0F, 1.5F);
            }
        } catch (Throwable ignored) {
        }
        return Mth.clamp(fallbackUiVolume, 0.0F, 1.5F);
    }

    public static void setUiVolume(float volume) {
        float clamped = Mth.clamp(volume, 0.0F, 1.0F);
        fallbackUiVolume = clamped;
        try {
            UI_VOLUME_SETTING.setValue((double) clamped);
        } catch (Throwable ignored) {
        }
    }

    private static SoundEvent createCustomEvent(Class<?> idClass, Method idFactory2, Method idFactory1, Method soundFactory, String path) {
        try {
            Object id = null;
            if (idFactory2 != null) {
                id = idFactory2.invoke(null, "attrium", path);
            } else if (idFactory1 != null) {
                id = idFactory1.invoke(null, "attrium:" + path);
            }
            if (id != null && soundFactory != null) {
                Object se = soundFactory.invoke(null, id);
                if (se instanceof SoundEvent event) {
                    return event;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void resolveCustomSoundEvents() {
        if (eventsResolved) {
            return;
        }
        eventsResolved = true;
        try {
            Object sampleId = BuiltInRegistries.ITEM.getKey(Items.APPLE);
            if (sampleId == null) {
                return;
            }
            Class<?> idClass = sampleId.getClass();
            Method idFactory2 = null;
            Method idFactory1 = null;
            for (Method m : idClass.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || !idClass.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == String.class && pt[1] == String.class) {
                    try {
                        m.setAccessible(true);
                        Object test = m.invoke(null, "attrium", "ui.click");
                        if (test != null && "attrium:ui.click".equals(test.toString())) {
                            idFactory2 = m;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (idFactory2 == null) {
                for (Method m : idClass.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) || !idClass.isAssignableFrom(m.getReturnType())) {
                        continue;
                    }
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 1 && pt[0] == String.class) {
                        try {
                            m.setAccessible(true);
                            Object test = m.invoke(null, "attrium:ui.click");
                            if (test != null && "attrium:ui.click".equals(test.toString())) {
                                idFactory1 = m;
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            Method soundFactory = null;
            for (Method m : SoundEvent.class.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(idClass)
                        && SoundEvent.class.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    soundFactory = m;
                    break;
                }
            }

            evClick = createCustomEvent(idClass, idFactory2, idFactory1, soundFactory, "ui.click");
            evToggle = createCustomEvent(idClass, idFactory2, idFactory1, soundFactory, "ui.toggle");
            evHover = createCustomEvent(idClass, idFactory2, idFactory1, soundFactory, "ui.hover");
            evScroll = createCustomEvent(idClass, idFactory2, idFactory1, soundFactory, "ui.scroll");
            evCollapse = createCustomEvent(idClass, idFactory2, idFactory1, soundFactory, "ui.collapse");
            evKeypress = createCustomEvent(idClass, idFactory2, idFactory1, soundFactory, "ui.keypress");
        } catch (Throwable ignored) {
        }
    }

    public static void playMasterSound(SoundEvent customEvent, float pitch, float volumeMultiplier) {
        UISoundsModule mod = UISoundsModule.getInstance();
        if (mod != null && !mod.isAudioActive()) {
            return;
        }
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
            boolean useCustom = (mod == null || mod.useCustomPack());
            SoundEvent targetEvent = (useCustom && customEvent != null)
                    ? customEvent
                    : SoundEvents.UI_BUTTON_CLICK.value();

            float pitchScale = mod != null ? mod.getPitchScale() : 1.0F;
            float jitter = (mod == null || mod.isPitchJitterEnabled())
                    ? (1.0F + (ThreadLocalRandom.current().nextFloat() - 0.5F) * 0.07F)
                    : 1.0F;
            float finalPitch = Mth.clamp(pitch * pitchScale * jitter, 0.5F, 2.0F);
            float finalVolume = Mth.clamp(baseVolume * volumeMultiplier, 0.0F, 1.5F);
            if (finalVolume > 0.001F) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(targetEvent, finalPitch, finalVolume));
            }
        } catch (Throwable ignored) {
        }
    }

    public static void playHoverTick() {
        UISoundsModule mod = UISoundsModule.getInstance();
        if (mod != null && (!mod.isAudioActive() || !mod.isHoverEnabled())) {
            return;
        }
        long nowNs = System.nanoTime();
        if ((nowNs - lastHoverNs) < 42_000_000L) {
            return;
        }
        lastHoverNs = nowNs;
        resolveCustomSoundEvents();
        float relVol = mod != null ? mod.getHoverVolume() : 0.45F;
        playMasterSound(evHover, 1.0F, relVol);
    }

    public static void playClick() {
        UISoundsModule mod = UISoundsModule.getInstance();
        if (mod != null && (!mod.isAudioActive() || !mod.isClickEnabled())) {
            return;
        }
        resolveCustomSoundEvents();
        playMasterSound(evClick, 1.0F, 1.0F);
    }

    public static void playTogglePop(boolean enabled) {
        UISoundsModule mod = UISoundsModule.getInstance();
        if (mod != null && (!mod.isAudioActive() || !mod.isToggleEnabled())) {
            return;
        }
        resolveCustomSoundEvents();
        float pitch = enabled ? 1.08F : 0.90F;
        playMasterSound(evToggle, pitch, 1.0F);
    }

    public static void playScroll() {
        UISoundsModule mod = UISoundsModule.getInstance();
        if (mod != null && (!mod.isAudioActive() || !mod.isScrollEnabled())) {
            return;
        }
        long nowNs = System.nanoTime();
        if ((nowNs - lastScrollNs) < 48_000_000L) {
            return;
        }
        lastScrollNs = nowNs;
        resolveCustomSoundEvents();
        float relVol = mod != null ? mod.getScrollVolume() : 0.50F;
        playMasterSound(evScroll, 1.0F, relVol);
    }

    public static void playCollapse(boolean expanded) {
        UISoundsModule mod = UISoundsModule.getInstance();
        if (mod != null && (!mod.isAudioActive() || !mod.isCollapseEnabled())) {
            return;
        }
        resolveCustomSoundEvents();
        float pitch = expanded ? 1.06F : 0.92F;
        playMasterSound(evCollapse, pitch, 0.95F);
    }

    public static void playKeypress() {
        UISoundsModule mod = UISoundsModule.getInstance();
        if (mod != null && (!mod.isAudioActive() || !mod.isKeypressEnabled())) {
            return;
        }
        long nowNs = System.nanoTime();
        if ((nowNs - lastKeypressNs) < 25_000_000L) {
            return;
        }
        lastKeypressNs = nowNs;
        resolveCustomSoundEvents();
        float relVol = mod != null ? mod.getKeypressVolume() : 0.65F;
        playMasterSound(evKeypress, 1.0F, relVol);
    }
}
