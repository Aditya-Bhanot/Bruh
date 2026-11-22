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
import attrium.ru.manager.ClientThemeManager;
import attrium.ru.manager.FriendManager;
import attrium.ru.manager.HudManager;
import attrium.ru.manager.TargetManager;
import attrium.ru.utils.render.AnimationUtil;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Standalone Notifications HUD Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Polished glassmorphic toast cards with:
 *  - Dynamic auto-sizing width (132px - 210px) so titles & messages never feel cramped
 *  - Subtle accent rim sheen, deep dark glass core, 3px left status bar & soft glowing indicator
 *  - Silky framerate-independent slide-in/slide-out & smooth vertical stack reflow
 *  - Recessed 2px shrinking timer bar synced to live ClientThemeManager accent
 *  - Zero per-frame heap allocations for FoldCraftLauncher
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
            "Renders a sleek 2px shrinking timer line along the bottom of each toast.",
            true
    ));

    private final BooleanSetting moduleToggles = register(new BooleanSetting(
            "Module Toggles",
            "Dispatch toast alerts whenever a module is turned ON or OFF.",
            true
    ));

    private final BooleanSetting totemPops = register(new BooleanSetting(
            "Totem Pops",
            "Dispatch gold alerts with pop count when you or a nearby player pops a totem.",
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
        Component msgComp;
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

    // Cached preview components (zero per-frame allocations in Layout Editor)
    private Component previewTitleComp;
    private Component previewMsgComp;

    public NotificationsModule() {
        super("Notifications", "Shows sleek toast alerts for toggles and combat events",
                "!", 0.985D, 0.945D, 224.0D, true);
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
        mod.push(playerName, "Popped Totem x" + popCount, TYPE_TOTEM);
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
        String safeTitle = (title == null || title.isEmpty()) ? "Attrium" : title;
        String safeMsg = (message == null) ? "" : message;

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
        slot.titleComp = Component.literal(safeTitle.length() > 22 ? safeTitle.substring(0, 22) : safeTitle)
                .withStyle(HudManager.getBoldStyle());
        slot.msgComp = Component.literal(safeMsg.length() > 30 ? safeMsg.substring(0, 30) : safeMsg)
                .withStyle(HudManager.getRegularStyle());
        slot.widthMeasured = false;
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
        int defaultCardW = 152;
        int cardH = 32;
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
        int cardBgBase = (Math.max(205, getBackgroundAlphaByte()) << 24) | 0x10131A;

        int visibleIdx = 0;
        for (int i = 0; i < MAX_POOL; i++) {
            Toast t = pool[i];
            if (!t.active) continue;

            t.ageSec += dt;
            if (t.ageSec >= lifeSec + 0.36F) {
                t.active = false;
                continue;
            }
            if (visibleIdx >= maxVisible) {
                continue;
            }

            if (!t.widthMeasured) {
                int tw = font.width(t.titleComp);
                int mw = font.width(t.msgComp);
                t.cachedCardW = Mth.clamp(Math.max(tw, mw) + 34, 132, 212);
                t.widthMeasured = true;
            }
            int cardW = t.cachedCardW;

            boolean exiting = t.ageSec >= lifeSec;
            t.slideAnim = AnimationUtil.lerp(t.slideAnim, exiting ? 0.0F : 1.0F, exiting ? 12.0F : 15.0F, dt);

            float targetY = (stackUp ? -1.0F : 1.0F) * visibleIdx * (cardH + gap);
            if (!t.offsetInit) {
                t.currentOffsetY = targetY + (stackUp ? 8.0F : -8.0F);
                t.offsetInit = true;
            } else {
                t.currentOffsetY = AnimationUtil.lerp(t.currentOffsetY, targetY, 16.0F, dt);
            }
            visibleIdx++;

            float raw = Mth.clamp(t.slideAnim, 0.0F, 1.0F);
            float posEase = exiting ? (raw * raw) : AnimationUtil.easeOutCubic(raw);
            float alphaEase = Mth.clamp(raw * 1.15F, 0.0F, 1.0F);
            if (alphaEase <= 0.02F) continue;

            float slidePx = (1.0F - posEase) * (cardW + 22.0F);
            int widthDelta = alignRight ? (defaultCardW - cardW) : 0;
            int drawX = widthDelta + (alignRight ? Math.round(slidePx) : -Math.round(slidePx));
            int drawY = Math.round(t.currentOffsetY);

            int accentCol;
            if (t.type == TYPE_DISABLED) {
                accentCol = 0xFFF43F5E; // Soft coral rose
            } else if (t.type == TYPE_TOTEM) {
                accentCol = 0xFFFBBF24; // Warm amber gold
            } else if (t.type == TYPE_FRIEND) {
                accentCol = 0xFF10B981; // Emerald mint
            } else {
                accentCol = ClientThemeManager.getAccentArgb();
            }

            float remaining = Mth.clamp(1.0F - (t.ageSec / lifeSec), 0.0F, 1.0F);

            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(baseX, baseY);
            guiGraphics.pose().scale(sc, sc);

            // Soft 1px outer rim tint + frosted dark glass card (10px radius)
            HudManager.drawRoundedRect(guiGraphics, drawX - 1, drawY - 1, cardW + 2, cardH + 2, 10,
                    AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(accentCol, 0x26), alphaEase));
            HudManager.drawGlassCard(guiGraphics, drawX, drawY, cardW, cardH, 9, AnimationUtil.withAlpha(cardBgBase, alphaEase), alphaEase);

            // Sleek 3px rounded left vertical status accent bar
            HudManager.drawRoundedRect(guiGraphics, drawX + 6, drawY + 6, 3, cardH - 12, 1,
                    AnimationUtil.withAlpha(accentCol, alphaEase));

            // Subtle ambient dot glow beside title
            int textX = drawX + 16;
            int titleCol = AnimationUtil.withAlpha(0xFFF8FAFC, alphaEase);
            int msgCol = AnimationUtil.withAlpha(0xFF94A3B8, alphaEase);
            if ((titleCol >>> 24) > 8) {
                guiGraphics.drawString(font, t.titleComp, textX, drawY + 6, titleCol, false);
                guiGraphics.drawString(font, t.msgComp, textX, drawY + 17, msgCol, false);
            }

            // Recessed 2px shrinking timer line
            if (showBar) {
                int barMargin = 10;
                int barMaxW = cardW - barMargin * 2;
                int barFillW = Math.round(barMaxW * remaining);
                HudManager.drawRoundedRect(guiGraphics, drawX + barMargin, drawY + cardH - 3, barMaxW, 2, 1,
                        AnimationUtil.withAlpha(0x1EFFFFFF, alphaEase));
                if (barFillW > 1) {
                    HudManager.drawRoundedRect(guiGraphics, drawX + barMargin, drawY + cardH - 3, barFillW, 2, 1,
                            AnimationUtil.withAlpha(accentCol, alphaEase));
                }
            }

            guiGraphics.pose().popMatrix();
        }

        if (visibleIdx == 0 && (editorMode || previewMode)) {
            if (previewTitleComp == null) {
                previewTitleComp = Component.literal("Notifications").withStyle(HudManager.getBoldStyle());
                previewMsgComp = Component.literal("Smooth Glass Alert").withStyle(HudManager.getRegularStyle());
            }
            int accent = ClientThemeManager.getAccentArgb();
            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(baseX, baseY);
            guiGraphics.pose().scale(sc, sc);
            HudManager.drawRoundedRect(guiGraphics, -1, -1, defaultCardW + 2, cardH + 2, 10,
                    AnimationUtil.withAlphaByte(accent, 0x28));
            HudManager.drawGlassCard(guiGraphics, 0, 0, defaultCardW, cardH, 9, cardBgBase, 1.0F);
            HudManager.drawRoundedRect(guiGraphics, 6, 6, 3, cardH - 12, 1, accent);
            guiGraphics.drawString(font, previewTitleComp, 16, 6, 0xFFF8FAFC, false);
            guiGraphics.drawString(font, previewMsgComp, 16, 17, 0xFF94A3B8, false);
            if (showBar) {
                HudManager.drawRoundedRect(guiGraphics, 10, cardH - 3, defaultCardW - 20, 2, 1, 0x1EFFFFFF);
                HudManager.drawRoundedRect(guiGraphics, 10, cardH - 3, (defaultCardW - 20) * 3 / 4, 2, 1, accent);
            }
            guiGraphics.pose().popMatrix();
        }
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
                    push(p.getName().getString(), "Entered render distance", TYPE_FRIEND);
                }
            }
        }
        for (UUID prev : visibleFriends) {
            if (!scratchFriends.contains(prev)) {
                String name = FriendManager.getFriendsMap().get(prev);
                if (name != null && !name.isEmpty()) {
                    push(name, "Left render distance", TYPE_DISABLED);
                }
            }
        }
        visibleFriends.clear();
        visibleFriends.addAll(scratchFriends);
    }
}
