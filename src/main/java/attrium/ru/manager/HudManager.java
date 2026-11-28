package attrium.ru.manager;

import attrium.ru.auth.AuthService;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.impl.visual.ArrayListModule;
import attrium.ru.api.module.impl.visual.HudModule;
import attrium.ru.api.module.impl.visual.KeybindsModule;
import attrium.ru.api.module.impl.visual.NotificationsModule;
import attrium.ru.api.module.impl.visual.PlayerRadarModule;
import attrium.ru.api.module.impl.visual.TargetHUDModule;
import attrium.ru.screens.GlassClickGuiScreen;
import attrium.ru.utils.render.AnimationUtil;
import attrium.ru.utils.render.RenderSafeGuard;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central orchestrator for Attrium's modular Glassmorphic HUD Suite (Minecraft 1.21.11).
 *
 * Orchestrates all registered HudModule subclasses (ArrayListModule, NotificationsModule,
 * TargetHUDModule, KeybindsModule), manages live totem pop tracking, shared zero-allocation
 * AA rounded-rectangle & player avatar rendering, and the interactive drag-and-drop Layout Editor
 * with magnetic center/edge snap guides directly mutating each HudModule's xRatio/yRatio settings.
 */
public final class HudManager {
    public static final int TYPE_ENABLED = NotificationsModule.TYPE_ENABLED;
    public static final int TYPE_DISABLED = NotificationsModule.TYPE_DISABLED;
    public static final int TYPE_ALERT = NotificationsModule.TYPE_TOTEM;

    // Precomputed AA rounded-corner lookup tables (radius 0..16, zero per-frame sqrt)
    private static final int MAX_RADIUS = 16;
    private static final int[][] AA_SOLID_INSET = new int[MAX_RADIUS + 1][];
    private static final float[][] AA_EDGE_COVERAGE = new float[MAX_RADIUS + 1][];

    static {
        for (int r = 0; r <= MAX_RADIUS; r++) {
            AA_SOLID_INSET[r] = new int[Math.max(1, r)];
            AA_EDGE_COVERAGE[r] = new float[Math.max(1, r)];
            for (int dy = 0; dy < r; dy++) {
                double yCenter = (r - dy) - 0.5D;
                double exactInset = r - Math.sqrt(Math.max(0.0D, (double) r * r - yCenter * yCenter));
                int solidInset = (int) Math.ceil(exactInset);
                float edgeCov = Mth.clamp((float) (solidInset - exactInset), 0.12F, 0.95F);
                AA_SOLID_INSET[r][dy] = Math.max(0, solidInset);
                AA_EDGE_COVERAGE[r][dy] = edgeCov;
            }
        }
    }

    // Live totem pop counts per player UUID
    private static final Map<UUID, Integer> TOTEM_POPS = new ConcurrentHashMap<>();

    // Cached module lists (zero per-frame allocations)
    private static List<Module> cachedAllModules = null;
    private static final List<HudModule> CACHED_HUD_MODULES = new ArrayList<>(8);
    private static boolean hudModuleCacheReady = false;

    // Shared Nunito styles & small icon components
    private static Style hudRegularStyle = Style.EMPTY;
    private static Style hudBoldStyle = Style.EMPTY;
    private static boolean stylesReady = false;
    private static Component iconCheckComp;
    private static Component iconCrossComp;
    private static Component iconShieldComp;
    private static Component iconStarComp;
    private static Component layoutBannerComp;
    private static Component layoutDoneComp;
    private static Component layoutResetComp;

    // Cached PlayerFaceRenderer reflection (1.21.11 safe)
    private static boolean faceReflectionInit = false;
    private static Method cachedFaceDrawMethod = null;
    private static Method cachedGetSkinMethod = null;

