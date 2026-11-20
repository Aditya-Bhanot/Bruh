package attrium.ru.manager;

import com.google.gson.JsonObject;
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
import attrium.ru.api.settings.Setting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.screens.GlassClickGuiScreen;
import attrium.ru.utils.render.AnimationUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Attrium Animated Glassmorphic HUD Suite & Layout Manager (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Renders four cohesive, framerate-independent, zero-allocation HUD widgets:
 *   0. ArrayList     — Cascading width-sorted active modules with slide/height animations,
 *                      scissor clipping, mode tags, and flush outer-edge accent bars.
 *   1. Notifications — Animated glassmorphic toast stack (module toggles, totem pops, friend changes)
 *                      with easeOutCubic slide-in, status icon badge, and 2.5s bottom progress bar.
 *   2. TargetHUD     — Dynamic combat card with 2D skin head + hurt flash, cached HP/distance strings,
 *                      dual-layer ghost-trail gradient health bar, and pop-in/out scale+alpha animation.
 *   3. Keybinds      — Compact overlay listing all bound modules with keycap badges that smoothly
 *                      glow Aqua-Cyan when active and smoothly lerp container height.
 *
 * Strictly 2D GuiGraphics rendering (never mutates Window/GuiScale) for FoldCraftLauncher stability.
 */
public final class HudManager {
    public static final int WIDGET_ARRAYLIST = 0;
    public static final int WIDGET_NOTIFICATIONS = 1;
    public static final int WIDGET_TARGETHUD = 2;
    public static final int WIDGET_KEYBINDS = 3;
    public static final int WIDGET_COUNT = 4;

    public static final int TYPE_ENABLED = 0;
    public static final int TYPE_DISABLED = 1;
    public static final int TYPE_ALERT = 2;

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

    // =========================================================================
    // Widget Configuration State
    // =========================================================================
    public static final class HudWidgetConfig {
        public final int index;
        public final String id;
        public final String title;
        public final String subtitle;
        public final String iconGlyph;

        public boolean enabled;
        public float normX;
        public float normY;
        public int accentTheme;   // 0=Aqua Cyan, 1=Pure White, 2=Cyber Blue, 3=Dynamic Gradient
        public float bgOpacity;   // 0.0f .. 1.0f
        public float scale;       // 0.75f .. 1.50f

        // ClickGUI HUD tab card UI animation states
        public boolean expanded = false;
        public float toggleAnim;
        public float expandAnim = 0.0F;

        // Cached last rendered bounding box (unscaled screen pixels) for Layout Editor drag hit-testing
        public int lastBoxX;
        public int lastBoxY;
        public int lastBoxW;
        public int lastBoxH;

        HudWidgetConfig(int index, String id, String title, String subtitle, String iconGlyph,
                        boolean enabled, float normX, float normY, float bgOpacity) {
            this.index = index;
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.iconGlyph = iconGlyph;
            this.enabled = enabled;
            this.normX = normX;
            this.normY = normY;
            this.accentTheme = 0;
            this.bgOpacity = bgOpacity;
            this.scale = 1.0F;
            this.toggleAnim = enabled ? 1.0F : 0.0F;
        }
    }

    private static final HudWidgetConfig[] WIDGETS = new HudWidgetConfig[]{
            new HudWidgetConfig(WIDGET_ARRAYLIST, "arraylist", "ArrayList",
                    "Cascading active modules with live mode tags", "A", true, 0.985F, 0.025F, 0.67F),
            new HudWidgetConfig(WIDGET_NOTIFICATIONS, "notifications", "Notifications",
                    "Animated toast stack for toggles, totems & friends", "!", true, 0.985F, 0.935F, 0.85F),
            new HudWidgetConfig(WIDGET_TARGETHUD, "targethud", "TargetHUD",
                    "Combat card with avatar, ghost HP bar & distance", "T", true, 0.560F, 0.620F, 0.85F),
            new HudWidgetConfig(WIDGET_KEYBINDS, "keybinds", "Keybinds",
                    "Active bound modules with glowing keycap badges", "K", true, 0.015F, 0.080F, 0.80F)
    };

    // =========================================================================
    // Pre-Allocated Zero-GC Pools for All 4 Widgets
    // =========================================================================
    private static final int MAX_ARRAY_ENTRIES = 128;
    private static final class ArrayEntry {
        Module module;
        String name;
        Component nameComp;
        String lastModeTag = "";
        Component modeTagComp;
        ModeSetting primaryModeSetting;
        boolean lastEnabledState = false;
        boolean stateInitialized = false;
        float slideAnim = 0.0F;
        float heightAnim = 0.0F;
        int cachedTotalWidth = 0;
        int cachedNameWidth = 0;
    }

    private static final ArrayEntry[] ARRAY_POOL = new ArrayEntry[MAX_ARRAY_ENTRIES];
    private static final ArrayEntry[] ARRAY_SORTED = new ArrayEntry[MAX_ARRAY_ENTRIES];
    private static int arrayPoolCount = 0;
    private static boolean arrayPoolInitialized = false;

    private static final int MAX_TOASTS = 16;
    private static final float TOAST_LIFETIME_SEC = 2.5F;
    private static final class ToastEntry {
        boolean active = false;
        String title = "";
        String message = "";
        Component titleComp;
        Component msgComp;
        int type = TYPE_ENABLED;
        float ageSeconds = 0.0F;
        float slideProgress = 0.0F;
        float currentOffsetY = 0.0F;
        boolean offsetInitialized = false;
    }

    private static final ToastEntry[] TOAST_POOL = new ToastEntry[MAX_TOASTS];
    static {
        for (int i = 0; i < MAX_TOASTS; i++) {
            TOAST_POOL[i] = new ToastEntry();
        }
    }

    private static final int MAX_KEYBINDS = 64;
    private static final class KeybindEntry {
        Module module;
        String name = "";
        Component nameComp;
        int keyCode = -1;
        String keyLabel = "";
        Component keyLabelComp;
        float activeAnim = 0.0F;
        int cachedKeyWidth = 18;
    }

    private static final KeybindEntry[] KEYBIND_POOL = new KeybindEntry[MAX_KEYBINDS];
    private static int keybindActiveCount = 0;
    private static float keybindContainerHeight = 26.0F;
    private static Component keybindHeaderComp = null;

    static {
        for (int i = 0; i < MAX_KEYBINDS; i++) {
            KEYBIND_POOL[i] = new KeybindEntry();
        }
    }

    // TargetHUD persistent state (zero per-frame allocations)
    private static Player trackedTarget = null;
    private static long lastTargetSeenMs = 0L;
    private static float targetPopAnim = 0.0F;
    private static float targetAnimatedHealth = 20.0F;
    private static float targetEasingHealth = 20.0F;
    private static int lastTrackedTargetId = -1;
    private static String cachedTargetName = "";
    private static Component cachedTargetNameComp = null;
    private static int cachedTargetHpTenths = -1;
    private static Component cachedTargetHpComp = null;
    private static int cachedTargetDistTenths = -1;
    private static Component cachedTargetDistComp = null;
    private static Component cachedPreviewTagComp = null;

    // Shared font styles (resolved once from GlassClickGuiScreen Nunito pipeline)
    private static Style hudRegularStyle = Style.EMPTY;
    private static Style hudBoldStyle = Style.EMPTY;
    private static boolean hudStylesSynced = false;

    // Cached PlayerFaceRenderer reflection (1.21.11 safe)
    private static boolean faceReflectionInit = false;
    private static Method cachedFaceDrawMethod = null;
    private static Method cachedGetSkinMethod = null;

    // Frame timing & Layout Editor drag state
    private static long lastHudFrameNanos = 0L;
    private static float animClockSeconds = 0.0F;
    private static boolean layoutEditorActive = false;
    private static int draggingWidgetIndex = -1;
    private static int selectedWidgetIndex = -1;
    private static float dragGrabOffsetX = 0.0F;
    private static float dragGrabOffsetY = 0.0F;
    private static boolean snapGuideXActive = false;
    private static boolean snapGuideYActive = false;
    private static int snapGuideXPos = 0;
    private static int snapGuideYPos = 0;

    // Pre-cached small UI strings for Layout Editor & icons
    private static Component iconCheckComp;
    private static Component iconCrossComp;
    private static Component iconStarComp;
    private static Component layoutBannerComp;
    private static Component layoutDoneComp;
    private static Component layoutResetComp;

    private HudManager() {
    }

    // =========================================================================
    // Public Accessors & Config API
    // =========================================================================
    public static HudWidgetConfig getWidget(int index) {
        if (index < 0 || index >= WIDGET_COUNT) {
            return WIDGETS[0];
        }
        return WIDGETS[index];
    }

    public static HudWidgetConfig[] getWidgets() {
        return WIDGETS;
    }

    public static boolean isLayoutEditorActive() {
        return layoutEditorActive;
    }

    public static void setLayoutEditorActive(boolean active, int focusWidgetIndex) {
        layoutEditorActive = active;
        selectedWidgetIndex = active ? focusWidgetIndex : -1;
        draggingWidgetIndex = -1;
        snapGuideXActive = false;
        snapGuideYActive = false;
    }

    public static void resetDefaultPositions() {
        WIDGETS[WIDGET_ARRAYLIST].normX = 0.985F;
        WIDGETS[WIDGET_ARRAYLIST].normY = 0.025F;
        WIDGETS[WIDGET_NOTIFICATIONS].normX = 0.985F;
        WIDGETS[WIDGET_NOTIFICATIONS].normY = 0.935F;
        WIDGETS[WIDGET_TARGETHUD].normX = 0.560F;
        WIDGETS[WIDGET_TARGETHUD].normY = 0.620F;
        WIDGETS[WIDGET_KEYBINDS].normX = 0.015F;
        WIDGETS[WIDGET_KEYBINDS].normY = 0.080F;
    }

    public static boolean isLegacyHudModuleName(String name) {
        if (name == null) return false;
        String low = name.trim().toLowerCase(Locale.ROOT);
        return low.equals("hud") || low.equals("arraylist") || low.equals("targethud")
                || low.equals("notifications") || low.equals("keybinds") || low.equals("watermark");
    }

    // =========================================================================
    // Public Notification Dispatch API
    // =========================================================================
    public static void pushNotification(String title, String message, int type) {
        if (!WIDGETS[WIDGET_NOTIFICATIONS].enabled) {
            return;
        }
        String safeTitle = (title == null || title.isEmpty()) ? "Attrium" : title;
        String safeMsg = (message == null) ? "" : message;

        ensureHudStyles();

        // Pick a free slot, or recycle the oldest active slot
        ToastEntry targetSlot = null;
        float oldestAge = -1.0F;
        for (int i = 0; i < MAX_TOASTS; i++) {
            ToastEntry e = TOAST_POOL[i];
            if (!e.active) {
                targetSlot = e;
                break;
            }
            if (e.ageSeconds > oldestAge) {
                oldestAge = e.ageSeconds;
                targetSlot = e;
            }
        }
        if (targetSlot == null) {
            return;
        }
        targetSlot.active = true;
        targetSlot.title = safeTitle;
        targetSlot.message = safeMsg;
        targetSlot.titleComp = Component.literal(safeTitle).withStyle(hudBoldStyle);
        targetSlot.msgComp = Component.literal(safeMsg).withStyle(hudRegularStyle);
        targetSlot.type = type;
        targetSlot.ageSeconds = 0.0F;
        targetSlot.slideProgress = 0.0F;
        targetSlot.offsetInitialized = false;
    }

    // =========================================================================
    // JSON Persistence (Normalized Screen Ratios + Theme/Opacity/Scale)
    // =========================================================================
    public static JsonObject serializeToJson() {
        JsonObject root = new JsonObject();
        for (int i = 0; i < WIDGET_COUNT; i++) {
            HudWidgetConfig w = WIDGETS[i];
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", w.enabled);
            obj.addProperty("normX", w.normX);
            obj.addProperty("normY", w.normY);
            obj.addProperty("accentTheme", w.accentTheme);
            obj.addProperty("bgOpacity", w.bgOpacity);
            obj.addProperty("scale", w.scale);
            root.add(w.id, obj);
        }
        return root;
    }

    public static void applyFromJson(JsonObject hudObj) {
        if (hudObj == null) {
            return;
        }
        for (int i = 0; i < WIDGET_COUNT; i++) {
            HudWidgetConfig w = WIDGETS[i];
            if (!hudObj.has(w.id) || !hudObj.get(w.id).isJsonObject()) {
                continue;
            }
            JsonObject obj = hudObj.getAsJsonObject(w.id);
            if (obj.has("enabled") && obj.get("enabled").isJsonPrimitive()) {
                w.enabled = obj.get("enabled").getAsBoolean();
                w.toggleAnim = w.enabled ? 1.0F : 0.0F;
            }
            if (obj.has("normX") && obj.get("normX").isJsonPrimitive()) {
                w.normX = Mth.clamp(obj.get("normX").getAsFloat(), 0.0F, 1.0F);
            }
            if (obj.has("normY") && obj.get("normY").isJsonPrimitive()) {
                w.normY = Mth.clamp(obj.get("normY").getAsFloat(), 0.0F, 1.0F);
            }
            if (obj.has("accentTheme") && obj.get("accentTheme").isJsonPrimitive()) {
                w.accentTheme = Mth.clamp(obj.get("accentTheme").getAsInt(), 0, AnimationUtil.ACCENT_THEME_NAMES.length - 1);
            }
            if (obj.has("bgOpacity") && obj.get("bgOpacity").isJsonPrimitive()) {
                w.bgOpacity = Mth.clamp(obj.get("bgOpacity").getAsFloat(), 0.0F, 1.0F);
            }
            if (obj.has("scale") && obj.get("scale").isJsonPrimitive()) {
                w.scale = Mth.clamp(obj.get("scale").getAsFloat(), 0.75F, 1.50F);
            }
        }
    }

    // =========================================================================
    // Main In-Game HUD Render Hook (Called from InGameHudMixin)
    // =========================================================================
    public static void renderInGameHud(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.font == null || guiGraphics == null) {
            return;
        }
        if (mc.options != null && mc.options.hideGui) {
            return;
        }
        // When Layout Editor is open inside GlassClickGuiScreen, the screen renders the interactive HUD layer on top
        if (layoutEditorActive && mc.screen instanceof GlassClickGuiScreen) {
            return;
        }
        boolean previewMode = (mc.screen instanceof GlassClickGuiScreen gui) && gui.isHudTabActive();
        renderAllWidgets(mc, guiGraphics, previewMode, false);
    }

    /**
     * Renders all HUD widgets (called either from InGameHudMixin or GlassClickGuiScreen Layout Editor).
     */
    public static void renderAllWidgets(Minecraft mc, GuiGraphics guiGraphics, boolean previewMode, boolean editorMode) {
        if (mc == null || mc.player == null || mc.level == null || mc.font == null || guiGraphics == null) {
            return;
        }
        ensureHudStyles();
        ensureModulePool(mc);

        long now = System.nanoTime();
        float dt = lastHudFrameNanos == 0L
                ? 0.016F
                : AnimationUtil.clampDelta((now - lastHudFrameNanos) * 1.0e-9F);
        lastHudFrameNanos = now;
        animClockSeconds += dt;
        if (animClockSeconds > 86400.0F) {
            animClockSeconds -= 86400.0F;
        }

        int screenW = guiGraphics.guiWidth();
        int screenH = guiGraphics.guiHeight();
        if (screenW <= 0 || screenH <= 0) {
            return;
        }

        // Always update ArrayList toggle states so Notifications fire even if ArrayList is hidden
        updateArrayListAndToggleNotifications(mc.font, dt);

        if (WIDGETS[WIDGET_ARRAYLIST].enabled) {
            renderArrayList(mc.font, guiGraphics, screenW, screenH, dt, editorMode);
        }
        if (WIDGETS[WIDGET_KEYBINDS].enabled) {
            renderKeybinds(mc, mc.font, guiGraphics, screenW, screenH, dt, editorMode);
        }
        if (WIDGETS[WIDGET_TARGETHUD].enabled) {
            renderTargetHud(mc, mc.font, guiGraphics, screenW, screenH, dt, previewMode || editorMode);
        }
        if (WIDGETS[WIDGET_NOTIFICATIONS].enabled) {
            renderNotifications(mc.font, guiGraphics, screenW, screenH, dt, editorMode);
        }
    }

    // =========================================================================
    // Widget A: ArrayList (Width-Sorted Cascading Active Modules)
    // =========================================================================
    private static void updateArrayListAndToggleNotifications(Font font, float dt) {
        for (int i = 0; i < arrayPoolCount; i++) {
            ArrayEntry e = ARRAY_POOL[i];
            if (e == null || e.module == null) continue;

            boolean enabled = e.module.isEnabled();
            if (e.stateInitialized && enabled != e.lastEnabledState) {
                pushNotification(e.name, enabled ? "Enabled" : "Disabled",
                        enabled ? TYPE_ENABLED : TYPE_DISABLED);
            }
            e.lastEnabledState = enabled;
            e.stateInitialized = true;

            float targetSlide = enabled ? 1.0F : 0.0F;
            float targetHeight = enabled ? 1.0F : 0.0F;
            e.slideAnim = AnimationUtil.lerp(e.slideAnim, targetSlide, 12.0F, dt);
            e.heightAnim = AnimationUtil.lerp(e.heightAnim, targetHeight, 14.0F, dt);

            // Refresh active mode tag only when its string actually changes
            if (e.primaryModeSetting != null) {
                String modeVal = e.primaryModeSetting.getValue();
                if (modeVal == null) modeVal = "";
                if (!modeVal.equals(e.lastModeTag)) {
                    e.lastModeTag = modeVal;
                    e.modeTagComp = modeVal.isEmpty()
                            ? null
                            : Component.literal(" " + modeVal).withStyle(hudRegularStyle);
                    e.cachedTotalWidth = 0;
                }
            }

            if (e.cachedTotalWidth <= 0 && (enabled || e.slideAnim > 0.01F)) {
                e.cachedNameWidth = font.width(e.nameComp);
                int modeW = e.modeTagComp != null ? font.width(e.modeTagComp) : 0;
                e.cachedTotalWidth = e.cachedNameWidth + modeW;
            }
        }
    }

    private static void renderArrayList(Font font, GuiGraphics guiGraphics, int screenW, int screenH,
                                        float dt, boolean editorMode) {
        HudWidgetConfig cfg = WIDGETS[WIDGET_ARRAYLIST];
        int visibleCount = 0;
        for (int i = 0; i < arrayPoolCount; i++) {
            ArrayEntry e = ARRAY_POOL[i];
            if (e != null && (e.slideAnim > 0.01F || e.heightAnim > 0.01F)) {
                ARRAY_SORTED[visibleCount++] = e;
            }
        }

        // In-place insertion sort descending by rendered pixel width (zero heap allocation)
        for (int i = 1; i < visibleCount; i++) {
            ArrayEntry key = ARRAY_SORTED[i];
            int j = i - 1;
            while (j >= 0 && ARRAY_SORTED[j].cachedTotalWidth < key.cachedTotalWidth) {
                ARRAY_SORTED[j + 1] = ARRAY_SORTED[j];
                j--;
            }
            ARRAY_SORTED[j + 1] = key;
        }

        float scale = cfg.scale;
        int rowHeight = 14;
        int rowGap = 2;
        int padX = 4;
        int accentBarW = 3;

        int maxRowW = 86;
        for (int i = 0; i < visibleCount; i++) {
            int rw = ARRAY_SORTED[i].cachedTotalWidth + padX * 2 + accentBarW + 3;
            if (rw > maxRowW) maxRowW = rw;
        }

        float totalLogicalH = 0.0F;
        for (int i = 0; i < visibleCount; i++) {
            totalLogicalH += (rowHeight + rowGap) * ARRAY_SORTED[i].heightAnim;
        }
        int boxW = Math.round(maxRowW * scale);
        int boxH = Math.max(Math.round(20 * scale), Math.round(totalLogicalH * scale));
        boolean alignRight = cfg.normX >= 0.5F;

        int anchorX = Math.round(cfg.normX * screenW);
        int baseX = alignRight ? (anchorX - boxW) : anchorX;
        int baseY = Math.round(cfg.normY * screenH);
        baseX = Mth.clamp(baseX, 2, Math.max(2, screenW - boxW - 2));
        baseY = Mth.clamp(baseY, 2, Math.max(2, screenH - boxH - 2));

        cfg.lastBoxX = baseX;
        cfg.lastBoxY = baseY;
        cfg.lastBoxW = boxW;
        cfg.lastBoxH = boxH;

        if (visibleCount == 0) {
            if (editorMode) {
                int bg = AnimationUtil.withAlphaByte(0x000E1015, Math.round(cfg.bgOpacity * 255.0F));
                drawRoundedRect(guiGraphics, baseX, baseY, boxW, boxH, 4, bg);
                guiGraphics.drawString(font, Component.literal("ArrayList").withStyle(hudBoldStyle),
                        baseX + 6, baseY + 6, 0xFFFFFFFF, false);
            }
            return;
        }

        int bgAlpha = Mth.clamp(Math.round(cfg.bgOpacity * 255.0F), 0, 255);
        int baseBgColor = (bgAlpha << 24) | 0x0E1015;

        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate(baseX, baseY);
        guiGraphics.pose().scale(scale, scale);

        float cursorY = 0.0F;
        for (int i = 0; i < visibleCount; i++) {
            ArrayEntry e = ARRAY_SORTED[i];
            float slideEase = AnimationUtil.easeOutCubic(e.slideAnim);
            int pillW = e.cachedTotalWidth + padX * 2 + accentBarW + 3;
            int pillH = rowHeight;
            int yInt = Math.round(cursorY);

            int targetLocalX = alignRight ? (maxRowW - pillW) : 0;
            int slideOffset = Math.round((1.0F - slideEase) * (pillW + 10));
            int drawX = alignRight ? (targetLocalX + slideOffset) : (targetLocalX - slideOffset);

            // Screen-space scissor clip so sliding text never bleeds outside pill bounds
            int scX1 = baseX + Math.round(targetLocalX * scale);
            int scY1 = baseY + Math.round(yInt * scale);
            int scX2 = baseX + Math.round((targetLocalX + pillW) * scale);
            int scY2 = baseY + Math.round((yInt + pillH * e.heightAnim + 1.0F) * scale);

            if (scX2 > scX1 && scY2 > scY1) {
                guiGraphics.enableScissor(scX1, scY1, scX2, scY2);

                int rowBg = AnimationUtil.withAlpha(baseBgColor, slideEase);
                drawRoundedRect(guiGraphics, drawX, yInt, pillW, pillH, 4, rowBg);

                int accentColor = AnimationUtil.withAlpha(
                        AnimationUtil.resolveAccentColor(cfg.accentTheme, animClockSeconds, i * 0.35F),
                        slideEase
                );
                int barX = alignRight ? (drawX + pillW - accentBarW) : drawX;
                drawRoundedRect(guiGraphics, barX, yInt + 1, accentBarW, pillH - 2, 1, accentColor);

                int textX = alignRight ? (drawX + padX) : (drawX + accentBarW + padX + 1);
                int textY = yInt + 3;
                int nameCol = AnimationUtil.withAlpha(0xFFFFFFFF, slideEase);
                if ((nameCol >>> 24) > 8) {
                    guiGraphics.drawString(font, e.nameComp, textX, textY, nameCol, false);
                    if (e.modeTagComp != null) {
                        int tagCol = AnimationUtil.withAlpha(0xFF9CA3AF, slideEase);
                        guiGraphics.drawString(font, e.modeTagComp, textX + e.cachedNameWidth, textY, tagCol, false);
                    }
                }

                guiGraphics.disableScissor();
            }

            cursorY += (rowHeight + rowGap) * e.heightAnim;
        }

        guiGraphics.pose().popMatrix();
    }

    // =========================================================================
    // Widget B: Notifications (Animated Toast Stack)
    // =========================================================================
    private static void renderNotifications(Font font, GuiGraphics guiGraphics, int screenW, int screenH,
                                            float dt, boolean editorMode) {
        HudWidgetConfig cfg = WIDGETS[WIDGET_NOTIFICATIONS];
        float scale = cfg.scale;
        int cardW = 140;
        int cardH = 34;
        int stackGap = 5;

        int boxW = Math.round(cardW * scale);
        int boxH = Math.round(cardH * scale);
        boolean alignRight = cfg.normX >= 0.5F;
        boolean stackUpward = cfg.normY >= 0.5F;

        int anchorX = Math.round(cfg.normX * screenW);
        int baseX = alignRight ? (anchorX - boxW) : anchorX;
        int baseY = Math.round(cfg.normY * screenH) - (stackUpward ? boxH : 0);
        baseX = Mth.clamp(baseX, 2, Math.max(2, screenW - boxW - 2));
        baseY = Mth.clamp(baseY, 2, Math.max(2, screenH - boxH - 2));

        cfg.lastBoxX = baseX;
        cfg.lastBoxY = baseY;
        cfg.lastBoxW = boxW;
        cfg.lastBoxH = boxH;

        int activeIndex = 0;
        int bgAlpha = Mth.clamp(Math.round(cfg.bgOpacity * 255.0F), 0, 255);
        int cardBgBase = (bgAlpha << 24) | 0x111318;

        for (int i = 0; i < MAX_TOASTS; i++) {
            ToastEntry t = TOAST_POOL[i];
            if (!t.active) continue;

            t.ageSeconds += dt;
            if (t.ageSeconds >= TOAST_LIFETIME_SEC + 0.35F) {
                t.active = false;
                continue;
            }

            boolean exiting = t.ageSeconds >= TOAST_LIFETIME_SEC;
            float targetSlide = exiting ? 0.0F : 1.0F;
            t.slideProgress = AnimationUtil.lerp(t.slideProgress, targetSlide, exiting ? 11.0F : 13.0F, dt);

            float targetOffsetY = (stackUpward ? -1.0F : 1.0F) * activeIndex * (cardH + stackGap);
            if (!t.offsetInitialized) {
                t.currentOffsetY = targetOffsetY;
                t.offsetInitialized = true;
            } else {
                t.currentOffsetY = AnimationUtil.lerp(t.currentOffsetY, targetOffsetY, 12.0F, dt);
            }
            activeIndex++;

            float ease = AnimationUtil.easeOutCubic(t.slideProgress);
            if (ease <= 0.01F) continue;

            float slidePx = (1.0F - ease) * (cardW + 24.0F);
            int drawX = alignRight ? Math.round(slidePx) : -Math.round(slidePx);
            int drawY = Math.round(t.currentOffsetY);

            int badgeColor;
            Component badgeIcon;
            if (t.type == TYPE_DISABLED) {
                badgeColor = 0xFFFF5C77;
                badgeIcon = iconCrossComp;
            } else if (t.type == TYPE_ALERT) {
                badgeColor = 0xFFFFC94A;
                badgeIcon = iconStarComp;
            } else {
                badgeColor = AnimationUtil.resolveAccentColor(cfg.accentTheme, animClockSeconds, i * 0.4F);
                badgeIcon = iconCheckComp;
            }

            float remaining = Mth.clamp(1.0F - (t.ageSeconds / TOAST_LIFETIME_SEC), 0.0F, 1.0F);

            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(baseX, baseY);
            guiGraphics.pose().scale(scale, scale);

            drawRoundedRect(guiGraphics, drawX, drawY, cardW, cardH, 8, AnimationUtil.withAlpha(cardBgBase, ease));

            // Left rounded square status badge (6px radius)
            int badgeSize = 20;
            int bx = drawX + 7;
            int by = drawY + 6;
            drawRoundedRect(guiGraphics, bx, by, badgeSize, badgeSize, 6,
                    AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(badgeColor, 0x38), ease));
            int iconCol = AnimationUtil.withAlpha(badgeColor, ease);
            if ((iconCol >>> 24) > 8) {
                int iw = font.width(badgeIcon);
                guiGraphics.drawString(font, badgeIcon, bx + (badgeSize - iw) / 2, by + 6, iconCol, false);
            }

            // Title & status text
            int textX = bx + badgeSize + 7;
            int titleCol = AnimationUtil.withAlpha(0xFFFFFFFF, ease);
            int msgCol = AnimationUtil.withAlpha(0xFF9CA3AF, ease);
            if ((titleCol >>> 24) > 8) {
                guiGraphics.drawString(font, t.titleComp, textX, drawY + 7, titleCol, false);
                guiGraphics.drawString(font, t.msgComp, textX, drawY + 18, msgCol, false);
            }

            // 2px rounded bottom timer progress bar
            int barMargin = 8;
            int barMaxW = cardW - barMargin * 2;
            int barFillW = Math.round(barMaxW * remaining);
            drawRoundedRect(guiGraphics, drawX + barMargin, drawY + cardH - 3, barMaxW, 2, 1,
                    AnimationUtil.withAlpha(0x33FFFFFF, ease));
            if (barFillW > 1) {
                drawRoundedRect(guiGraphics, drawX + barMargin, drawY + cardH - 3, barFillW, 2, 1,
                        AnimationUtil.withAlpha(badgeColor, ease));
            }

            guiGraphics.pose().popMatrix();
        }

        // Show clean preview toast in Layout Editor when no live toasts are active
        if (activeIndex == 0 && editorMode) {
            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(baseX, baseY);
            guiGraphics.pose().scale(scale, scale);

            int accent = AnimationUtil.resolveAccentColor(cfg.accentTheme, animClockSeconds, 0.0F);
            drawRoundedRect(guiGraphics, 0, 0, cardW, cardH, 8, cardBgBase);
            drawRoundedRect(guiGraphics, 7, 6, 20, 20, 6, AnimationUtil.withAlphaByte(accent, 0x38));
            int iw = font.width(iconCheckComp);
            guiGraphics.drawString(font, iconCheckComp, 7 + (20 - iw) / 2, 12, accent, false);
            guiGraphics.drawString(font, Component.literal("Notifications").withStyle(hudBoldStyle), 34, 7, 0xFFFFFFFF, false);
            guiGraphics.drawString(font, Component.literal("Preview Toast").withStyle(hudRegularStyle), 34, 18, 0xFF9CA3AF, false);
            drawRoundedRect(guiGraphics, 8, cardH - 3, cardW - 16, 2, 1, accent);

            guiGraphics.pose().popMatrix();
        }
    }

    // =========================================================================
    // Widget C: TargetHUD (Dynamic Combat Card + Live Preview)
    // =========================================================================
    private static void renderTargetHud(Minecraft mc, Font font, GuiGraphics guiGraphics,
                                        int screenW, int screenH, float dt, boolean previewMode) {
        HudWidgetConfig cfg = WIDGETS[WIDGET_TARGETHUD];
        Player activeTarget = resolveTargetHudPlayer(mc, previewMode);

        long nowMs = System.currentTimeMillis();
        if (activeTarget != null) {
            trackedTarget = activeTarget;
            lastTargetSeenMs = nowMs;
        } else if (trackedTarget != null) {
            if (!trackedTarget.isAlive() || trackedTarget.isRemoved() || (nowMs - lastTargetSeenMs) > 1500L) {
                trackedTarget = null;
            }
        }

        float targetPop = (trackedTarget != null) ? 1.0F : 0.0F;
        targetPopAnim = AnimationUtil.lerp(targetPopAnim, targetPop, 12.0F, dt);

        float scale = cfg.scale;
        int cardW = 155;
        int cardH = 46;
        int boxW = Math.round(cardW * scale);
        int boxH = Math.round(cardH * scale);

        int baseX = Mth.clamp(Math.round(cfg.normX * screenW), 2, Math.max(2, screenW - boxW - 2));
        int baseY = Mth.clamp(Math.round(cfg.normY * screenH), 2, Math.max(2, screenH - boxH - 2));

        cfg.lastBoxX = baseX;
        cfg.lastBoxY = baseY;
        cfg.lastBoxW = boxW;
        cfg.lastBoxH = boxH;

        if (targetPopAnim <= 0.01F || trackedTarget == null) {
            return;
        }

        Player target = trackedTarget;
        float maxHp = Math.max(1.0F, target.getMaxHealth());
        float hp = Mth.clamp(target.getHealth() + target.getAbsorptionAmount(), 0.0F, maxHp + 20.0F);
        float hpRatio = Mth.clamp(hp / maxHp, 0.0F, 1.0F);

        if (target.getId() != lastTrackedTargetId) {
            lastTrackedTargetId = target.getId();
            targetAnimatedHealth = hpRatio;
            targetEasingHealth = hpRatio;
            cachedTargetName = "";
            cachedTargetHpTenths = -1;
            cachedTargetDistTenths = -1;
        }

        // Fast front fill + slower lagging ghost bar for burst damage visualization
        targetAnimatedHealth = AnimationUtil.lerp(targetAnimatedHealth, hpRatio, 13.0F, dt);
        targetEasingHealth = AnimationUtil.lerp(targetEasingHealth, hpRatio, 3.8F, dt);
        if (targetEasingHealth < targetAnimatedHealth) {
            targetEasingHealth = targetAnimatedHealth;
        }

        // Cache formatted strings (rebuild ONLY when rounded tenths or name change)
        String rawName = target.getName().getString();
        if (!rawName.equals(cachedTargetName) || cachedTargetNameComp == null) {
            cachedTargetName = rawName;
            String clipped = rawName.length() > 14 ? rawName.substring(0, 14) : rawName;
            cachedTargetNameComp = Component.literal(clipped).withStyle(hudBoldStyle);
        }

        int hpTenths = Math.round(hp * 10.0F);
        if (hpTenths != cachedTargetHpTenths || cachedTargetHpComp == null) {
            cachedTargetHpTenths = hpTenths;
            cachedTargetHpComp = Component.literal((hpTenths / 10) + "." + (hpTenths % 10) + " HP")
                    .withStyle(hudRegularStyle);
        }

        double dx = target.getX() - mc.player.getX();
        double dy = target.getY() - mc.player.getY();
        double dz = target.getZ() - mc.player.getZ();
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        int distTenths = Math.round(dist * 10.0F);
        if (distTenths != cachedTargetDistTenths || cachedTargetDistComp == null) {
            cachedTargetDistTenths = distTenths;
            cachedTargetDistComp = Component.literal((distTenths / 10) + "." + (distTenths % 10) + "m")
                    .withStyle(hudRegularStyle);
        }

        float alpha = Mth.clamp(targetPopAnim, 0.0F, 1.0F);
        float popScale = scale * (0.85F + 0.15F * AnimationUtil.easeOutCubic(alpha));

        int bgAlpha = Mth.clamp(Math.round(cfg.bgOpacity * 255.0F * alpha), 0, 255);
        int cardBg = (bgAlpha << 24) | 0x111318;

        int accentStart = AnimationUtil.withAlpha(
                AnimationUtil.resolveAccentColor(cfg.accentTheme, animClockSeconds, 0.0F), alpha);
        int accentEnd = AnimationUtil.withAlpha(
                AnimationUtil.resolveAccentSecondary(cfg.accentTheme, animClockSeconds, 0.0F), alpha);

        // Scale around card center for smooth pop-in/out
        float centerX = baseX + boxW * 0.5F;
        float centerY = baseY + boxH * 0.5F;

        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate(centerX, centerY);
        guiGraphics.pose().scale(popScale, popScale);
        guiGraphics.pose().translate(-cardW * 0.5F, -cardH * 0.5F);

        drawRoundedRect(guiGraphics, 0, 0, cardW, cardH, 10, cardBg);

        // 28x28 Player Skin Head on left + subtle red hurt flash overlay
        int avatarX = 9;
        int avatarY = 9;
        int avatarSize = 28;
        drawRoundedRect(guiGraphics, avatarX - 1, avatarY - 1, avatarSize + 2, avatarSize + 2, 6,
                AnimationUtil.withAlpha(0x33FFFFFF, alpha));
        drawPlayerAvatar(guiGraphics, mc, target, avatarX, avatarY, avatarSize);
        if (target.hurtTime > 0) {
            float hurtAlpha = Mth.clamp((target.hurtTime / 10.0F) * 0.55F * alpha, 0.0F, 0.6F);
            drawRoundedRect(guiGraphics, avatarX, avatarY, avatarSize, avatarSize, 4,
                    AnimationUtil.withAlpha(0xFFFF2E4C, hurtAlpha));
        }

        // Username + Distance & Health Readout
        int infoX = avatarX + avatarSize + 9;
        int infoRight = cardW - 10;
        int textCol = AnimationUtil.withAlpha(0xFFFFFFFF, alpha);
        if ((textCol >>> 24) > 8) {
            guiGraphics.drawString(font, cachedTargetNameComp, infoX, 9, textCol, false);

            int hpW = font.width(cachedTargetHpComp);
            guiGraphics.drawString(font, cachedTargetHpComp, infoX, 20, accentStart, false);

            int distCol = AnimationUtil.withAlpha(0xFF9CA3AF, alpha);
            int distW = font.width(cachedTargetDistComp);
            if (infoX + hpW + 6 + distW <= infoRight) {
                guiGraphics.drawString(font, cachedTargetDistComp, infoRight - distW, 20, distCol, false);
            }
        }

        // Dual-Layer 6px Rounded Health Bar (3px radius)
        int barX = infoX;
        int barY = 33;
        int barW = infoRight - barX;
        int barH = 6;
        drawRoundedRect(guiGraphics, barX, barY, barW, barH, 3,
                AnimationUtil.withAlpha(0x55000000, alpha));

        int ghostW = Math.round(barW * targetEasingHealth);
        if (ghostW > 2) {
            drawRoundedRect(guiGraphics, barX, barY, ghostW, barH, 3,
                    AnimationUtil.withAlpha(0x77A5F3FC, alpha));
        }

        int fillW = Math.round(barW * targetAnimatedHealth);
        if (fillW > 2) {
            drawHorizontalGradientRoundedRect(guiGraphics, barX, barY, fillW, barH, 3, accentStart, accentEnd);
        }

        guiGraphics.pose().popMatrix();
    }

    private static Player resolveTargetHudPlayer(Minecraft mc, boolean previewMode) {
        if (previewMode) {
            return mc.player;
        }
        // Check closest enemy player from TargetManager within combat range (8.0 blocks)
        Player[] enemies = TargetManager.getEnemyPlayersArray();
        int count = TargetManager.getEnemyPlayerCount();
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Player p = enemies[i];
            if (p == null || p == mc.player || !p.isAlive() || p.isRemoved()) continue;
            if (FriendManager.isFriend(p)) continue;
            double distSq = mc.player.distanceToSqr(p);
            if (distSq > 64.0D) continue;
            // Prioritize enemies actively being hit (hurtTime > 0) or closest in combat range
            double score = distSq - (p.hurtTime > 0 ? 18.0D : 0.0D);
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (best != null && (best.hurtTime > 0 || mc.crosshairPickEntity == best || mc.player.swinging)) {
            return best;
        }
        return null;
    }

    // =========================================================================
    // Widget D: Keybinds (Active Bound Modules with Glowing Keycap Badges)
    // =========================================================================
    private static void renderKeybinds(Minecraft mc, Font font, GuiGraphics guiGraphics,
                                       int screenW, int screenH, float dt, boolean editorMode) {
        HudWidgetConfig cfg = WIDGETS[WIDGET_KEYBINDS];
        GlassClickGuiScreen gui = GlassClickGuiScreen.getInstance();

        // Populate pre-allocated keybind pool without creating Lists
        int count = 0;
        for (int i = 0; i < arrayPoolCount && count < MAX_KEYBINDS; i++) {
            ArrayEntry ae = ARRAY_POOL[i];
            if (ae == null || ae.module == null) continue;
            int key = gui.getBoundKeyForModule(ae.module);
            if (key == -1 || key == 0 || key == GLFW.GLFW_KEY_UNKNOWN) continue;

            KeybindEntry ke = KEYBIND_POOL[count++];
            ke.module = ae.module;
            if (!ae.name.equals(ke.name) || ke.nameComp == null) {
                ke.name = ae.name;
                String clipped = ae.name.length() > 12 ? ae.name.substring(0, 12) : ae.name;
                ke.nameComp = Component.literal(clipped).withStyle(hudRegularStyle);
            }
            if (key != ke.keyCode || ke.keyLabelComp == null) {
                ke.keyCode = key;
                ke.keyLabel = formatCompactKeyLabel(key);
                ke.keyLabelComp = Component.literal(ke.keyLabel).withStyle(hudBoldStyle);
                ke.cachedKeyWidth = Math.max(16, font.width(ke.keyLabelComp) + 8);
            }
            float targetAct = ae.module.isEnabled() ? 1.0F : 0.0F;
            ke.activeAnim = AnimationUtil.lerp(ke.activeAnim, targetAct, 12.0F, dt);
        }
        keybindActiveCount = count;

        int headerH = 22;
        int rowH = 17;
        float targetH = (count == 0)
                ? (editorMode ? 42.0F : 24.0F)
                : (headerH + 4 + count * rowH + 4);
        keybindContainerHeight = AnimationUtil.lerp(keybindContainerHeight, targetH, 12.0F, dt);

        float scale = cfg.scale;
        int cardW = 120;
        int cardH = Math.round(keybindContainerHeight);
        int boxW = Math.round(cardW * scale);
        int boxH = Math.round(cardH * scale);

        int baseX = Mth.clamp(Math.round(cfg.normX * screenW), 2, Math.max(2, screenW - boxW - 2));
        int baseY = Mth.clamp(Math.round(cfg.normY * screenH), 2, Math.max(2, screenH - boxH - 2));

        cfg.lastBoxX = baseX;
        cfg.lastBoxY = baseY;
        cfg.lastBoxW = boxW;
        cfg.lastBoxH = boxH;

        if (count == 0 && !editorMode) {
            return;
        }

        int bgAlpha = Mth.clamp(Math.round(cfg.bgOpacity * 255.0F), 0, 255);
        int cardBg = (bgAlpha << 24) | 0x111318;
        int accent = AnimationUtil.resolveAccentColor(cfg.accentTheme, animClockSeconds, 0.0F);

        guiGraphics.enableScissor(baseX, baseY, baseX + boxW, baseY + boxH);
        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate(baseX, baseY);
        guiGraphics.pose().scale(scale, scale);

        drawRoundedRect(guiGraphics, 0, 0, cardW, cardH, 8, cardBg);

        // Header + subtle 1px divider
        guiGraphics.drawString(font, keybindHeaderComp, 9, 7, 0xFFFFFFFF, false);
        guiGraphics.fill(8, headerH - 1, cardW - 8, headerH, 0x22FFFFFF);

        if (count == 0 && editorMode) {
            guiGraphics.drawString(font, Component.literal("No keys bound").withStyle(hudRegularStyle),
                    9, headerH + 6, 0xFF9CA3AF, false);
        } else {
            int y = headerH + 4;
            for (int i = 0; i < count; i++) {
                KeybindEntry ke = KEYBIND_POOL[i];
                int nameCol = AnimationUtil.lerpColor(0xFFD1D5DB, 0xFFFFFFFF, ke.activeAnim);
                guiGraphics.drawString(font, ke.nameComp, 9, y + 3, nameCol, false);

                int pillW = ke.cachedKeyWidth;
                int pillH = 13;
                int pillX = cardW - 8 - pillW;
                int pillY = y + 1;

                int pillBg = AnimationUtil.lerpColor(0x33FFFFFF, accent, ke.activeAnim);
                int pillTextCol = AnimationUtil.lerpColor(0xFFE5E7EB, 0xFF0B0D10, ke.activeAnim);
                drawRoundedRect(guiGraphics, pillX, pillY, pillW, pillH, 4, pillBg);

                int kw = font.width(ke.keyLabelComp);
                guiGraphics.drawString(font, ke.keyLabelComp, pillX + (pillW - kw) / 2, pillY + 3, pillTextCol, false);

                y += rowH;
            }
        }

        guiGraphics.pose().popMatrix();
        guiGraphics.disableScissor();
    }

    // =========================================================================
    // Interactive Layout Editor (Drag-and-Drop + Magnetic Center/Edge Snap)
    // =========================================================================
    public static void renderLayoutEditor(Minecraft mc, GuiGraphics guiGraphics, Font font, int mouseX, int mouseY) {
        int screenW = guiGraphics.guiWidth();
        int screenH = guiGraphics.guiHeight();

        // Soft backdrop dim
        guiGraphics.fill(0, 0, screenW, screenH, 0x55080A10);

        // Render live HUD widgets in preview/editor mode
        renderAllWidgets(mc, guiGraphics, true, true);

        // Draw magnetic snap alignment lines when snapping
        if (snapGuideXActive) {
            guiGraphics.fill(snapGuideXPos, 0, snapGuideXPos + 1, screenH, 0x9900E5FF);
        }
        if (snapGuideYActive) {
            guiGraphics.fill(0, snapGuideYPos, screenW, snapGuideYPos + 1, 0x9900E5FF);
        }

        // Draw subtle interactive highlight pills around each enabled widget
        for (int i = 0; i < WIDGET_COUNT; i++) {
            HudWidgetConfig w = WIDGETS[i];
            if (!w.enabled || w.lastBoxW <= 0 || w.lastBoxH <= 0) continue;
            boolean hovered = isPointInRect(mouseX, mouseY, w.lastBoxX - 3, w.lastBoxY - 3, w.lastBoxW + 6, w.lastBoxH + 6);
            boolean active = (draggingWidgetIndex == i || selectedWidgetIndex == i);
            if (hovered || active) {
                int tint = active ? 0x3300E5FF : 0x1AFFFFFF;
                drawRoundedRect(guiGraphics, w.lastBoxX - 3, w.lastBoxY - 3, w.lastBoxW + 6, w.lastBoxH + 6, 8, tint);
            }
        }

        // Floating top toolbar pill ("HUD Layout Editor  •  Reset  •  Done")
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

        boolean hoverReset = isPointInRect(mouseX, mouseY, resetX, btnY, resetW, btnH);
        boolean hoverDone = isPointInRect(mouseX, mouseY, doneX, btnY, doneW, btnH);

        drawRoundedRect(guiGraphics, resetX, btnY, resetW, btnH, 6, hoverReset ? 0x44FFFFFF : 0x26FFFFFF);
        int rw = font.width(layoutResetComp);
        guiGraphics.drawString(font, layoutResetComp, resetX + (resetW - rw) / 2, btnY + 5, 0xFFE5E7EB, false);

        drawRoundedRect(guiGraphics, doneX, btnY, doneW, btnH, 6, hoverDone ? 0xFF38BDF8 : 0xFF00E5FF);
        int dw = font.width(layoutDoneComp);
        guiGraphics.drawString(font, layoutDoneComp, doneX + (doneW - dw) / 2, btnY + 5, 0xFF0B0D10, false);
    }

    /**
     * Handles mouse down in Layout Editor. Returns true if editor was closed or reset (triggers config save).
     */
    public static boolean handleLayoutEditorClick(int screenW, int screenH, double mouseX, double mouseY) {
        int barW = 248;
        int barH = 26;
        int barX = (screenW - barW) / 2;
        int barY = 10;
        int doneW = 42;
        int resetW = 46;
        int btnH = 18;
        int btnY = barY + 4;
        int doneX = barX + barW - doneW - 6;
        int resetX = doneX - resetW - 5;

        if (isPointInRect(mouseX, mouseY, doneX, btnY, doneW, btnH)) {
            setLayoutEditorActive(false, -1);
            return true;
        }
        if (isPointInRect(mouseX, mouseY, resetX, btnY, resetW, btnH)) {
            resetDefaultPositions();
            return true;
        }

        // Hit-test widgets in reverse draw order
        for (int i = WIDGET_COUNT - 1; i >= 0; i--) {
            HudWidgetConfig w = WIDGETS[i];
            if (!w.enabled) continue;
            if (isPointInRect(mouseX, mouseY, w.lastBoxX - 4, w.lastBoxY - 4, w.lastBoxW + 8, w.lastBoxH + 8)) {
                draggingWidgetIndex = i;
                selectedWidgetIndex = i;
                dragGrabOffsetX = (float) mouseX - w.lastBoxX;
                dragGrabOffsetY = (float) mouseY - w.lastBoxY;
                return false;
            }
        }
        selectedWidgetIndex = -1;
        return false;
    }

    public static void handleLayoutEditorDrag(int screenW, int screenH, double mouseX, double mouseY) {
        if (draggingWidgetIndex < 0 || draggingWidgetIndex >= WIDGET_COUNT || screenW <= 0 || screenH <= 0) {
            return;
        }
        HudWidgetConfig w = WIDGETS[draggingWidgetIndex];
        float rawX = (float) mouseX - dragGrabOffsetX;
        float rawY = (float) mouseY - dragGrabOffsetY;
        int bw = Math.max(24, w.lastBoxW);
        int bh = Math.max(16, w.lastBoxH);

        rawX = Mth.clamp(rawX, 2.0F, Math.max(2.0F, screenW - bw - 2.0F));
        rawY = Mth.clamp(rawY, 2.0F, Math.max(2.0F, screenH - bh - 2.0F));

        // Magnetic center & 8px margin snap (6px snap radius)
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

        // Convert back to normalized screen ratios (right-anchored for ArrayList/Notifications when on right half)
        boolean rightAnchor = (w.index == WIDGET_ARRAYLIST || w.index == WIDGET_NOTIFICATIONS)
                && (rawX + bw * 0.5F >= screenW * 0.5F);
        boolean bottomAnchor = (w.index == WIDGET_NOTIFICATIONS)
                && (rawY + bh * 0.5F >= screenH * 0.5F);

        w.normX = Mth.clamp((rightAnchor ? (rawX + bw) : rawX) / (float) screenW, 0.0F, 1.0F);
        w.normY = Mth.clamp((bottomAnchor ? (rawY + bh) : rawY) / (float) screenH, 0.0F, 1.0F);
    }

    public static boolean handleLayoutEditorRelease() {
        boolean wasDragging = draggingWidgetIndex >= 0;
        draggingWidgetIndex = -1;
        snapGuideXActive = false;
        snapGuideYActive = false;
        return wasDragging;
    }

    // =========================================================================
    // Module Pool Initialization & Helpers
    // =========================================================================
    private static void ensureModulePool(Minecraft mc) {
        if (arrayPoolInitialized && arrayPoolCount > 0) {
            return;
        }
        GlassClickGuiScreen gui = GlassClickGuiScreen.getInstance();
        List<Module> modules = gui.getDiscoveredModulesSnapshot();
        if (modules == null || modules.isEmpty()) {
            return;
        }
        arrayPoolCount = 0;
        for (int i = 0; i < modules.size() && arrayPoolCount < MAX_ARRAY_ENTRIES; i++) {
            Module mod = modules.get(i);
            if (mod == null) continue;
            String name = mod.getName();
            if (name == null || name.isEmpty()) continue;
            String low = name.toLowerCase(Locale.ROOT);
            if (low.contains("clickgui") || isLegacyHudModuleName(name)) {
                continue;
            }

            ArrayEntry e = new ArrayEntry();
            e.module = mod;
            e.name = name;
            e.nameComp = Component.literal(name).withStyle(hudBoldStyle);
            e.primaryModeSetting = findPrimaryModeSetting(mod);
            e.lastEnabledState = mod.isEnabled();
            e.stateInitialized = true;
            e.slideAnim = e.lastEnabledState ? 1.0F : 0.0F;
            e.heightAnim = e.lastEnabledState ? 1.0F : 0.0F;
            ARRAY_POOL[arrayPoolCount++] = e;
        }
        arrayPoolInitialized = arrayPoolCount > 0;
    }

    private static ModeSetting findPrimaryModeSetting(Module mod) {
        try {
            Class<?> cls = mod.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (ModeSetting.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object val = f.get(mod);
                        if (val instanceof ModeSetting ms) {
                            return ms;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void ensureHudStyles() {
        if (hudStylesSynced) {
            return;
        }
        hudStylesSynced = true;
        Style reg = GlassClickGuiScreen.getNunitoRegularStyle();
        hudRegularStyle = reg != null ? reg.withBold(false) : Style.EMPTY.withBold(false);
        hudBoldStyle = hudRegularStyle.withBold(true);

        keybindHeaderComp = Component.literal("Keybinds").withStyle(hudBoldStyle);
        iconCheckComp = Component.literal("+").withStyle(hudBoldStyle);
        iconCrossComp = Component.literal("x").withStyle(hudBoldStyle);
        iconStarComp = Component.literal("*").withStyle(hudBoldStyle);
        layoutBannerComp = Component.literal("HUD Layout Editor").withStyle(hudBoldStyle);
        layoutDoneComp = Component.literal("Done").withStyle(hudBoldStyle);
        layoutResetComp = Component.literal("Reset").withStyle(hudRegularStyle);
        cachedPreviewTagComp = Component.literal("Preview").withStyle(hudRegularStyle);
    }

    private static void drawPlayerAvatar(GuiGraphics guiGraphics, Minecraft mc, Player player, int x, int y, int size) {
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
        // Fallback sleek avatar silhouette
        drawRoundedRect(guiGraphics, x, y, size, size, 5, 0xFF1F2937);
        guiGraphics.fill(x + 7, y + 10, x + 11, y + 14, 0xFF00E5FF);
        guiGraphics.fill(x + size - 11, y + 10, x + size - 7, y + 14, 0xFF00E5FF);
        guiGraphics.fill(x + 9, y + size - 8, x + size - 9, y + size - 6, 0xCCFFFFFF);
    }

    public static void drawRoundedRect(GuiGraphics guiGraphics, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
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

    private static void drawHorizontalGradientRoundedRect(GuiGraphics guiGraphics, int x, int y, int w, int h,
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

    private static String formatCompactKeyLabel(int keyCode) {
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

    private static boolean isPointInRect(double px, double py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }
}
