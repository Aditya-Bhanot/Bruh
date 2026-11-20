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
 * Standalone Notifications HUD Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Renders animated 145x34 glassmorphic toast cards with:
 *  - Anchor Position (Bottom Right, Top Right, Bottom Left, Top Left) + Drag-and-Drop override
 *  - Duration (1.0 - 5.0s), Max Toasts (2 - 6), Progress Bar toggle
 *  - Event toggles: Module Toggles, Totem Pops (with live pop count), Friend Alerts (add/remove & render range)
 */
public class NotificationsModule extends HudModule {
    public static final int TYPE_ENABLED = 0;
    public static final int TYPE_DISABLED = 1;
    public static final int TYPE_TOTEM = 2;
    public static final int TYPE_FRIEND = 3;

    private static volatile NotificationsModule instance;

    private final ModeSetting anchorPosition = register(new ModeSetting(
            "Anchor Position",
            "Preferred screen corner anchor for toast notifications.",
            "Bottom Right",
            "Bottom Right", "Top Right", "Bottom Left", "Top Left"
    ));

    private final NumberSetting duration = register(new NumberSetting(
            "Duration",
            "How long each notification stays visible in seconds (1.0 - 5.0).",
            2.5D, 1.0D, 5.0D, 0.25D
    ));

    private final NumberSetting maxToasts = register(new NumberSetting(
            "Max Toasts",
            "Maximum simultaneous notifications stacked on screen (2 - 6).",
            4.0D, 2.0D, 6.0D, 1.0D
    ));

    private final BooleanSetting progressBar = register(new BooleanSetting(
            "Progress Bar",
            "Renders a 2px rounded shrinking timer bar along the bottom edge of each toast.",
            true
    ));

    private final BooleanSetting moduleToggles = register(new BooleanSetting(
            "Module Toggles",
            "Dispatch toast alerts whenever a module is turned ON or OFF.",
            true
    ));

