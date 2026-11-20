package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.HudManager;
import attrium.ru.utils.render.AnimationUtil;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

/**
 * Standalone ArrayList HUD Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Displays active modules in an animated cascading staircase along the screen edge with:
 *  - Color modes: Aqua Cyan, Cyan-Blue Wave, Pure White, Category Color, Dynamic Rainbow
 *  - Sort modes: Width (pixel width via Nunito Bold) or Alphabetical
 *  - Background styles: Glass Pill, Connected Dock, Transparent
 *  - Accent bar: Outer Edge, Inner Edge, Full Outline, None
 *  - Suffix style: Clean Gray, Brackets, Dash, Hidden
 *  - Smooth per-module slideAnim & heightAnim clipped cleanly with GuiGraphics#enableScissor
 */
public class ArrayListModule extends HudModule {
    private static volatile ArrayListModule instance;

    private final ModeSetting colorMode = register(new ModeSetting(
            "Color Mode",
            "Accent color theme for active module rows.",
            "Cyan-Blue Wave",
            "Aqua Cyan", "Cyan-Blue Wave", "Pure White", "Category Color", "Dynamic Rainbow"
    ));

    private final ModeSetting sortMode = register(new ModeSetting(
            "Sort Mode",
            "Sort active modules by rendered pixel width or alphabetically.",
            "Width",
            "Width", "Alphabetical"
    ));

    private final ModeSetting backgroundStyle = register(new ModeSetting(
            "Background Style",
            "Visual container style behind each active module.",
            "Glass Pill",
            "Glass Pill", "Connected Dock", "Transparent"
    ));

    private final ModeSetting accentBar = register(new ModeSetting(
            "Accent Bar",
            "Accent bar placement on each row.",
            "Outer Edge",
            "Outer Edge", "Inner Edge", "Full Outline", "None"
    ));

    private final ModeSetting suffixStyle = register(new ModeSetting(
            "Suffix Style",
            "Formatting for each module's active mode tag.",
            "Clean Gray",
            "Clean Gray", "Brackets", "Dash", "Hidden"
    ));

    private final NumberSetting animationSpeed = register(new NumberSetting(
            "Animation Speed",
            "Exponential lerp speed for slide-in and height transitions.",
            14.0D, 5.0D, 25.0D, 0.5D
    ));

    private final NumberSetting rowSpacing = register(new NumberSetting(
            "Row Spacing",
            "Vertical gap in pixels between module rows.",
            1.5D, 0.0D, 4.0D, 0.5D
    ));

    private final BooleanSetting hideHudModules = register(new BooleanSetting(
            "Hide HUD Modules",
            "Filters out HUD widgets from the ArrayList so it stays focused on combat & utility.",
            true
    ));

    // Pre-allocated zero-GC entry pool
    private static final int MAX_ENTRIES = 128;
    private static final class Entry {
        Module module;
        String name;
        String nameLower;
        Component nameComp;
        ModeSetting primaryMode;
        String lastRawMode = "";
        String lastSuffixStyle = "";
        Component suffixComp;
        boolean lastEnabled = false;
        boolean initialized = false;
        float slideAnim = 0.0F;
        float heightAnim = 0.0F;
        int nameWidth = 0;
        int totalWidth = 0;
    }

    private final Entry[] pool = new Entry[MAX_ENTRIES];
    private final Entry[] sorted = new Entry[MAX_ENTRIES];
    private int poolCount = 0;
    private boolean poolReady = false;

    public ArrayListModule() {
        super("ArrayList", "Displays active modules in an animated screen-edge list",
                "A", 0.985D, 0.025D, 170.0D, true);
        instance = this;
    }

    public static ArrayListModule getInstance() {
        return instance;
    }

    @Override
    public boolean usesRightAnchorWhenOnRightHalf() {
        return true;
    }

