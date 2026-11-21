package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.api.module.impl.misc.NameProtectModule;
import attrium.ru.manager.ClientThemeManager;
import attrium.ru.manager.FriendManager;
import attrium.ru.manager.HudManager;
import attrium.ru.utils.render.AnimationUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * PlayerRadar & Proximity Alert Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Extends HudModule so it syncs seamlessly between the Modules tab, ClickGUI HUD tab,
 * and interactive drag-and-drop Layout Editor (protected by HudManager's RenderSafeGuard).
 *
 * Features:
 *  - Zero-allocation once-per-tick proximity scan with Anti-Bot & FriendManager filters.
 *  - Discreet NotificationsModule warning toast + subtle note-block chime on player entry,
 *    with configurable Re-Alert Cooldown and automatic world-change state reset.
 *  - Compact 145px dark glassmorphic Radar HUD card sorted nearest-first with 14x14 player
 *    heads, NameProtect-safe names, color-coded distances, rotated directional arrows,
 *    and smooth exponential height interpolation.
 */
public class PlayerRadarModule extends HudModule {
    private static volatile PlayerRadarModule instance;

    private final BooleanSetting alertToast = register(new BooleanSetting(
            "Alert Toast",
            "Dispatches a sleek notification toast when an unknown player enters range.",
            true
    ));
    private final BooleanSetting alertChime = register(new BooleanSetting(
            "Alert Chime",
            "Plays a subtle note-block chime when an unknown player enters range.",
            true
    ));
    private final NumberSetting alertRange = register(new NumberSetting(
            "Alert Range",
            "Detection radius in blocks (16 - 128).",
            64.0D, 16.0D, 128.0D, 4.0D
    ));
    private final NumberSetting reAlertCooldown = register(new NumberSetting(
            "Re-Alert Cooldown",
            "Minimum seconds before re-alerting for the same player (5 - 60).",
            20.0D, 5.0D, 60.0D, 5.0D
    ));
    private final BooleanSetting ignoreFriends = register(new BooleanSetting(
            "Ignore Friends",
            "Skips any player whitelisted in FriendManager.",
            true
    ));
    private final BooleanSetting antiBotFilter = register(new BooleanSetting(
            "Anti-Bot Filter",
            "Ignores fake NPCs by verifying tab-list presence and entity age >= 10 ticks.",
            true
    ));
    private final BooleanSetting showRadarHud = register(new BooleanSetting(
            "Show Radar HUD",
            "Renders a compact nearby-player radar card on the HUD.",
            true
    ));
    private final NumberSetting maxHudRows = register(new NumberSetting(
            "Max HUD Rows",
            "Maximum player rows displayed on the Radar HUD card (3 - 8).",
            5.0D, 3.0D, 8.0D, 1.0D
    ));

    // Pre-allocated zero-churn tracking structures
    private static final int MAX_TRACKED = 64;
    private static final class RadarEntry {
        AbstractClientPlayer player;
        double distSq;
        int tenthMeters = -1;
        String lastName = "";
        Component nameComp;
        Component distComp;
        float relAngleRad;
    }

    private final RadarEntry[] radarPool = new RadarEntry[MAX_TRACKED];
    private int radarCount = 0;

    private final HashMap<UUID, Long> lastAlertTimestamps = new HashMap<>(64);
    private final HashSet<UUID> activeInRange = new HashSet<>(64);
    private final HashSet<UUID> currentTickInRange = new HashSet<>(64);
    private ClientLevel lastWorldRef = null;
    private long lastScannedGameTime = -1L;

    // Smooth height animation & cached static UI components
    private float animatedCardH = 26.0F;
    private int cachedHeaderCount = -1;
    private Component headerTitleComp;
    private Component headerCountComp;
    private Component arrowComp;
    private Component previewNameComp;
    private Component previewDistComp;

    public PlayerRadarModule() {
        super("PlayerRadar", "Alerts and tracks nearby unknown players with distance radar",
                "R", 0.015D, 0.24D, 204.0D, false);
        instance = this;
        this.maxHudRows.visibleWhen(() -> Boolean.TRUE.equals(this.showRadarHud.getValue()));
        for (int i = 0; i < MAX_TRACKED; i++) {
            radarPool[i] = new RadarEntry();
        }
    }

    public static PlayerRadarModule getInstance() {
        return instance;
    }

    // =========================================================================
    // Once-Per-Tick Proximity Detection & Anti-Bot Pipeline
    // =========================================================================
    public static void tickProximity(Minecraft mc) {
        PlayerRadarModule mod = instance;
        if (mod == null || !mod.isEnabled()) {
            return;
        }
        mod.scanPlayersOncePerTick(mc);
    }

    private void scanPlayersOncePerTick(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            if (lastWorldRef != null) {
                lastWorldRef = null;
                lastAlertTimestamps.clear();
                activeInRange.clear();
                currentTickInRange.clear();
                radarCount = 0;
            }
            return;
        }

        if (mc.level != lastWorldRef) {
            lastWorldRef = mc.level;
            lastAlertTimestamps.clear();
            activeInRange.clear();
            currentTickInRange.clear();
            radarCount = 0;
            lastScannedGameTime = -1L;
        }

        long gameTime = mc.level.getGameTime();
        if (gameTime == lastScannedGameTime) {
            return;
        }
        lastScannedGameTime = gameTime;

        double range = Mth.clamp(alertRange.getValue(), 16.0D, 128.0D);
        double maxDistSq = range * range;
        long cooldownMs = Math.round(Mth.clamp(reAlertCooldown.getValue(), 5.0D, 60.0D) * 1000.0D);
        boolean doIgnoreFriends = Boolean.TRUE.equals(ignoreFriends.getValue());
        boolean doAntiBot = Boolean.TRUE.equals(antiBotFilter.getValue());
        boolean doToast = Boolean.TRUE.equals(alertToast.getValue());
        boolean doChime = Boolean.TRUE.equals(alertChime.getValue());

        ClientPacketListener conn = mc.getConnection();
        List<AbstractClientPlayer> players = mc.level.players();
        long nowMs = System.currentTimeMillis();

        currentTickInRange.clear();
        int count = 0;

        double selfX = mc.player.getX();
        double selfZ = mc.player.getZ();
        float selfYaw = mc.player.getYRot();

        for (int i = 0, size = players.size(); i < size; i++) {
            AbstractClientPlayer p = players.get(i);
            if (p == null || p == mc.player) {
                continue;
            }
            UUID uuid = p.getUUID();
            if (uuid == null) {
                continue;
            }
            if (doAntiBot) {
                if (p.tickCount < 10) {
                    continue;
                }
                if (conn != null && conn.getPlayerInfo(uuid) == null) {
                    continue;
                }
            }
            if (doIgnoreFriends && FriendManager.isFriend(p)) {
                continue;
            }

            double distSq = mc.player.distanceToSqr(p);
            if (distSq > maxDistSq) {
                continue;
            }

            currentTickInRange.add(uuid);

            // Populate sorted radar pool (capped at MAX_TRACKED)
            if (count < MAX_TRACKED) {
                RadarEntry entry = radarPool[count++];
                entry.player = p;
                entry.distSq = distSq;

                double dx = p.getX() - selfX;
                double dz = p.getZ() - selfZ;
                float targetYawDeg = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
                float relDeg = Mth.wrapDegrees(targetYawDeg - selfYaw);
                entry.relAngleRad = (float) Math.toRadians(relDeg);
            }

            // Trigger entry alert if newly in range & cooldown elapsed
            if (!activeInRange.contains(uuid)) {
                Long prevTs = lastAlertTimestamps.get(uuid);
                long lastTs = (prevTs != null) ? prevTs : 0L;
                if (nowMs - lastTs >= cooldownMs) {
                    double dist = Math.sqrt(distSq);
                    String distStr = String.format(Locale.ROOT, "%.1fm", dist);
                    String rawName = p.getName() != null ? p.getName().getString() : "Player";
                    String safeName = NameProtectModule.isActive()
                            ? NameProtectModule.protect(rawName)
                            : rawName;

                    if (doToast) {
                        NotificationsModule.pushCustom(
                                "Player Nearby",
                                safeName + " (" + distStr + ")",
                                NotificationsModule.TYPE_TOTEM
                        );
                    }
                    if (doChime) {
                        try {
                            mc.player.playSound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.5F, 1.35F);
                        } catch (Throwable ignored) {
                        }
                    }
                    lastAlertTimestamps.put(uuid, nowMs);
                }
            }
        }

        // Update activeInRange for next tick (zero allocation swap via clear+addAll)
        activeInRange.clear();
        activeInRange.addAll(currentTickInRange);

        // Sort nearest-first via in-place insertion sort
        for (int i = 1; i < count; i++) {
            RadarEntry key = radarPool[i];
            int j = i - 1;
            while (j >= 0 && radarPool[j].distSq > key.distSq) {
                radarPool[j + 1] = radarPool[j];
                j--;
            }
            radarPool[j + 1] = key;
        }
        radarCount = count;
    }

    // =========================================================================
    // Compact Glassmorphic Radar HUD Widget
    // =========================================================================
    @Override
    public void renderHud(Minecraft mc, GuiGraphics guiGraphics, Font font,
                          int screenW, int screenH, float dt, float animClockSeconds,
                          boolean previewMode, boolean editorMode) {
        scanPlayersOncePerTick(mc);

        boolean showHud = Boolean.TRUE.equals(showRadarHud.getValue());
        if (!showHud && !previewMode && !editorMode) {
            return;
        }

        int maxRows = Mth.clamp(maxHudRows.getValue().intValue(), 3, 8);
        int visibleRows = Math.min(radarCount, maxRows);
        boolean showPreviewRow = (visibleRows == 0) && (previewMode || editorMode);
        if (visibleRows == 0 && !showPreviewRow) {
            return;
        }

        ensureStaticComponents();

        float sc = getHudScale();
        int cardW = 148;
        int headerH = 22;
        int rowH = 19;
        int rowCountForHeight = showPreviewRow ? 1 : visibleRows;
        float targetCardH = headerH + rowCountForHeight * rowH + 6.0F;
        animatedCardH = AnimationUtil.lerp(animatedCardH, targetCardH, 14.0F, AnimationUtil.clampDelta(dt));
        int cardH = Math.max(headerH + 4, Math.round(animatedCardH));

        int boxW = Math.round(cardW * sc);
        int boxH = Math.round(cardH * sc);
        int baseX = Mth.clamp(Math.round(getXRatio() * screenW), 2, Math.max(2, screenW - boxW - 2));
        int baseY = Mth.clamp(Math.round(getYRatio() * screenH), 2, Math.max(2, screenH - boxH - 2));

        this.lastBoxX = baseX;
        this.lastBoxY = baseY;
        this.lastBoxW = boxW;
        this.lastBoxH = boxH;

        int accent = ClientThemeManager.getAccentArgb();
        int cardBg = (Math.max(200, getBackgroundAlphaByte()) << 24) | 0x111318;

        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate(baseX, baseY);
        guiGraphics.pose().scale(sc, sc);

        // Soft accent rim + dark glassmorphic card (10px radius)
        HudManager.drawRoundedRect(guiGraphics, -1, -1, cardW + 2, cardH + 2, 11,
                ClientThemeManager.getAccentWithAlpha(0.18F));
        HudManager.drawRoundedRect(guiGraphics, 0, 0, cardW, cardH, 10, cardBg);

        // Header Row: "Radar" Bold + Aqua-Cyan count pill
        guiGraphics.drawString(font, headerTitleComp, 9, 7, 0xFFFFFFFF, false);
        int badgeNum = showPreviewRow ? 1 : radarCount;
        if (badgeNum != cachedHeaderCount || headerCountComp == null) {
            cachedHeaderCount = badgeNum;
            headerCountComp = Component.literal(String.valueOf(badgeNum)).withStyle(HudManager.getBoldStyle());
        }
        int cw = font.width(headerCountComp);
        int pillW = Math.max(18, cw + 10);
        int pillX = cardW - pillW - 8;
        HudManager.drawRoundedRect(guiGraphics, pillX, 5, pillW, 13, 6, ClientThemeManager.getAccentWithAlpha(0.22F));
        guiGraphics.drawString(font, headerCountComp, pillX + (pillW - cw) / 2, 7, accent, false);

        // Subtle divider line under header
        guiGraphics.fill(8, headerH, cardW - 8, headerH + 1, 0x1AFFFFFF);

        // Player Rows
        int curY = headerH + 4;
        if (showPreviewRow) {
            HudManager.drawRoundedRect(guiGraphics, 8, curY + 1, 14, 14, 3, 0x3300E5FF);
            guiGraphics.drawString(font, previewNameComp, 26, curY + 4, 0xFFE5E7EB, false);
            int dw = font.width(previewDistComp);
            guiGraphics.drawString(font, previewDistComp, cardW - dw - 18, curY + 4, 0xFFFACC15, false);
            drawRotatedArrow(guiGraphics, font, cardW - 11, curY + 8, 0.45F, 0xFFFACC15);
        } else {
            for (int i = 0; i < visibleRows; i++) {
                if (curY + rowH > cardH + 2) break;
                RadarEntry entry = radarPool[i];
                if (entry.player == null) continue;

                // Left: 14x14 player skin head
                HudManager.drawPlayerAvatar(guiGraphics, mc, entry.player, 8, curY + 1, 14);

                // Distance & color tier (<20m Coral-Red, 20-45m Amber, >45m Aqua-Cyan)
                double dist = Math.sqrt(entry.distSq);
                int tenths = (int) Math.round(dist * 10.0D);
                if (tenths != entry.tenthMeters || entry.distComp == null) {
                    entry.tenthMeters = tenths;
                    int whole = tenths / 10;
                    int frac = tenths % 10;
                    entry.distComp = Component.literal(whole + "." + frac + "m").withStyle(HudManager.getBoldStyle());
                }
                int distCol = (dist < 20.0D) ? 0xFFEF4444 : ((dist <= 45.0D) ? 0xFFFACC15 : accent);
                int distW = font.width(entry.distComp);
                int distX = cardW - distW - 18;
                guiGraphics.drawString(font, entry.distComp, distX, curY + 4, distCol, false);

                // Rotated directional arrow indicator
                drawRotatedArrow(guiGraphics, font, cardW - 11, curY + 8, entry.relAngleRad, distCol);

                // Center: NameProtect-safe username clipped cleanly before distance
                String rawName = entry.player.getName() != null ? entry.player.getName().getString() : "Player";
                String safeName = NameProtectModule.isActive()
                        ? NameProtectModule.protect(rawName)
                        : rawName;
                if (!safeName.equals(entry.lastName) || entry.nameComp == null) {
                    entry.lastName = safeName;
                    String clipped = safeName.length() > 12 ? safeName.substring(0, 11) + ".." : safeName;
                    entry.nameComp = Component.literal(clipped).withStyle(HudManager.getRegularStyle());
                }
                guiGraphics.drawString(font, entry.nameComp, 26, curY + 4, 0xFFE5E7EB, false);

                curY += rowH;
            }
        }

        guiGraphics.pose().popMatrix();
    }

    private void drawRotatedArrow(GuiGraphics guiGraphics, Font font, int cx, int cy, float angleRad, int color) {
        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate(cx, cy);
        guiGraphics.pose().rotate(angleRad);
        int aw = font.width(arrowComp);
        guiGraphics.drawString(font, arrowComp, -aw / 2, -4, color, false);
        guiGraphics.pose().popMatrix();
    }

    private void ensureStaticComponents() {
        if (headerTitleComp != null) return;
        headerTitleComp = Component.literal("Radar").withStyle(HudManager.getBoldStyle());
        arrowComp = Component.literal("^").withStyle(HudManager.getBoldStyle());
        previewNameComp = Component.literal("Opponent").withStyle(HudManager.getRegularStyle());
        previewDistComp = Component.literal("34.5m").withStyle(HudManager.getBoldStyle());
    }
}
