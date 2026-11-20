package attrium.ru.manager;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import attrium.ru.utils.render.AnimationUtil;
import attrium.ru.utils.sound.SoundUtil;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Centralized Client Appearance, Liquid Accent Theme Engine, & Mobile Controls Manager
 * (Minecraft 1.21.11 Official Mojang Mappings — Zero Per-Frame Allocations).
 *
 * Drives:
 *  - Liquid RGB color morphing across ClickGUI, ArrayList, and TargetHUD (lerp speed 12.0f)
 *  - Glass panel opacity (55% - 95%) and animated Background Dim vignette (0x00 -> 0x88)
 *  - Smooth ClickGUI matrix scale presets (0.85x, 1.0x, 1.15x, 1.30x)
 *  - Customizable ClickGUI keybind ([ RSHIFT ] default)
 *  - Draggable Floating Touch Button ("A" pill) on InGameHud for FoldCraftLauncher
 *  - Tactile UI Feedback Sounds toggle
 */
public final class ClientThemeManager {
    public static final int[] ACCENT_SWATCHES = new int[]{
            0xFF00E5FF, // 0: Aqua Cyan
            0xFFF8FAFC, // 1: Ice White
            0xFF38BDF8, // 2: Sky Blue
            0xFF10B981  // 3: Mint
    };

    public static final String[] ACCENT_SWATCH_NAMES = new String[]{
            "Aqua Cyan", "Ice White", "Sky Blue", "Mint"
    };

    public static final float[] GUI_SCALE_PRESETS = new float[]{
            0.85F, 1.00F, 1.15F, 1.30F
    };

    public static final String[] GUI_SCALE_LABELS = new String[]{
            "0.85x", "1.0x", "1.15x", "1.30x"
    };

    // Theme & Appearance state
    private static int accentIndex = 0;
    private static float currentR = 0.0F;
    private static float currentG = 229.0F;
    private static float currentB = 255.0F;
    private static float targetR = 0.0F;
    private static float targetG = 229.0F;
    private static float targetB = 255.0F;
    private static int cachedAccentArgb = 0xFF00E5FF;

    private static float panelOpacity = 0.85F; // 0.55f .. 0.95f
    private static boolean backgroundDim = true;
    private static float bgDimAnim = 0.0F;

    // Controls & Mobile state
    private static int guiScaleIndex = 1; // 1.0x default
    private static float animatedGuiScale = 1.00F;
    private static int clickGuiKey = GLFW.GLFW_KEY_RIGHT_SHIFT;

    private static boolean floatingButtonEnabled = false;
    private static float floatingButtonSize = 24.0F; // 20f .. 36f
    private static float floatingBtnXRatio = 0.92F;
    private static float floatingBtnYRatio = 0.46F;

    private static boolean uiSoundsEnabled = true;

    private ClientThemeManager() {
    }

    /**
     * Steps all liquid color morphing, background dim vignette, and GUI scale animations.
     * Strictly clamped dt <= 0.05f, zero heap allocations.
     */
    public static void tick(float dtSeconds, boolean guiOpen) {
        float dt = AnimationUtil.clampDelta(dtSeconds);
        currentR = AnimationUtil.lerp(currentR, targetR, 12.0F, dt);
        currentG = AnimationUtil.lerp(currentG, targetG, 12.0F, dt);
        currentB = AnimationUtil.lerp(currentB, targetB, 12.0F, dt);

        int r = Mth.clamp(Math.round(currentR), 0, 255);
        int g = Mth.clamp(Math.round(currentG), 0, 255);
        int b = Mth.clamp(Math.round(currentB), 0, 255);
        cachedAccentArgb = 0xFF000000 | (r << 16) | (g << 8) | b;

        float dimTarget = (guiOpen && backgroundDim) ? 1.0F : 0.0F;
        bgDimAnim = AnimationUtil.lerp(bgDimAnim, dimTarget, 10.0F, dt);

        float scaleTarget = GUI_SCALE_PRESETS[Mth.clamp(guiScaleIndex, 0, GUI_SCALE_PRESETS.length - 1)];
        animatedGuiScale = AnimationUtil.lerp(animatedGuiScale, scaleTarget, 14.0F, dt);
    }

    public static int getAccentArgb() {
        return cachedAccentArgb;
    }

    public static int getAccentRgb() {
        return cachedAccentArgb & 0x00FFFFFF;
    }

    public static int getAccentWithAlpha(float alpha01) {
        int a = Mth.clamp(Math.round(alpha01 * 255.0F), 0, 255);
        return (a << 24) | (cachedAccentArgb & 0x00FFFFFF);
    }

    public static int getAccentIndex() {
        return accentIndex;
    }

    public static void setAccentIndex(int idx) {
        int clamped = Mth.clamp(idx, 0, ACCENT_SWATCHES.length - 1);
        accentIndex = clamped;
        int col = ACCENT_SWATCHES[clamped];
        targetR = (float) ((col >>> 16) & 0xFF);
        targetG = (float) ((col >>> 8) & 0xFF);
        targetB = (float) (col & 0xFF);
    }

    public static float getPanelOpacity() {
        return panelOpacity;
    }

    public static void setPanelOpacity(float opacity) {
        panelOpacity = Mth.clamp(opacity, 0.55F, 0.95F);
    }