    // Frame timing & Layout Editor drag state
    private static long lastFrameNanos = 0L;
    private static float animClockSeconds = 0.0F;
    private static boolean layoutEditorActive = false;
    private static HudModule draggingModule = null;
    private static HudModule selectedModule = null;
    private static float dragGrabOffsetX = 0.0F;
    private static float dragGrabOffsetY = 0.0F;
    private static boolean snapGuideXActive = false;
    private static boolean snapGuideYActive = false;
    private static int snapGuideXPos = 0;
    private static int snapGuideYPos = 0;
    private static float lastDtSeconds = 0.016F;
    private static boolean wasCustomGuiKeyDown = false;
    private static boolean wasFloatingBtnMouseDown = false;
    private static boolean draggingFloatingBtn = false;
    private static long floatingBtnDownMs = 0L;
    private static double floatingBtnDownX = 0.0D;
    private static double floatingBtnDownY = 0.0D;
    private static Component floatingBtnLabelComp = null;

    private HudManager() {
    }

    // =========================================================================
    // Public Style, Icon & Module Accessors
    // =========================================================================
    public static Style getRegularStyle() {
        ensureStyles();
        return hudRegularStyle;
    }

    public static Style getBoldStyle() {
        ensureStyles();
        return hudBoldStyle;
    }

    public static Component getIconCheck() { ensureStyles(); return iconCheckComp; }
    public static Component getIconCross() { ensureStyles(); return iconCrossComp; }
    public static Component getIconShield() { ensureStyles(); return iconShieldComp; }
    public static Component getIconStar() { ensureStyles(); return iconStarComp; }

    public static List<Module> getAllClientModules() {
        ensureHudModuleCache();
        return cachedAllModules;
    }

    public static List<HudModule> getHudModules() {
        ensureHudModuleCache();
        return CACHED_HUD_MODULES;
    }

    public static boolean isLegacyHudModuleName(String name) {
        if (name == null) return false;
        String low = name.trim().toLowerCase(Locale.ROOT);
        return low.equals("hud") || low.equals("watermark");
    }

    // =========================================================================
    // Notifications & Totem Pop Tracking
    // =========================================================================
    public static void pushNotification(String title, String message, int type) {
        if (type == TYPE_ENABLED || type == TYPE_DISABLED) {
            if ("Added Friend".equals(message) || "Removed Friend".equals(message)) {
                NotificationsModule.notifyFriendEvent(title, message, type == TYPE_ENABLED);
                return;
            }
        }
        NotificationsModule.pushCustom(title, message, type);
    }

    public static void recordTotemPop(UUID uuid, String playerName) {
        TotemPopManager.recordPop(uuid, playerName);
    }

    public static int getTotemPopCount(UUID uuid) {
        return TotemPopManager.getPopCount(uuid);
    }

    public static void clearTotemPop(UUID uuid) {
        TotemPopManager.clearPop(uuid);
    }

    // =========================================================================
    // Layout Editor State Control
    // =========================================================================
    public static boolean isLayoutEditorActive() {
        return layoutEditorActive;
    }

    public static void setLayoutEditorActive(boolean active, HudModule focusModule) {
        layoutEditorActive = active;
        selectedModule = active ? focusModule : null;
        draggingModule = null;
        snapGuideXActive = false;
        snapGuideYActive = false;
    }

    public static void resetAllPositions() {
        ensureHudModuleCache();
        for (int i = 0; i < CACHED_HUD_MODULES.size(); i++) {
            CACHED_HUD_MODULES.get(i).resetPosition();
        }
    }

    // =========================================================================
    // Main In-Game HUD Render Hook (Called from InGameHudMixin)
    // =========================================================================
    public static void renderInGameHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (!AuthService.isAuthorized()) { return; }
        Minecraft mc = Minecraft.getInstance();
        TotemPopManager.tickCleanup(mc);
        if (mc == null || mc.player == null || mc.level == null || mc.font == null || guiGraphics == null) {
            return;
        }
        if (mc.options != null && mc.options.hideGui) {
            return;
        }
        if (layoutEditorActive && mc.screen instanceof GlassClickGuiScreen) {
            return;
        }
        GlassClickGuiScreen.pollGlobalKeybinds();
        boolean previewMode = (mc.screen instanceof GlassClickGuiScreen gui) && gui.isHudTabActive();

