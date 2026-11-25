package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.FriendManager;
import attrium.ru.manager.HudManager;
import attrium.ru.manager.TargetManager;
import attrium.ru.utils.render.AnimationUtil;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Frosted Glass Notifications HUD Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Ultra-clean single-line acrylic glass toast design optimized for mobile GPUs:
 *  - Zero custom shaders, zero FBOs, zero per-frame heap allocations.
 *  - Translucent frosted glass core with crisp 1px glass rim & subtle top sheen.
 *  - Full-height curved left accent bar (Emerald Green / Coral Red / Amber / Cyan)
 *    with a soft 3-band inner glow fading rightwards into the card.
 *  - Razor-sharp custom vector icon + vertically centered Inter SemiBold & Medium text.
 */
public class NotificationsModule extends HudModule {
    public static final int TYPE_ENABLED = 0;
    public static final int TYPE_DISABLED = 1;
    public static final int TYPE_TOTEM = 2;
    public static final int TYPE_FRIEND = 3;

    private static volatile NotificationsModule instance;
    private static volatile boolean suppressToggles = false;

    public static void setSuppressToggles(boolean suppress) {
        suppressToggles = suppress;
    }

    private final ModeSetting anchorPosition = register(new ModeSetting(
            "Anchor Position",
            "Preferred screen corner anchor for toast notifications.",
            "Bottom Right",
            "Bottom Right", "Top Right", "Bottom Left", "Top Left"
    ));

    private final NumberSetting duration = register(new NumberSetting(
            "Duration",
            "How long each notification stays visible in seconds (1.0 - 5.0).",
            2.4D, 1.0D, 5.0D, 0.2D
    ));

    private final NumberSetting maxToasts = register(new NumberSetting(
            "Max Toasts",
            "Maximum simultaneous notifications stacked on screen (2 - 6).",
            4.0D, 2.0D, 6.0D, 1.0D
    ));

    private final BooleanSetting progressBar = register(new BooleanSetting(
            "Progress Bar",
            "Renders a subtle 1.5px timer line along the bottom edge of each toast.",
            false
    ));

    private final BooleanSetting moduleToggles = register(new BooleanSetting(
            "Module Toggles",
            "Dispatch toast alerts whenever a module is turned ON or OFF.",
            true
    ));

    private final BooleanSetting totemPops = register(new BooleanSetting(
            "Totem Pops",
            "Dispatch amber alerts with pop count when you or a nearby player pops a totem.",
            true
    ));

    private final BooleanSetting friendAlerts = register(new BooleanSetting(
            "Friend Alerts",
            "Dispatch alerts when friends are added/removed or enter/leave render distance.",
            true
    ));

    private static final int MAX_POOL = 12;
    private static final class Toast {
        boolean active = false;
        Component titleComp;
        Component suffixComp;
        int cachedTitleW = 0;
        int cachedCardW = 148;
        boolean widthMeasured = false;
        int type = TYPE_ENABLED;
        float ageSec = 0.0F;
        float slideAnim = 0.0F;
        float currentOffsetY = 0.0F;
        boolean offsetInit = false;
    }

    private final Toast[] pool = new Toast[MAX_POOL];
    private final Set<UUID> visibleFriends = new HashSet<>();
    private final Set<UUID> scratchFriends = new HashSet<>();
    private long lastFriendScanMs = 0L;
    private String lastAnchorApplied = "Bottom Right";

    // Cached preview components (zero allocations in Layout Editor / HUD tab)
    private Component previewTitleComp;
    private Component previewSuffixComp;

    public NotificationsModule() {
        super("Notifications", "Shows sleek frosted glass alerts for toggles and combat events",
                "!", 0.985D, 0.945D, 170.0D, true);
        instance = this;
        for (int i = 0; i < MAX_POOL; i++) {
            pool[i] = new Toast();
        }
    }

    public static NotificationsModule getInstance() {
        return instance;
    }

    @Override
    public boolean usesRightAnchorWhenOnRightHalf() {
        return true;
    }

    @Override
    public boolean usesBottomAnchorWhenOnBottomHalf() {
        return true;
    }