    /**
     * Always ticks module toggle detection (even when ArrayList itself is hidden) so
     * NotificationsModule receives module toggle alerts reliably.
     */
    public void tickModuleStates(Font font, float dt) {
        ensurePoolInitialized();
        float speed = Mth.clamp(animationSpeed.getValue().floatValue(), 5.0F, 25.0F);
        String sufStyle = suffixStyle.getValue();

        for (int i = 0; i < poolCount; i++) {
            Entry e = pool[i];
            if (e == null || e.module == null) continue;

            boolean enabled = e.module.isEnabled();
            if (e.initialized && enabled != e.lastEnabled) {
                NotificationsModule.notifyModuleToggle(e.name, enabled);
            }
            e.lastEnabled = enabled;
            e.initialized = true;

            float target = enabled ? 1.0F : 0.0F;
            e.slideAnim = AnimationUtil.lerp(e.slideAnim, target, speed, dt);
            e.heightAnim = AnimationUtil.lerp(e.heightAnim, target, speed * 1.1F, dt);

            String rawMode = (e.primaryMode != null && e.primaryMode.getValue() != null)
                    ? e.primaryMode.getValue()
                    : "";
            if (!rawMode.equals(e.lastRawMode) || !sufStyle.equals(e.lastSuffixStyle)) {
                e.lastRawMode = rawMode;
                e.lastSuffixStyle = sufStyle;
                e.suffixComp = buildSuffixComponent(rawMode, sufStyle);
                e.totalWidth = 0;
            }

            if (e.totalWidth <= 0 && (enabled || e.slideAnim > 0.01F)) {
                e.nameWidth = font.width(e.nameComp);
                int sw = e.suffixComp != null ? font.width(e.suffixComp) : 0;
                e.totalWidth = e.nameWidth + sw;
            }
        }
    }

