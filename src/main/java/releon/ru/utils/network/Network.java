package releon.ru.utils.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import releon.ru.mixin.accessor.BossHealthOverlayAccessor;

import java.util.Locale;
import java.util.Map;

public final class Network {
    private static final Minecraft MC = Minecraft.getInstance();
    private static long lastPvpMs;
    private static long lastTpsPacketNs;
    private static float tps = 20.0F;
    private static String server = "Vanilla";

    private Network() {
    }

    public static void tick() {
        server = detectServer();
        updatePvpState();
    }

    public static void handleTimePacket() {
        long now = System.nanoTime();
        if (lastTpsPacketNs != 0L) {
            float raw = 20.0F * (1.0E9F / (now - lastTpsPacketNs));
            tps = Mth.clamp(raw, 0.0F, 20.0F);
        }
        lastTpsPacketNs = now;
    }

    public static float getTPS() {
        return tps;
    }

    public static String getServer() {
        server = detectServer();
        return server;
    }

    public static boolean isPvp() {
        updatePvpState();
        return System.currentTimeMillis() - lastPvpMs <= 500L;
    }

    public static boolean isCopyTime() {
        String current = getServer();
        return "CopyTime".equals(current) || "SpookyTime".equals(current) || "FunTime".equals(current);
    }

    public static boolean isFunTime() {
        return "FunTime".equals(getServer());
    }

    public static boolean isReallyWorld() {
        return "ReallyWorld".equals(getServer());
    }

    public static boolean isGulPvP() {
        return "GulPvP".equals(getServer());
    }

    public static boolean isHolyWorld() {
        return "HolyWorld".equals(getServer());
    }

    public static boolean isSpookyTime() {
        return "SpookyTime".equals(getServer());
    }

    public static boolean isVanilla() {
        return "Vanilla".equals(getServer());
    }

    public static float getResolvedHealth(LivingEntity entity, boolean includeAbsorption) {
        if (entity == null) {
            return 0.0F;
        }
        float health = Math.max(0.0F, entity.getHealth());
        return includeAbsorption ? health + Math.max(0.0F, entity.getAbsorptionAmount()) : health;
    }

    public static String formatHealthValue(float hp) {
        if (hp >= 100.0F) {
            return Integer.toString((int) hp);
        }
        if (hp >= 10.0F) {
            return String.format(Locale.ROOT, "%.1f", hp);
        }
        return String.format(Locale.ROOT, "%.2f", hp);
    }

    public static int getAnarchyMode() {
        if (MC.level == null) {
            return -1;
        }
        Scoreboard scoreboard = MC.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            return -1;
        }

        String header = clean(objective.getDisplayName().getString());
        int fromHeader = extractNumberAfter(header, "#");
        if (fromHeader != -1) {
            return fromHeader;
        }

        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            String row = clean(PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()).getString());
            int value = extractNumberAfter(row, "#");
            if (value != -1) {
                return value;
            }
        }
        return -1;
    }

    private static String detectServer() {
        if (MC.getConnection() == null || MC.getConnection().getServerData() == null) {
            return "Vanilla";
        }

        String address = safeLower(MC.getConnection().getServerData().ip);
        String brand = safeLower(MC.getConnection().serverBrand());
        String normalizedBrand = normalizeServerToken(MC.getConnection().serverBrand());

        if (brand.contains("botfilter") || normalizedBrand.contains("botfilter")) {
            return "FunTime";
        }
        if (address.contains("spooky") || address.contains("pookie") || brand.contains("spooky") || brand.contains("pookie")
                || normalizedBrand.contains("spookycore") || normalizedBrand.contains("spookytime") || normalizedBrand.contains("pookietime")) {
            return "SpookyTime";
        }
        if (address.contains("funtime") || address.contains("skytime") || address.contains("space-times") || address.contains("funsky")) {
            return "CopyTime";
        }
        if (brand.contains("holyworld") || normalizedBrand.contains("holyworld") || brand.contains("vk.com/idwok")) {
            return "HolyWorld";
        }
        if (address.contains("reallyworld")) {
            return "ReallyWorld";
        }
        if (address.contains("gulpvp")) {
            return "GulPvP";
        }
        return "Vanilla";
    }

    private static void updatePvpState() {
        if (MC.gui == null || MC.gui.getBossOverlay() == null) {
            return;
        }
        Map<?, LerpingBossEvent> events = ((BossHealthOverlayAccessor) MC.gui.getBossOverlay()).releon$getEvents();
        for (LerpingBossEvent event : events.values()) {
            String name = safeLower(event.getName().getString());
            if (name.contains("pvp") || name.contains("пвп")) {
                lastPvpMs = System.currentTimeMillis();
                return;
            }
        }
    }

    private static int extractNumberAfter(String value, String marker) {
        int markerIndex = value.indexOf(marker);
        if (markerIndex == -1) {
            return -1;
        }
        int start = markerIndex + marker.length();
        StringBuilder digits = new StringBuilder();
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (!digits.isEmpty()) {
                break;
            }
        }
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String normalizeServerToken(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        boolean skip = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (skip) {
                skip = false;
                continue;
            }
            if (c == '\u00A7') {
                skip = true;
                continue;
            }
            if (c == 194 || Character.isWhitespace(c)) {
                continue;
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    private static String clean(String value) {
        return normalizeServerToken(value).replace('\u00A0', ' ').trim();
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
