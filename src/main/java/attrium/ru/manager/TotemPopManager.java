package attrium.ru.manager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import attrium.ru.api.module.impl.visual.NotificationsModule;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight Totem Pop Tracking Engine (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Tracks live Totem of Undying pops (EntityEvent status 35) per player UUID using a
 * ConcurrentHashMap, automatically clears pops on player death/removal or world/dimension
 * transitions, and dispatches Gold Shield notifications to NotificationsModule.
 */
public final class TotemPopManager {
    private static final ConcurrentHashMap<UUID, Integer> POPS = new ConcurrentHashMap<>();
    private static ClientLevel lastObservedLevel = null;
    private static long lastCleanupMs = 0L;

    private TotemPopManager() {
    }

    /**
     * Records a totem pop for the given player UUID and dispatches a HUD notification.
     */
    public static int recordPop(UUID uuid, String playerName) {
        if (uuid == null) {
            return 0;
        }
        int count = POPS.merge(uuid, 1, Integer::sum);
        NotificationsModule.notifyTotemPop(playerName != null ? playerName : "Player", count);
        return count;
    }

    /**
     * Returns the current tracked totem pop count for a player UUID (0 if none).
     */
    public static int getPopCount(UUID uuid) {
        return uuid != null ? POPS.getOrDefault(uuid, 0) : 0;
    }

    /**
     * Clears a single player's tracked totem pop count (e.g. on death or removal).
     */
    public static void clearPop(UUID uuid) {
        if (uuid != null) {
            POPS.remove(uuid);
        }
    }

    /**
     * Clears all tracked totem pop counts (e.g. on world/dimension switch or disconnect).
     */
    public static void clearAll() {
        POPS.clear();
    }

    /**
     * Zero-allocation periodic cleanup called from the HUD render loop:
     *  - Detects world/dimension changes or disconnect and clears all counts immediately.
     *  - Evicts dead or removed players every ~200ms.
     */
    public static void tickCleanup(Minecraft mc) {
        if (mc == null || mc.level == null) {
            if (lastObservedLevel != null || !POPS.isEmpty()) {
                lastObservedLevel = null;
                POPS.clear();
            }
            return;
        }

        if (mc.level != lastObservedLevel) {
            lastObservedLevel = mc.level;
            POPS.clear();
            return;
        }

        if (POPS.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCleanupMs < 200L) {
            return;
        }
        lastCleanupMs = now;

        Iterator<Map.Entry<UUID, Integer>> it = POPS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            UUID id = entry.getKey();
            Player p = mc.level.getPlayerByUUID(id);
            if (p != null && (p.isDeadOrDying() || !p.isAlive() || p.isRemoved())) {
                it.remove();
            }
        }
    }
}