    private final BooleanSetting totemPops = register(new BooleanSetting(
            "Totem Pops",
            "Dispatch gold shield alerts with pop count when you or a nearby player pops a totem.",
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

    public NotificationsModule() {
        super("Notifications", "Shows sleek toast alerts for toggles and combat events",
                "!", 0.985D, 0.935D, 217.0D, true);
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
        NotificationsModule mod = instance;
        if (mod == null || !mod.isEnabled() || !Boolean.TRUE.equals(mod.moduleToggles.getValue())) return;
        mod.push(moduleName, enabled ? "Enabled" : "Disabled", enabled ? TYPE_ENABLED : TYPE_DISABLED);
    }

    public static void notifyTotemPop(String playerName, int popCount) {
        NotificationsModule mod = instance;
        if (mod == null || !mod.isEnabled() || !Boolean.TRUE.equals(mod.totemPops.getValue())) return;
        mod.push(playerName, "Popped Totem (" + popCount + ")", TYPE_TOTEM);
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
        slot.titleComp = Component.literal(safeTitle.length() > 16 ? safeTitle.substring(0, 16) : safeTitle)
                .withStyle(HudManager.getBoldStyle());
        slot.msgComp = Component.literal(safeMsg.length() > 22 ? safeMsg.substring(0, 22) : safeMsg)
                .withStyle(HudManager.getRegularStyle());
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
        int cardW = 145;
        int cardH = 34;
        int gap = 5;

        int boxW = Math.round(cardW * sc);
        int boxH = Math.round(cardH * sc);
        boolean alignRight = getXRatio() >= 0.5F;
        boolean stackUp = getYRatio() >= 0.5F;

        int anchorX = Math.round(getXRatio() * screenW);
        int baseX = alignRight ? (anchorX - boxW) : anchorX;
        int baseY = Math.round(getYRatio() * screenH) - (stackUp ? boxH : 0);
        baseX = Mth.clamp(baseX, 2, Math.max(2, screenW - boxW - 2));
        baseY = Mth.clamp(baseY, 2, Math.max(2, screenH - boxH - 2));

        this.lastBoxX = baseX;
        this.lastBoxY = baseY;
        this.lastBoxW = boxW;
        this.lastBoxH = boxH;

        float lifeSec = Mth.clamp(duration.getValue().floatValue(), 1.0F, 5.0F);
        int maxVisible = Mth.clamp(maxToasts.getValue().intValue(), 2, 6);
        boolean showBar = Boolean.TRUE.equals(progressBar.getValue());
        int cardBgBase = (getBackgroundAlphaByte() << 24) | 0x111318;

        int visibleIdx = 0;
        for (int i = 0; i < MAX_POOL; i++) {
            Toast t = pool[i];
            if (!t.active) continue;

            t.ageSec += dt;
            if (t.ageSec >= lifeSec + 0.35F) {
                t.active = false;
                continue;
            }
            if (visibleIdx >= maxVisible) {
                continue;
            }

            boolean exiting = t.ageSec >= lifeSec;
            t.slideAnim = AnimationUtil.lerp(t.slideAnim, exiting ? 0.0F : 1.0F, exiting ? 11.0F : 14.0F, dt);

            float targetY = (stackUp ? -1.0F : 1.0F) * visibleIdx * (cardH + gap);
            if (!t.offsetInit) {
                t.currentOffsetY = targetY;
                t.offsetInit = true;
            } else {
                t.currentOffsetY = AnimationUtil.lerp(t.currentOffsetY, targetY, 13.0F, dt);
            }
            visibleIdx++;

            float ease = AnimationUtil.easeOutCubic(t.slideAnim);
            if (ease <= 0.01F) continue;

            float slidePx = (1.0F - ease) * (cardW + 24.0F);
            int drawX = alignRight ? Math.round(slidePx) : -Math.round(slidePx);
            int drawY = Math.round(t.currentOffsetY);

            int badgeCol;
            Component badgeIcon;
            if (t.type == TYPE_DISABLED) {
                badgeCol = 0xFFFF5C77;
                badgeIcon = HudManager.getIconCross();
            } else if (t.type == TYPE_TOTEM) {
                badgeCol = 0xFFFFC94A;
                badgeIcon = HudManager.getIconShield();
            } else if (t.type == TYPE_FRIEND) {
                badgeCol = 0xFF34D399;
                badgeIcon = HudManager.getIconStar();
            } else {
                badgeCol = AnimationUtil.ACCENT_AQUA_CYAN;
                badgeIcon = HudManager.getIconCheck();
            }

            float remaining = Mth.clamp(1.0F - (t.ageSec / lifeSec), 0.0F, 1.0F);

            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(baseX, baseY);
            guiGraphics.pose().scale(sc, sc);

            HudManager.drawRoundedRect(guiGraphics, drawX, drawY, cardW, cardH, 8,
                    AnimationUtil.withAlpha(cardBgBase, ease));

            int badgeSize = 20;
            int bx = drawX + 7;
            int by = drawY + 6;
            HudManager.drawRoundedRect(guiGraphics, bx, by, badgeSize, badgeSize, 6,
                    AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(badgeCol, 0x38), ease));
            int iconCol = AnimationUtil.withAlpha(badgeCol, ease);
            if ((iconCol >>> 24) > 8) {
                int iw = font.width(badgeIcon);
                guiGraphics.drawString(font, badgeIcon, bx + (badgeSize - iw) / 2, by + 6, iconCol, false);
            }

            int textX = bx + badgeSize + 7;
            int titleCol = AnimationUtil.withAlpha(0xFFFFFFFF, ease);
            int msgCol = AnimationUtil.withAlpha(0xFF9CA3AF, ease);
            if ((titleCol >>> 24) > 8) {
                guiGraphics.drawString(font, t.titleComp, textX, drawY + 7, titleCol, false);
                guiGraphics.drawString(font, t.msgComp, textX, drawY + 18, msgCol, false);
            }

            if (showBar) {
                int barMargin = 8;
                int barMaxW = cardW - barMargin * 2;
                int barFillW = Math.round(barMaxW * remaining);
                HudManager.drawRoundedRect(guiGraphics, drawX + barMargin, drawY + cardH - 3, barMaxW, 2, 1,
                        AnimationUtil.withAlpha(0x33FFFFFF, ease));
                if (barFillW > 1) {
                    HudManager.drawRoundedRect(guiGraphics, drawX + barMargin, drawY + cardH - 3, barFillW, 2, 1,
                            AnimationUtil.withAlpha(badgeCol, ease));
                }
            }

            guiGraphics.pose().popMatrix();
        }

        if (visibleIdx == 0 && editorMode) {
            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(baseX, baseY);
            guiGraphics.pose().scale(sc, sc);
            HudManager.drawRoundedRect(guiGraphics, 0, 0, cardW, cardH, 8, cardBgBase);
            HudManager.drawRoundedRect(guiGraphics, 7, 6, 20, 20, 6, 0x3800E5FF);
            int iw = font.width(HudManager.getIconCheck());
            guiGraphics.drawString(font, HudManager.getIconCheck(), 7 + (20 - iw) / 2, 12, 0xFF00E5FF, false);
            guiGraphics.drawString(font, Component.literal("Notifications").withStyle(HudManager.getBoldStyle()), 34, 7, 0xFFFFFFFF, false);
            guiGraphics.drawString(font, Component.literal("Preview Alert").withStyle(HudManager.getRegularStyle()), 34, 18, 0xFF9CA3AF, false);
            if (showBar) {
                HudManager.drawRoundedRect(guiGraphics, 8, cardH - 3, cardW - 16, 2, 1, 0xFF00E5FF);
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
            case "Bottom Left" -> setPositionRatio(0.015F, 0.935F);
            case "Top Left" -> setPositionRatio(0.015F, 0.055F);
            default -> setPositionRatio(0.985F, 0.935F);
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
                    push(p.getName().getString(), "Friend in range", TYPE_FRIEND);
                }
            }
        }
        for (UUID oldId : visibleFriends) {
            if (!scratchFriends.contains(oldId)) {
                String name = FriendManager.getFriendsMap().getOrDefault(oldId, "Friend");
                push(name, "Friend left range", TYPE_DISABLED);
            }
        }
        visibleFriends.clear();
        visibleFriends.addAll(scratchFriends);
    }
}
