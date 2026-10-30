package releon.ru.api.module.impl.visual.frageffect;

import net.minecraft.sounds.SoundEvent;
import releon.ru.utils.sounds.SoundManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class FragEffectSoundQueue {
    private final List<ScheduledSound> scheduledSounds = new ArrayList<>();

    public void schedule(SoundEvent sound, long delayMillis, float volume) {
        if (sound == null) {
            return;
        }

        if (delayMillis <= 0L) {
            SoundManager.playSoundDirect(sound, volume, 1.0F);
            return;
        }

        this.scheduledSounds.add(new ScheduledSound(sound, System.currentTimeMillis() + delayMillis, volume));
    }

    public void tick() {
        if (this.scheduledSounds.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Iterator<ScheduledSound> iterator = this.scheduledSounds.iterator();
        while (iterator.hasNext()) {
            ScheduledSound scheduledSound = iterator.next();
            if (scheduledSound.playAtMillis <= now) {
                SoundManager.playSoundDirect(scheduledSound.sound, scheduledSound.volume, 1.0F);
                iterator.remove();
            }
        }
    }

    public void clear() {
        this.scheduledSounds.clear();
    }

    private record ScheduledSound(SoundEvent sound, long playAtMillis, float volume) {
    }
}
