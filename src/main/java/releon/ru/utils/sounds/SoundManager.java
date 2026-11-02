package releon.ru.utils.sounds;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public final class SoundManager {
    public static final SoundEvent MOAN1 = sound("moan1");
    public static final SoundEvent MOAN2 = sound("moan2");
    public static final SoundEvent MOAN3 = sound("moan3");
    public static final SoundEvent MOAN4 = sound("moan4");
    public static final SoundEvent CRIME = sound("crime");
    public static final SoundEvent METALLIC = sound("metallic");
    public static final SoundEvent MODULE_ENABLE = sound("module_enable");
    public static final SoundEvent MODULE_DISABLE = sound("module_disable");
    public static final SoundEvent ON = sound("on");
    public static final SoundEvent OFF = sound("off");
    public static final SoundEvent MODULE_ENABLE_TYPE2 = sound("module_enable_type2");
    public static final SoundEvent MODULE_DISABLE_TYPE2 = sound("module_disable_type2");
    public static final SoundEvent SEARCH_TYPING = sound("searchtyping");
    public static final SoundEvent SLIDER = sound("slider");
    public static final SoundEvent SWITCH_CATEGORY = sound("switchcategory");
    public static final SoundEvent NO_SETTINGS = sound("no-settings");
    public static final SoundEvent BUTTON_CLICK = sound("buttonclick");
    public static final SoundEvent UNKNOWN_COMMAND = sound("unknowncommand");
    public static final SoundEvent NOTIFICATION = sound("welcome");
    public static final SoundEvent FRAG_EFFECT_ECHO_MAIN = sound("frag_effect_echo_main");
    public static final SoundEvent FRAG_EFFECT_KNOCK_MAIN = sound("frag_effect_knock_main");
    public static final SoundEvent FRAG_EFFECT_PULSE = sound("frag_effect_pulse");
    public static final SoundEvent FRAG_EFFECT_SPARKS_COLLISION = sound("frag_effect_sparks_collision");
    private static boolean initialized;

    private SoundManager() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        register(MOAN1);
        register(MOAN2);
        register(MOAN3);
        register(MOAN4);
        register(CRIME);
        register(METALLIC);
        register(MODULE_ENABLE);
        register(MODULE_DISABLE);
        register(ON);
        register(OFF);
        register(MODULE_ENABLE_TYPE2);
        register(MODULE_DISABLE_TYPE2);
        register(SEARCH_TYPING);
        register(SLIDER);
        register(SWITCH_CATEGORY);
        register(NO_SETTINGS);
        register(BUTTON_CLICK);
        register(UNKNOWN_COMMAND);
        register(NOTIFICATION);
        register(FRAG_EFFECT_ECHO_MAIN);
        register(FRAG_EFFECT_KNOCK_MAIN);
        register(FRAG_EFFECT_PULSE);
        register(FRAG_EFFECT_SPARKS_COLLISION);
    }

    public static void playSound(SoundEvent sound, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        mc.level.playSound(mc.player, mc.player.blockPosition(), sound, SoundSource.BLOCKS, volume, pitch);
    }

    public static void playSoundDirect(SoundEvent sound, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
        }
    }

    private static SoundEvent sound(String path) {
        return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("releon", path));
    }

    private static void register(SoundEvent sound) {
        Registry.register(BuiltInRegistries.SOUND_EVENT, sound.location(), sound);
    }
}