    @Override
    public void renderHud(Minecraft mc, GuiGraphics guiGraphics, Font font,
                          int screenW, int screenH, float dt, float animClockSeconds,
                          boolean previewMode, boolean editorMode) {
        ensurePoolInitialized();
        boolean hideHud = Boolean.TRUE.equals(hideHudModules.getValue());

        int visibleCount = 0;
        for (int i = 0; i < poolCount; i++) {
            Entry e = pool[i];
            if (e == null || e.module == null) continue;
            if (hideHud && e.module instanceof HudModule) continue;
            if (e.slideAnim > 0.01F || e.heightAnim > 0.01F) {
                sorted[visibleCount++] = e;
            }
        }

        boolean sortAlpha = "Alphabetical".equalsIgnoreCase(sortMode.getValue());
        for (int i = 1; i < visibleCount; i++) {
            Entry key = sorted[i];
            int j = i - 1;
            while (j >= 0 && shouldSwap(sorted[j], key, sortAlpha)) {
                sorted[j + 1] = sorted[j];
                j--;
            }
            sorted[j + 1] = key;
        }

        float sc = getHudScale();
        int rowH = 14;
        float gap = "Connected Dock".equalsIgnoreCase(backgroundStyle.getValue())
                ? 0.0F
                : Mth.clamp(rowSpacing.getValue().floatValue(), 0.0F, 4.0F);
        int padX = 4;
        String barMode = accentBar.getValue();
        int barW = "None".equalsIgnoreCase(barMode) ? 0 : 3;

        int maxRowW = 88;
        for (int i = 0; i < visibleCount; i++) {
            int rw = sorted[i].totalWidth + padX * 2 + barW + 3;
            if (rw > maxRowW) maxRowW = rw;
        }

        float logicalH = 0.0F;
        for (int i = 0; i < visibleCount; i++) {
            logicalH += (rowH + gap) * sorted[i].heightAnim;
        }

        int boxW = Math.round(maxRowW * sc);
        int boxH = Math.max(Math.round(20 * sc), Math.round(logicalH * sc));
        boolean alignRight = getXRatio() >= 0.5F;

        int anchorX = Math.round(getXRatio() * screenW);
        int baseX = alignRight ? (anchorX - boxW) : anchorX;
        int baseY = Math.round(getYRatio() * screenH);
        baseX = Mth.clamp(baseX, 2, Math.max(2, screenW - boxW - 2));
        baseY = Mth.clamp(baseY, 2, Math.max(2, screenH - boxH - 2));

        this.lastBoxX = baseX;
        this.lastBoxY = baseY;
        this.lastBoxW = boxW;
        this.lastBoxH = boxH;

        int bgAlpha = getBackgroundAlphaByte();
        int baseBg = (bgAlpha << 24) | 0x0E1015;
        String bgStyle = backgroundStyle.getValue();

        if (visibleCount == 0) {
            if (editorMode) {
                HudManager.drawRoundedRect(guiGraphics, baseX, baseY, boxW, boxH, 4, baseBg);
                guiGraphics.drawString(font, Component.literal("ArrayList").withStyle(HudManager.getBoldStyle()),
                        baseX + 6, baseY + 6, 0xFFFFFFFF, false);
            }
            return;
        }

        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate(baseX, baseY);
        guiGraphics.pose().scale(sc, sc);

        float cursorY = 0.0F;
        for (int i = 0; i < visibleCount; i++) {
            Entry e = sorted[i];
            float ease = AnimationUtil.easeOutCubic(e.slideAnim);
            int pillW = "Connected Dock".equalsIgnoreCase(bgStyle)
                    ? maxRowW
                    : (e.totalWidth + padX * 2 + barW + 3);
            int yInt = Math.round(cursorY);

            int targetLocalX = alignRight ? (maxRowW - pillW) : 0;
            int slidePx = Math.round((1.0F - ease) * (pillW + 10));
            int drawX = alignRight ? (targetLocalX + slidePx) : (targetLocalX - slidePx);

            int localY2 = Math.round(yInt + rowH * e.heightAnim + 1.0F);
            boolean useScissor = e.slideAnim < 0.98F && (localY2 > yInt);
            if (pillW > 0 && localY2 > yInt) {
                // Local coordinates inside pose().translate(baseX, baseY).scale(sc, sc)
                if (useScissor) {
                    guiGraphics.enableScissor(targetLocalX, yInt, targetLocalX + pillW, localY2);
                }

                int accentCol = AnimationUtil.withAlpha(
                        resolveEntryColor(e.module, i, animClockSeconds), ease);

                // Background
                if (!"Transparent".equalsIgnoreCase(bgStyle)) {
                    int rowBg = AnimationUtil.withAlpha(baseBg, ease);
                    int radius = "Glass Pill".equalsIgnoreCase(bgStyle) ? 4 : (i == 0 || i == visibleCount - 1 ? 4 : 0);
                    HudManager.drawRoundedRect(guiGraphics, drawX, yInt, pillW, rowH, radius, rowBg);
                }

                // Accent Bar
                if ("Outer Edge".equalsIgnoreCase(barMode)) {
                    int barX = alignRight ? (drawX + pillW - barW) : drawX;
                    HudManager.drawRoundedRect(guiGraphics, barX, yInt + 1, barW, rowH - 2, 1, accentCol);
                } else if ("Inner Edge".equalsIgnoreCase(barMode)) {
                    int barX = alignRight ? drawX : (drawX + pillW - barW);
                    HudManager.drawRoundedRect(guiGraphics, barX, yInt + 1, barW, rowH - 2, 1, accentCol);
                } else if ("Full Outline".equalsIgnoreCase(barMode)) {
                    guiGraphics.fill(drawX, yInt, drawX + pillW, yInt + 1, accentCol);
                    guiGraphics.fill(drawX, yInt + rowH - 1, drawX + pillW, yInt + rowH, accentCol);
                    guiGraphics.fill(drawX, yInt, drawX + 1, yInt + rowH, accentCol);
                    guiGraphics.fill(drawX + pillW - 1, yInt, drawX + pillW, yInt + rowH, accentCol);
                }

                // Module Name & Mode Suffix
                int textX;
                if (alignRight) {
                    int rightEdge = drawX + pillW - ("Outer Edge".equalsIgnoreCase(barMode) ? (barW + padX) : padX);
                    textX = rightEdge - e.totalWidth;
                } else {
                    textX = drawX + ("Outer Edge".equalsIgnoreCase(barMode) ? (barW + padX + 1) : padX);
                }
                int textY = yInt + 3;
                int nameCol = AnimationUtil.withAlpha(0xFFFFFFFF, ease);
                if ((nameCol >>> 24) > 8) {
                    guiGraphics.drawString(font, e.nameComp, textX, textY, nameCol, false);
                    if (e.suffixComp != null) {
                        int tagCol = AnimationUtil.withAlpha(0xFF9CA3AF, ease);
                        guiGraphics.drawString(font, e.suffixComp, textX + e.nameWidth, textY, tagCol, false);
                    }
                }

                if (useScissor) { guiGraphics.disableScissor(); }
            }

            cursorY += (rowH + gap) * e.heightAnim;
        }

        guiGraphics.pose().popMatrix();
    }

