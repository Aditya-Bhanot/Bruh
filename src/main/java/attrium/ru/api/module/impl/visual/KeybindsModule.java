package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.module.Module;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.manager.HudManager;
import attrium.ru.screens.GlassClickGuiScreen;
import attrium.ru.utils.render.AnimationUtil;

import java.util.List;
import java.util.Locale;

/**
 * Standalone Keybinds HUD Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Features:
 *  - Keycap Style: Glowing Pill (#00E5FF ON / 0x2AFFFFFF OFF), Outline Badge, Clean Brackets
 *  - Filter Mode: All Bound or Active Only
 *  - Show Header toggle ("Keybinds" + subtle 1px 0x22FFFFFF divider)
 *  - Smooth lerped container height clipped with GuiGraphics#enableScissor
 */
public class KeybindsModule extends HudModule {
    private static volatile KeybindsModule instance;

    private final ModeSetting keycapStyle = register(new ModeSetting(
            "Keycap Style",
            "Visual appearance of the keybind badge on each row.",
            "Glowing Pill",
            "Glowing Pill", "Outline Badge", "Clean Brackets"
    ));

    private final ModeSetting filterMode = register(new ModeSetting(
            "Filter Mode",
            "Show all bound modules or only bound modules that are currently active.",
            "All Bound",
            "All Bound", "Active Only"
    ));

    private final BooleanSetting showHeader = register(new BooleanSetting(
            "Show Header",
            "Renders the Keybinds title header with a subtle divider line.",
            true
    ));

    private static final int MAX_ENTRIES = 64;
    private static final class Entry {
        Module module;
        String name = "";
        Component nameComp;
        int keyCode = -1;
        String lastStyle = "";
        Component keyComp;
        int cachedKeyWidth = 18;
        float activeAnim = 0.0F;
    }

    private final Entry[] pool = new Entry[MAX_ENTRIES];
    private float animatedHeight = 26.0F;
    private Component headerComp = null;

    public KeybindsModule() {
        super("Keybinds", "Lists bound modules and their live toggle states",
                "K", 0.015D, 0.080D, 204.0D, true);
        instance = this;
        for (int i = 0; i < MAX_ENTRIES; i++) {
            pool[i] = new Entry();
        }
    }

    public static KeybindsModule getInstance() {
        return instance;
    }