    // =========================================================================
    // Event Dispatch Entry Points
    // =========================================================================
    public static void notifyModuleToggle(String moduleName, boolean enabled) {
        if (suppressToggles) return;
        NotificationsModule mod = instance;
        if (mod == null || !mod.isEnabled() || !Boolean.TRUE.equals(mod.moduleToggles.getValue())) return;
        mod.push(moduleName, enabled ? "Enabled" : "Disabled", enabled ? TYPE_ENABLED : TYPE_DISABLED);
    }

    public static void notifyTotemPop(String playerName, int popCount) {
        NotificationsModule mod = instance;
        if (mod == null || !mod.isEnabled() || !Boolean.TRUE.equals(mod.totemPops.getValue())) return;
        mod.push(playerName, "Popped x" + popCount, TYPE_TOTEM);
    }

    public static void notifyFriendEvent(String friendName, String actionMessage, boolean positive) {
        NotificationsModule mod = instance;
        if (mod == null || !mod.isEnabled() || !Boolean.TRUE.equals(mod.friendAlerts.getValue())) return;
        mod.push(friendName, actionMessage, positive ? TYPE_FRIEND : TYPE_DISABLED);
    }

    public static void pushCustom(String title, String message, int type) {
        NotificationsModule mod = instance;
        if (mod == null || !mod.isEnabled()) return;
        mod.push(title, message, type);
    }

    private void push(String title, String message, int type) {
        String safeTitle = (title == null || title.isEmpty()) ? "Attrium" : title.trim();
        String safeMsg = (message == null) ? "" : message.trim();

        Toast slot = null;
        float oldestAge = -1.0F;
        for (int i = 0; i < MAX_POOL; i++) {
            Toast t = pool[i];
            if (!t.active) {
                slot = t;
                break;
            }
            if (t.ageSec > oldestAge) {
                oldestAge = t.ageSec;
                slot = t;
            }
        }
        if (slot == null) return;

        slot.active = true;
        String clippedTitle = safeTitle.length() > 18 ? safeTitle.substring(0, 18) : safeTitle;
        String clippedMsg = safeMsg.length() > 26 ? safeMsg.substring(0, 26) : safeMsg;
        slot.titleComp = Component.literal(clippedTitle).withStyle(HudManager.getBoldStyle());
        slot.suffixComp = clippedMsg.isEmpty()
                ? null
                : Component.literal(" " + clippedMsg).withStyle(HudManager.getRegularStyle());
        slot.widthMeasured = false;
        slot.cachedTitleW = 0;
        slot.cachedCardW = 148;
        slot.type = type;
        slot.ageSec = 0.0F;
        slot.slideAnim = 0.0F;
        slot.offsetInit = false;
    }

