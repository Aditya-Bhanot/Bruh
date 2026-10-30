package releon.ru.api.module.impl.visual.esp;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EspHealthTracker {
    private static final long SERVER_HEALTH_TIMEOUT_MS = 3_000L;
    private static final Pattern HEALTH_NUMBER_PATTERN = Pattern.compile("\\d+(?:[.,]\\d+)?");

    private final Map<UUID, ServerHealth> serverHealthByPlayer = new HashMap<>();

    public void clear() {
        serverHealthByPlayer.clear();
    }

    public void prune() {
        long now = System.currentTimeMillis();
        serverHealthByPlayer.entrySet().removeIf(entry -> now - entry.getValue().timestampMs() > SERVER_HEALTH_TIMEOUT_MS);
    }

    public void capture(Entity entity, Component scoreText) {
        if (!(entity instanceof Player player)) {
            return;
        }
        if (scoreText == null) {
            serverHealthByPlayer.remove(player.getUUID());
            return;
        }

        Float health = parseServerHealth(scoreText.getString());
        if (health != null) {
            serverHealthByPlayer.put(player.getUUID(), new ServerHealth(health, System.currentTimeMillis()));
        }
    }

    public float resolveDisplayHealth(LivingEntity entity) {
        return resolveDisplayHealth(entity, true);
    }

    public float resolveDisplayHealth(LivingEntity entity, boolean includeAbsorption) {
        if (entity instanceof Player player) {
            Float trackedHealth = resolveTrackedHealth(player);
            if (trackedHealth != null) {
                return trackedHealth;
            }
        }
        float health = Math.max(0.0F, entity.getHealth());
        return includeAbsorption ? health + Math.max(0.0F, entity.getAbsorptionAmount()) : health;
    }

    public String formatHealth(float health) {
        int scaled = Math.max(0, Math.round(health * 10.0F));
        int whole = scaled / 10;
        int fraction = scaled % 10;
        if (fraction == 0) {
            return Integer.toString(whole);
        }
        return whole + "." + fraction;
    }

    private Float parseServerHealth(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        Matcher matcher = HEALTH_NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }

        try {
            float value = Float.parseFloat(matcher.group().replace(',', '.'));
            return Float.isFinite(value) && value >= 0.0F ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Float resolveScoreboardHealth(Player player) {
        try {
            if (player.level() == null) {
                return null;
            }
            Scoreboard scoreboard = player.level().getScoreboard();
            Objective objective = scoreboard.getDisplayObjective(DisplaySlot.BELOW_NAME);
            if (objective == null) {
                return null;
            }

            ReadOnlyScoreInfo score = scoreboard.getPlayerScoreInfo(player, objective);
            if (score == null) {
                return null;
            }

            float health = score.value();
            return isValidServerHealth(player, health) ? health : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Float resolveTrackedHealth(Player player) {
        Float scoreboardHealth = resolveScoreboardHealth(player);
        if (scoreboardHealth != null) {
            return scoreboardHealth;
        }

        ServerHealth serverHealth = serverHealthByPlayer.get(player.getUUID());
        if (serverHealth != null && System.currentTimeMillis() - serverHealth.timestampMs() <= SERVER_HEALTH_TIMEOUT_MS) {
            return Math.max(0.0F, serverHealth.health());
        }
        return null;
    }


    private boolean isValidServerHealth(Player player, float health) {
        if (!Float.isFinite(health) || health < 0.0F || health > 500.0F) {
            return false;
        }

        float maxHealth = Math.max(1.0F, player.getMaxHealth() + player.getAbsorptionAmount());
        return health <= Math.max(maxHealth * 8.0F, 200.0F);
    }

    private record ServerHealth(float health, long timestampMs) {
    }
}