    @Override
    public void renderHud(Minecraft mc, GuiGraphics guiGraphics, Font font,
                          int screenW, int screenH, float dt, float animClockSeconds,
                          boolean previewMode, boolean editorMode) {
        if (headerComp == null) {
            headerComp = Component.literal("Keybinds").withStyle(HudManager.getBoldStyle());
        }

        GlassClickGuiScreen gui = GlassClickGuiScreen.getInstance();
        List<Module> modules = HudManager.getAllClientModules();
        boolean activeOnly = "Active Only".equalsIgnoreCase(filterMode.getValue());
        String kStyle = keycapStyle.getValue();

        int count = 0;
        if (modules != null) {
            for (int i = 0; i < modules.size() && count < MAX_ENTRIES; i++) {
                Module m = modules.get(i);
                if (m == null) continue;
                int key = gui.getBoundKeyForModule(m);
                if (key == -1 || key == 0 || key == GLFW.GLFW_KEY_UNKNOWN) continue;
                if (activeOnly && !m.isEnabled()) continue;

                Entry e = pool[count++];
                e.module = m;
                String n = m.getName() != null ? m.getName() : m.getClass().getSimpleName();
                if (!n.equals(e.name) || e.nameComp == null) {
                    e.name = n;
                    String clipped = n.length() > 12 ? n.substring(0, 12) : n;
                    e.nameComp = Component.literal(clipped).withStyle(HudManager.getRegularStyle());
                }
                if (key != e.keyCode || !kStyle.equals(e.lastStyle) || e.keyComp == null) {
                    e.keyCode = key;
                    e.lastStyle = kStyle;
                    String raw = HudManager.formatCompactKeyLabel(key);
                    String disp = "Clean Brackets".equalsIgnoreCase(kStyle) ? ("[" + raw + "]") : raw;
                    e.keyComp = Component.literal(disp).withStyle(HudManager.getBoldStyle());
                    e.cachedKeyWidth = Math.max(16, font.width(e.keyComp) + ("Clean Brackets".equalsIgnoreCase(kStyle) ? 4 : 8));
                }
                e.activeAnim = AnimationUtil.lerp(e.activeAnim, m.isEnabled() ? 1.0F : 0.0F, 13.0F, dt);
            }
        }

        boolean headerOn = Boolean.TRUE.equals(showHeader.getValue());
        int headerH = headerOn ? 22 : 4;
        int rowH = 17;
        float targetH = (count == 0)
                ? (editorMode ? (headerH + 20.0F) : (headerOn ? 24.0F : 8.0F))
                : (headerH + 4 + count * rowH + 4);
        animatedHeight = AnimationUtil.lerp(animatedHeight, targetH, 13.0F, dt);

        float sc = getHudScale();
        int cardW = 122;
        int cardH = Math.round(animatedHeight);
        int boxW = Math.round(cardW * sc);
        int boxH = Math.round(cardH * sc);

        int baseX = Mth.clamp(Math.round(getXRatio() * screenW), 2, Math.max(2, screenW - boxW - 2));
        int baseY = Mth.clamp(Math.round(getYRatio() * screenH), 2, Math.max(2, screenH - boxH - 2));

        this.lastBoxX = baseX;
        this.lastBoxY = baseY;
        this.lastBoxW = boxW;
        this.lastBoxH = boxH;

        if (count == 0 && !editorMode) {
            return;
        }

        int cardBg = (getBackgroundAlphaByte() << 24) | 0x111318;

        guiGraphics.enableScissor(baseX, baseY, baseX + boxW, baseY + boxH);
        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate(baseX, baseY);
        guiGraphics.pose().scale(sc, sc);

        HudManager.drawRoundedRect(guiGraphics, 0, 0, cardW, cardH, 8, cardBg);

        if (headerOn) {
            guiGraphics.drawString(font, headerComp, 9, 7, 0xFFFFFFFF, false);
            guiGraphics.fill(8, headerH - 1, cardW - 8, headerH, 0x22FFFFFF);
        }

        if (count == 0 && editorMode) {
            guiGraphics.drawString(font, Component.literal("No keys bound").withStyle(HudManager.getRegularStyle()),
                    9, headerH + 6, 0xFF9CA3AF, false);
        } else {
            int y = headerH + 4;
            for (int i = 0; i < count; i++) {
                Entry e = pool[i];
                int nameCol = AnimationUtil.lerpColor(0xFFD1D5DB, 0xFFFFFFFF, e.activeAnim);
                guiGraphics.drawString(font, e.nameComp, 9, y + 3, nameCol, false);

                int pillW = e.cachedKeyWidth;
                int pillH = 13;
                int pillX = cardW - 8 - pillW;
                int pillY = y + 1;
                int kw = font.width(e.keyComp);

                if ("Clean Brackets".equalsIgnoreCase(kStyle)) {
                    int txtCol = AnimationUtil.lerpColor(0xFF9CA3AF, AnimationUtil.ACCENT_AQUA_CYAN, e.activeAnim);
                    guiGraphics.drawString(font, e.keyComp, pillX + (pillW - kw) / 2, pillY + 3, txtCol, false);
                } else if ("Outline Badge".equalsIgnoreCase(kStyle)) {
                    int borderCol = AnimationUtil.lerpColor(0x44FFFFFF, AnimationUtil.ACCENT_AQUA_CYAN, e.activeAnim);
                    guiGraphics.fill(pillX, pillY, pillX + pillW, pillY + 1, borderCol);
                    guiGraphics.fill(pillX, pillY + pillH - 1, pillX + pillW, pillY + pillH, borderCol);
                    guiGraphics.fill(pillX, pillY, pillX + 1, pillY + pillH, borderCol);
                    guiGraphics.fill(pillX + pillW - 1, pillY, pillX + pillW, pillY + pillH, borderCol);
                    int txtCol = AnimationUtil.lerpColor(0xFFE5E7EB, AnimationUtil.ACCENT_AQUA_CYAN, e.activeAnim);
                    guiGraphics.drawString(font, e.keyComp, pillX + (pillW - kw) / 2, pillY + 3, txtCol, false);
                } else {
                    // Glowing Pill
                    int pillBg = AnimationUtil.lerpColor(0x2AFFFFFF, AnimationUtil.ACCENT_AQUA_CYAN, e.activeAnim);
                    int txtCol = AnimationUtil.lerpColor(0xFFE5E7EB, 0xFF0B0D10, e.activeAnim);
                    HudManager.drawRoundedRect(guiGraphics, pillX, pillY, pillW, pillH, 4, pillBg);
                    guiGraphics.drawString(font, e.keyComp, pillX + (pillW - kw) / 2, pillY + 3, txtCol, false);
                }

                y += rowH;
            }
        }

        guiGraphics.pose().popMatrix();
        guiGraphics.disableScissor();
    }
}