    @Override
    public void renderHud(Minecraft mc, GuiGraphics guiGraphics, Font font,
                          int screenW, int screenH, float dt, float animClockSeconds,
                          boolean previewMode, boolean editorMode) {
        syncAnchorPresetIfChanged();
        pollFriendProximityAlerts(mc);

        float sc = getHudScale();
        int defaultCardW = 146;
        int cardH = 28;
        int gap = 6;

        int boxW = Math.round(defaultCardW * sc);
        int boxH = Math.round(cardH * sc);
        boolean alignRight = getXRatio() >= 0.5F;
        boolean stackUp = getYRatio() >= 0.5F;

        int anchorX = Math.round(getXRatio() * screenW);
        int baseX = alignRight ? (anchorX - boxW) : anchorX;
        int baseY = Math.round(getYRatio() * screenH) - (stackUp ? boxH : 0);
        baseX = Mth.clamp(baseX, 4, Math.max(4, screenW - boxW - 4));
        baseY = Mth.clamp(baseY, 4, Math.max(4, screenH - boxH - 4));

        this.lastBoxX = baseX;
        this.lastBoxY = baseY;
        this.lastBoxW = boxW;
        this.lastBoxH = boxH;

        float lifeSec = Mth.clamp(duration.getValue().floatValue(), 1.0F, 5.0F);
        int maxVisible = Mth.clamp(maxToasts.getValue().intValue(), 2, 6);
        boolean showBar = Boolean.TRUE.equals(progressBar.getValue());
        int coreAlpha = Mth.clamp(getBackgroundAlphaByte(), 135, 205);

        int visibleIdx = 0;
        for (int i = 0; i < MAX_POOL; i++) {
            Toast t = pool[i];
            if (!t.active) continue;

            t.ageSec += dt;
            if (t.ageSec >= lifeSec + 0.34F) {
                t.active = false;
                continue;
            }
            if (visibleIdx >= maxVisible) {
                continue;
            }

            if (!t.widthMeasured) {
                int tw = font.width(t.titleComp);
                int sw = (t.suffixComp != null) ? font.width(t.suffixComp) : 0;
                t.cachedTitleW = tw;
                // Balanced horizontal padding: 4px bar + 12px pad + 10px icon + 7px gap + text + 14px right pad
                t.cachedCardW = Mth.clamp(47 + tw + sw, 118, 240);
                t.widthMeasured = true;
            }
            int cardW = t.cachedCardW;

            boolean exiting = t.ageSec >= lifeSec;
            t.slideAnim = AnimationUtil.lerp(t.slideAnim, exiting ? 0.0F : 1.0F, exiting ? 13.0F : 16.0F, dt);

            float targetY = (stackUp ? -1.0F : 1.0F) * visibleIdx * (cardH + gap);
            if (!t.offsetInit) {
                t.currentOffsetY = targetY + (stackUp ? 6.0F : -6.0F);
                t.offsetInit = true;
            } else {
                t.currentOffsetY = AnimationUtil.lerp(t.currentOffsetY, targetY, 16.0F, dt);
            }
            visibleIdx++;

            float raw = Mth.clamp(t.slideAnim, 0.0F, 1.0F);
            float posEase = exiting ? (raw * raw) : AnimationUtil.easeOutCubic(raw);
            float alphaEase = Mth.clamp(raw * 1.18F, 0.0F, 1.0F);
            if (alphaEase <= 0.02F) continue;

            float slidePx = (1.0F - posEase) * (cardW + 20.0F);
            int widthDelta = alignRight ? (defaultCardW - cardW) : 0;
            int drawX = widthDelta + (alignRight ? Math.round(slidePx) : -Math.round(slidePx));
            int drawY = Math.round(t.currentOffsetY);

            int accentCol = resolveAccentColor(t.type);
            float remaining = Mth.clamp(1.0F - (t.ageSec / lifeSec), 0.0F, 1.0F);

            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(baseX, baseY);
            guiGraphics.pose().scale(sc, sc);

            drawFrostedNotificationCard(
                    guiGraphics, font, drawX, drawY, cardW, cardH,
                    accentCol, coreAlpha, alphaEase,
                    t.type, t.titleComp, t.suffixComp, t.cachedTitleW,
                    showBar, remaining
            );

            guiGraphics.pose().popMatrix();
        }

        if (visibleIdx == 0 && (editorMode || previewMode)) {
            if (previewTitleComp == null) {
                previewTitleComp = Component.literal("Velocity").withStyle(HudManager.getBoldStyle());
                previewSuffixComp = Component.literal(" Enabled").withStyle(HudManager.getRegularStyle());
            }
            int tw = font.width(previewTitleComp);
            int sw = font.width(previewSuffixComp);
            int pCardW = Mth.clamp(47 + tw + sw, 118, 220);

            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(baseX, baseY);
            guiGraphics.pose().scale(sc, sc);

            drawFrostedNotificationCard(
                    guiGraphics, font, 0, 0, pCardW, cardH,
                    0xFF22E56B, coreAlpha, 1.0F,
                    TYPE_ENABLED, previewTitleComp, previewSuffixComp, tw,
                    showBar, 0.72F
            );

            guiGraphics.pose().popMatrix();
        }
    }

    private static int resolveAccentColor(int type) {
        if (type == TYPE_DISABLED) {
            return 0xFFF43F5E; // Soft coral red
        }
        if (type == TYPE_TOTEM) {
            return 0xFFFBBF24; // Warm amber gold
        }
        if (type == TYPE_FRIEND) {
            return 0xFF00E5FF; // Aqua cyan
        }
        return 0xFF22E56B; // Vibrant emerald green (matches reference)
    }

