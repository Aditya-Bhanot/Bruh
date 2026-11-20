package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.ClientThemeManager;
import attrium.ru.manager.HudManager;
import attrium.ru.utils.render.AnimationUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

/**
 * Standalone ArrayList HUD Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Defaults to the clean top-left Sharp Cascade aesthetic:
 *  - Crisp vanilla Minecraft pixel font with drop shadow (or optional Nunito)
 *  - Pure White module names + muted cool-gray live mode/info suffixes
 *  - Flush 0px-gap dark charcoal staircase bars hugging each row's exact width
 *  - Continuous 2px outer-edge vertical accent bar with zero seam between rows
 *  - Smooth slide-in & height animations with zero per-frame heap allocations
 */
public class ArrayListModule extends HudModule {
    private static volatile ArrayListModule instance;

    private final ModeSetting fontStyle = register(new ModeSetting(
            "Font Style",
            "Typography used for ArrayList rows.",
            "Minecraft",
            "Minecraft", "Nunito"
    ));

    private final ModeSetting colorMode = register(new ModeSetting(
            "Color Mode",
            "Accent color theme for the edge bar and optional tinting.",
            "Pure White",
            "Pure White", "Theme Accent", "Cyan-Blue Wave", "Category Color", "Dynamic Rainbow"
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
            "Sharp Cascade",
            "Sharp Cascade", "Glass Pill", "Connected Dock", "Transparent"
    ));

    private final ModeSetting accentBar = register(new ModeSetting(
            "Accent Bar",
            "Accent bar placement on each row.",
            "Outer Edge",
            "Outer Edge", "Inner Edge", "Full Outline", "None"
    ));