    private boolean shouldSwap(Entry left, Entry right, boolean alpha) {
        if (alpha) {
            return left.nameLower.compareTo(right.nameLower) > 0;
        }
        return left.totalWidth < right.totalWidth;
    }

    private int resolveEntryColor(Module mod, int index, float clockSec) {
        String cm = colorMode.getValue();
        if ("Pure White".equalsIgnoreCase(cm)) {
            return AnimationUtil.ACCENT_PURE_WHITE;
        }
        if ("Aqua Cyan".equalsIgnoreCase(cm)) {
            return AnimationUtil.ACCENT_AQUA_CYAN;
        }
        if ("Category Color".equalsIgnoreCase(cm)) {
            ModuleCategory cat = mod.getCategory();
            if (cat == ModuleCategory.COMBAT) return 0xFFFF5C77;
            if (cat == ModuleCategory.MOVEMENT) return 0xFF38BDF8;
            if (cat == ModuleCategory.VISUAL) return 0xFFA855F7;
            return 0xFF00E5FF;
        }
        if ("Dynamic Rainbow".equalsIgnoreCase(cm)) {
            float hue = ((clockSec * 0.12F) + index * 0.055F) % 1.0F;
            return hsbToArgb(hue, 0.58F, 1.0F);
        }
        // Default: Cyan-Blue Wave
        float wave = 0.5F + 0.5F * (float) Math.sin(clockSec * 2.4F - index * 0.42F);
        return AnimationUtil.lerpColor(AnimationUtil.ACCENT_AQUA_CYAN, 0xFF3B82F6, wave);
    }

    private Component buildSuffixComponent(String rawMode, String style) {
        if (rawMode == null || rawMode.isEmpty() || "Hidden".equalsIgnoreCase(style)) {
            return null;
        }
        String formatted = switch (style) {
            case "Brackets" -> " [" + rawMode + "]";
            case "Dash" -> " - " + rawMode;
            default -> " " + rawMode;
        };
        return Component.literal(formatted).withStyle(HudManager.getRegularStyle());
    }

    private void ensurePoolInitialized() {
        if (poolReady && poolCount > 0) return;
        List<Module> modules = HudManager.getAllClientModules();
        if (modules == null || modules.isEmpty()) return;

        poolCount = 0;
        for (int i = 0; i < modules.size() && poolCount < MAX_ENTRIES; i++) {
            Module m = modules.get(i);
            if (m == null) continue;
            String n = m.getName();
            if (n == null || n.isEmpty()) continue;
            if (n.toLowerCase(Locale.ROOT).contains("clickgui")) continue;

            Entry e = new Entry();
            e.module = m;
            e.name = n;
            e.nameLower = n.toLowerCase(Locale.ROOT);
            e.nameComp = Component.literal(n).withStyle(HudManager.getBoldStyle());
            e.primaryMode = findPrimaryModeSetting(m);
            e.lastEnabled = m.isEnabled();
            e.initialized = true;
            e.slideAnim = e.lastEnabled ? 1.0F : 0.0F;
            e.heightAnim = e.lastEnabled ? 1.0F : 0.0F;
            pool[poolCount++] = e;
        }
        poolReady = poolCount > 0;
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

    private static int hsbToArgb(float hue, float sat, float bri) {
        float h = (hue - (float) Math.floor(hue)) * 6.0F;
        int sector = (int) h;
        float f = h - sector;
        float p = bri * (1.0F - sat);
        float q = bri * (1.0F - sat * f);
        float t = bri * (1.0F - sat * (1.0F - f));
        float r, g, b;
        switch (sector % 6) {
            case 0 -> { r = bri; g = t; b = p; }
            case 1 -> { r = q; g = bri; b = p; }
            case 2 -> { r = p; g = bri; b = t; }
            case 3 -> { r = p; g = q; b = bri; }
            case 4 -> { r = t; g = p; b = bri; }
            default -> { r = bri; g = p; b = q; }
        }
        return 0xFF000000
                | (Mth.clamp(Math.round(r * 255.0F), 0, 255) << 16)
                | (Mth.clamp(Math.round(g * 255.0F), 0, 255) << 8)
                | Mth.clamp(Math.round(b * 255.0F), 0, 255);
    }
}