        float pt = (deltaTracker != null) ? deltaTracker.getGameTimeDeltaPartialTick(false) : 1.0F;
        try { attrium.ru.api.module.impl.visual.BlockOverlayModule.renderOverlay(mc, guiGraphics, pt); } catch (Throwable t) { RenderSafeGuard.onRenderFailure(attrium.ru.api.module.impl.visual.BlockOverlayModule.getInstance(), t); }
        try { attrium.ru.api.module.impl.visual.TrailsModule.renderTrails(mc, guiGraphics, pt); } catch (Throwable t) { RenderSafeGuard.onRenderFailure(attrium.ru.api.module.impl.visual.TrailsModule.getInstance(), t); }
        renderAllWidgets(mc, guiGraphics, previewMode, false);
        ClientThemeManager.tick(lastDtSeconds, mc.screen instanceof GlassClickGuiScreen);
        int screenW = guiGraphics.guiWidth();
        int screenH = guiGraphics.guiHeight();
        if (screenW > 0 && screenH > 0) {
            renderFloatingTouchButtonAndPollKeybind(mc, guiGraphics, screenW, screenH);
        }
    }

    public static void renderAllWidgets(Minecraft mc, GuiGraphics guiGraphics, boolean previewMode, boolean editorMode) {
        if (mc == null || mc.player == null || mc.level == null || mc.font == null || guiGraphics == null) {
            return;
        }
        ensureStyles();
        ensureHudModuleCache();

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L
                ? 0.016F
                : AnimationUtil.clampDelta((now - lastFrameNanos) * 1.0e-9F);
        lastFrameNanos = now;
        lastDtSeconds = dt;
        animClockSeconds += dt;
        if (animClockSeconds > 86400.0F) {
            animClockSeconds -= 86400.0F;
        }

        int screenW = guiGraphics.guiWidth();
        int screenH = guiGraphics.guiHeight();
        if (screenW <= 0 || screenH <= 0) {
            return;
        }

        // Always tick ArrayList module toggle watcher so Notifications fire even if ArrayList is disabled
        ArrayListModule al = ArrayListModule.getInstance();
        if (al != null) {
            try {
                al.tickModuleStates(mc.font, dt);
            } catch (Throwable t) {
                RenderSafeGuard.onRenderFailure(al, t);
            }
        }

        for (int i = 0, size = CACHED_HUD_MODULES.size(); i < size; i++) {
            HudModule mod = CACHED_HUD_MODULES.get(i);
            if (mod != null && mod.isEnabled()) {
                try {
                    mod.renderHud(mc, guiGraphics, mc.font, screenW, screenH, dt, animClockSeconds, previewMode, editorMode);
                    RenderSafeGuard.onRenderSuccess(mod);
                } catch (Throwable t) {
                    RenderSafeGuard.onRenderFailure(mod, t);
                }
            }
        }
    }

    // =========================================================================
    // Interactive Drag-and-Drop Layout Editor with Magnetic Snap Guides
    // =========================================================================
    public static void renderLayoutEditor(Minecraft mc, GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        ensureStyles();
        ensureHudModuleCache();

        int screenW = guiGraphics.guiWidth();
        int screenH = guiGraphics.guiHeight();

        guiGraphics.fill(0, 0, screenW, screenH, 0x55080A10);
        renderAllWidgets(mc, guiGraphics, true, true);

        if (snapGuideXActive) {
            guiGraphics.fill(snapGuideXPos, 0, snapGuideXPos + 1, screenH, 0x9900E5FF);
        }
        if (snapGuideYActive) {
            guiGraphics.fill(0, snapGuideYPos, screenW, snapGuideYPos + 1, 0x9900E5FF);
        }

        for (int i = 0; i < CACHED_HUD_MODULES.size(); i++) {
            HudModule m = CACHED_HUD_MODULES.get(i);
            if (!m.isEnabled() || m.lastBoxW <= 0 || m.lastBoxH <= 0) continue;
            boolean hovered = m.isHovered(mouseX, mouseY);
            boolean active = (draggingModule == m || selectedModule == m);
            if (hovered || active) {
                int tint = active ? 0x3300E5FF : 0x1AFFFFFF;
                drawRoundedRect(guiGraphics, m.lastBoxX - 3, m.lastBoxY - 3, m.lastBoxW + 6, m.lastBoxH + 6, 8, tint);
            }
        }

        // Floating top bar
        int barW = 248;
        int barH = 26;
        int barX = (screenW - barW) / 2;
        int barY = 10;
        drawRoundedRect(guiGraphics, barX, barY, barW, barH, 10, 0xE6111318);
        guiGraphics.drawString(font, layoutBannerComp, barX + 12, barY + 9, 0xFFFFFFFF, false);

        int doneW = 42;
        int resetW = 46;
        int btnH = 18;
        int btnY = barY + 4;
        int doneX = barX + barW - doneW - 6;
        int resetX = doneX - resetW - 5;

        boolean hoverReset = mouseX >= resetX && mouseX <= resetX + resetW && mouseY >= btnY && mouseY <= btnY + btnH;
        boolean hoverDone = mouseX >= doneX && mouseX <= doneX + doneW && mouseY >= btnY && mouseY <= btnY + btnH;

        drawRoundedRect(guiGraphics, resetX, btnY, resetW, btnH, 6, hoverReset ? 0x44FFFFFF : 0x26FFFFFF);
        int rw = font.width(layoutResetComp);
        guiGraphics.drawString(font, layoutResetComp, resetX + (resetW - rw) / 2, btnY + 5, 0xFFE5E7EB, false);

        drawRoundedRect(guiGraphics, doneX, btnY, doneW, btnH, 6, hoverDone ? 0xFF38BDF8 : 0xFF00E5FF);
        int dw = font.width(layoutDoneComp);
        guiGraphics.drawString(font, layoutDoneComp, doneX + (doneW - dw) / 2, btnY + 5, 0xFF0B0D10, false);
    }

    public static boolean handleLayoutEditorClick(int screenW, int screenH, double mouseX, double mouseY) {
        int barW = 248;
        int barX = (screenW - barW) / 2;
        int barY = 10;
        int doneW = 42;
        int resetW = 46;
        int btnH = 18;
        int btnY = barY + 4;
        int doneX = barX + barW - doneW - 6;
        int resetX = doneX - resetW - 5;

        if (mouseX >= doneX && mouseX <= doneX + doneW && mouseY >= btnY && mouseY <= btnY + btnH) {
            setLayoutEditorActive(false, null);
            return true;
        }
        if (mouseX >= resetX && mouseX <= resetX + resetW && mouseY >= btnY && mouseY <= btnY + btnH) {
            resetAllPositions();
            return true;
        }

        for (int i = CACHED_HUD_MODULES.size() - 1; i >= 0; i--) {
            HudModule m = CACHED_HUD_MODULES.get(i);
            if (!m.isEnabled()) continue;
            if (m.isHovered(mouseX, mouseY)) {
                draggingModule = m;
                selectedModule = m;
                dragGrabOffsetX = (float) mouseX - m.lastBoxX;
                dragGrabOffsetY = (float) mouseY - m.lastBoxY;
                return false;
            }
        }
        selectedModule = null;
        return false;
    }

    public static void handleLayoutEditorDrag(int screenW, int screenH, double mouseX, double mouseY) {
        if (draggingModule == null || screenW <= 0 || screenH <= 0) return;

        HudModule m = draggingModule;
        float rawX = (float) mouseX - dragGrabOffsetX;
        float rawY = (float) mouseY - dragGrabOffsetY;
        int bw = Math.max(24, m.lastBoxW);
        int bh = Math.max(16, m.lastBoxH);

        rawX = Mth.clamp(rawX, 2.0F, Math.max(2.0F, screenW - bw - 2.0F));
        rawY = Mth.clamp(rawY, 2.0F, Math.max(2.0F, screenH - bh - 2.0F));

        snapGuideXActive = false;
        snapGuideYActive = false;
        float snapDist = 6.0F;

        float centerX = rawX + bw * 0.5F;
        float screenMidX = screenW * 0.5F;
        if (Math.abs(centerX - screenMidX) <= snapDist) {
            rawX = screenMidX - bw * 0.5F;
            snapGuideXActive = true;
            snapGuideXPos = Math.round(screenMidX);
        } else if (Math.abs(rawX - 8.0F) <= snapDist) {
            rawX = 8.0F;
            snapGuideXActive = true;
            snapGuideXPos = 8;
        } else if (Math.abs((rawX + bw) - (screenW - 8.0F)) <= snapDist) {
            rawX = screenW - 8.0F - bw;
            snapGuideXActive = true;
            snapGuideXPos = screenW - 8;
        }

        float centerY = rawY + bh * 0.5F;
        float screenMidY = screenH * 0.5F;
        if (Math.abs(centerY - screenMidY) <= snapDist) {
            rawY = screenMidY - bh * 0.5F;
            snapGuideYActive = true;
            snapGuideYPos = Math.round(screenMidY);
        } else if (Math.abs(rawY - 8.0F) <= snapDist) {
            rawY = 8.0F;
            snapGuideYActive = true;
            snapGuideYPos = 8;
        } else if (Math.abs((rawY + bh) - (screenH - 8.0F)) <= snapDist) {
            rawY = screenH - 8.0F - bh;
            snapGuideYActive = true;
            snapGuideYPos = screenH - 8;
        }

        boolean rightAnchor = m.usesRightAnchorWhenOnRightHalf() && (rawX + bw * 0.5F >= screenW * 0.5F);
        boolean bottomAnchor = m.usesBottomAnchorWhenOnBottomHalf() && (rawY + bh * 0.5F >= screenH * 0.5F);

        float nx = Mth.clamp((rightAnchor ? (rawX + bw) : rawX) / (float) screenW, 0.0F, 1.0F);
        float ny = Mth.clamp((bottomAnchor ? (rawY + bh) : rawY) / (float) screenH, 0.0F, 1.0F);
        m.setPositionRatio(nx, ny);
    }

    public static boolean handleLayoutEditorRelease() {
        boolean wasDragging = draggingModule != null;
        draggingModule = null;
        snapGuideXActive = false;
        snapGuideYActive = false;
        return wasDragging;
    }

    // =========================================================================
    // Shared Rendering & Formatting Helpers (Zero Allocations)
    // =========================================================================
    public static void drawPlayerAvatar(GuiGraphics guiGraphics, Minecraft mc, Player player, int x, int y, int size) {
        try {
            if (!faceReflectionInit) {
                faceReflectionInit = true;
                for (Method m : PlayerFaceRenderer.class.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 5
                            && params[0] == GuiGraphics.class
                            && params[2] == int.class
                            && params[3] == int.class
                            && params[4] == int.class) {
                        m.setAccessible(true);
                        cachedFaceDrawMethod = m;
                        break;
                    }
                }
                try {
                    cachedGetSkinMethod = AbstractClientPlayer.class.getMethod("getSkin");
                } catch (Throwable ignored) {
                }
            }
            if (cachedFaceDrawMethod != null) {
                Object skinObj = null;
                if (player instanceof AbstractClientPlayer acp && cachedGetSkinMethod != null) {
                    skinObj = cachedGetSkinMethod.invoke(acp);
                }
                if (skinObj == null && mc.getConnection() != null) {
                    PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
                    if (info != null) {
                        skinObj = info.getSkin();
                    }
                }
                if (skinObj != null && cachedFaceDrawMethod.getParameterTypes()[1].isAssignableFrom(skinObj.getClass())) {
                    cachedFaceDrawMethod.invoke(null, guiGraphics, skinObj, x, y, size);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        drawRoundedRect(guiGraphics, x, y, size, size, 5, 0xFF1F2937);
        guiGraphics.fill(x + 7, y + 10, x + 11, y + 14, 0xFF00E5FF);
        guiGraphics.fill(x + size - 11, y + 10, x + size - 7, y + 14, 0xFF00E5FF);
        guiGraphics.fill(x + 9, y + size - 8, x + size - 9, y + size - 6, 0xCCFFFFFF);
    }

    public static void drawGlassCard(GuiGraphics g, int x, int y, int w, int h, int radius, int bgColor, float alpha) {
        if (g == null || w <= 0 || h <= 0 || alpha <= 0.01F) {
            return;
        }
        int r = Mth.clamp(radius, 0, MAX_RADIUS);
        drawRoundedRect(g, x - 1, y - 1, w + 2, h + 2, Math.min(MAX_RADIUS, r + 1),
                AnimationUtil.withAlpha(0x30FFFFFF, alpha));
        drawRoundedRect(g, x, y, w, h, r, AnimationUtil.withAlpha(bgColor, alpha));
        if (h >= 8 && w >= 6) {
            drawRoundedRect(g, x + 1, y + 1, w - 2, h / 2, Math.max(0, r - 1),
                    AnimationUtil.withAlpha(0x12FFFFFF, alpha));
        }
    }

    public static void drawRoundedRect(GuiGraphics guiGraphics, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        int r = Mth.clamp(Math.min(radius, Math.min(w / 2, h / 2)), 0, MAX_RADIUS);
        if (r <= 1) {
            guiGraphics.fill(x, y, x + w, y + h, color);
            return;
        }
        if (h > 2 * r) {
            guiGraphics.fill(x, y + r, x + w, y + h - r, color);
        }
        int[] insets = AA_SOLID_INSET[r];
        float[] covs = AA_EDGE_COVERAGE[r];
        for (int dy = 0; dy < r; dy++) {
            int inset = insets[dy];
            int topY = y + dy;
            int botY = y + h - 1 - dy;
            int lx = x + inset;
            int rx = x + w - inset;
            if (rx > lx) {
                guiGraphics.fill(lx, topY, rx, topY + 1, color);
                if (botY != topY) {
                    guiGraphics.fill(lx, botY, rx, botY + 1, color);
                }
            }
            if (inset > 0) {
                int aaCol = AnimationUtil.withAlpha(color, covs[dy]);
                guiGraphics.fill(lx - 1, topY, lx, topY + 1, aaCol);
                guiGraphics.fill(rx, topY, rx + 1, topY + 1, aaCol);
                if (botY != topY) {
                    guiGraphics.fill(lx - 1, botY, lx, botY + 1, aaCol);
                    guiGraphics.fill(rx, botY, rx + 1, botY + 1, aaCol);
                }
            }
        }
    }

    public static void drawHorizontalGradientBar(GuiGraphics guiGraphics, int x, int y, int w, int h,
                                                 int radius, int leftColor, int rightColor) {
        if (w <= 0 || h <= 0) return;
        int slices = Math.min(w, 12);
        int sliceW = Math.max(1, w / slices);
        for (int s = 0; s < slices; s++) {
            int sx1 = x + s * sliceW;
            int sx2 = (s == slices - 1) ? (x + w) : Math.min(x + w, sx1 + sliceW);
            float t = slices <= 1 ? 0.0F : (float) s / (float) (slices - 1);
            int col = AnimationUtil.lerpColor(leftColor, rightColor, t);
            int topCut = (s == 0 || s == slices - 1) ? Math.min(1, radius) : 0;
            guiGraphics.fill(sx1, y + topCut, sx2, y + h - topCut, col);
        }
    }

    public static String formatCompactKeyLabel(int keyCode) {
        if (keyCode < -1) {
            int btn = keyCode <= -100 ? (-100 - keyCode) : (-10 - keyCode);
            return "M" + (btn + 1);
        }
        if (keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z) {
            return String.valueOf((char) ('A' + (keyCode - GLFW.GLFW_KEY_A)));
        }
        if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
            return String.valueOf((char) ('0' + (keyCode - GLFW.GLFW_KEY_0)));
        }
        return switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> "SHF";
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> "CTL";
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> "ALT";
            case GLFW.GLFW_KEY_SPACE -> "SPC";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CAP";
            default -> {
                String n = GLFW.glfwGetKeyName(keyCode, 0);
                if (n != null && !n.isEmpty()) {
                    String up = n.toUpperCase(Locale.ROOT);
                    yield up.length() > 3 ? up.substring(0, 3) : up;
                }
                yield "K" + keyCode;
            }
        };
    }

    private static void ensureHudModuleCache() {
        if (hudModuleCacheReady && !CACHED_HUD_MODULES.isEmpty()) return;
        List<Module> all = GlassClickGuiScreen.getInstance().getDiscoveredModulesSnapshot();
        if (all == null || all.isEmpty()) return;
        cachedAllModules = all;
        CACHED_HUD_MODULES.clear();
        for (int i = 0; i < all.size(); i++) {
            Module m = all.get(i);
            if (m instanceof HudModule hm) {
                CACHED_HUD_MODULES.add(hm);
            }
        }
        // Fallback if ModuleManager hasn't registered them yet
        if (CACHED_HUD_MODULES.isEmpty()) {
            if (ArrayListModule.getInstance() != null) CACHED_HUD_MODULES.add(ArrayListModule.getInstance());
            if (NotificationsModule.getInstance() != null) CACHED_HUD_MODULES.add(NotificationsModule.getInstance());
            if (TargetHUDModule.getInstance() != null) CACHED_HUD_MODULES.add(TargetHUDModule.getInstance());
            if (KeybindsModule.getInstance() != null) CACHED_HUD_MODULES.add(KeybindsModule.getInstance());
            if (PlayerRadarModule.getInstance() != null) CACHED_HUD_MODULES.add(PlayerRadarModule.getInstance());
        }
        hudModuleCacheReady = !CACHED_HUD_MODULES.isEmpty();
    }

    private static void ensureStyles() {
        if (stylesReady) return;
        stylesReady = true;
        Style reg = GlassClickGuiScreen.getNunitoRegularStyle();
        Style bold = GlassClickGuiScreen.getNunitoBoldStyle();
        hudRegularStyle = reg != null ? reg.withBold(false) : Style.EMPTY.withBold(false);
        hudBoldStyle = bold != null ? bold.withBold(false) : hudRegularStyle;

        iconCheckComp = Component.literal("+").withStyle(hudBoldStyle);
        iconCrossComp = Component.literal("x").withStyle(hudBoldStyle);
        iconShieldComp = Component.literal("T").withStyle(hudBoldStyle);
        iconStarComp = Component.literal("*").withStyle(hudBoldStyle);
        layoutBannerComp = Component.literal("HUD Layout Editor").withStyle(hudBoldStyle);
        layoutDoneComp = Component.literal("Done").withStyle(hudBoldStyle);
        layoutResetComp = Component.literal("Reset").withStyle(hudRegularStyle);
    }

    // =========================================================================
    // Floating Touch Button ("A" Pill) & Custom ClickGUI Keybind Polling
    // =========================================================================
    private static void renderFloatingTouchButtonAndPollKeybind(
            Minecraft mc, GuiGraphics guiGraphics, int screenW, int screenH) {
        if (mc == null || mc.getWindow() == null) return;
        long winHandle = mc.getWindow().handle();
        if (winHandle == 0L) return;

        // ClickGUI keybind polling & instant GLFW callbacks are handled centrally in GlassClickGuiScreen.

        // 2. Render & interact with draggable Floating Touch Button ("A" pill)
        if (!ClientThemeManager.isFloatingButtonEnabled() || mc.screen != null) {
            wasFloatingBtnMouseDown = false;
            draggingFloatingBtn = false;
            return;
        }

        int size = Math.round(ClientThemeManager.getFloatingButtonSize());
        int bx = Mth.clamp(Math.round(ClientThemeManager.getFloatingBtnXRatio() * screenW), 4, Math.max(4, screenW - size - 4));
        int by = Mth.clamp(Math.round(ClientThemeManager.getFloatingBtnYRatio() * screenH), 4, Math.max(4, screenH - size - 4));
        int radius = Math.max(6, size / 2);

        int accent = ClientThemeManager.getAccentArgb();
        int glowBg = ClientThemeManager.getAccentWithAlpha(0.24F);
        drawRoundedRect(guiGraphics, bx - 1, by - 1, size + 2, size + 2, radius + 1, glowBg);
        drawRoundedRect(guiGraphics, bx, by, size, size, radius, 0xCC111318);

        if (floatingBtnLabelComp == null) {
            floatingBtnLabelComp = Component.literal("A").withStyle(getBoldStyle());
        }
        int lw = mc.font.width(floatingBtnLabelComp);
        guiGraphics.drawString(mc.font, floatingBtnLabelComp, bx + (size - lw) / 2, by + (size - 8) / 2, accent, false);

        // Touch / mouse tap & drag support when cursor is unlocked (FoldCraftLauncher touch mode)
        double[] mx = new double[1];
        double[] my = new double[1];
        GLFW.glfwGetCursorPos(winHandle, mx, my);
        int fbW = Math.max(1, mc.getWindow().getScreenWidth());
        int fbH = Math.max(1, mc.getWindow().getScreenHeight());
        double guiX = mx[0] * screenW / fbW;
        double guiY = my[0] * screenH / fbH;
        boolean lDown = GLFW.glfwGetMouseButton(winHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean inside = guiX >= bx - 3 && guiX <= bx + size + 3 && guiY >= by - 3 && guiY <= by + size + 3;

        if (lDown && !wasFloatingBtnMouseDown && inside) {
            draggingFloatingBtn = true;
            floatingBtnDownMs = System.currentTimeMillis();
            floatingBtnDownX = guiX;
            floatingBtnDownY = guiY;
        } else if (lDown && draggingFloatingBtn) {
            double distSq = (guiX - floatingBtnDownX) * (guiX - floatingBtnDownX)
                    + (guiY - floatingBtnDownY) * (guiY - floatingBtnDownY);
            if (distSq > 25.0D) {
                ClientThemeManager.setFloatingBtnPosRatio(
                        (float) ((guiX - size * 0.5D) / Math.max(1, screenW)),
                        (float) ((guiY - size * 0.5D) / Math.max(1, screenH))
                );
            }
        } else if (!lDown && draggingFloatingBtn) {
            draggingFloatingBtn = false;
            double distSq = (guiX - floatingBtnDownX) * (guiX - floatingBtnDownX)
                    + (guiY - floatingBtnDownY) * (guiY - floatingBtnDownY);
            if (distSq <= 25.0D && (System.currentTimeMillis() - floatingBtnDownMs) < 450L) {
                GlassClickGuiScreen.openClickGuiFromBind(mc);
            }
        }
        wasFloatingBtnMouseDown = lDown;
    }

}