    private final ModeSetting suffixStyle = register(new ModeSetting(
            "Suffix Style",
            "Formatting for each module's active mode/info tag.",
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
            "Vertical gap in pixels between module rows (0 = flush staircase).",
            0.0D, 0.0D, 4.0D, 0.5D
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
        String lastFontStyle = "";
        ModeSetting primaryMode;
        Method customSuffixMethod;
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
        // Default top-left flush position matching Sharp Cascade layout
        super("ArrayList", "Displays active modules in an animated screen-edge list",
                "A", 0.004D, 0.008D, 185.0D, true);
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
        String fStyle = fontStyle.getValue();
        boolean useMinecraftFont = "Minecraft".equalsIgnoreCase(fStyle);

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

            if (!fStyle.equals(e.lastFontStyle) || e.nameComp == null) {
                e.lastFontStyle = fStyle;
                e.nameComp = useMinecraftFont
                        ? Component.literal(e.name)
                        : Component.literal(e.name).withStyle(HudManager.getBoldStyle());
                e.lastRawMode = "\u0000"; // force suffix rebuild with matching font
                e.totalWidth = 0;
            }

            String rawMode = resolveModuleSuffix(e);
            if (!rawMode.equals(e.lastRawMode) || !sufStyle.equals(e.lastSuffixStyle)) {
                e.lastRawMode = rawMode;
                e.lastSuffixStyle = sufStyle;
                e.suffixComp = buildSuffixComponent(rawMode, sufStyle, useMinecraftFont);
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
        String bgStyle = backgroundStyle.getValue();
        boolean sharpCascade = "Sharp Cascade".equalsIgnoreCase(bgStyle);
        boolean useMinecraftFont = "Minecraft".equalsIgnoreCase(fontStyle.getValue());

        int rowH = sharpCascade ? 11 : 14;
        float gap = (sharpCascade || "Connected Dock".equalsIgnoreCase(bgStyle))
                ? 0.0F
                : Mth.clamp(rowSpacing.getValue().floatValue(), 0.0F, 4.0F);
        int padX = sharpCascade ? 3 : 4;
        String barMode = accentBar.getValue();
        int barW = "None".equalsIgnoreCase(barMode) ? 0 : (sharpCascade ? 2 : 3);

        int maxRowW = 72;
        for (int i = 0; i < visibleCount; i++) {
            int rw = sorted[i].totalWidth + padX * 2 + barW + 1;
            if (rw > maxRowW) maxRowW = rw;
        }

        float logicalH = 0.0F;
        for (int i = 0; i < visibleCount; i++) {
            logicalH += (rowH + gap) * sorted[i].heightAnim;
        }

        int boxW = Math.round(maxRowW * sc);
        int boxH = Math.max(Math.round(18 * sc), Math.round(logicalH * sc));
        boolean alignRight = getXRatio() >= 0.5F;

        int anchorX = Math.round(getXRatio() * screenW);
        int baseX = alignRight ? (anchorX - boxW) : anchorX;
        int baseY = Math.round(getYRatio() * screenH);
        baseX = Mth.clamp(baseX, 0, Math.max(0, screenW - boxW));
        baseY = Mth.clamp(baseY, 0, Math.max(0, screenH - boxH));

        this.lastBoxX = baseX;
        this.lastBoxY = baseY;
        this.lastBoxW = boxW;
        this.lastBoxH = boxH;

        int bgAlpha = getBackgroundAlphaByte();
        int baseBg = (bgAlpha << 24) | 0x101216;

        if (visibleCount == 0) {
            if (editorMode) {
                guiGraphics.fill(baseX, baseY, baseX + boxW, baseY + boxH, baseBg);
                guiGraphics.fill(baseX, baseY, baseX + 2, baseY + boxH, 0xFFFFFFFF);
                guiGraphics.drawString(font, Component.literal("ArrayList"),
                        baseX + 5, baseY + 5, 0xFFFFFFFF, true);
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
                    : (e.totalWidth + padX * 2 + barW + 1);
            int yInt = Math.round(cursorY);

            int targetLocalX = alignRight ? (maxRowW - pillW) : 0;
            int slidePx = Math.round((1.0F - ease) * (pillW + 8));
            int drawX = alignRight ? (targetLocalX + slidePx) : (targetLocalX - slidePx);

            int localY2 = Math.round(yInt + rowH * e.heightAnim + 0.5F);
            boolean useScissor = e.slideAnim < 0.98F && (localY2 > yInt);
            if (pillW > 0 && localY2 > yInt) {
                if (useScissor) {
                    guiGraphics.enableScissor(targetLocalX, yInt, targetLocalX + pillW, localY2);
                }

                int accentCol = AnimationUtil.withAlpha(
                        resolveEntryColor(e.module, i, animClockSeconds), ease);

                // Background
                if (!"Transparent".equalsIgnoreCase(bgStyle)) {
                    int rowBg = AnimationUtil.withAlpha(baseBg, ease);
                    if (sharpCascade) {
                        guiGraphics.fill(drawX, yInt, drawX + pillW, yInt + rowH, rowBg);
                    } else {
                        int radius = "Glass Pill".equalsIgnoreCase(bgStyle) ? 4 : (i == 0 || i == visibleCount - 1 ? 4 : 0);
                        HudManager.drawRoundedRect(guiGraphics, drawX, yInt, pillW, rowH, radius, rowBg);
                    }
                }

                // Accent Bar (continuous flush edge line in Sharp Cascade mode)
                if ("Outer Edge".equalsIgnoreCase(barMode)) {
                    int barX = alignRight ? (drawX + pillW - barW) : drawX;
                    if (sharpCascade) {
                        guiGraphics.fill(barX, yInt, barX + barW, yInt + rowH, accentCol);
                    } else {
                        HudManager.drawRoundedRect(guiGraphics, barX, yInt + 1, barW, rowH - 2, 1, accentCol);
                    }
                } else if ("Inner Edge".equalsIgnoreCase(barMode)) {
                    int barX = alignRight ? drawX : (drawX + pillW - barW);
                    if (sharpCascade) {
                        guiGraphics.fill(barX, yInt, barX + barW, yInt + rowH, accentCol);
                    } else {
                        HudManager.drawRoundedRect(guiGraphics, barX, yInt + 1, barW, rowH - 2, 1, accentCol);
                    }
                } else if ("Full Outline".equalsIgnoreCase(barMode)) {
                    guiGraphics.fill(drawX, yInt, drawX + pillW, yInt + 1, accentCol);
                    guiGraphics.fill(drawX, yInt + rowH - 1, drawX + pillW, yInt + rowH, accentCol);
                    guiGraphics.fill(drawX, yInt, drawX + 1, yInt + rowH, accentCol);
                    guiGraphics.fill(drawX + pillW - 1, yInt, drawX + pillW, yInt + rowH, accentCol);
                }

                // Module Name & Info Suffix
                int textX;
                if (alignRight) {
                    int rightEdge = drawX + pillW - ("Outer Edge".equalsIgnoreCase(barMode) ? (barW + padX) : padX);
                    textX = rightEdge - e.totalWidth;
                } else {
                    textX = drawX + ("Outer Edge".equalsIgnoreCase(barMode) ? (barW + padX) : padX);
                }
                int textY = sharpCascade ? (yInt + 2) : (yInt + 3);
                int nameCol = "Pure White".equalsIgnoreCase(colorMode.getValue())
                        ? AnimationUtil.withAlpha(0xFFFFFFFF, ease)
                        : accentCol;
                if ((nameCol >>> 24) > 8) {
                    guiGraphics.drawString(font, e.nameComp, textX, textY, nameCol, useMinecraftFont);
                    if (e.suffixComp != null) {
                        int tagCol = AnimationUtil.withAlpha(0xFF8C929C, ease);
                        guiGraphics.drawString(font, e.suffixComp, textX + e.nameWidth, textY, tagCol, useMinecraftFont);
                    }
                }

                if (useScissor) {
                    guiGraphics.disableScissor();
                }
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
        if ("Theme Accent".equalsIgnoreCase(cm)) {
            return ClientThemeManager.getAccentArgb();
        }
        if ("Category Color".equalsIgnoreCase(cm)) {
            ModuleCategory cat = mod.getCategory();
            if (cat == ModuleCategory.COMBAT) return 0xFFFF5C77;
            if (cat == ModuleCategory.MOVEMENT) return 0xFF38BDF8;
            if (cat == ModuleCategory.VISUAL) return 0xFFA855F7;
            return ClientThemeManager.getAccentArgb();
        }
        if ("Dynamic Rainbow".equalsIgnoreCase(cm)) {
            float hue = ((clockSec * 0.12F) + index * 0.055F) % 1.0F;
            return hsbToArgb(hue, 0.58F, 1.0F);
        }
        // Cyan-Blue Wave
        float wave = 0.5F + 0.5F * (float) Math.sin(clockSec * 2.4F - index * 0.42F);
        return AnimationUtil.lerpColor(ClientThemeManager.getAccentArgb(), 0xFF3B82F6, wave);
    }

    private static String resolveModuleSuffix(Entry e) {
        if (e.customSuffixMethod != null) {
            try {
                Object res = e.customSuffixMethod.invoke(e.module);
                if (res instanceof String s && !s.isEmpty()) {
                    return s;
                }
            } catch (Throwable ignored) {
            }
        }
        if (e.primaryMode != null && e.primaryMode.getValue() != null) {
            return e.primaryMode.getValue();
        }
        return "";
    }

    private Component buildSuffixComponent(String rawMode, String style, boolean useMinecraftFont) {
        if (rawMode == null || rawMode.isEmpty() || "Hidden".equalsIgnoreCase(style)) {
            return null;
        }
        String formatted = switch (style) {
            case "Brackets" -> " [" + rawMode + "]";
            case "Dash" -> " - " + rawMode;
            default -> " " + rawMode;
        };
        return useMinecraftFont
                ? Component.literal(formatted)
                : Component.literal(formatted).withStyle(HudManager.getRegularStyle());
    }

    private void ensurePoolInitialized() {
        if (poolReady && poolCount > 0) return;
        List<Module> modules = HudManager.getAllClientModules();
        if (modules == null || modules.isEmpty()) return;

        boolean useMinecraftFont = "Minecraft".equalsIgnoreCase(fontStyle.getValue());
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
            e.lastFontStyle = fontStyle.getValue();
            e.nameComp = useMinecraftFont
                    ? Component.literal(n)
                    : Component.literal(n).withStyle(HudManager.getBoldStyle());
            e.primaryMode = findPrimaryModeSetting(m);
            e.customSuffixMethod = findCustomSuffixMethod(m);
            e.lastEnabled = m.isEnabled();
            e.initialized = true;
            e.slideAnim = e.lastEnabled ? 1.0F : 0.0F;
            e.heightAnim = e.lastEnabled ? 1.0F : 0.0F;
            pool[poolCount++] = e;
        }
        poolReady = poolCount > 0;
    }

    private static Method findCustomSuffixMethod(Module mod) {
        for (String name : new String[]{"getArrayListSuffix", "getSuffix", "getDisplayInfo"}) {
            try {
                Method m = mod.getClass().getMethod(name);
                if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                    return m;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
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