    /**
     * Renders the acrylic frosted glass toast with curved full-height left accent bar,
     * soft horizontal inner glow, 1px frosted border rim, crisp vector icon, and centered Inter text.
     */
    private static void drawFrostedNotificationCard(
            GuiGraphics g, Font font, int x, int y, int w, int h,
            int accentCol, int coreAlpha, float alpha,
            int type, Component titleComp, Component suffixComp, int titleW,
            boolean showBar, float remaining
    ) {
        int r = 7;

        // 1. Crisp 1px frosted glass rim (outer rounded silhouette)
        HudManager.drawRoundedRect(g, x - 1, y - 1, w + 2, h + 2, r + 1,
                AnimationUtil.withAlpha(0x36FFFFFF, alpha));

        // 2. Frosted dark-slate glass core (translucent so world shows cleanly behind it)
        int glassCore = (coreAlpha << 24) | 0x161B24;
        HudManager.drawRoundedRect(g, x, y, w, h, r, AnimationUtil.withAlpha(glassCore, alpha));

        // 3. Subtle frosted top-half sheen (gives acrylic light-catch with zero blur shader)
        HudManager.drawRoundedRect(g, x + 1, y + 1, w - 2, h / 2, Math.max(2, r - 1),
                AnimationUtil.withAlpha(0x14FFFFFF, alpha));

        // 4. Full-height curved left accent bar (4px wide, matches left rounded corners!)
        int barCol = AnimationUtil.withAlpha(accentCol, alpha);
        // Rounded left cap via clipped rounded rect + straight inner edge
        HudManager.drawRoundedRect(g, x, y, 8, h, r, barCol);
        // Mask the right 4px of that 8px cap back to glass core so only the curved 4px left strip shows
        g.fill(x + 4, y, x + 8, y + h, AnimationUtil.withAlpha(glassCore, alpha));
        g.fill(x + 4, y + 1, x + 8, y + 1 + h / 2, AnimationUtil.withAlpha(0x14FFFFFF, alpha));

        // 5. Soft 3-band inner glow bleeding rightwards from the accent edge (zero-shader bloom)
        g.fill(x + 4, y + 2, x + 7, y + h - 2, AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(accentCol, 0x2C), alpha));
        g.fill(x + 7, y + 2, x + 11, y + h - 2, AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(accentCol, 0x16), alpha));
        g.fill(x + 11, y + 3, x + 16, y + h - 3, AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(accentCol, 0x08), alpha));

        // 6. Crisp 9x9 Vector Icon (vertically centered at y + (h - 9) / 2)
        int iconX = x + 15;
        int iconY = y + (h - 9) / 2;
        int iconCol = AnimationUtil.withAlpha(0xFFE6EDF5, alpha);
        if ((iconCol >>> 24) > 8) {
            drawCrispVectorIcon(g, iconX, iconY, type, iconCol, barCol);
        }

        // 7. Vertically Centered Single-Line Inter SemiBold + Inter Medium Text
        int textX = iconX + 16;
        int textY = y + (h - 8) / 2;
        int titleCol = AnimationUtil.withAlpha(0xFFFFFFFF, alpha);
        int suffixCol = AnimationUtil.withAlpha(0xFFD8E0EA, alpha);
        if ((titleCol >>> 24) > 8) {
            g.drawString(font, titleComp, textX, textY, titleCol, false);
            if (suffixComp != null) {
                g.drawString(font, suffixComp, textX + titleW, textY, suffixCol, false);
            }
        }

        // 8. Optional sleek bottom timer line
        if (showBar) {
            int barX = x + 10;
            int barW = w - 20;
            int fillW = Math.round(barW * remaining);
            g.fill(barX, y + h - 2, barX + barW, y + h - 1, AnimationUtil.withAlpha(0x1CFFFFFF, alpha));
            if (fillW > 0) {
                g.fill(barX, y + h - 2, barX + fillW, y + h - 1, barCol);
            }
        }
    }

    /**
     * Zero-allocation crisp 9x9 geometric icons (rebound arrow, clean X, diamond shield, star).
     */
    private static void drawCrispVectorIcon(GuiGraphics g, int x, int y, int type, int lightCol, int accentCol) {
        if (type == TYPE_DISABLED) {
            // Clean 7x7 diagonal X
            for (int i = 0; i < 7; i++) {
                g.fill(x + 1 + i, y + 1 + i, x + 2 + i, y + 2 + i, lightCol);
                g.fill(x + 7 - i, y + 1 + i, x + 8 - i, y + 2 + i, lightCol);
            }
            return;
        }
        if (type == TYPE_TOTEM) {
            // Crisp diamond emblem
            g.fill(x + 4, y + 1, x + 5, y + 8, accentCol);
            g.fill(x + 3, y + 2, x + 6, y + 7, accentCol);
            g.fill(x + 2, y + 3, x + 7, y + 6, accentCol);
            g.fill(x + 1, y + 4, x + 8, y + 5, accentCol);
            return;
        }
        if (type == TYPE_FRIEND) {
            // Crisp plus/star emblem
            g.fill(x + 4, y + 1, x + 5, y + 8, accentCol);
            g.fill(x + 1, y + 4, x + 8, y + 5, accentCol);
            g.fill(x + 3, y + 3, x + 6, y + 6, lightCol);
            return;
        }
        // TYPE_ENABLED: Clean rebound / trajectory arrow over a soft horizon arc (matching reference)
        // Down-left to center bounce
        g.fill(x + 1, y + 2, x + 2, y + 3, lightCol);
        g.fill(x + 2, y + 3, x + 3, y + 4, lightCol);
        g.fill(x + 3, y + 4, x + 4, y + 5, lightCol);
        g.fill(x + 4, y + 5, x + 5, y + 6, lightCol);
        // Bounce up-right to arrow tip
        g.fill(x + 5, y + 4, x + 6, y + 5, lightCol);
        g.fill(x + 6, y + 3, x + 7, y + 4, lightCol);
        g.fill(x + 7, y + 2, x + 8, y + 3, lightCol);
        // Arrowhead at top-right
        g.fill(x + 5, y + 2, x + 8, y + 3, lightCol);
        g.fill(x + 7, y + 2, x + 8, y + 5, lightCol);
        // Soft curved base horizon below bounce
        g.fill(x + 2, y + 7, x + 7, y + 8, AnimationUtil.withAlpha(lightCol, 0.72F));
        g.fill(x + 1, y + 8, x + 8, y + 9, AnimationUtil.withAlpha(lightCol, 0.45F));
    }

    private void syncAnchorPresetIfChanged() {
        String current = anchorPosition.getValue();
        if (current == null || current.equals(lastAnchorApplied)) return;
        lastAnchorApplied = current;
        switch (current) {
            case "Top Right" -> setPositionRatio(0.985F, 0.055F);
            case "Bottom Left" -> setPositionRatio(0.015F, 0.945F);
            case "Top Left" -> setPositionRatio(0.015F, 0.055F);
            default -> setPositionRatio(0.985F, 0.945F);
        }
    }

    private void pollFriendProximityAlerts(Minecraft mc) {
        if (!Boolean.TRUE.equals(friendAlerts.getValue()) || mc.player == null || mc.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFriendScanMs < 1000L) return;
        lastFriendScanMs = now;

        scratchFriends.clear();
        Player[] players = TargetManager.getAllPlayersArray();
        int count = TargetManager.getAllPlayerCount();
        for (int i = 0; i < count; i++) {
            Player p = players[i];
            if (p == null || p == mc.player) continue;
            if (FriendManager.isFriend(p)) {
                UUID id = p.getUUID();
                scratchFriends.add(id);
                if (!visibleFriends.contains(id)) {
                    push(p.getName().getString(), "Entered range", TYPE_FRIEND);
                }
            }
        }
        for (UUID prev : visibleFriends) {
            if (!scratchFriends.contains(prev)) {
                String name = FriendManager.getFriendsMap().get(prev);
                if (name != null && !name.isEmpty()) {
                    push(name, "Left range", TYPE_DISABLED);
                }
            }
        }
        visibleFriends.clear();
        visibleFriends.addAll(scratchFriends);
    }
}
