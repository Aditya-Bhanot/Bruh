package releon.ru.api.module.impl.visual.frageffect;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

public final class FragEffectDeathMemoryTracker {
    private static final int MAX_MEMORIES = 12;
    private static final long MEMORY_LIFETIME_MS = 950L;
    private static final long CONFIRMED_DEATH_GRACE_MS = 300L;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final List<DeathMemory> memories = new ArrayList<>();

    public void remember(LivingEntity entity, boolean allowNonPlayer) {
        if (entity == null || entity == this.minecraft.player || entity.tickCount < 2 || !entity.isAlive()) {
            return;
        }
        if (!allowNonPlayer && !(entity instanceof Player)) {
            return;
        }

        for (DeathMemory memory : this.memories) {
            if (memory.matches(entity)) {
                memory.refresh(entity);
                return;
            }
        }

        while (this.memories.size() >= MAX_MEMORIES) {
            this.memories.removeFirst();
        }
        this.memories.add(new DeathMemory(entity));
    }

    public void tick(Consumer<LivingEntity> onConfirmedDeath) {
        if (this.memories.isEmpty()) {
            return;
        }

        Iterator<DeathMemory> iterator = this.memories.iterator();
        while (iterator.hasNext()) {
            DeathMemory memory = iterator.next();
            if (!memory.isActive()) {
                iterator.remove();
                continue;
            }
            if (memory.shouldDiscard(this.minecraft)) {
                iterator.remove();
                continue;
            }
            if (!memory.triggered && memory.shouldTrigger()) {
                onConfirmedDeath.accept(memory.entity);
                memory.markTriggered();
            }
            if (!memory.isActive()) {
                iterator.remove();
            }
        }
    }

    public void clear() {
        this.memories.clear();
    }

    private static final class DeathMemory {
        private final int entityId;
        private LivingEntity entity;
        private long expiresAt;
        private boolean triggered;

        private DeathMemory(LivingEntity entity) {
            this.entity = entity;
            this.entityId = entity.getId();
            this.expiresAt = System.currentTimeMillis() + MEMORY_LIFETIME_MS;
        }

        private boolean matches(LivingEntity other) {
            return other.getId() == this.entityId;
        }

        private void refresh(LivingEntity entity) {
            if (isConfirmedDead(this.entity)) {
                return;
            }
            this.entity = entity;
            this.triggered = false;
            this.expiresAt = System.currentTimeMillis() + MEMORY_LIFETIME_MS;
        }

        private boolean shouldTrigger() {
            return isConfirmedDead(this.entity);
        }

        private boolean shouldDiscard(Minecraft minecraft) {
            if (isConfirmedDead(this.entity)) {
                return false;
            }
            if (minecraft.level == null) {
                return true;
            }
            if (this.entity.isRemoved()) {
                return true;
            }
            return minecraft.level.getEntity(this.entityId) == null;
        }

        private void markTriggered() {
            this.triggered = true;
            this.expiresAt = System.currentTimeMillis() + CONFIRMED_DEATH_GRACE_MS;
        }

        private boolean isActive() {
            return System.currentTimeMillis() <= this.expiresAt;
        }

        private static boolean isConfirmedDead(LivingEntity living) {
            return living != null && (living.getHealth() <= 0.0F || living.isDeadOrDying() || living.deathTime > 0);
        }
    }
}