    public static int getWindowBgColor() {
        int a = Mth.clamp(Math.round(panelOpacity * 255.0F), 140, 245);
        return (a << 24) | 0x101218;
    }

    public static int getBentoCardBgColor(float cardAlpha) {
        int baseA = Mth.clamp(Math.round(panelOpacity * 240.0F * cardAlpha), 0, 240);
        return (baseA << 24) | 0x111318;
    }

    public static boolean isBackgroundDimEnabled() {
        return backgroundDim;
    }

    public static void setBackgroundDimEnabled(boolean enabled) {
        backgroundDim = enabled;
    }

    public static int getAnimatedBackdropDimColor() {
        int a = Mth.clamp(Math.round(bgDimAnim * 136.0F), 0, 136); // 0x00 -> 0x88
        return (a << 24);
    }

    public static void resetBackdropAnimOnOpen() {
        bgDimAnim = 0.0F;
    }

    public static int getGuiScaleIndex() {
        return guiScaleIndex;
    }

    public static void setGuiScaleIndex(int idx) {
        guiScaleIndex = Mth.clamp(idx, 0, GUI_SCALE_PRESETS.length - 1);
    }

    public static float getAnimatedGuiScale() {
        return Mth.clamp(animatedGuiScale, 0.75F, 1.40F);
    }

    public static int getClickGuiKey() {
        return clickGuiKey;
    }

    public static void setClickGuiKey(int key) {
        // Allow keyboard keys or mouse buttons Middle..8 (<= -102); never allow LMB (-100) or RMB (-101)
        if (key <= -102) {
            clickGuiKey = key;
        } else if (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST) {
            clickGuiKey = key;
        } else {
            clickGuiKey = GLFW.GLFW_KEY_RIGHT_SHIFT;
        }
    }

    public static boolean isFloatingButtonEnabled() {
        return floatingButtonEnabled;
    }

    public static void setFloatingButtonEnabled(boolean enabled) {
        floatingButtonEnabled = enabled;
    }

    public static float getFloatingButtonSize() {
        return floatingButtonSize;
    }

    public static void setFloatingButtonSize(float size) {
        floatingButtonSize = Mth.clamp(size, 20.0F, 36.0F);
    }

    public static float getFloatingBtnXRatio() {
        return floatingBtnXRatio;
    }

    public static float getFloatingBtnYRatio() {
        return floatingBtnYRatio;
    }

    public static void setFloatingBtnPosRatio(float xRatio, float yRatio) {
        floatingBtnXRatio = Mth.clamp(xRatio, 0.01F, 0.96F);
        floatingBtnYRatio = Mth.clamp(yRatio, 0.02F, 0.94F);
    }

    public static boolean isUiSoundsEnabled() {
        return uiSoundsEnabled;
    }

    public static void setUiSoundsEnabled(boolean enabled) {
        uiSoundsEnabled = enabled;
        try {
            SoundUtil.UI_VOLUME_SETTING.setValue(enabled ? 1.0D : 0.0D);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Smoothly resets all HUD widget coordinates, GUI scale (1.0x), and floating touch button position.
     */
    public static void resetLayout() {
        HudManager.resetAllPositions();
        guiScaleIndex = 1;
        panelOpacity = 0.85F;
        floatingBtnXRatio = 0.92F;
        floatingBtnYRatio = 0.46F;
    }

    /**
     * Returns the single unified Attrium config file path inside Fabric's default config folder:
     * <gameDir>/config/acc.json
     */
    public static Path getUnifiedConfigPath() {
        try {
            Class<?> fl = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object inst = fl.getMethod("getInstance").invoke(null);
            Object cfgDir = fl.getMethod("getConfigDir").invoke(inst);
            if (cfgDir instanceof Path p) {
                return p.resolve("acc.json");
            }
        } catch (Throwable ignored) {
        }
        Minecraft mc = Minecraft.getInstance();
        Path base = (mc != null && mc.gameDirectory != null) ? mc.gameDirectory.toPath() : Path.of(".");
        return base.resolve("config").resolve("acc.json");
    }

    /**
     * Opens Fabric's default config directory (where acc.json lives) safely via Minecraft's Util helper.
     */
    public static void openConfigFolder() {
        try {
            Path cfgDirPath = getUnifiedConfigPath().getParent();
            File attriumDir = (cfgDirPath != null) ? cfgDirPath.toFile() : new File("config");
            if (!attriumDir.exists()) {
                attriumDir.mkdirs();
            }
            Class<?> utilCls = Class.forName("net.minecraft.Util");
            Method getPlatform = null;
            for (Method m : utilCls.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && (m.getName().equals("getPlatform") || m.getName().equals("getOperatingSystem"))) {
                    m.setAccessible(true);
                    getPlatform = m;
                    break;
                }
            }
            if (getPlatform != null) {
                Object os = getPlatform.invoke(null);
                if (os != null) {
                    try {
                        Method openPath = os.getClass().getMethod("openPath", Path.class);
                        openPath.invoke(os, attriumDir.toPath());
                        return;
                    } catch (Throwable ignored) {
                    }
                    try {
                        Method openFile = os.getClass().getMethod("openFile", File.class);
                        openFile.invoke(os, attriumDir);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
