package attrium.ru.screens;


import attrium.ru.manager.AutoUpdater;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.GsonBuilder;
import com.google.gson.Gson;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import attrium.ru.api.module.Module;
import attrium.ru.manager.FriendManager;
import attrium.ru.api.module.impl.visual.HudModule;
import attrium.ru.manager.ClientThemeManager;
import attrium.ru.manager.HudManager;
import attrium.ru.utils.render.AnimationUtil;
import attrium.ru.manager.FriendManager.FriendRecord;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.module.impl.combat.MaceTarget;
import attrium.ru.api.module.impl.combat.SilentAura;
import attrium.ru.api.module.impl.movement.ElytraTarget;
import attrium.ru.api.module.impl.movement.LungeHelper;
import attrium.ru.api.settings.Setting;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.MultiModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.api.settings.impl.StringSetting;
import attrium.ru.utils.sound.SoundUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Attrium ClickGUI: Isolated Regular Nunito Typography (`nunito_regular.ttf`),
 * Action-Macro AutoLunge Support (Hides ON/OFF Toggle Pill, Keybind-Only Trigger),
 * Type-Safe Keybinds, World-Join Null Guards, and Tactile UI Volume Mixer.
 */
public class GlassClickGuiScreen extends Screen {
    private float updCheckHoverAnim = 0.0F;
    private float updRestartHoverAnim = 0.0F;
    private float updPulsePhase = 0.0F;

    private float updChangelogRevealAnim = 0.0F;

    private static GlassClickGuiScreen instance;

    // =========================================================================
    // Premium Outline-Free Glassmorphism Palette
    // =========================================================================
    public static final int COLOR_WINDOW_BG = 0xD9101218;           // Main 16px rounded window background
    private static final int COLOR_SCREEN_BACKDROP = 0x6007080C;
    private static final int COLOR_TOP_NAV_BG = 0xD0171A24;
    private static final int COLOR_TOP_NAV_ACTIVE_PILL = 0xFF282C3C;

    private static final int COLOR_SEARCH_BG = 0xE0171923;
    private static final int COLOR_SEARCH_BG_FOCUSED = 0xEE1F2230;

    // Category Pills: Solid White active w/ dark text; Transparent inactive w/ white text
    private static final int COLOR_PILL_ACTIVE_BG = 0xFFFFFFFF;
    private static final int COLOR_PILL_ACTIVE_TEXT = 0xFF11131A;
    private static final int COLOR_PILL_INACTIVE_BG = 0x00191C26;
    private static final int COLOR_PILL_INACTIVE_HOVER = 0x2EFFFFFF;

    // Module Rows: Transparent unless hovered; subtle dark 6px rounded rect on hover
    private static final int COLOR_ROW_EVEN_BG = 0x00151720;
    private static final int COLOR_ROW_ODD_BG = 0x00181B26;
    private static final int COLOR_ROW_IDLE_BG = 0x99161922;
    private static final int COLOR_ROW_HOVER_BG = 0xCC1E2330;
    private static final int COLOR_ACCENT_CYAN = 0xFF00E5FF;
    private static final int COLOR_TEXT_OFF_SLATE = 0xFF9CA3AF;
    private static final int COLOR_TEXT_DESC_PREMIUM = 0xFF8B94A5;
    private static final int COLOR_ICON_BOX_BG = 0xE0181B26;
    private static final int COLOR_ICON_BOX_HOVER_BG = 0xEE232738;
    private static final int COLOR_DRAWER_BG = 0xC8131620;

    public static final int COLOR_TEXT_BRIGHT_WHITE = 0xFFFFFFFF;   // Module Names & Category Tabs (0xFFFFFF)
    public static final int COLOR_TEXT_DIMMED_GRAY = 0xFFAAAAAA;    // Module Descriptions & Bind Text (0xAAAAAA)

    private static final int COLOR_BIND_BG = 0xCC1E212E;
    private static final int COLOR_BIND_BG_HOVER = 0xEE2C3044;
    private static final int COLOR_BIND_LISTENING = 0xFF4E4C78;

    // iOS Pill Toggle (24x12, Radius 6): Gray to Accent/White track with solid white circle knob
    private static final int COLOR_TOGGLE_ON_BG = 0xFF00E5FF;
    private static final int COLOR_TOGGLE_OFF_BG = 0xFF2A2D3C;
    private static final int COLOR_TOGGLE_KNOB_ON = 0xFFFFFFFF;
    private static final int COLOR_TOGGLE_KNOB_OFF = 0xFFFFFFFF;

    // =========================================================================
    // Layout & Radius Constants
    // =========================================================================
    private static final int WINDOW_CORNER_RADIUS = 14;             // 16px Main Container Radius
    private static final int SEARCH_BAR_RADIUS = 10;                // 10px Search Bar Radius
    private static final int CATEGORY_PILL_RADIUS = 10;             // Full Pill Radius for Categories
    private static final int ICON_BOX_RADIUS = 8;                   // 8px Module Icon Square Radius
    private static final int ROW_AND_SEARCH_RADIUS = 8;             // 6px Hovered Row Radius
    private static final int TOGGLE_PILL_WIDTH = 24;                // 24px iOS Pill Switch Width
    private static final int TOGGLE_PILL_HEIGHT = 12;               // 12px iOS Pill Switch Height
    private static final int TOGGLE_PILL_RADIUS = 6;                // 6px iOS Pill Switch Radius
    private static final int MAX_PRECOMPUTED_RADIUS = 20;

    private static final int BASE_WINDOW_WIDTH = 600;
    private static final int BASE_WINDOW_HEIGHT = 390;
    private static final int HEADER_HEIGHT = 76;
    private static final int ROW_HEIGHT = 44;
    private static final int ROW_GAP = 4;                           // Flush module rows (0px gap)
    private static final int SETTING_ROW_HEIGHT = 26;
    private static final int LIST_SIDE_PADDING = 18;

    private static final String[] TOP_TABS = new String[]{
            "Modules", "Configs", "HUD", "Friends", "Client"
    };

    private static final String[] CATEGORY_PILLS = new String[]{
            "All", "Combat", "Movement", "Render", "Player", "World", "Misc"
    };

    // =========================================================================
    // Pre-Computed Sub-Pixel Anti-Aliased Corner Tables (Radii 1..14)
    // =========================================================================
    private static final int[][] AA_SOLID_INSET = new int[MAX_PRECOMPUTED_RADIUS + 1][MAX_PRECOMPUTED_RADIUS];
    private static final float[][] AA_EDGE_COVERAGE = new float[MAX_PRECOMPUTED_RADIUS + 1][MAX_PRECOMPUTED_RADIUS];

    static {
        for (int r = 2; r <= MAX_PRECOMPUTED_RADIUS; r++) {
            for (int dy = 0; dy < r; dy++) {
                double yCenter = (r - 0.5D) - dy;
                double exactSpan = Math.sqrt(Math.max(0.0D, (double) (r * r) - yCenter * yCenter));
                double exactInset = (double) r - exactSpan;
                int solidInset = (int) Math.ceil(exactInset);
                float edgeAlpha = (float) Mth.clamp(1.0D - (exactInset - Math.floor(exactInset)), 0.15D, 0.95D);
                AA_SOLID_INSET[r][dy] = Math.max(0, solidInset);
                AA_EDGE_COVERAGE[r][dy] = edgeAlpha;
            }
        }
    }

    // =========================================================================
    // Strictly Isolated Nunito Regular Font Style (No Bold Style!)
    // =========================================================================
    private static Style nunitoRegularStyle = Style.EMPTY;
    private static Style nunitoBoldStyle = Style.EMPTY;
    private static boolean nunitoStylesInitialized = false;

    // =========================================================================
    // Pre-Allocated 2-Pass Batch Queues (Pass 1: Anti-Aliased Rounded Quads -> Pass 2: Nunito Text)
    // =========================================================================
    private static final int MAX_BATCHED_QUADS = 6144;
    private static final int MAX_BATCHED_TEXTS = 512;

    private final int[] bgQuadQueue = new int[MAX_BATCHED_QUADS * 5];
    private int bgQuadCount = 0;

    private final Component[] textComponentQueue = new Component[MAX_BATCHED_TEXTS];
    private final int[] textCoordsAndColorQueue = new int[MAX_BATCHED_TEXTS * 3];
    private int textQueueCount = 0;

    // =========================================================================
    // Cached Reflection & Chained GLFW Input Handles
    // =========================================================================
    private static Field cachedModuleKeyField = null;
    private static Method cachedModuleGetKeyMethod = null;
    private static Method cachedModuleSetKeyMethod = null;
    private static boolean moduleKeyReflectionInitialized = false;

    private static long hookedWindowHandle = 0L;
    private static GLFWKeyCallback prevKeyCallback = null;
    private static GLFWCharCallback prevCharCallback = null;
    private GLFWMouseButtonCallback prevMouseButtonCallback;
    private volatile boolean pendingLeftClickFromCallback = false;
    private volatile boolean pendingRightClickFromCallback = false;
    private static final Gson CONFIG_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile boolean configLoadedOnce = false;
    private static final java.util.Map<String, Integer> PERSISTENT_MODULE_BINDS = new java.util.concurrent.ConcurrentHashMap<>();
    private char lastKeyAppendedChar = 0;
    private long lastKeyAppendTimeNs = 0L;

    // =========================================================================
    // Dirty-Flag Cached Structures
    // =========================================================================
    private static final class CachedSettingEntry {
        final Setting<?> setting;
        final String name;
        final Component nameComponent;
        final int type; // 0 = Boolean, 1 = Number, 2 = Mode, 3 = MultiMode
        final double step;
        final String[] modeOptions;
        final Component[] modeOptionComponents;
        final int[] modePillWidths;

        int cachedNameWidth = 0;
        boolean visible = true;
        float toggleAnim = 0.0F;

        double lastCachedNumberValue = Double.NaN;
        String cachedFormattedValue = "";
        Component cachedValueComponent = Component.empty();
        int cachedValueTextWidth = 0;

        CachedSettingEntry(
                Setting<?> setting,
                String name,
                int type,
                double step,
                String[] modeOptions
        ) {
            this.setting = setting;
            this.name = (name != null && !name.isEmpty()) ? name : "Setting";
            this.nameComponent = Component.literal(this.name).withStyle(nunitoRegularStyle);
            this.type = type;
            this.step = step > 0.0D ? step : 0.1D;
            this.modeOptions = modeOptions != null ? modeOptions : new String[0];
            this.modeOptionComponents = new Component[this.modeOptions.length];
            for (int i = 0; i < this.modeOptions.length; i++) {
                String opt = this.modeOptions[i] != null ? this.modeOptions[i] : "";
                this.modeOptionComponents[i] = Component.literal(opt).withStyle(nunitoRegularStyle);
            }
            this.modePillWidths = new int[this.modeOptions.length];
            if (type == 0 && setting instanceof BooleanSetting bs) {
                Object raw = ((Setting<?>) bs).getValue();
                this.toggleAnim = (raw instanceof Boolean b && b) ? 1.0F : 0.0F;
            }
        }
    }

    private static final class CachedModuleRow {
        final Module module;
        final String name;
        final String nameLower;
        final String description;
        final String descriptionLower;
        final Component nunitoTitle;
        final Component nunitoRegularDesc;
        final int categoryPillIndex;
        final CachedSettingEntry[] settings;

        boolean expanded = false;
        boolean wasHoveredLastFrame = false;
        float hoverAnim = 0.0F;
        float expandAnim = 0.0F;
        float animatedDrawerHeight = 0.0F;
        float toggleAnim = 0.0F;
        float bindHoverAnim = 0.0F;

        int cachedYOffset = 0;
        int cachedTotalHeight = ROW_HEIGHT;
        int cachedVisibleSettingCount = 0;

        int boundKey = -1;
        boolean wasKeyDownInGame = false;
        long lastBindTriggerMs = 0L;

        String cachedBindText = "NONE";
        Component cachedBindComponent = Component.literal("NONE");
        int cachedBindTextWidth = 24;
        int cachedBindPillWidth = 34;
        int lastObservedKey = Integer.MIN_VALUE;

        CachedModuleRow(
                Module module,
                String name,
                String description,
                int categoryPillIndex,
                CachedSettingEntry[] settings,
                int initialKey
        ) {
            this.module = module;
            this.name = (name != null && !name.isEmpty()) ? name : "Module";
            this.nameLower = this.name.toLowerCase(Locale.ROOT);
            this.description = resolvePremiumDescription(this.name, description);
            this.descriptionLower = this.description.toLowerCase(Locale.ROOT);
            this.nunitoTitle = Component.literal(this.name).withStyle(nunitoRegularStyle);
            this.nunitoRegularDesc = Component.literal(this.description).withStyle(nunitoRegularStyle);
            this.categoryPillIndex = categoryPillIndex;
            this.settings = settings != null ? settings : new CachedSettingEntry[0];
            this.toggleAnim = module.isEnabled() ? 1.0F : 0.0F;
            this.boundKey = (initialKey == 0 || initialKey == GLFW.GLFW_KEY_UNKNOWN) ? -1 : initialKey;
            this.cachedBindComponent = Component.literal("NONE").withStyle(nunitoRegularStyle);
        }
    }

    private CachedModuleRow[] allModuleRows = new CachedModuleRow[0];
    private CachedModuleRow[] filteredRows = new CachedModuleRow[0];
    private int filteredRowCount = 0;
    private boolean moduleRegistryInitialized = false;

    private CachedSettingEntry clientTabUiVolumeEntry;

    private boolean windowGeometryDirty = true;
    private boolean stringWidthsDirty = true;
    private boolean filterDirty = true;
    private boolean layoutDirty = true;

    private int lastScreenWidth = -1;
    private int lastScreenHeight = -1;

    private int activeTopTabIndex = 0;
    private int activeCategoryIndex = 0;
    private int navContainerX, navContainerY, navContainerW, navContainerH;
    private final int[] topTabX = new int[TOP_TABS.length];
    private final int[] topTabW = new int[TOP_TABS.length];
    private final int[] topTabTextX = new int[TOP_TABS.length];
    private final float[] topTabHoverAnim = new float[TOP_TABS.length];
    private final Component[] topTabComponents = new Component[TOP_TABS.length];

    private final int[] categoryPillX = new int[CATEGORY_PILLS.length];
    private final int[] categoryPillW = new int[CATEGORY_PILLS.length];
    private final int[] categoryPillTextX = new int[CATEGORY_PILLS.length];
    private final float[] categoryPillHoverAnim = new float[CATEGORY_PILLS.length];
    private final Component[] categoryPillComponents = new Component[CATEGORY_PILLS.length];

    private boolean searchFocused = false;
    private String searchQuery = "";
    private String searchQueryLower = "";
    private Component cachedSearchDisplayComponent = Component.literal("Search");
    private String lastRenderedSearchString = "";
    private float searchFocusAnim = 0.0F;
    private final Component[] cachedSecondaryTabComponents = new Component[TOP_TABS.length];
    private final int[] cachedSecondaryTabMessageWidths = new int[TOP_TABS.length];

    private Component chevronCollapsedComponent;
    private Component chevronExpandedComponent;
    private Component bindDotsComponent;
    private Component clearXComponent;
    private Component backspaceLtComponent;
    private Component slashComponent;
    private Component clientAudioHeaderComponent;

    private float scrollOffset = 0.0F;
    private float targetScrollOffset = 0.0F;
    private float maxScrollOffset = 0.0F;
    private int totalContentHeight = 0;
    private boolean draggingScrollbar = false;
    private float scrollbarDragStartMouseY = 0.0F;
    private float scrollbarDragStartOffset = 0.0F;

    private CachedModuleRow bindingRow = null;
    private boolean waitingForBindRelease = false;
    private CachedSettingEntry activeDraggingSliderEntry = null;
    private int activeSliderTrackX = 0;
    private int activeSliderTrackW = 1;

    private boolean wasLeftMouseDown = false;
    private long lastClickTime = 0L;
    private float topTabIndicatorX = -1.0F;
    private float topTabIndicatorW = 0.0F;
    private float catPillIndicatorX = -1.0F;
    private float catPillIndicatorW = 0.0F;
    private float scrollbarFadeAnim = 0.0F;
    private long lastScrollActivityMs = 0L;
    private Component emptySearchComponent = null;
    private float clientTabOpenTime = 0.0F;
    private float hudTabOpenTime = 0.0F;
    private int hudResetBtnX, hudResetBtnY, hudResetBtnW, hudResetBtnH;
    private Component hudHeaderTitleComp = null;
    private Component hudEditLayoutComp = null;
    private Component hudResetAllComp = null;
    private Component hudMovePillComp = null;
    private int hudCachedActiveCount = -1;
    private int hudCachedTotalCount = -1;
    private Component hudCountPillComp = null;
    private int lastObservedTopTab = -1;
    private final float[] swatchRingAnim = new float[]{1.0F, 0.0F, 0.0F, 0.0F};
    private float bgDimToggleAnim = 1.0F;
    private float floatingBtnToggleAnim = 0.0F;
    private float uiSoundsToggleAnim = 1.0F;
    private float guiScalePillX = -1.0F;
    private float guiScalePillW = 0.0F;
    private boolean listeningForGuiKeybind = false;
    private static boolean wasClickGuiKeyDownInGame = false;
    private static long lastClickGuiToggleMs = 0L;
    private boolean draggingPanelOpacitySlider = false;
    private boolean draggingFloatingSizeSlider = false;
    private int clientOpacitySliderX = 0;
    private int clientOpacitySliderW = 1;
    private int clientFloatSizeSliderX = 0;
    private int clientFloatSizeSliderW = 1;
    private Component bentoBrandComp = null;
    private Component bentoVerComp = null;
    private String bentoCachedConfigStr = "";
    private Component bentoActiveCfgComp = null;
    private Component bentoOpenFolderComp = null;
    private Component bentoResetLayoutComp = null;
    private Component bentoAppearanceTitleComp = null;
    private Component bentoAccentLabelComp = null;
    private Component bentoOpacityLabelComp = null;
    private int bentoCachedOpacityPct = -1;
    private Component bentoOpacityValComp = null;
    private Component bentoBgDimLabelComp = null;
    private Component bentoControlsTitleComp = null;
    private Component bentoScaleLabelComp = null;
    private final Component[] bentoScaleOptComps = new Component[4];
    private Component bentoKeybindLabelComp = null;
    private int bentoCachedGuiKey = -999;
    private Component bentoGuiKeyValComp = null;
    private Component bentoGuiKeyListenComp = null;
    private Component bentoFloatingLabelComp = null;
    private int bentoCachedFloatSize = -1;
    private Component bentoFloatSizeValComp = null;
    private Component bentoSoundsLabelComp = null;
    private boolean wasRightMouseDown = false;
    private long lastFrameTimeNs = 0L;

    private int winX, winY, winW, winH;
    private int listLeft, listTop, listRight, listBottom, listHeight, rowWidth;
    private int searchBoxX, searchBoxY, searchBoxW, searchBoxH;
    private int searchClearBtnX, searchBackspaceBtnX, searchBtnY, searchSlashBadgeX;

    // Friends Tab State (Index 3 — Replaced "Theme" Tab)
    private boolean friendInputFocused = false;
    private CachedSettingEntry activeStringSettingEntry = null;
    private String friendInputText = "";


    // HUD Tab & Layout Editor State (activeTopTabIndex == 2)
    private float hudScrollOffset = 0.0F;
    private float hudTargetScrollOffset = 0.0F;
    private float hudMaxScrollOffset = 0.0F;
    private int hudEditLayoutBtnX, hudEditLayoutBtnY, hudEditLayoutBtnW, hudEditLayoutBtnH;
    private int hudDraggingWidgetIndex = -1;
    private int hudDraggingSliderType = -1; // 0 = Background Opacity, 1 = Scale
    private int hudDraggingTrackX = 0;
    private int hudDraggingTrackW = 0;

    // Configs Tab State (activeTopTabIndex == 1)
    private String configInputText = "";
    private boolean configInputFocused = false;
    private String lastRenderedConfigInputString = "";
    private Component cachedConfigInputComponent = Component.empty();
    private int configInputBoxX = 0, configInputBoxY = 0, configInputBoxW = 0, configInputBoxH = 0;
    private int configSaveBtnX = 0, configSaveBtnY = 0, configSaveBtnW = 0, configSaveBtnH = 0;
    private int configReloadBtnX = 0, configReloadBtnY = 0, configReloadBtnW = 0, configReloadBtnH = 0;
    private float configScrollOffset = 0.0F;
    private float configTargetScrollOffset = 0.0F;
    private float configMaxScrollOffset = 0.0F;
    private String activeConfigName = "default";
    private String configStatusMessage = "";
    private long configStatusExpireMs = 0L;
    private final List<String> cachedConfigNames = new ArrayList<>();
    private long lastConfigListRefreshMs = 0L;
    private Component cachedFriendInputComponent = Component.literal("Enter username...");
    private String lastRenderedFriendInputString = "";
    private boolean listeningForFriendBind = false;
    private float friendScrollOffset = 0.0F;
    private float friendTargetScrollOffset = 0.0F;
    private float friendMaxScrollOffset = 0.0F;
    private int friendInputBoxX, friendInputBoxY, friendInputBoxW, friendInputBoxH;
    private int friendAddBtnX, friendAddBtnY, friendAddBtnW, friendAddBtnH;
    private int friendBindBtnX, friendBindBtnY, friendBindBtnW, friendBindBtnH;

    public GlassClickGuiScreen() {
        super(Component.literal("ClickGUI"));
        instance = this;
        initIsolatedNunitoStylesOnce();
        this.clientTabUiVolumeEntry = new CachedSettingEntry(
                SoundUtil.UI_VOLUME_SETTING,
                "UI Volume",
                1,
                0.05D,
                new String[0]
        );
    }

    public static GlassClickGuiScreen getInstance() {
        if (instance == null) {
            instance = new GlassClickGuiScreen();
        }
        return instance;
    }

    public static void openClickGuiFromBind(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClickGuiToggleMs < 180L) {
            return;
        }
        lastClickGuiToggleMs = now;
        wasClickGuiKeyDownInGame = true;
        GlassClickGuiScreen gui = getInstance();
        mc.setScreen(gui);
    }

    private void syncClickGuiKeyToBindings(int key) {
        int normalized = (key < -1 || (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST))
                ? key : GLFW.GLFW_KEY_RIGHT_SHIFT;
        if (allModuleRows != null) {
            for (int i = 0; i < allModuleRows.length; i++) {
                CachedModuleRow row = allModuleRows[i];
                if (row != null && ("ClickGUI".equalsIgnoreCase(row.name) || "ClickGui".equalsIgnoreCase(row.name))) {
                    // Keep legacy ClickGui module unbound so ONLY ClientThemeManager's bind opens ClickGUI
                    row.boundKey = -1;
                    setModuleBindKey(row.module, -1);
                    refreshModuleBindLabelIfNeeded(row, true);
                }
            }
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null && mc.options.keyMappings != null) {
                boolean changed = false;
                for (KeyMapping km : mc.options.keyMappings) {
                    if (km == null) continue;
                    String kmName = km.getName();
                    if (kmName == null) continue;
                    String low = kmName.toLowerCase(Locale.ROOT);
                    if (low.contains("clickgui") || (low.contains("attrium") && low.contains("gui"))) {
                        // Unbind legacy Fabric/vanilla KeyMapping so ONLY ClientThemeManager's unified bind opens ClickGUI
                        km.setKey(InputConstants.UNKNOWN);
                        changed = true;
                    }
                }
                if (changed) {
                    KeyMapping.resetMapping();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isSearchBarFocused() {
        return searchFocused || friendInputFocused || activeStringSettingEntry != null || bindingRow != null || listeningForFriendBind || listeningForGuiKeybind;
    }

    public static Style getNunitoBoldStyle() {
        initIsolatedNunitoStylesOnce();
        return nunitoRegularStyle;
    }

    public boolean isHudTabActive() {
        return activeTopTabIndex == 2;
    }

    public List<Module> getDiscoveredModulesSnapshot() {
        ensureModuleCacheReady();
        List<Module> list = new ArrayList<>(allModuleRows.length);
        for (CachedModuleRow row : allModuleRows) {
            if (row == null || HudManager.isLegacyHudModuleName(row.name)) continue;
            if (row != null && row.module != null) {
                list.add(row.module);
            }
        }
        return list;
    }

    public int getBoundKeyForModule(Module mod) {
        if (mod == null) return -1;
        ensureModuleCacheReady();
        for (CachedModuleRow row : allModuleRows) {
            if (row != null && row.module == mod) {
                return resolveEffectiveBindKey(row);
            }
        }
        return getModuleBindKey(mod);
    }


    public static Style getNunitoRegularStyle() {
        initIsolatedNunitoStylesOnce();
        return nunitoRegularStyle;
    }

    private static void initIsolatedNunitoStylesOnce() {
        if (nunitoStylesInitialized) {
            return;
        }
        nunitoStylesInitialized = true;
        boolean hasNunitoAssets = GlassClickGuiScreen.class.getResource("/assets/attrium/font/nunito_regular.json") != null
                && GlassClickGuiScreen.class.getResource("/assets/attrium/font/nunito_regular.ttf") != null;
        if (hasNunitoAssets) {
            Style resolvedRegular = loadIsolatedFontStyle("attrium", "nunito_regular");
            nunitoRegularStyle = resolvedRegular != null ? resolvedRegular.withBold(false) : Style.EMPTY.withBold(false);
        } else {
            // Safe fallback to vanilla Minecraft default font so text is NEVER invisible when nunito_regular.ttf is absent
            nunitoRegularStyle = Style.EMPTY.withBold(false);
        }
        nunitoBoldStyle = nunitoRegularStyle;
    }

    private static Style loadIsolatedFontStyle(String namespace, String path) {
        try {
            Object sampleRl = BuiltInRegistries.ITEM.getKey(Items.APPLE);
            if (sampleRl == null) {
                return null;
            }
            Class<?> rlClass = sampleRl.getClass();
            String safeNamespace = namespace.toLowerCase(Locale.ROOT);
            String safePath = path.toLowerCase(Locale.ROOT);
            String expectedString = safeNamespace + ":" + safePath;
            Object fontId = null;

            for (Method m : rlClass.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == String.class
                        && m.getParameterTypes()[1] == String.class
                        && rlClass.isAssignableFrom(m.getReturnType())) {
                    try {
                        m.setAccessible(true);
                        Object candidate = m.invoke(null, safeNamespace, safePath);
                        if (candidate != null && expectedString.equals(candidate.toString())) {
                            fontId = candidate;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (fontId == null) {
                for (Method m : rlClass.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == String.class
                            && rlClass.isAssignableFrom(m.getReturnType())) {
                        try {
                            m.setAccessible(true);
                            Object candidate = m.invoke(null, expectedString);
                            if (candidate != null && expectedString.equals(candidate.toString())) {
                                fontId = candidate;
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            if (fontId != null) {
                for (Method wm : Style.class.getDeclaredMethods()) {
                    if (!Modifier.isStatic(wm.getModifiers())
                            && wm.getParameterCount() == 1
                            && wm.getReturnType() == Style.class) {
                        Class<?> paramType = wm.getParameterTypes()[0];
                        if (paramType.isAssignableFrom(rlClass)) {
                            wm.setAccessible(true);
                            Style result = (Style) wm.invoke(Style.EMPTY, fontId);
                            if (result != null) {
                                return result.withBold(false);
                            }
                        } else if (!paramType.isPrimitive()
                                && paramType != String.class
                                && paramType != Boolean.class
                                && paramType != Integer.class) {
                            for (Constructor<?> ctor : paramType.getDeclaredConstructors()) {
                                if (ctor.getParameterCount() == 1
                                        && ctor.getParameterTypes()[0].isAssignableFrom(rlClass)) {
                                    ctor.setAccessible(true);
                                    Object fontDesc = ctor.newInstance(fontId);
                                    wm.setAccessible(true);
                                    Style result = (Style) wm.invoke(Style.EMPTY, fontDesc);
                                    if (result != null) {
                                        return result.withBold(false);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Identifies whether a module row is a pure keybind-driven Action Macro (AutoLunge)
     * rather than an ON/OFF toggleable module.
     */
    private static boolean isElytraHelperRow(CachedModuleRow row) {
        if (row == null) return false;
        if ("ElytraHelper".equalsIgnoreCase(row.name)) return true;
        return row.module != null && "ElytraHelper".equalsIgnoreCase(row.module.getClass().getSimpleName());
    }

    private static void invokeStaticMacroTick(Module mod, Minecraft mc, int key) {
        if (mod == null) return;
        try {
            Method m = mod.getClass().getMethod("tickMacroGlobal", Minecraft.class, int.class);
            m.invoke(null, mc, key);
        } catch (Throwable ignored) {
        }
    }

    private static void invokeStaticMacroSetKey(Module mod, int key) {
        if (mod == null) return;
        try {
            Method m = mod.getClass().getMethod("setMacroBoundKey", int.class);
            m.invoke(null, key);
        } catch (Throwable ignored) {
        }
    }

    private static int invokeStaticMacroGetKey(Module mod) {
        if (mod == null) return -1;
        try {
            Method m = mod.getClass().getMethod("getMacroBoundKey");
            Object v = m.invoke(null);
            if (v instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void invokeStaticMacroCallback(Module mod, int key) {
        if (mod == null) return;
        try {
            Method m = mod.getClass().getMethod("onMacroKeyPressedFromCallback", int.class);
            m.invoke(null, key);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isActionMacroRow(CachedModuleRow row) {
        if (row == null) {
            return false;
        }
        return (row.module instanceof LungeHelper)
                || "LungeHelper".equalsIgnoreCase(row.name)
                || "AutoLunge".equalsIgnoreCase(row.name)
                || "Lunge".equalsIgnoreCase(row.name)
                || "ElytraHelper".equalsIgnoreCase(row.name);
    }

    @Override
    protected void init() {
        super.init();
        initIsolatedNunitoStylesOnce();
        ensureGlfwCallbacksHooked();
        ensureModuleCacheReady();
        windowGeometryDirty = true;
        layoutDirty = true;
        processDirtyCachesIfNeeded();
        lastFrameTimeNs = System.nanoTime();
    }

    private void ensureModuleCacheReady() {
        if (!moduleRegistryInitialized || allModuleRows.length == 0) {
            rebuildModuleCacheFromRegistry();
            moduleRegistryInitialized = true;
            stringWidthsDirty = true;
            filterDirty = true;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Zero background blur shader overhead
    }

    public void onMouseScroll(double verticalDelta) {
        if (verticalDelta == 0.0D) {
            return;
        }
        float step = (float) (-verticalDelta * 36.0D);
        if (activeTopTabIndex == 1) {
            configTargetScrollOffset = Mth.clamp(configTargetScrollOffset + step, 0.0F, configMaxScrollOffset);
        } else if (activeTopTabIndex == 2) {
            hudTargetScrollOffset = Mth.clamp(hudTargetScrollOffset + step, 0.0F, hudMaxScrollOffset);
        } else if (activeTopTabIndex == 3) {
            friendTargetScrollOffset = Mth.clamp(friendTargetScrollOffset + step, 0.0F, friendMaxScrollOffset);
        } else {
            targetScrollOffset = Mth.clamp(targetScrollOffset + step, 0.0F, maxScrollOffset);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        lastScrollActivityMs = System.currentTimeMillis();
        onMouseScroll(scrollY);
        return true;
    }

    // =========================================================================
    // Global In-Game Keybind & AutoLunge Action Macro Listener
    // =========================================================================
    public static void pollGlobalKeybinds() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.getWindow() == null) {
            return;
        }
        GlassClickGuiScreen gui = getInstance();
        gui.ensureGlfwCallbacksHooked();
        gui.ensureModuleCacheReady();

        if (mc.screen != null) {
            for (int i = 0; i < gui.allModuleRows.length; i++) {
                gui.allModuleRows[i].wasKeyDownInGame = false;
            }
            // Allow AutoLunge to restore previousSlot if a GUI screen was opened while holding the bind
            wasClickGuiKeyDownInGame = true;
            LungeHelper.tickMacroGlobal(mc, LungeHelper.getMacroBoundKey());
            return;
        }

        long windowHandle = mc.getWindow().handle();
        if (windowHandle == 0L) {
            return;
        }

        long nowMs = System.currentTimeMillis();

        // Poll ClickGUI keybind (supports keyboard keys & mouse buttons)
        int clickGuiKey = ClientThemeManager.getClickGuiKey();
        boolean guiKeyDown;
        if (clickGuiKey <= -102) {
            int btn = -100 - clickGuiKey;
            guiKeyDown = btn >= GLFW.GLFW_MOUSE_BUTTON_MIDDLE && btn <= GLFW.GLFW_MOUSE_BUTTON_LAST
                    && GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS;
        } else {
            guiKeyDown = clickGuiKey >= GLFW.GLFW_KEY_SPACE && clickGuiKey <= GLFW.GLFW_KEY_LAST
                    && (InputConstants.isKeyDown(mc.getWindow(), clickGuiKey)
                    || GLFW.glfwGetKey(windowHandle, clickGuiKey) == GLFW.GLFW_PRESS);
        }
        if (guiKeyDown && !wasClickGuiKeyDownInGame) {
            wasClickGuiKeyDownInGame = true;
            openClickGuiFromBind(mc);
            return;
        }
        wasClickGuiKeyDownInGame = guiKeyDown;

        boolean lungeRowHandled = false;

        for (int i = 0; i < gui.allModuleRows.length; i++) {
            CachedModuleRow row = gui.allModuleRows[i];
            int key = gui.resolveEffectiveBindKey(row);

            // Action Macros (LungeHelper & ElytraHelper): pure keybind-driven macros with no ON/OFF toggle
            if (isActionMacroRow(row)) {
                if (isElytraHelperRow(row)) {
                    invokeStaticMacroTick(row.module, mc, key);
                } else {
                    lungeRowHandled = true;
                    LungeHelper.tickMacroGlobal(mc, key);
                }
                continue;
            }

            if (key == -1 || key == 0 || key == GLFW.GLFW_KEY_UNKNOWN) {
                row.wasKeyDownInGame = false;
                continue;
            }

            if ("ClickGUI".equalsIgnoreCase(row.name) || "ClickGui".equalsIgnoreCase(row.name)) {
                continue;
            }

            boolean isDown;
            if (key < -1) {
                int btn = -100 - key;
                isDown = btn >= 0 && btn <= GLFW.GLFW_MOUSE_BUTTON_LAST
                        && GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS;
            } else {
                isDown = key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST
                        && (InputConstants.isKeyDown(mc.getWindow(), key)
                        || GLFW.glfwGetKey(windowHandle, key) == GLFW.GLFW_PRESS);
            }

            if (isDown && !row.wasKeyDownInGame) {
                if (nowMs - row.lastBindTriggerMs > 60L) {
                    row.lastBindTriggerMs = nowMs;
                    row.module.toggle();
                }
            }
            row.wasKeyDownInGame = isDown;
        }

        if (!lungeRowHandled) {
            LungeHelper.tickMacroGlobal(mc, LungeHelper.getMacroBoundKey());
        }

        int fKey = FriendManager.getFriendBindKey();
        if (fKey != -1 && fKey != 0 && fKey != GLFW.GLFW_KEY_UNKNOWN) {
            boolean fDown;
            if (fKey < -1) {
                int btn = -100 - fKey;
                fDown = btn >= 0 && btn <= GLFW.GLFW_MOUSE_BUTTON_LAST
                        && GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS;
            } else {
                fDown = fKey >= GLFW.GLFW_KEY_SPACE && fKey <= GLFW.GLFW_KEY_LAST
                        && (InputConstants.isKeyDown(mc.getWindow(), fKey)
                        || GLFW.glfwGetKey(windowHandle, fKey) == GLFW.GLFW_PRESS);
            }
            FriendManager.pollFriendKeybindInGame(mc, fDown);
        } else {
            FriendManager.pollFriendKeybindInGame(mc, false);
        }
    }

    private boolean onGlobalKeyPressFromCallback(int key) {
        if (key <= 0 || key == GLFW.GLFW_KEY_UNKNOWN) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.screen != null) {
            return false;
        }
        ensureModuleCacheReady();
        int clickGuiKey = ClientThemeManager.getClickGuiKey();
        if (clickGuiKey > 0 && clickGuiKey != GLFW.GLFW_KEY_UNKNOWN && key == clickGuiKey) {
            if (mc.isSameThread()) {
                openClickGuiFromBind(mc);
            } else {
                mc.execute(() -> openClickGuiFromBind(mc));
            }
            return true;
        }
        long nowMs = System.currentTimeMillis();
        for (int i = 0; i < allModuleRows.length; i++) {
            CachedModuleRow row = allModuleRows[i];
            if ("ClickGUI".equalsIgnoreCase(row.name) || "ClickGui".equalsIgnoreCase(row.name)) {
                continue;
            }
            // Forward GLFW key press directly to Action Macros (LungeHelper & ElytraHelper)
            if (isActionMacroRow(row)) {
                int boundMacro = resolveEffectiveBindKey(row);
                if (boundMacro == key) {
                    if (isElytraHelperRow(row)) {
                        invokeStaticMacroCallback(row.module, key);
                    } else {
                        LungeHelper.onMacroKeyPressedFromCallback(key);
                    }
                }
                continue;
            }
            int bound = resolveEffectiveBindKey(row);
            if (bound == key) {
                row.wasKeyDownInGame = true;
                if (nowMs - row.lastBindTriggerMs > 60L) {
                    row.lastBindTriggerMs = nowMs;
                    row.module.toggle();
                }
            }
        }
        return false;
    }

    private static int lerpColor(int c1, int c2, float t) {
        float clamped = Mth.clamp(t, 0.0F, 1.0F);
        if (clamped <= 0.001F) return c1;
        if (clamped >= 0.999F) return c2;

        int a1 = (c1 >>> 24) & 0xFF;
        int r1 = (c1 >>> 16) & 0xFF;
        int g1 = (c1 >>> 8) & 0xFF;
        int b1 = c1 & 0xFF;

        int a2 = (c2 >>> 24) & 0xFF;
        int r2 = (c2 >>> 16) & 0xFF;
        int g2 = (c2 >>> 8) & 0xFF;
        int b2 = c2 & 0xFF;

        int a = Math.round(a1 + (a2 - a1) * clamped);
        int r = Math.round(r1 + (r2 - r1) * clamped);
        int g = Math.round(g1 + (g2 - g1) * clamped);
        int b = Math.round(b1 + (b2 - b1) * clamped);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int multiplyAlpha(int color, float factor) {
        int baseAlpha = (color >>> 24) & 0xFF;
        int scaledAlpha = Mth.clamp(Math.round(baseAlpha * factor), 0, 255);
        return (scaledAlpha << 24) | (color & 0x00FFFFFF);
    }

    private void resetBatchQueues() {
        bgQuadCount = 0;
        textQueueCount = 0;
    }

    private void enqueueBgQuad(int x1, int y1, int x2, int y2, int color) {
        if (x2 <= x1 || y2 <= y1 || (color >>> 24) == 0 || bgQuadCount >= MAX_BATCHED_QUADS) {
            return;
        }
        int idx = bgQuadCount * 5;
        bgQuadQueue[idx] = x1;
        bgQuadQueue[idx + 1] = y1;
        bgQuadQueue[idx + 2] = x2;
        bgQuadQueue[idx + 3] = y2;
        bgQuadQueue[idx + 4] = color;
        bgQuadCount++;
    }

    private void drawRoundedRect(int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) {
            return;
        }
        int r = Mth.clamp(Math.min(radius, Math.min(w / 2, h / 2)), 0, MAX_PRECOMPUTED_RADIUS);
        if (r <= 1) {
            enqueueBgQuad(x, y, x + w, y + h, color);
            return;
        }

        if (h > 2 * r) {
            enqueueBgQuad(x, y + r, x + w, y + h - r, color);
        }

        int[] insets = AA_SOLID_INSET[r];
        float[] coverages = AA_EDGE_COVERAGE[r];

        for (int dy = 0; dy < r; dy++) {
            int inset = insets[dy];
            int topY = y + dy;
            int botY = y + h - 1 - dy;

            int leftSolidX = x + inset;
            int rightSolidX = x + w - inset;

            if (rightSolidX > leftSolidX) {
                enqueueBgQuad(leftSolidX, topY, rightSolidX, topY + 1, color);
                if (botY != topY) {
                    enqueueBgQuad(leftSolidX, botY, rightSolidX, botY + 1, color);
                }
            }

            if (inset > 0) {
                int aaColor = multiplyAlpha(color, coverages[dy]);
                int leftAaX = leftSolidX - 1;
                int rightAaX = rightSolidX;
                enqueueBgQuad(leftAaX, topY, leftAaX + 1, topY + 1, aaColor);
                enqueueBgQuad(rightAaX, topY, rightAaX + 1, topY + 1, aaColor);
                if (botY != topY) {
                    enqueueBgQuad(leftAaX, botY, leftAaX + 1, botY + 1, aaColor);
                    enqueueBgQuad(rightAaX, botY, rightAaX + 1, botY + 1, aaColor);
                }
            }
        }
    }

    private void enqueueComponentText(Component comp, int x, int y, int color) {
        if (comp == null || textQueueCount >= MAX_BATCHED_TEXTS) {
            return;
        }
        textComponentQueue[textQueueCount] = comp;
        int idx = textQueueCount * 3;
        textCoordsAndColorQueue[idx] = x;
        textCoordsAndColorQueue[idx + 1] = y;
        textCoordsAndColorQueue[idx + 2] = color;
        textQueueCount++;
    }

    private void flushBatchedRenderPasses(GuiGraphics guiGraphics) {
        for (int i = 0; i < bgQuadCount; i++) {
            int idx = i * 5;
            guiGraphics.fill(
                    bgQuadQueue[idx],
                    bgQuadQueue[idx + 1],
                    bgQuadQueue[idx + 2],
                    bgQuadQueue[idx + 3],
                    bgQuadQueue[idx + 4]
            );
        }

        for (int i = 0; i < textQueueCount; i++) {
            int idx = i * 3;
            guiGraphics.drawString(
                    this.font,
                    textComponentQueue[i],
                    textCoordsAndColorQueue[idx],
                    textCoordsAndColorQueue[idx + 1],
                    textCoordsAndColorQueue[idx + 2],
                    false
            );
            textComponentQueue[i] = null;
        }

        resetBatchQueues();
    }

    private void processDirtyCachesIfNeeded() {
        if (this.width != lastScreenWidth || this.height != lastScreenHeight) {
            lastScreenWidth = this.width;
            lastScreenHeight = this.height;
            windowGeometryDirty = true;
            layoutDirty = true;
        }

        if (stringWidthsDirty && this.font != null) {
            cacheAllStaticStringWidths();
            stringWidthsDirty = false;
            windowGeometryDirty = true;
        }

        if (windowGeometryDirty && this.font != null) {
            recomputeWindowGeometry();
            windowGeometryDirty = false;
            layoutDirty = true;
        }

        if (filterDirty) {
            rebuildFilteredModuleList();
            filterDirty = false;
            layoutDirty = true;
        }

        if (layoutDirty) {
            recalculateRowYOffsets();
            layoutDirty = false;
        }
    }

    private void cacheAllStaticStringWidths() {
        chevronCollapsedComponent = Component.literal(">").withStyle(nunitoRegularStyle);
        chevronExpandedComponent = Component.literal("v").withStyle(nunitoRegularStyle);
        bindDotsComponent = Component.literal("...").withStyle(nunitoRegularStyle);
        clearXComponent = Component.literal("x").withStyle(nunitoRegularStyle);
        backspaceLtComponent = Component.literal("<").withStyle(nunitoRegularStyle);
        slashComponent = Component.literal("/").withStyle(nunitoRegularStyle);
        clientAudioHeaderComponent = Component.literal("Client Audio & Tactile Mixer").withStyle(nunitoRegularStyle);

        for (int i = 0; i < TOP_TABS.length; i++) {
            topTabComponents[i] = Component.literal(TOP_TABS[i]).withStyle(nunitoRegularStyle);
            String msg = TOP_TABS[i] + " panel is synchronized with your active client profile.";
            cachedSecondaryTabComponents[i] = Component.literal(msg).withStyle(nunitoRegularStyle);
            cachedSecondaryTabMessageWidths[i] = this.font.width(cachedSecondaryTabComponents[i]);
        }

        for (int i = 0; i < CATEGORY_PILLS.length; i++) {
            categoryPillComponents[i] = Component.literal(CATEGORY_PILLS[i]).withStyle(nunitoRegularStyle);
        }

        if (clientTabUiVolumeEntry != null) {
            clientTabUiVolumeEntry.cachedNameWidth = this.font.width(clientTabUiVolumeEntry.nameComponent);
            updateCachedSliderStringIfNeeded(clientTabUiVolumeEntry);
        }

        for (int i = 0; i < allModuleRows.length; i++) {
            CachedModuleRow row = allModuleRows[i];
            refreshModuleBindLabelIfNeeded(row, true);
            for (int s = 0; s < row.settings.length; s++) {
                CachedSettingEntry se = row.settings[s];
                se.cachedNameWidth = this.font.width(se.nameComponent);
                for (int m = 0; m < se.modeOptions.length; m++) {
                    int pad = (se.type == 2) ? 12 : 10;
                    se.modePillWidths[m] = this.font.width(se.modeOptionComponents[m]) + pad;
                }
                if (se.type == 1) {
                    updateCachedSliderStringIfNeeded(se);
                }
            }
        }
    }

    private void updateCachedSliderStringIfNeeded(CachedSettingEntry se) {
        NumberSetting ns = (NumberSetting) se.setting;
        double val = ns.getValue();
        if (Double.compare(val, se.lastCachedNumberValue) != 0 || se.cachedFormattedValue.isEmpty()) {
            se.lastCachedNumberValue = val;
            se.cachedFormattedValue = formatSliderValue(val, se.step);
            se.cachedValueComponent = Component.literal(se.cachedFormattedValue).withStyle(nunitoRegularStyle);
            if (this.font != null) {
                se.cachedValueTextWidth = this.font.width(se.cachedValueComponent);
            }
        }
    }

    private void recomputeWindowGeometry() {
        winW = Math.min(BASE_WINDOW_WIDTH, this.width - 28);
        winH = Math.min(BASE_WINDOW_HEIGHT, this.height - 24);
        winX = (this.width - winW) / 2;
        winY = (this.height - winH) / 2;

        int totalTopNavW = 0;
        for (int i = 0; i < TOP_TABS.length; i++) {
            int labelW = this.font.width(topTabComponents[i] != null ? topTabComponents[i] : Component.literal(TOP_TABS[i]));
            int w = labelW + 20;
            topTabW[i] = w;
            totalTopNavW += w + (i > 0 ? 4 : 0);
        }
        int curTopX = winX + (winW - totalTopNavW) / 2;
        for (int i = 0; i < TOP_TABS.length; i++) {
            int labelW = topTabW[i] - 20;
            topTabX[i] = curTopX;
            topTabTextX[i] = curTopX + (topTabW[i] - labelW) / 2;
            curTopX += topTabW[i] + 4;
        }
        navContainerX = topTabX[0] - 6;
        navContainerW = (topTabX[TOP_TABS.length - 1] + topTabW[TOP_TABS.length - 1]) - navContainerX + 6;
        navContainerY = winY + 11;
        navContainerH = 22;

        searchBoxX = winX + LIST_SIDE_PADDING;
        searchBoxY = winY + 42;
        searchBoxW = Math.min(182, (int) (winW * 0.31F));
        searchBoxH = 22;
        searchBtnY = searchBoxY + 4;
        searchClearBtnX = searchBoxX + searchBoxW - 18;
        searchBackspaceBtnX = searchClearBtnX - 19;
        searchSlashBadgeX = searchBoxX + searchBoxW - 19;

        int pillStartX = searchBoxX + searchBoxW + 12;
        int curPillX = pillStartX;
        for (int i = 0; i < CATEGORY_PILLS.length; i++) {
            int labelW = this.font.width(categoryPillComponents[i] != null ? categoryPillComponents[i] : Component.literal(CATEGORY_PILLS[i]));
            int pw = labelW + 16;
            categoryPillX[i] = curPillX;
            categoryPillW[i] = pw;
            categoryPillTextX[i] = curPillX + (pw - labelW) / 2;
            curPillX += pw + 5;
        }

        listLeft = winX + LIST_SIDE_PADDING;
        listTop = winY + HEADER_HEIGHT;
        listRight = winX + winW - LIST_SIDE_PADDING;
        listBottom = winY + winH - 14;
        listHeight = Math.max(40, listBottom - listTop);
        rowWidth = (listRight - listLeft) - 8;
    }

    private void rebuildFilteredModuleList() {
        int count = 0;
        boolean filterByCategory = activeCategoryIndex > 0;
        boolean filterBySearch = !searchQueryLower.isEmpty();

        for (int i = 0; i < allModuleRows.length; i++) {
            CachedModuleRow row = allModuleRows[i];
            if (filterByCategory && row.categoryPillIndex != activeCategoryIndex) {
                continue;
            }
            if (filterBySearch
                    && !row.nameLower.contains(searchQueryLower)
                    && !row.descriptionLower.contains(searchQueryLower)) {
                continue;
            }
            filteredRows[count++] = row;
        }
        filteredRowCount = count;
        layoutDirty = true;
    }

    private void recalculateRowYOffsets() {
        int yCursor = 0;
        for (int i = 0; i < filteredRowCount; i++) {
            CachedModuleRow row = filteredRows[i];
            row.cachedYOffset = yCursor;

            int visSettings = 0;
            if (row.expanded || row.expandAnim > 0.01F || row.animatedDrawerHeight > 0.5F) {
                for (int s = 0; s < row.settings.length; s++) {
                    CachedSettingEntry se = row.settings[s];
                    se.visible = se.setting.isVisible();
                    if (se.visible) {
                        visSettings++;
                    }
                }
            }
            row.cachedVisibleSettingCount = visSettings;
            int animatedDrawerH = Math.max(0, Math.round(row.animatedDrawerHeight));
            row.cachedTotalHeight = ROW_HEIGHT + animatedDrawerH;

            yCursor += row.cachedTotalHeight + ROW_GAP;
        }

        totalContentHeight = Math.max(0, yCursor - ROW_GAP);
        maxScrollOffset = Math.max(0.0F, (float) (totalContentHeight - listHeight));
        targetScrollOffset = Mth.clamp(targetScrollOffset, 0.0F, maxScrollOffset);
        scrollOffset = Mth.clamp(scrollOffset, 0.0F, maxScrollOffset);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        long nowNs = System.nanoTime();
        float dtSeconds = lastFrameTimeNs == 0L
                ? 0.016F
                : Mth.clamp((nowNs - lastFrameTimeNs) / 1_000_000_000.0F, 0.001F, 0.05F);
        lastFrameTimeNs = nowNs;

        ClientThemeManager.tick(dtSeconds, true);

        if (activeTopTabIndex != lastObservedTopTab) {
            lastObservedTopTab = activeTopTabIndex;
            if (activeTopTabIndex == 4) {
                clientTabOpenTime = 0.0F;
            } else if (activeTopTabIndex == 2) {
                hudTabOpenTime = 0.0F;
            }
        }
        if (activeTopTabIndex == 4) {
            clientTabOpenTime = Math.min(2.0F, clientTabOpenTime + dtSeconds);
        } else if (activeTopTabIndex == 2) {
            hudTabOpenTime = Math.min(2.0F, hudTabOpenTime + dtSeconds);
        }

        processDirtyCachesIfNeeded();

        if (HudManager.isLayoutEditorActive()) {
            pollGlfwInputAndInteractions(mouseX, mouseY);
            stepAnimationsAndScroll(dtSeconds);
            HudManager.renderLayoutEditor(Minecraft.getInstance(), guiGraphics, this.font, mouseX, mouseY);
            return;
        }

        float guiScale = ClientThemeManager.getAnimatedGuiScale();
        float centerX = winX + winW * 0.5F;
        float centerY = winY + winH * 0.5F;
        int effMouseX = Math.round(centerX + (mouseX - centerX) / guiScale);
        int effMouseY = Math.round(centerY + (mouseY - centerY) / guiScale);

        pollGlfwInputAndInteractions(effMouseX, effMouseY);
        stepAnimationsAndScroll(dtSeconds);
        stepClientBentoAnimations(dtSeconds);
        processDirtyCachesIfNeeded();

        // Full-screen animated Background Dim vignette (0x00000000 -> 0x88000000)
        int dimCol = ClientThemeManager.getAnimatedBackdropDimColor();
        if ((dimCol >>> 24) > 1) {
            guiGraphics.fill(0, 0, this.width, this.height, dimCol);
        }

        boolean applyScale = Math.abs(guiScale - 1.0F) > 0.002F;
        if (applyScale) {
            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(centerX, centerY);
            guiGraphics.pose().scale(guiScale, guiScale);
            guiGraphics.pose().translate(-centerX, -centerY);
        }

        resetBatchQueues();
        drawRoundedRect(winX, winY, winW, winH, WINDOW_CORNER_RADIUS, ClientThemeManager.getWindowBgColor());

        enqueueTopNavigationBar(dtSeconds, effMouseX, effMouseY);
        if (activeTopTabIndex == 0) {
            enqueueSubNavigationBar(dtSeconds, effMouseX, effMouseY);
        } else if (activeTopTabIndex == 1) {
            enqueueConfigsTopInputBar(effMouseX, effMouseY);
        } else if (activeTopTabIndex == 2) {
            enqueueHudTopBar(effMouseX, effMouseY);
        } else if (activeTopTabIndex == 3) {
            enqueueFriendsTopInputBar(effMouseX, effMouseY);
        }

        if (activeTopTabIndex == 0 && maxScrollOffset > 1.0F) {
            enqueueScrollbar();
        }
        flushBatchedRenderPasses(guiGraphics);

        int scX1 = applyScale ? Math.max(0, Math.round(centerX + (listLeft - centerX) * guiScale)) : listLeft;
        int scY1 = applyScale ? Math.max(0, Math.round(centerY + (listTop - centerY) * guiScale)) : listTop;
        int scX2 = applyScale ? Math.min(this.width, Math.round(centerX + (listRight - centerX) * guiScale)) : listRight;
        int scY2 = applyScale ? Math.min(this.height, Math.round(centerY + (listBottom - centerY) * guiScale)) : listBottom;

        if (activeTopTabIndex == 4) {
            // Bento dashboard renders across the full content area below the top navigation bar
            int bentoTop = navContainerY + navContainerH + 10;
            int scBentoY1 = applyScale ? Math.max(0, Math.round(centerY + (bentoTop - centerY) * guiScale)) : bentoTop;
            guiGraphics.enableScissor(scX1, scBentoY1, scX2, scY2);
            renderClientBentoDashboard(guiGraphics, dtSeconds, effMouseX, effMouseY, bentoTop);
            guiGraphics.disableScissor();
        } else {
            guiGraphics.enableScissor(scX1, scY1, scX2, scY2);
            resetBatchQueues();
            if (activeTopTabIndex == 0) {
                enqueueScrollingModuleList(dtSeconds, effMouseX, effMouseY);
                flushBatchedRenderPasses(guiGraphics);
            } else if (activeTopTabIndex == 1) {
                renderConfigsListPanel(guiGraphics, dtSeconds, effMouseX, effMouseY);
            } else if (activeTopTabIndex == 2) {
                renderHudTabPanel(guiGraphics, dtSeconds, effMouseX, effMouseY);
            } else if (activeTopTabIndex == 3) {
                renderFriendsListPanel(guiGraphics, dtSeconds, effMouseX, effMouseY);
            } else {
                enqueueSecondaryTabView();
                flushBatchedRenderPasses(guiGraphics);
            }
            guiGraphics.disableScissor();
        }

        if (applyScale) {
            guiGraphics.pose().popMatrix();
        }
    }

    private void stepAnimationsAndScroll(float dt) {
        float scrollLerp = Mth.clamp(dt * 15.0F, 0.08F, 1.0F);
        float pillSlideLerp = 1.0F - (float) Math.exp(-16.0F * dt);
        if (activeTopTabIndex >= 0 && activeTopTabIndex < TOP_TABS.length && topTabW[activeTopTabIndex] > 0) {
            float targetTX = topTabX[activeTopTabIndex];
            float targetTW = topTabW[activeTopTabIndex];
            if (topTabIndicatorX < 0.0F) {
                topTabIndicatorX = targetTX;
                topTabIndicatorW = targetTW;
            } else {
                topTabIndicatorX += (targetTX - topTabIndicatorX) * pillSlideLerp;
                topTabIndicatorW += (targetTW - topTabIndicatorW) * pillSlideLerp;
            }
        }
        if (activeCategoryIndex >= 0 && activeCategoryIndex < CATEGORY_PILLS.length && categoryPillW[activeCategoryIndex] > 0) {
            float targetCX = categoryPillX[activeCategoryIndex];
            float targetCW = categoryPillW[activeCategoryIndex];
            if (catPillIndicatorX < 0.0F) {
                catPillIndicatorX = targetCX;
                catPillIndicatorW = targetCW;
            } else {
                catPillIndicatorX += (targetCX - catPillIndicatorX) * pillSlideLerp;
                catPillIndicatorW += (targetCW - catPillIndicatorW) * pillSlideLerp;
            }
        }
        boolean scrollActive = draggingScrollbar
                || Math.abs(targetScrollOffset - scrollOffset) > 0.35F
                || (System.currentTimeMillis() - lastScrollActivityMs) < 900L;
        float targetSbAlpha = (maxScrollOffset > 1.0F && scrollActive) ? 1.0F : 0.0F;
        scrollbarFadeAnim += (targetSbAlpha - scrollbarFadeAnim) * Mth.clamp(dt * 10.0F, 0.05F, 1.0F);
        if (Math.abs(targetScrollOffset - scrollOffset) > 0.1F) {
            scrollOffset += (targetScrollOffset - scrollOffset) * scrollLerp;
        } else {
            scrollOffset = targetScrollOffset;
        }
        if (Math.abs(hudTargetScrollOffset - hudScrollOffset) > 0.1F) {
            hudScrollOffset += (hudTargetScrollOffset - hudScrollOffset) * scrollLerp;
        } else {
            hudScrollOffset = hudTargetScrollOffset;
        }
        if (Math.abs(friendTargetScrollOffset - friendScrollOffset) > 0.1F) {
            friendScrollOffset += (friendTargetScrollOffset - friendScrollOffset) * scrollLerp;
        } else {
            friendScrollOffset = friendTargetScrollOffset;
        }

        float toggleRate = Mth.clamp(dt / 0.15F, 0.05F, 1.0F);
        float expandLerp = Mth.clamp(dt * 14.0F, 0.08F, 1.0F);
        searchFocusAnim = Mth.clamp(searchFocusAnim + (searchFocused ? toggleRate : -toggleRate), 0.0F, 1.0F);

        for (int i = 0; i < filteredRowCount; i++) {
            CachedModuleRow row = filteredRows[i];
            float targetToggle = row.module.isEnabled() ? 1.0F : 0.0F;
            if (row.toggleAnim < targetToggle) {
                row.toggleAnim = Math.min(targetToggle, row.toggleAnim + toggleRate);
            } else if (row.toggleAnim > targetToggle) {
                row.toggleAnim = Math.max(targetToggle, row.toggleAnim - toggleRate);
            }

            float targetExpand = row.expanded ? 1.0F : 0.0F;
            if (Math.abs(targetExpand - row.expandAnim) > 0.005F) {
                row.expandAnim += (targetExpand - row.expandAnim) * expandLerp;
                layoutDirty = true;
            } else if (row.expandAnim != targetExpand) {
                row.expandAnim = targetExpand;
                layoutDirty = true;
            }

            int visCountNow = 0;
            if (row.expanded || row.expandAnim > 0.02F || row.animatedDrawerHeight > 0.5F) {
                for (int s = 0; s < row.settings.length; s++) {
                    CachedSettingEntry se = row.settings[s];
                    se.visible = se.setting.isVisible();
                    if (se.visible) {
                        visCountNow++;
                    }
                    if (se.type == 0 && se.setting instanceof BooleanSetting bs) {
                        Object raw = ((Setting<?>) bs).getValue();
                        float st = (raw instanceof Boolean b && b) ? 1.0F : 0.0F;
                        if (se.toggleAnim < st) {
                            se.toggleAnim = Math.min(st, se.toggleAnim + toggleRate);
                        } else if (se.toggleAnim > st) {
                            se.toggleAnim = Math.max(st, se.toggleAnim - toggleRate);
                        }
                    }
                }
            }
            row.cachedVisibleSettingCount = visCountNow;
            float targetDrawerH = (row.expanded && visCountNow > 0)
                    ? (float) (visCountNow * SETTING_ROW_HEIGHT + 10)
                    : 0.0F;
            if (Math.abs(targetDrawerH - row.animatedDrawerHeight) > 0.25F) {
                row.animatedDrawerHeight += (targetDrawerH - row.animatedDrawerHeight) * expandLerp;
                layoutDirty = true;
            } else if (row.animatedDrawerHeight != targetDrawerH) {
                row.animatedDrawerHeight = targetDrawerH;
                layoutDirty = true;
            }
        }
    }

    private void enqueueTopNavigationBar(float dt, int mouseX, int mouseY) {
        drawRoundedRect(navContainerX, navContainerY, navContainerW, navContainerH, 6, COLOR_TOP_NAV_BG);
        if (topTabIndicatorW > 2.0F) {
            int ix = Math.round(topTabIndicatorX);
            int iw = Math.round(topTabIndicatorW);
            drawRoundedRect(ix, navContainerY + 2, iw, navContainerH - 4, 5, COLOR_TOP_NAV_ACTIVE_PILL);
            drawRoundedRect(ix + 6, navContainerY + navContainerH - 4, Math.max(4, iw - 12), 2, 1, ClientThemeManager.getAccentArgb());
        }

        float hoverStep = Mth.clamp(dt / 0.15F, 0.05F, 1.0F);
        int textY = navContainerY + (navContainerH - 8) / 2;
        for (int i = 0; i < TOP_TABS.length; i++) {
            int tx = topTabX[i];
            int tw = topTabW[i];
            boolean isActive = (i == activeTopTabIndex);
            boolean isHovered = isHovered(mouseX, mouseY, tx, navContainerY + 2, tw, navContainerH - 4);

            topTabHoverAnim[i] = Mth.clamp(topTabHoverAnim[i] + (isHovered ? hoverStep : -hoverStep), 0.0F, 1.0F);

            if (!isActive && topTabHoverAnim[i] > 0.01F) {
                int lerpedHoverBg = lerpColor(0x00222530, 0x88262A38, topTabHoverAnim[i]);
                drawRoundedRect(tx, navContainerY + 2, tw, navContainerH - 4, 5, lerpedHoverBg);
            }

            int textColor = isActive
                    ? COLOR_TEXT_BRIGHT_WHITE
                    : lerpColor(COLOR_TEXT_DIMMED_GRAY, COLOR_TEXT_BRIGHT_WHITE, topTabHoverAnim[i]);
            enqueueComponentText(topTabComponents[i], topTabTextX[i], textY, textColor);
        }
    }

    private void enqueueSubNavigationBar(float dt, int mouseX, int mouseY) {
        int searchBg = lerpColor(COLOR_SEARCH_BG, COLOR_SEARCH_BG_FOCUSED, searchFocusAnim);
        drawRoundedRect(searchBoxX, searchBoxY, searchBoxW, searchBoxH, SEARCH_BAR_RADIUS, searchBg);

        // Left-aligned magnifying glass icon inside the 10px-radius search bar
        enqueueSearchMagnifierIcon(searchBoxX + 8, searchBoxY + 6, COLOR_TEXT_DIMMED_GRAY);

        String displaySearch;
        int searchTextCol;
        if (searchQuery.isEmpty() && !searchFocused) {
            displaySearch = "/ Search";
            searchTextCol = COLOR_TEXT_DIMMED_GRAY;
        } else {
            boolean blink = (System.currentTimeMillis() / 500L) % 2L == 0L;
            displaySearch = searchQuery + (searchFocused && blink ? "|" : "");
            searchTextCol = COLOR_TEXT_BRIGHT_WHITE;
        }
        if (!displaySearch.equals(lastRenderedSearchString)) {
            lastRenderedSearchString = displaySearch;
            cachedSearchDisplayComponent = Component.literal(displaySearch).withStyle(nunitoRegularStyle);
        }
        enqueueComponentText(cachedSearchDisplayComponent, searchBoxX + 22, searchBoxY + 7, searchTextCol);

        if (searchFocused || !searchQuery.isEmpty()) {
            boolean clearHover = isHovered(mouseX, mouseY, searchClearBtnX, searchBtnY, 14, 14);
            drawRoundedRect(searchClearBtnX, searchBtnY, 14, 14, 5, clearHover ? COLOR_BIND_BG_HOVER : 0xCC232632);
            enqueueComponentText(clearXComponent, searchClearBtnX + 4, searchBtnY + 3, clearHover ? COLOR_TEXT_BRIGHT_WHITE : COLOR_TEXT_DIMMED_GRAY);

            boolean bsHover = isHovered(mouseX, mouseY, searchBackspaceBtnX, searchBtnY, 16, 14);
            drawRoundedRect(searchBackspaceBtnX, searchBtnY, 16, 14, 5, bsHover ? COLOR_BIND_BG_HOVER : 0xCC232632);
            enqueueComponentText(backspaceLtComponent, searchBackspaceBtnX + 5, searchBtnY + 3, bsHover ? COLOR_TEXT_BRIGHT_WHITE : COLOR_TEXT_DIMMED_GRAY);
        }

        float hoverStep = Mth.clamp(dt / 0.15F, 0.05F, 1.0F);
        int pillTextY = searchBoxY + (searchBoxH - 8) / 2;
        if (catPillIndicatorW > 2.0F) {
            drawRoundedRect(Math.round(catPillIndicatorX), searchBoxY + 1, Math.round(catPillIndicatorW), searchBoxH - 2, CATEGORY_PILL_RADIUS, ClientThemeManager.getAccentArgb());
        }
        for (int i = 0; i < CATEGORY_PILLS.length; i++) {
            int px = categoryPillX[i];
            int pw = categoryPillW[i];
            if (px + pw > listRight) {
                break;
            }
            boolean isActive = (i == activeCategoryIndex);
            boolean isHovered = isHovered(mouseX, mouseY, px, searchBoxY + 1, pw, searchBoxH - 2);
            categoryPillHoverAnim[i] = Mth.clamp(categoryPillHoverAnim[i] + (isHovered ? hoverStep : -hoverStep), 0.0F, 1.0F);

            if (!isActive && categoryPillHoverAnim[i] > 0.01F) {
                int hoverPillBg = lerpColor(COLOR_PILL_INACTIVE_BG, COLOR_PILL_INACTIVE_HOVER, categoryPillHoverAnim[i]);
                drawRoundedRect(px, searchBoxY + 1, pw, searchBoxH - 2, CATEGORY_PILL_RADIUS, hoverPillBg);
            }

            int pillTextCol = isActive ? COLOR_PILL_ACTIVE_TEXT : COLOR_TEXT_BRIGHT_WHITE;
            enqueueComponentText(categoryPillComponents[i], categoryPillTextX[i], pillTextY, pillTextCol);
        }
    }

    private void enqueueScrollingModuleList(float dt, int mouseX, int mouseY) {
        int scrollInt = Math.round(scrollOffset);
        boolean mouseInsideViewport = isHovered(mouseX, mouseY, listLeft, listTop, listRight - listLeft, listHeight);
        int iconBoxSize = 28;
        int iconBoxX = listLeft + 8;
        int textStartX = iconBoxX + iconBoxSize + 10;
        int toggleW = TOGGLE_PILL_WIDTH;
        int toggleH = TOGGLE_PILL_HEIGHT;
        int toggleX = listLeft + rowWidth - toggleW - 12;
        int chevronX = toggleX - 14;
        float hoverStep = Mth.clamp(dt / 0.15F, 0.05F, 1.0F);

        for (int i = 0; i < filteredRowCount; i++) {
            CachedModuleRow row = filteredRows[i];
            int rowTop = listTop + row.cachedYOffset - scrollInt;
            int rowBottom = rowTop + row.cachedTotalHeight;

            if (rowBottom < listTop || rowTop > listBottom) {
                row.wasHoveredLastFrame = false;
                row.hoverAnim = Math.max(0.0F, row.hoverAnim - hoverStep);
                continue;
            }

            boolean rowHeaderHovered = mouseInsideViewport
                    && isHovered(mouseX, mouseY, listLeft, rowTop, rowWidth, ROW_HEIGHT);

            if (rowHeaderHovered && !row.wasHoveredLastFrame) {
                SoundUtil.playHoverTick();
            }
            row.wasHoveredLastFrame = rowHeaderHovered;

            row.hoverAnim = Mth.clamp(row.hoverAnim + (rowHeaderHovered ? hoverStep : -hoverStep), 0.0F, 1.0F);

            boolean isEnabled = row.module.isEnabled();
            // Dark glassmorphic card surface smoothly lerping from 0x99161922 (idle) to 0xCC1E2330 (hovered)
            int cardBg = lerpColor(COLOR_ROW_IDLE_BG, COLOR_ROW_HOVER_BG, row.hoverAnim);
            drawRoundedRect(listLeft, rowTop, rowWidth, row.cachedTotalHeight, ROW_AND_SEARCH_RADIUS, cardBg);

            // Illuminated 3px rounded Aqua-Cyan (#00E5FF) indicator pill on left edge when enabled
            if (row.toggleAnim > 0.02F && !isActionMacroRow(row)) {
                int pillA = Mth.clamp(Math.round(row.toggleAnim * 255.0F), 0, 255);
                int indicatorCol = (pillA << 24) | ClientThemeManager.getAccentRgb();
                int indH = Math.max(8, Math.round((ROW_HEIGHT - 16) * row.toggleAnim));
                int indY = rowTop + (ROW_HEIGHT - indH) / 2;
                drawRoundedRect(listLeft + 2, indY, 3, indH, 1, indicatorCol);
            }

            // Far-Left Module Icon Badge: subtle dark rounded square (8px radius) + category icon
            int iconBoxY = rowTop + (ROW_HEIGHT - iconBoxSize) / 2;
            int iconBoxBg = lerpColor(COLOR_ICON_BOX_BG, COLOR_ICON_BOX_HOVER_BG, row.hoverAnim);
            drawRoundedRect(iconBoxX, iconBoxY, iconBoxSize, iconBoxSize, ICON_BOX_RADIUS, iconBoxBg);
            int iconFg = isEnabled ? ClientThemeManager.getAccentArgb() : COLOR_TEXT_OFF_SLATE;
            enqueueCategoryIcon(row.categoryPillIndex, iconBoxX + 7, iconBoxY + 7, iconFg);

            // Typography: Pure White when ON, Muted Slate (0xFF9CA3AF) when OFF; Description in 0xFF8B94A5
            int titleColor = lerpColor(COLOR_TEXT_OFF_SLATE, COLOR_TEXT_BRIGHT_WHITE, isActionMacroRow(row) ? 1.0F : row.toggleAnim);
            enqueueComponentText(row.nunitoTitle, textStartX, rowTop + 9, titleColor);
            enqueueComponentText(row.nunitoRegularDesc, textStartX, rowTop + 24, COLOR_TEXT_DESC_PREMIUM);

            // Action Macro Check (AutoLunge): Hide the ON/OFF toggle pill and show only the Keybind pill + chevron
            boolean isActionMacro = isActionMacroRow(row);
            int rowChevronX = isActionMacro ? (listLeft + rowWidth - 18) : chevronX;
            int chevronY = rowTop + (ROW_HEIGHT - 8) / 2;
            enqueueComponentText(
                    row.expanded ? chevronExpandedComponent : chevronCollapsedComponent,
                    rowChevronX,
                    chevronY,
                    COLOR_TEXT_DIMMED_GRAY
            );

            refreshModuleBindLabelIfNeeded(row, false);
            boolean isBindingThis = (bindingRow == row);
            Component bindComp = isBindingThis ? bindDotsComponent : row.cachedBindComponent;
            int bindPillW = isBindingThis ? 28 : row.cachedBindPillWidth;
            int bindPillH = 15;
            int bindPillX = rowChevronX - bindPillW - 8;
            int bindPillY = rowTop + (ROW_HEIGHT - bindPillH) / 2;
            boolean bindHovered = mouseInsideViewport
                    && isHovered(mouseX, mouseY, bindPillX, bindPillY, bindPillW, bindPillH);
            row.bindHoverAnim = Mth.clamp(row.bindHoverAnim + (bindHovered ? hoverStep : -hoverStep), 0.0F, 1.0F);

            int bindBg = isBindingThis
                    ? COLOR_BIND_LISTENING
                    : lerpColor(COLOR_BIND_BG, COLOR_BIND_BG_HOVER, row.bindHoverAnim);
            drawRoundedRect(bindPillX, bindPillY, bindPillW, bindPillH, 6, bindBg);
            int bindTextW = isBindingThis ? 12 : row.cachedBindTextWidth;
            int bindTextCol = isBindingThis ? COLOR_TEXT_BRIGHT_WHITE : COLOR_TEXT_DIMMED_GRAY;
            enqueueComponentText(bindComp, bindPillX + (bindPillW - bindTextW) / 2, bindPillY + 4, bindTextCol);

            // iOS Pill Toggle Switch (Width = 24, Height = 12, Radius = 6) with animated solid white circle
            if (!isActionMacro) {
                int toggleY = rowTop + (ROW_HEIGHT - toggleH) / 2;
                enqueueIosPillToggleSwitch(toggleX, toggleY, toggleW, toggleH, row.toggleAnim);
            }

            if (row.expandAnim > 0.02F && row.cachedVisibleSettingCount > 0) {
                enqueueModuleSettingsDrawer(row, listLeft + 4, rowTop + ROW_HEIGHT, rowWidth - 8, mouseX, mouseY, mouseInsideViewport);
            }
        }

        if (filteredRowCount == 0) {
            if (emptySearchComponent == null) {
                emptySearchComponent = Component.literal("No modules match your search").withStyle(nunitoRegularStyle);
            }
            int msgW = this.font != null ? this.font.width(emptySearchComponent) : 140;
            enqueueComponentText(emptySearchComponent, listLeft + (rowWidth - msgW) / 2, listTop + Math.max(24, listHeight / 3), 0xFF6B7280);
        }

        // Slim 3px rounded scrollbar thumb on right edge fading in while scrolling
        if (maxScrollOffset > 1.0F && scrollbarFadeAnim > 0.02F) {
            float visibleRatio = Mth.clamp((float) listHeight / (float) Math.max(1, totalContentHeight), 0.12F, 1.0F);
            int thumbH = Math.max(22, Math.round(listHeight * visibleRatio));
            int travel = Math.max(1, listHeight - thumbH);
            int thumbY = listTop + Math.round((scrollOffset / maxScrollOffset) * travel);
            int sbAlpha = Mth.clamp(Math.round(scrollbarFadeAnim * 200.0F), 0, 200);
            drawRoundedRect(listRight - 4, thumbY, 3, thumbH, 1, (sbAlpha << 24) | ClientThemeManager.getAccentRgb());
        }
    }

    private void enqueueModuleSettingsDrawer(
            CachedModuleRow row,
            int drawerX,
            int drawerY,
            int drawerW,
            int mouseX,
            int mouseY,
            boolean mouseInsideViewport
    ) {
        int drawerH = row.cachedTotalHeight - ROW_HEIGHT;
        if (drawerH <= 4) {
            return;
        }

        drawRoundedRect(drawerX, drawerY, drawerW, drawerH - 3, 5, COLOR_DRAWER_BG);

        int curY = drawerY + 5;
        for (int i = 0; i < row.settings.length; i++) {
            CachedSettingEntry se = row.settings[i];
            if (!se.visible) {
                continue;
            }
            if (curY + SETTING_ROW_HEIGHT > drawerY + drawerH + 2) {
                break;
            }

            enqueueSingleSettingRow(se, drawerX, curY, drawerW, mouseX, mouseY, mouseInsideViewport);
            curY += SETTING_ROW_HEIGHT;
        }
    }

    private void enqueueSingleSettingRow(
            CachedSettingEntry se,
            int drawerX,
            int curY,
            int drawerW,
            int mouseX,
            int mouseY,
            boolean mouseInsideViewport
    ) {
        int labelX = drawerX + 14;
        int labelY = curY + (SETTING_ROW_HEIGHT - 8) / 2;
        enqueueComponentText(se.nameComponent, labelX, labelY, 0xFFD4D7E2);

        switch (se.type) {
            case 0 -> {
                int swW = TOGGLE_PILL_WIDTH;
                int swH = TOGGLE_PILL_HEIGHT;
                int swX = drawerX + drawerW - swW - 14;
                int swY = curY + (SETTING_ROW_HEIGHT - swH) / 2;
                enqueueIosPillToggleSwitch(swX, swY, swW, swH, se.toggleAnim);
            }
            case 1 -> {
                NumberSetting ns = (NumberSetting) se.setting;
                double val = ns.getValue();
                double min = ns.getMin();
                double max = ns.getMax();
                double ratio = max > min ? Mth.clamp((val - min) / (max - min), 0.0D, 1.0D) : 0.0D;

                int trackW = 120;
                int trackH = 4;
                int trackX = drawerX + drawerW - trackW - 14;
                int trackY = curY + (SETTING_ROW_HEIGHT - trackH) / 2;

                updateCachedSliderStringIfNeeded(se);
                int valStrX = trackX - se.cachedValueTextWidth - 8;
                enqueueComponentText(se.cachedValueComponent, valStrX, labelY, COLOR_TEXT_DIMMED_GRAY);

                drawRoundedRect(trackX, trackY, trackW, trackH, 2, COLOR_TOGGLE_OFF_BG);
                int fillW = Math.max(2, (int) Math.round(trackW * ratio));
                drawRoundedRect(trackX, trackY, fillW, trackH, 2, COLOR_PILL_ACTIVE_BG);

                int knobX = trackX + fillW - 4;
                int knobY = trackY - 2;
                drawRoundedRect(knobX, knobY, 8, 8, 4, COLOR_TOGGLE_KNOB_ON);

                if (activeDraggingSliderEntry == se) {
                    activeSliderTrackX = trackX;
                    activeSliderTrackW = trackW;
                }
            }
            case 2 -> {
                ModeSetting ms = (ModeSetting) se.setting;
                String currentMode = ms.getValue();
                int rightEdge = drawerX + drawerW - 14;
                for (int m = se.modeOptions.length - 1; m >= 0; m--) {
                    String modeLabel = se.modeOptions[m];
                    int pw = se.modePillWidths[m];
                    int px = rightEdge - pw;
                    int py = curY + 4;
                    boolean selected = modeLabel.equalsIgnoreCase(currentMode);
                    boolean hovered = mouseInsideViewport && isHovered(mouseX, mouseY, px, py, pw, 18);
                    int bg = selected ? COLOR_PILL_ACTIVE_BG : (hovered ? COLOR_BIND_BG_HOVER : COLOR_BIND_BG);
                    int fg = selected ? COLOR_PILL_ACTIVE_TEXT : COLOR_TEXT_DIMMED_GRAY;
                    drawRoundedRect(px, py, pw, 18, 4, bg);
                    enqueueComponentText(se.modeOptionComponents[m], px + 6, py + 5, fg);
                    rightEdge = px - 4;
                }
            }
            case 3 -> {
                MultiModeSetting mms = (MultiModeSetting) se.setting;
                int rightEdge = drawerX + drawerW - 14;
                int minPx = labelX + se.cachedNameWidth + 12;
                for (int m = se.modeOptions.length - 1; m >= 0; m--) {
                    String opt = se.modeOptions[m];
                    int pw = se.modePillWidths[m];
                    int px = rightEdge - pw;
                    if (px < minPx) {
                        break;
                    }
                    int py = curY + 4;
                    boolean selected = mms.isSelected(opt);
                    boolean hovered = mouseInsideViewport && isHovered(mouseX, mouseY, px, py, pw, 18);
                    int bg = selected ? COLOR_PILL_ACTIVE_BG : (hovered ? COLOR_BIND_BG_HOVER : COLOR_BIND_BG);
                    int fg = selected ? COLOR_PILL_ACTIVE_TEXT : COLOR_TEXT_DIMMED_GRAY;
                    drawRoundedRect(px, py, pw, 18, 4, bg);
                    enqueueComponentText(se.modeOptionComponents[m], px + 5, py + 5, fg);
                    rightEdge = px - 3;
                }
            }
        }
    }

    private static Method cachedFaceDrawMethod = null;
    private static boolean faceDrawReflectionInit = false;

    private void enqueueFriendsTopInputBar(int mouseX, int mouseY) {
        int barY = winY + 42;
        int barH = 22;

        String bindLabelStr;
        if (listeningForFriendBind) {
            bindLabelStr = "Bind: [...]";
        } else {
            int fKey = FriendManager.getFriendBindKey();
            bindLabelStr = "Bind: [" + formatBoundKeyDisplayName(fKey) + "]";
        }
        Component bindComp = Component.literal(bindLabelStr).withStyle(nunitoRegularStyle);
        int bindLabelW = this.font.width(bindComp);
        friendBindBtnW = Math.max(68, bindLabelW + 16);
        friendBindBtnH = barH;
        friendBindBtnX = listRight - friendBindBtnW;
        friendBindBtnY = barY;

        Component addComp = Component.literal("Add").withStyle(nunitoRegularStyle);
        int addLabelW = this.font.width(addComp);
        friendAddBtnW = Math.max(44, addLabelW + 18);
        friendAddBtnH = barH;
        friendAddBtnX = friendBindBtnX - friendAddBtnW - 6;
        friendAddBtnY = barY;

        friendInputBoxX = listLeft;
        friendInputBoxY = barY;
        friendInputBoxW = Math.max(120, friendAddBtnX - friendInputBoxX - 6);
        friendInputBoxH = barH;

        int inputBg = friendInputFocused ? COLOR_SEARCH_BG_FOCUSED : COLOR_SEARCH_BG;
        drawRoundedRect(friendInputBoxX, friendInputBoxY, friendInputBoxW, friendInputBoxH, SEARCH_BAR_RADIUS, inputBg);

        String displayInput;
        int inputTextCol;
        if (friendInputText.isEmpty() && !friendInputFocused) {
            displayInput = "Enter username...";
            inputTextCol = COLOR_TEXT_DIMMED_GRAY;
        } else {
            boolean blink = (System.currentTimeMillis() / 500L) % 2L == 0L;
            displayInput = friendInputText + (friendInputFocused && blink ? "|" : "");
            inputTextCol = COLOR_TEXT_BRIGHT_WHITE;
        }
        if (!displayInput.equals(lastRenderedFriendInputString)) {
            lastRenderedFriendInputString = displayInput;
            cachedFriendInputComponent = Component.literal(displayInput).withStyle(nunitoRegularStyle);
        }
        enqueueComponentText(cachedFriendInputComponent, friendInputBoxX + 10, friendInputBoxY + 7, inputTextCol);

        boolean addHover = isHovered(mouseX, mouseY, friendAddBtnX, friendAddBtnY, friendAddBtnW, friendAddBtnH);
        int addBg = addHover ? 0xFFE2E6F5 : COLOR_PILL_ACTIVE_BG;
        drawRoundedRect(friendAddBtnX, friendAddBtnY, friendAddBtnW, friendAddBtnH, CATEGORY_PILL_RADIUS, addBg);
        enqueueComponentText(
                addComp,
                friendAddBtnX + (friendAddBtnW - addLabelW) / 2,
                friendAddBtnY + 7,
                COLOR_PILL_ACTIVE_TEXT
        );

        boolean bindHover = isHovered(mouseX, mouseY, friendBindBtnX, friendBindBtnY, friendBindBtnW, friendBindBtnH);
        int bindBg = listeningForFriendBind
                ? COLOR_BIND_LISTENING
                : (bindHover ? COLOR_BIND_BG_HOVER : COLOR_BIND_BG);
        drawRoundedRect(friendBindBtnX, friendBindBtnY, friendBindBtnW, friendBindBtnH, CATEGORY_PILL_RADIUS, bindBg);
        enqueueComponentText(
                bindComp,
                friendBindBtnX + (friendBindBtnW - bindLabelW) / 2,
                friendBindBtnY + 7,
                listeningForFriendBind ? COLOR_TEXT_BRIGHT_WHITE : COLOR_TEXT_DIMMED_GRAY
        );
    }

    private void renderFriendsListPanel(GuiGraphics guiGraphics, float dt, int mouseX, int mouseY) {
        List<FriendRecord> friends = FriendManager.getSortedFriends();
        int friendRowH = 40;
        int totalH = friends.size() * friendRowH;
        friendMaxScrollOffset = Math.max(0.0F, (float) (totalH - listHeight));
        friendTargetScrollOffset = Mth.clamp(friendTargetScrollOffset, 0.0F, friendMaxScrollOffset);
        friendScrollOffset = Mth.clamp(friendScrollOffset, 0.0F, friendMaxScrollOffset);

        if (friends.isEmpty()) {
            Component emptyComp = Component.literal("No friends added yet. Enter a username above or use Bind in-game.")
                    .withStyle(nunitoRegularStyle);
            int ew = this.font.width(emptyComp);
            enqueueComponentText(emptyComp, winX + (winW - ew) / 2, listTop + listHeight / 2 - 6, COLOR_TEXT_DIMMED_GRAY);
            flushBatchedRenderPasses(guiGraphics);
            return;
        }

        int scrollInt = Math.round(friendScrollOffset);
        boolean mouseInside = isHovered(mouseX, mouseY, listLeft, listTop, listRight - listLeft, listHeight);
        Component removeXComp = Component.literal("X").withStyle(nunitoRegularStyle);
        int removeXW = this.font.width(removeXComp);

        for (int i = 0; i < friends.size(); i++) {
            FriendRecord rec = friends.get(i);
            int rowTop = listTop + i * friendRowH - scrollInt;
            int rowBottom = rowTop + friendRowH;
            if (rowBottom < listTop || rowTop > listBottom) {
                continue;
            }

            boolean rowHovered = mouseInside && isHovered(mouseX, mouseY, listLeft, rowTop, rowWidth, friendRowH);
            if (rowHovered) {
                drawRoundedRect(listLeft, rowTop, rowWidth, friendRowH, ROW_AND_SEARCH_RADIUS, COLOR_ROW_HOVER_BG);
            }

            int avatarSize = 24;
            int avatarX = listLeft + 10;
            int avatarY = rowTop + (friendRowH - avatarSize) / 2;
            drawRoundedRect(avatarX - 1, avatarY - 1, avatarSize + 2, avatarSize + 2, 6, COLOR_ICON_BOX_BG);

            int textX = avatarX + avatarSize + 10;
            Component nameComp = Component.literal(rec.username).withStyle(nunitoRegularStyle);
            enqueueComponentText(nameComp, textX, rowTop + 9, COLOR_TEXT_BRIGHT_WHITE);

            String subText = (!rec.alias.isEmpty() && !rec.alias.equalsIgnoreCase(rec.username))
                    ? ("Alias: " + rec.alias + "  •  UUID Verified")
                    : "Ally Whitelisted  •  UUID Verified";
            Component subComp = Component.literal(subText).withStyle(nunitoRegularStyle);
            enqueueComponentText(subComp, textX, rowTop + 22, 0xFF00FFCC);

            int remW = 28;
            int remH = 18;
            int remX = listLeft + rowWidth - remW - 10;
            int remY = rowTop + (friendRowH - remH) / 2;
            boolean remHover = mouseInside && isHovered(mouseX, mouseY, remX, remY, remW, remH);
            int remBg = remHover ? 0xEE9E2A3B : COLOR_BIND_BG;
            int remFg = remHover ? COLOR_TEXT_BRIGHT_WHITE : COLOR_TEXT_DIMMED_GRAY;
            drawRoundedRect(remX, remY, remW, remH, 5, remBg);
            enqueueComponentText(removeXComp, remX + (remW - removeXW) / 2, remY + 5, remFg);
        }

        flushBatchedRenderPasses(guiGraphics);

        for (int i = 0; i < friends.size(); i++) {
            FriendRecord rec = friends.get(i);
            int rowTop = listTop + i * friendRowH - scrollInt;
            int rowBottom = rowTop + friendRowH;
            if (rowBottom < listTop || rowTop > listBottom) {
                continue;
            }
            int avatarSize = 24;
            int avatarX = listLeft + 10;
            int avatarY = rowTop + (friendRowH - avatarSize) / 2;
            drawFriendAvatar(guiGraphics, rec, avatarX, avatarY, avatarSize);
        }
    }

    private void drawFriendAvatar(GuiGraphics guiGraphics, FriendRecord rec, int x, int y, int size) {
        PlayerInfo info = FriendManager.resolveOnlineFriendPlayerInfo(rec.uuid, rec.username);
        if (info != null) {
            try {
                if (!faceDrawReflectionInit) {
                    faceDrawReflectionInit = true;
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
                }
                if (cachedFaceDrawMethod != null) {
                    Object skinObj = info.getSkin();
                    Class<?> skinParamType = cachedFaceDrawMethod.getParameterTypes()[1];
                    if (skinObj != null && skinParamType.isAssignableFrom(skinObj.getClass())) {
                        cachedFaceDrawMethod.invoke(null, guiGraphics, skinObj, x, y, size);
                        return;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        int seed = rec.username != null ? rec.username.toLowerCase(Locale.ROOT).hashCode() : 0;
        int baseHueCol = 0xFF243447 | ((seed & 0x3F) << 16) | (((seed >> 6) & 0x3F) << 8);
        guiGraphics.fill(x + 2, y + 2, x + size - 2, y + size - 2, baseHueCol);
        guiGraphics.fill(x + 5, y + 9, x + 9, y + 13, 0xFF00FFCC);
        guiGraphics.fill(x + size - 9, y + 9, x + size - 5, y + 13, 0xFF00FFCC);
        guiGraphics.fill(x + 7, y + size - 7, x + size - 7, y + size - 5, 0xCCFFFFFF);
    }

    private void submitFriendInputNow() {
        String trimmed = friendInputText != null ? friendInputText.trim() : "";
        if (trimmed.isEmpty()) {
            return;
        }
        boolean added = FriendManager.addFriendByNameOrAliasInput(trimmed);
        friendInputText = "";
        lastRenderedFriendInputString = "";
        SoundUtil.playTogglePop(added);
    }

    private void enqueueClientAudioTabView(int mouseX, int mouseY) {
        int cardY = listTop + 10;
        int cardH = 68;
        drawRoundedRect(listLeft, cardY, rowWidth, cardH, ROW_AND_SEARCH_RADIUS, COLOR_ROW_ODD_BG);
        enqueueComponentText(clientAudioHeaderComponent, listLeft + 14, cardY + 10, COLOR_TEXT_BRIGHT_WHITE);

        if (clientTabUiVolumeEntry != null) {
            enqueueSingleSettingRow(
                    clientTabUiVolumeEntry,
                    listLeft,
                    cardY + 32,
                    rowWidth,
                    mouseX,
                    mouseY,
                    true
            );
        }
    }

    private void enqueueSecondaryTabView() {
        Component comp = cachedSecondaryTabComponents[activeTopTabIndex];
        int msgW = cachedSecondaryTabMessageWidths[activeTopTabIndex];
        int tx = winX + (winW - msgW) / 2;
        int ty = listTop + listHeight / 2 - 6;
        enqueueComponentText(comp, tx, ty, COLOR_TEXT_DIMMED_GRAY);
    }

    private void enqueueScrollbar() {
        int barX = listRight - 3;
        int barW = 3;
        drawRoundedRect(barX, listTop, barW, listHeight, 1, 0x55222530);

        float viewRatio = Mth.clamp((float) listHeight / (float) Math.max(listHeight, totalContentHeight), 0.12F, 1.0F);
        int thumbH = Math.max(24, Math.round(listHeight * viewRatio));
        float scrollProgress = maxScrollOffset > 0.0F ? (scrollOffset / maxScrollOffset) : 0.0F;
        int thumbY = listTop + Math.round((listHeight - thumbH) * scrollProgress);

        int thumbCol = draggingScrollbar ? COLOR_PILL_ACTIVE_BG : 0xAA7E8291;
        drawRoundedRect(barX, thumbY, barW, thumbH, 1, thumbCol);
    }

    private void enqueueIosPillToggleSwitch(
            int x,
            int y,
            int w,
            int h,
            float animProgress
    ) {
        float t = Mth.clamp(animProgress, 0.0F, 1.0F);
        int liveAccent = ClientThemeManager.getAccentArgb();
        int trackColor = lerpColor(COLOR_TOGGLE_OFF_BG, liveAccent, t);
        drawRoundedRect(x, y, w, h, Math.max(4, h / 2), trackColor);

        // Elastic pill thumb: stretches horizontally mid-slide (sin(t * PI))
        int knobH = Math.max(6, h - 4);
        int elasticStretch = Math.round(4.0F * (float) Math.sin(t * Math.PI));
        int knobW = knobH + elasticStretch;
        int minKnobX = x + 2;
        int maxKnobX = x + w - knobW - 2;
        int knobX = Math.round(Mth.lerp(t, minKnobX, maxKnobX));
        int knobY = y + (h - knobH) / 2;

        drawRoundedRect(knobX, knobY, knobW, knobH, knobH / 2, 0xFFFFFFFF);
    }

    private void enqueueSearchMagnifierIcon(int ix, int iy, int color) {
        // 7x7 circular lens + 3px diagonal handle
        drawRoundedRect(ix, iy, 7, 7, 3, multiplyAlpha(color, 0.35F));
        enqueueBgQuad(ix + 2, iy + 1, ix + 5, iy + 2, color);
        enqueueBgQuad(ix + 2, iy + 5, ix + 5, iy + 6, color);
        enqueueBgQuad(ix + 1, iy + 2, ix + 2, iy + 5, color);
        enqueueBgQuad(ix + 5, iy + 2, ix + 6, iy + 5, color);
        enqueueBgQuad(ix + 6, iy + 6, ix + 8, iy + 8, color);
        enqueueBgQuad(ix + 7, iy + 7, ix + 9, iy + 9, color);
    }

    private void enqueueCategoryIcon(int categoryIndex, int ix, int iy, int color) {
        int dimColor = multiplyAlpha(color, 0.55F);
        switch (categoryIndex) {
            case 1 -> {
                // Combat: Crossed blade / crosshair emblem (14x14)
                enqueueBgQuad(ix + 6, iy + 1, ix + 8, iy + 13, color);
                enqueueBgQuad(ix + 1, iy + 6, ix + 13, iy + 8, color);
                drawRoundedRect(ix + 4, iy + 4, 6, 6, 3, dimColor);
            }
            case 2 -> {
                // Movement: Dual forward speed chevrons / wing (14x14)
                enqueueBgQuad(ix + 2, iy + 3, ix + 5, iy + 5, dimColor);
                enqueueBgQuad(ix + 4, iy + 5, ix + 7, iy + 9, color);
                enqueueBgQuad(ix + 2, iy + 9, ix + 5, iy + 11, dimColor);
                enqueueBgQuad(ix + 7, iy + 3, ix + 10, iy + 5, dimColor);
                enqueueBgQuad(ix + 9, iy + 5, ix + 12, iy + 9, color);
                enqueueBgQuad(ix + 7, iy + 9, ix + 10, iy + 11, dimColor);
            }
            case 3 -> {
                // Render / Visual: Eye / lens prism (14x14)
                drawRoundedRect(ix + 1, iy + 3, 12, 8, 4, dimColor);
                drawRoundedRect(ix + 5, iy + 5, 4, 4, 2, color);
            }
            case 4 -> {
                // Player: Head & shoulders silhouette (14x14)
                drawRoundedRect(ix + 4, iy + 1, 6, 6, 3, color);
                drawRoundedRect(ix + 2, iy + 8, 10, 5, 2, dimColor);
            }
            case 5 -> {
                // World: Isometric block / globe grid (14x14)
                drawRoundedRect(ix + 2, iy + 2, 10, 10, 3, dimColor);
                enqueueBgQuad(ix + 4, iy + 6, ix + 10, iy + 8, color);
                enqueueBgQuad(ix + 6, iy + 4, ix + 8, iy + 10, color);
            }
            default -> {
                // Misc / Utility: 4-node module spark grid (14x14)
                drawRoundedRect(ix + 2, iy + 2, 4, 4, 2, color);
                drawRoundedRect(ix + 8, iy + 2, 4, 4, 2, dimColor);
                drawRoundedRect(ix + 2, iy + 8, 4, 4, 2, dimColor);
                drawRoundedRect(ix + 8, iy + 8, 4, 4, 2, color);
            }
        }
    }

    // =========================================================================
    // Type-Safe Keybind Resolution & Reflection Helpers
    // =========================================================================
    private static void initModuleKeyReflection() {
        if (moduleKeyReflectionInitialized) {
            return;
        }
        moduleKeyReflectionInitialized = true;
        try {
            for (Method m : Module.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (m.getParameterCount() == 0 && m.getReturnType() == int.class
                        && (n.contains("key") || n.contains("bind"))) {
                    m.setAccessible(true);
                    cachedModuleGetKeyMethod = m;
                    break;
                }
            }
            for (Method m : Module.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class
                        && (n.contains("key") || n.contains("bind"))) {
                    m.setAccessible(true);
                    cachedModuleSetKeyMethod = m;
                    break;
                }
            }
            for (Field f : Module.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                    f.setAccessible(true);
                    cachedModuleKeyField = f;
                    String fn = f.getName().toLowerCase(Locale.ROOT);
                    if (fn.contains("key") || fn.contains("bind")) {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isDedicatedKeybindSettingInstance(Setting<?> s) {
        if (s == null
                || s instanceof BooleanSetting
                || s instanceof NumberSetting
                || s instanceof ModeSetting
                || s instanceof MultiModeSetting
                || s instanceof StringSetting) {
            return false;
        }
        String clsName = s.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return clsName.contains("bind") || clsName.contains("key");
    }

    private static int getModuleBindKey(Module mod) {
        if (mod == null) {
            return -1;
        }
        if (mod instanceof LungeHelper) {
            int macroKey = LungeHelper.getMacroBoundKey();
            if (macroKey != -1 && macroKey != 0 && macroKey != GLFW.GLFW_KEY_UNKNOWN) {
                return macroKey;
            }
        } else if ("ElytraHelper".equalsIgnoreCase(mod.getClass().getSimpleName())) {
            int macroKey = invokeStaticMacroGetKey(mod);
            if (macroKey != -1 && macroKey != 0 && macroKey != GLFW.GLFW_KEY_UNKNOWN) {
                return macroKey;
            }
        }
        initModuleKeyReflection();
        try {
            if (cachedModuleGetKeyMethod != null) {
                Object val = cachedModuleGetKeyMethod.invoke(mod);
                if (val instanceof Number num) {
                    int k = num.intValue();
                    if (k != 0 && k != -1 && k != GLFW.GLFW_KEY_UNKNOWN) {
                        return k;
                    }
                }
            }
            if (cachedModuleKeyField != null) {
                int k = cachedModuleKeyField.getInt(mod);
                if (k != 0 && k != -1 && k != GLFW.GLFW_KEY_UNKNOWN) {
                    return k;
                }
            }
            List<Setting<?>> rawSettings = mod.getSettings();
            if (rawSettings != null) {
                for (int i = 0; i < rawSettings.size(); i++) {
                    Setting<?> s = rawSettings.get(i);
                    if (isDedicatedKeybindSettingInstance(s)) {
                        Object v = s.getValue();
                        if (v instanceof Integer k && k != 0 && k != -1 && k != GLFW.GLFW_KEY_UNKNOWN) {
                            return k;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void setModuleBindKey(Module mod, int key) {
        if (mod == null) {
            return;
        }
        if (mod instanceof LungeHelper) {
            LungeHelper.setMacroBoundKey(key);
        } else if ("ElytraHelper".equalsIgnoreCase(mod.getClass().getSimpleName())) {
            invokeStaticMacroSetKey(mod, key);
        }
        initModuleKeyReflection();
        try {
            if (cachedModuleSetKeyMethod != null) {
                cachedModuleSetKeyMethod.invoke(mod, key);
            }
            if (cachedModuleKeyField != null) {
                cachedModuleKeyField.setInt(mod, key);
            }
            List<Setting<?>> rawSettings = mod.getSettings();
            if (rawSettings != null) {
                for (int i = 0; i < rawSettings.size(); i++) {
                    Setting<?> s = rawSettings.get(i);
                    if (isDedicatedKeybindSettingInstance(s)) {
                        for (Method sm : s.getClass().getMethods()) {
                            if (sm.getParameterCount() == 1
                                    && (sm.getParameterTypes()[0] == int.class
                                    || sm.getParameterTypes()[0] == Integer.class)) {
                                try {
                                    sm.setAccessible(true);
                                    sm.invoke(s, key);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private int resolveEffectiveBindKey(CachedModuleRow row) {
        if (row == null) {
            return -1;
        }
        if (row.boundKey == -1 && row.name != null) {
            Integer persisted = PERSISTENT_MODULE_BINDS.get(row.name.toLowerCase(Locale.ROOT));
            if (persisted != null && persisted != -1 && persisted != 0 && persisted != GLFW.GLFW_KEY_UNKNOWN) {
                row.boundKey = persisted;
                return row.boundKey;
            }
        }
        int modKey = getModuleBindKey(row.module);
        if (modKey != -1 && modKey != 0 && modKey != GLFW.GLFW_KEY_UNKNOWN && row.boundKey == -1) {
            row.boundKey = modKey;
        }
        return row.boundKey;
    }

    private void assignModuleBind(CachedModuleRow row, int newKey) {
        if (row == null) {
            return;
        }
        int normalized = (newKey == 0 || newKey == GLFW.GLFW_KEY_UNKNOWN) ? -1 : newKey;
        row.boundKey = normalized;
        if (row.name != null) {
            PERSISTENT_MODULE_BINDS.put(row.name.toLowerCase(Locale.ROOT), normalized);
        }
        if (isActionMacroRow(row)) {
            if (isElytraHelperRow(row)) {
                invokeStaticMacroSetKey(row.module, normalized);
            } else {
                LungeHelper.setMacroBoundKey(normalized);
            }
        }
        setModuleBindKey(row.module, normalized);
        if ("ClickGUI".equalsIgnoreCase(row.name) || "ClickGui".equalsIgnoreCase(row.name)) {
            ClientThemeManager.setClickGuiKey(normalized);
            syncClickGuiKeyToBindings(ClientThemeManager.getClickGuiKey());
        }
        refreshModuleBindLabelIfNeeded(row, true);
        saveConfigNow();
    }

    private static boolean isBindRelatedSetting(Setting<?> s, String sName) {
        if (s == null) {
            return false;
        }
        String clsName = s.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (clsName.contains("bind")) {
            return true;
        }
        if (sName != null) {
            String lower = sName.trim().toLowerCase(Locale.ROOT);
            return lower.equals("bind")
                    || lower.equals("keybind")
                    || lower.equals("key")
                    || lower.contains("bind");
        }
        return false;
    }

    private static double extractNumberSettingStep(NumberSetting ns) {
        if (ns == null) {
            return 0.1D;
        }
        double range = Math.max(0.0001D, ns.getMax() - ns.getMin());
        try {
            for (Method m : NumberSetting.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (m.getParameterCount() == 0
                        && (m.getReturnType() == double.class || m.getReturnType() == float.class || Number.class.isAssignableFrom(m.getReturnType()))
                        && (n.contains("step") || n.contains("inc"))) {
                    m.setAccessible(true);
                    Object v = m.invoke(ns);
                    if (v instanceof Number num) {
                        double d = num.doubleValue();
                        if (d > 0.0D && d <= range) {
                            return d;
                        }
                    }
                }
            }
            for (Field f : NumberSetting.class.getDeclaredFields()) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if ((f.getType() == double.class || f.getType() == float.class)
                        && (n.contains("step") || n.contains("inc"))) {
                    f.setAccessible(true);
                    double d = f.getDouble(ns);
                    if (d > 0.0D && d <= range) {
                        return d;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String nameLower = ns.getName() != null ? ns.getName().toLowerCase(Locale.ROOT) : "";
        if (nameLower.contains("tick") || nameLower.contains("slot") || nameLower.contains("delay")
                || nameLower.contains("count") || nameLower.contains("fov") || nameLower.contains("cps")) {
            return 1.0D;
        }
        return range >= 50.0D ? 1.0D : 0.1D;
    }

    private static String[] extractMultiModeOptions(MultiModeSetting mms) {
        if (mms == null) {
            return new String[0];
        }
        try {
            for (Method m : MultiModeSetting.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    if (m.getReturnType() == String[].class) {
                        String[] arr = (String[]) m.invoke(mms);
                        if (arr != null && arr.length > 0) {
                            return arr;
                        }
                    } else if (Collection.class.isAssignableFrom(m.getReturnType())) {
                        Collection<?> col = (Collection<?>) m.invoke(mms);
                        if (col != null && !col.isEmpty()) {
                            List<String> out = new ArrayList<>(col.size());
                            for (Object o : col) {
                                if (o != null) {
                                    out.add(o.toString());
                                }
                            }
                            if (!out.isEmpty()) {
                                return out.toArray(new String[0]);
                            }
                        }
                    }
                }
            }
            for (Field f : MultiModeSetting.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(mms);
                if (val instanceof String[] arr && arr.length > 0) {
                    return arr;
                }
                if (val instanceof Collection<?> col && !col.isEmpty()) {
                    List<String> out = new ArrayList<>(col.size());
                    for (Object o : col) {
                        if (o != null) {
                            out.add(o.toString());
                        }
                    }
                    if (!out.isEmpty()) {
                        return out.toArray(new String[0]);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[0];
    }

    @SuppressWarnings("unchecked")
    private static void toggleMultiModeOption(MultiModeSetting mms, String opt) {
        if (mms == null || opt == null) {
            return;
        }
        try {
            for (Method m : MultiModeSetting.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class
                        && !n.startsWith("is") && !n.startsWith("get") && !n.startsWith("has")
                        && (n.contains("toggle") || n.contains("click"))) {
                    m.setAccessible(true);
                    m.invoke(mms, opt);
                    return;
                }
                if (m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == String.class
                        && m.getParameterTypes()[1] == boolean.class) {
                    m.setAccessible(true);
                    m.invoke(mms, opt, !mms.isSelected(opt));
                    return;
                }
            }
            Object rawVal = mms.getValue();
            if (rawVal instanceof Collection<?> col) {
                Collection<String> strCol = (Collection<String>) col;
                if (strCol.contains(opt)) {
                    strCol.remove(opt);
                } else {
                    strCol.add(opt);
                }
                return;
            }
            for (Field f : MultiModeSetting.class.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(mms);
                if (val instanceof Collection<?> col) {
                    String[] allOpts = extractMultiModeOptions(mms);
                    if (col.size() == allOpts.length && col.contains(opt)) {
                        continue;
                    }
                    Collection<String> strCol = (Collection<String>) col;
                    if (strCol.contains(opt)) {
                        strCol.remove(opt);
                    } else {
                        strCol.add(opt);
                    }
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void rebuildModuleCacheFromRegistry() {
        List<Module> rawDiscovered = discoverClientModules();
        List<Module> discovered = new ArrayList<>(rawDiscovered.size() + 2);

        java.util.Set<String> seenModuleNames = new java.util.HashSet<>();
        boolean hasLungeHelper = false;
        boolean hasSilentAura = false;
        boolean hasAutoMace = false;
        boolean hasAutoElytra = false;
        for (int i = 0; i < rawDiscovered.size(); i++) {
            Module m = rawDiscovered.get(i);
            if (m == null) {
                continue;
            }
            String mName = m.getName() != null ? m.getName() : m.getClass().getSimpleName();
            String clsName = m.getClass().getSimpleName();
            // Hide legacy Aura / AuraModule / AutoLunge / AutoSpear duplicates
            if ("Aura".equalsIgnoreCase(mName) || "AuraModule".equalsIgnoreCase(mName)
                    || "AutoLunge".equalsIgnoreCase(clsName) || "AutoSpear".equalsIgnoreCase(clsName)
                    || "AutoLunge".equalsIgnoreCase(mName) || "AutoSpear".equalsIgnoreCase(mName)) {
                continue;
            }
            if (m instanceof LungeHelper || "LungeHelper".equalsIgnoreCase(mName)) {
                if (hasLungeHelper) {
                    continue;
                }
                hasLungeHelper = true;
            }
            if (m instanceof SilentAura || "SilentAura".equalsIgnoreCase(mName)) {
                if (hasSilentAura) {
                    continue;
                }
                hasSilentAura = true;
            }
            if (m instanceof MaceTarget || "AutoMace".equalsIgnoreCase(mName) || "MaceTarget".equalsIgnoreCase(mName)) {
                if (hasAutoMace) {
                    continue;
                }
                hasAutoMace = true;
            }
            if (m instanceof ElytraTarget || "AutoElytra".equalsIgnoreCase(mName) || "ElytraTarget".equalsIgnoreCase(mName)) {
                if (hasAutoElytra) {
                    continue;
                }
                hasAutoElytra = true;
            }
            String dedupKey = mName.trim().toLowerCase(Locale.ROOT);
            if (!seenModuleNames.add(dedupKey)) {
                continue;
            }
            discovered.add(m);
        }
        if (!hasSilentAura && seenModuleNames.add("silentaura")) {
            try {
                discovered.add(SilentAura.getInstance());
            } catch (Throwable ignored) {
            }
        }
        if (!hasLungeHelper && seenModuleNames.add("lungehelper")) {
            try {
                discovered.add(LungeHelper.getInstance());
            } catch (Throwable ignored) {
            }
        }
        if (!hasAutoMace && seenModuleNames.add("automace")) {
            try {
                MaceTarget mt = MaceTarget.getInstance() != null ? MaceTarget.getInstance() : new MaceTarget();
                discovered.add(mt);
            } catch (Throwable ignored) {
            }
        }
        if (!hasAutoElytra && seenModuleNames.add("autoelytra")) {
            try {
                ElytraTarget et = ElytraTarget.getInstance() != null ? ElytraTarget.getInstance() : new ElytraTarget();
                discovered.add(et);
            } catch (Throwable ignored) {
            }
        }

        discovered.sort(Comparator.comparing(m -> (m != null && m.getName() != null) ? m.getName().toLowerCase(Locale.ROOT) : ""));

        int total = discovered.size();
        allModuleRows = new CachedModuleRow[total];
        filteredRows = new CachedModuleRow[total];

        for (int i = 0; i < total; i++) {
            Module mod = discovered.get(i);
            String safeName = (mod.getName() != null && !mod.getName().isEmpty())
                    ? mod.getName()
                    : mod.getClass().getSimpleName();
            String safeDesc = extractModuleDescription(mod, safeName);
            int catIdx = (mod instanceof LungeHelper) ? 1 : mapCategoryToPillIndex(mod.getCategory());
            CachedSettingEntry[] settings = extractModuleSettings(mod, safeName);
            Integer persistedBind = PERSISTENT_MODULE_BINDS.get(safeName.toLowerCase(Locale.ROOT));
            int initialKey = (persistedBind != null) ? persistedBind : getModuleBindKey(mod);
            if (initialKey != -1 && initialKey != 0 && initialKey != GLFW.GLFW_KEY_UNKNOWN) {
                PERSISTENT_MODULE_BINDS.put(safeName.toLowerCase(Locale.ROOT), initialKey);
            }
            allModuleRows[i] = new CachedModuleRow(mod, safeName, safeDesc, catIdx, settings, initialKey);
        }
        loadConfigIfPresent();
        syncClickGuiKeyToBindings(ClientThemeManager.getClickGuiKey());
    }

    private List<Module> discoverClientModules() {
        List<Module> result = new ArrayList<>();
        String[] candidateRepoClasses = new String[]{
                "attrium.ru.Main",
                "attrium.ru.manager.Manager",
                "attrium.ru.api.module.ModuleRepository",
                "attrium.ru.api.module.ModuleManager",
                "attrium.ru.manager.ModuleManager"
        };

        for (String className : candidateRepoClasses) {
            try {
                Class<?> cls = Class.forName(className);
                extractModulesFromClassOrInstance(cls, null, result);
                if (!result.isEmpty()) {
                    return result;
                }
                for (Field f : cls.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        Object singleton = f.get(null);
                        if (singleton != null) {
                            extractModulesFromClassOrInstance(singleton.getClass(), singleton, result);
                            if (!result.isEmpty()) {
                                return result;
                            }
                        }
                    }
                }
                for (Method m : cls.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                            && (m.getName().equalsIgnoreCase("getInstance") || m.getName().equalsIgnoreCase("instance"))) {
                        m.setAccessible(true);
                        Object singleton = m.invoke(null);
                        if (singleton != null) {
                            extractModulesFromClassOrInstance(singleton.getClass(), singleton, result);
                            if (!result.isEmpty()) {
                                return result;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private void extractModulesFromClassOrInstance(Class<?> cls, Object instanceObj, List<Module> out) {
        if (cls == null) {
            return;
        }
        try {
            for (Field f : cls.getDeclaredFields()) {
                boolean isStatic = Modifier.isStatic(f.getModifiers());
                if (!isStatic && instanceObj == null) {
                    continue;
                }
                f.setAccessible(true);
                Object val = f.get(isStatic ? null : instanceObj);
                if (val == null) {
                    continue;
                }
                if (val instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Module) {
                    for (Object item : list) {
                        if (item instanceof Module mod && !out.contains(mod)) {
                            out.add(mod);
                        }
                    }
                    if (!out.isEmpty()) {
                        return;
                    }
                }
                String typeName = val.getClass().getSimpleName().toLowerCase(Locale.ROOT);
                if (typeName.contains("module") || typeName.contains("manager") || typeName.contains("repository")) {
                    extractModulesFromObjectDirect(val, out);
                    if (!out.isEmpty()) {
                        return;
                    }
                }
            }
            for (Method m : cls.getDeclaredMethods()) {
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                if ((!isStatic && instanceObj == null) || m.getParameterCount() != 0) {
                    continue;
                }
                m.setAccessible(true);
                Object val = m.invoke(isStatic ? null : instanceObj);
                if (val instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Module) {
                    for (Object item : list) {
                        if (item instanceof Module mod && !out.contains(mod)) {
                            out.add(mod);
                        }
                    }
                    if (!out.isEmpty()) {
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void extractModulesFromObjectDirect(Object managerObj, List<Module> out) {
        Class<?> cls = managerObj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(managerObj);
                    if (val instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Module) {
                        for (Object item : list) {
                            if (item instanceof Module mod && !out.contains(mod)) {
                                out.add(mod);
                            }
                        }
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private String extractModuleDescription(Module mod, String fallbackName) {
        try {
            for (Method m : Module.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == String.class
                        && !m.getName().equals("getName") && !m.getName().equals("toString")) {
                    m.setAccessible(true);
                    Object res = m.invoke(mod);
                    if (res instanceof String s && !s.isEmpty()) {
                        return s;
                    }
                }
            }
            for (Field f : Module.class.getDeclaredFields()) {
                if (f.getType() == String.class && !f.getName().equalsIgnoreCase("name")) {
                    f.setAccessible(true);
                    Object res = f.get(mod);
                    if (res instanceof String s && !s.isEmpty()) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "Configure " + fallbackName + " module settings";
    }

    private CachedSettingEntry[] extractModuleSettings(Module mod, String modName) {
        List<CachedSettingEntry> list = new ArrayList<>();
        if (modName != null && (modName.toLowerCase(Locale.ROOT).contains("sound") || modName.toLowerCase(Locale.ROOT).contains("gui"))) {
            list.add(new CachedSettingEntry(SoundUtil.UI_VOLUME_SETTING, "UI Volume", 1, 0.05D, new String[0]));
        }
        try {
            List<Setting<?>> rawSettings = mod.getSettings();
            if (rawSettings != null) {
                for (int i = 0; i < rawSettings.size(); i++) {
                    Setting<?> s = rawSettings.get(i);
                    if (s == null) {
                        continue;
                    }
                    String sName = s.getName();
                    if (sName != null && (sName.startsWith("_") || sName.equalsIgnoreCase("UI Volume"))) {
                        continue;
                    }
                    if (isBindRelatedSetting(s, sName)) {
                        continue;
                    }

                    int type = -1;
                    double step = 0.1D;
                    String[] opts = null;

                    if (s instanceof BooleanSetting) {
                        type = 0;
                    } else if (s instanceof NumberSetting ns) {
                        type = 1;
                        step = extractNumberSettingStep(ns);
                    } else if (s instanceof ModeSetting ms) {
                        type = 2;
                        List<String> modes = ms.getModes();
                        opts = modes != null ? modes.toArray(new String[0]) : new String[0];
                    } else if (s instanceof MultiModeSetting mms) {
                        type = 3;
                        opts = extractMultiModeOptions(mms);
                    } else if (s instanceof StringSetting) {
                        type = 4;
                    }
                    if (type != -1) {
                        list.add(new CachedSettingEntry(s, sName, type, step, opts));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return list.toArray(new CachedSettingEntry[0]);
    }

    private static String resolvePremiumDescription(String moduleName, String fallback) {
        if (moduleName != null) {
            String key = moduleName.trim().toLowerCase(Locale.ROOT).replace(" ", "");
            switch (key) {
                case "silentaura", "killaura" -> { return "Automatically attacks nearby targets with silent rotations"; }
                case "autocrystal" -> { return "Places and detonates End Crystals with damage prediction"; }
                case "autototem" -> { return "Automatically equips Totems of Undying to your offhand"; }
                case "lungehelper", "autolunge" -> { return "Executes timed weapon swaps for instant lunge momentum"; }
                case "automace", "macetarget" -> { return "Switches to Mace mid-fall for high-damage smash hits"; }
                case "autoelytra", "elytratarget" -> { return "Deploys and manages Elytra flight automatically in midair"; }
                case "elytrahelper" -> { return "Swaps between Chestplate and Elytra on keybind"; }
                case "aimassist" -> { return "Smoothly guides crosshair toward targets using legit curves"; }
                case "esp" -> { return "Highlights players and entities through walls"; }
                case "nametags" -> { return "Renders clear health, armor, and distance tags above players"; }
                case "nohurtcam" -> { return "Removes camera shake when taking damage"; }
                case "nameprotect" -> { return "Masks your username and friends across chat and HUD"; }
                case "arraylist" -> { return "Displays active modules in an animated screen-edge list"; }
                case "notifications" -> { return "Shows sleek toast alerts for toggles and combat events"; }
                case "targethud" -> { return "Displays target health, armor durability, and totem pops"; }
                case "keybinds" -> { return "Lists bound modules and their live toggle states"; }
                case "ambience" -> { return "Customizes sky, fog, time, and weather atmosphere"; }
            }
        }
        if (fallback != null && !fallback.trim().isEmpty()) {
            String clean = fallback.trim();
            if (clean.endsWith(".")) {
                clean = clean.substring(0, clean.length() - 1);
            }
            return clean.length() > 64 ? clean.substring(0, 61) + "..." : clean;
        }
        return "Configure " + (moduleName != null ? moduleName : "module") + " settings";
    }

    private int mapCategoryToPillIndex(ModuleCategory cat) {
        if (cat == null) {
            return 1;
        }
        String name = cat.name().toUpperCase(Locale.ROOT);
        if (name.contains("COMBAT")) {
            return 1;
        }
        if (name.contains("MOVE")) {
            return 2;
        }
        if (name.contains("VISUAL") || name.contains("RENDER")) {
            return 3;
        }
        if (name.contains("PLAYER")) {
            return 4;
        }
        if (name.contains("WORLD")) {
            return 5;
        }
        return 6;
    }

    private void ensureGlfwCallbacksHooked() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        long windowHandle = mc.getWindow().handle();
        if (windowHandle == 0L || hookedWindowHandle == windowHandle) {
            return;
        }
        hookedWindowHandle = windowHandle;

        prevKeyCallback = GLFW.glfwSetKeyCallback(windowHandle, (window, key, scancode, action, mods) -> {
            Minecraft client = Minecraft.getInstance();
            if (client != null) {
                if (client.screen instanceof GlassClickGuiScreen gui) {
                    if (gui.handleGlfwKeyCallback(key, scancode, action, mods)) {
                        return;
                    }
                } else if (client.screen == null && action == GLFW.GLFW_PRESS) {
                    if (getInstance().onGlobalKeyPressFromCallback(key)) {
                        return;
                    }
                }
            }
            if (prevKeyCallback != null) {
                prevKeyCallback.invoke(window, key, scancode, action, mods);
            }
        });

        prevCharCallback = GLFW.glfwSetCharCallback(windowHandle, (window, codepoint) -> {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.screen instanceof GlassClickGuiScreen gui) {
                if (gui.handleGlfwCharCallback(codepoint)) {
                    return;
                }
            }
            if (prevCharCallback != null) {
                prevCharCallback.invoke(window, codepoint);
            }
        });

        prevMouseButtonCallback = GLFW.glfwSetMouseButtonCallback(windowHandle, (window, button, action, mods) -> {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.screen instanceof GlassClickGuiScreen gui && action == GLFW.GLFW_PRESS) {
                if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    gui.pendingLeftClickFromCallback = true;
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    gui.pendingRightClickFromCallback = true;
                }
            }
            if (prevMouseButtonCallback != null) {
                prevMouseButtonCallback.invoke(window, button, action, mods);
            }
        });
    }

    private boolean handleGlfwKeyCallback(int key, int scancode, int action, int mods) {
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) {
            return false;
        }

        if (listeningForGuiKeybind) {
            if (action != GLFW.GLFW_PRESS) {
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
                ClientThemeManager.setClickGuiKey(GLFW.GLFW_KEY_RIGHT_SHIFT);
                SoundUtil.playTogglePop(false);
            } else if (key > 0 && key != GLFW.GLFW_KEY_UNKNOWN) {
                ClientThemeManager.setClickGuiKey(key);
                SoundUtil.playTogglePop(true);
            } else {
                return true;
            }
            bentoCachedGuiKey = Integer.MIN_VALUE;
            int boundNow = ClientThemeManager.getClickGuiKey();
            syncClickGuiKeyToBindings(boundNow);
            listeningForGuiKeybind = false;
            waitingForBindRelease = false;
            lastClickGuiToggleMs = System.currentTimeMillis();
            wasClickGuiKeyDownInGame = true;
            saveConfigNow();
            HudManager.pushNotification("ClickGUI", "Bind set to " + formatBoundKeyDisplayName(boundNow), HudManager.TYPE_ENABLED);
            return true;
        }

        if (listeningForFriendBind) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
                FriendManager.setFriendBindKey(-1);
                SoundUtil.playTogglePop(false);
            } else if (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST) {
                FriendManager.setFriendBindKey(key);
                SoundUtil.playTogglePop(true);
            }
            listeningForFriendBind = false;
            waitingForBindRelease = false;
            return true;
        }

        if (bindingRow != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE) {
                assignModuleBind(bindingRow, -1);
                SoundUtil.playTogglePop(false);
            } else if (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST) {
                assignModuleBind(bindingRow, key);
                SoundUtil.playTogglePop(true);
            }
            bindingRow = null;
            waitingForBindRelease = false;
            return true;
        }

        if (configInputFocused && activeTopTabIndex == 1) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!configInputText.isEmpty()) {
                    configInputText = configInputText.substring(0, configInputText.length() - 1);
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_DELETE) {
                configInputText = "";
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                submitConfigSaveFromInput();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                configInputFocused = false;
                return true;
            }
            if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0) {
                boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
                char ch = (char) ((shift ? 'A' : 'a') + (key - GLFW.GLFW_KEY_A));
                appendConfigInputCharFromKey(ch);
                return true;
            }
            if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9 && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0) {
                char ch = (char) ('0' + (key - GLFW.GLFW_KEY_0));
                appendConfigInputCharFromKey(ch);
                return true;
            }
            if (key == GLFW.GLFW_KEY_MINUS) {
                boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
                appendConfigInputCharFromKey(shift ? '_' : '-');
                return true;
            }
            return true;
        }

        if (activeStringSettingEntry != null && activeStringSettingEntry.setting instanceof StringSetting activeSs) {
            String curVal = activeSs.getValue() != null ? activeSs.getValue() : "";
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!curVal.isEmpty()) {
                    activeSs.setValue(curVal.substring(0, curVal.length() - 1));
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_DELETE) {
                activeSs.setValue("");
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_ESCAPE) {
                activeStringSettingEntry = null;
                return true;
            }
            if (key == GLFW.GLFW_KEY_SPACE) {
                if (curVal.length() < activeSs.getMaxLength()) {
                    lastKeyAppendedChar = ' ';
                    lastKeyAppendTimeNs = System.nanoTime();
                    activeSs.setValue(curVal + ' ');
                }
                return true;
            }
            if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0) {
                boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
                char ch = (char) ((shift ? 'A' : 'a') + (key - GLFW.GLFW_KEY_A));
                if (curVal.length() < activeSs.getMaxLength()) {
                    lastKeyAppendedChar = ch;
                    lastKeyAppendTimeNs = System.nanoTime();
                    activeSs.setValue(curVal + ch);
                }
                return true;
            }
            if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9 && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0) {
                char ch = (char) ('0' + (key - GLFW.GLFW_KEY_0));
                if (curVal.length() < activeSs.getMaxLength()) {
                    lastKeyAppendedChar = ch;
                    lastKeyAppendTimeNs = System.nanoTime();
                    activeSs.setValue(curVal + ch);
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_MINUS) {
                boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
                char ch = shift ? '_' : '-';
                if (curVal.length() < activeSs.getMaxLength()) {
                    lastKeyAppendedChar = ch;
                    lastKeyAppendTimeNs = System.nanoTime();
                    activeSs.setValue(curVal + ch);
                }
                return true;
            }
            return true;
        }

        if (friendInputFocused && activeTopTabIndex == 3) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!friendInputText.isEmpty()) {
                    friendInputText = friendInputText.substring(0, friendInputText.length() - 1);
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_DELETE) {
                friendInputText = "";
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                submitFriendInputNow();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                friendInputFocused = false;
                return true;
            }
            if (key == GLFW.GLFW_KEY_SPACE) {
                appendFriendInputCharFromKey(' ');
                return true;
            }
            if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0) {
                boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
                char ch = (char) ((shift ? 'A' : 'a') + (key - GLFW.GLFW_KEY_A));
                appendFriendInputCharFromKey(ch);
                return true;
            }
            if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9 && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0) {
                char ch = (char) ('0' + (key - GLFW.GLFW_KEY_0));
                appendFriendInputCharFromKey(ch);
                return true;
            }
            if (key == GLFW.GLFW_KEY_MINUS) {
                boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
                appendFriendInputCharFromKey(shift ? '_' : '-');
                return true;
            }
            return true;
        }

        if (!searchFocused && action == GLFW.GLFW_PRESS && key == GLFW.GLFW_KEY_SLASH) {
            searchFocused = true;
            lastKeyAppendedChar = '/';
            lastKeyAppendTimeNs = System.nanoTime();
            SoundUtil.playClick();
            return true;
        }

        if (searchFocused) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                deleteLastSearchChar();
                return true;
            }
            if (key == GLFW.GLFW_KEY_DELETE) {
                if (!searchQuery.isEmpty()) {
                    searchQuery = "";
                    searchQueryLower = "";
                    targetScrollOffset = 0.0F;
                    filterDirty = true;
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_ESCAPE) {
                searchFocused = false;
                return true;
            }
            if (key == GLFW.GLFW_KEY_SPACE) {
                appendSearchCharFromKey(' ');
                return true;
            }
            if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0) {
                boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
                char ch = (char) ((shift ? 'A' : 'a') + (key - GLFW.GLFW_KEY_A));
                appendSearchCharFromKey(ch);
                return true;
            }
            if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9 && (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT)) == 0) {
                char ch = (char) ('0' + (key - GLFW.GLFW_KEY_0));
                appendSearchCharFromKey(ch);
                return true;
            }
            if (key == GLFW.GLFW_KEY_MINUS) {
                boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
                appendSearchCharFromKey(shift ? '_' : '-');
                return true;
            }
            return true;
        }

        if (key == GLFW.GLFW_KEY_DOWN) {
            targetScrollOffset = Mth.clamp(targetScrollOffset + 18.0F, 0.0F, maxScrollOffset);
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP) {
            targetScrollOffset = Mth.clamp(targetScrollOffset - 18.0F, 0.0F, maxScrollOffset);
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
            targetScrollOffset = Mth.clamp(targetScrollOffset + listHeight * 0.75F, 0.0F, maxScrollOffset);
            return true;
        }
        if (key == GLFW.GLFW_KEY_PAGE_UP) {
            targetScrollOffset = Mth.clamp(targetScrollOffset - listHeight * 0.75F, 0.0F, maxScrollOffset);
            return true;
        }

        return false;
    }


    private boolean handleGlfwCharCallback(int codepoint) {
        if (configInputFocused && activeTopTabIndex == 1) {
            if (codepoint < 32 || codepoint == 127) {
                return true;
            }
            char ch = (char) codepoint;
            long nowNs = System.nanoTime();
            if (Character.toLowerCase(ch) == Character.toLowerCase(lastKeyAppendedChar)
                    && (nowNs - lastKeyAppendTimeNs) < 25_000_000L) {
                lastKeyAppendedChar = 0;
                return true;
            }
            appendConfigInputCharRaw(ch);
            return true;
        }
        if (activeStringSettingEntry != null && activeStringSettingEntry.setting instanceof StringSetting charSs) {
            if (codepoint < 32 || codepoint == 127) {
                return true;
            }
            char ch = (char) codepoint;
            long nowNs = System.nanoTime();
            if (Character.toLowerCase(ch) == Character.toLowerCase(lastKeyAppendedChar)
                    && (nowNs - lastKeyAppendTimeNs) < 25_000_000L) {
                lastKeyAppendedChar = 0;
                return true;
            }
            String curVal = charSs.getValue() != null ? charSs.getValue() : "";
            if (curVal.length() < charSs.getMaxLength()) {
                charSs.setValue(curVal + ch);
            }
            return true;
        }
        if (friendInputFocused && activeTopTabIndex == 3) {
            if (codepoint < 32 || codepoint == 127) {
                return true;
            }
            char ch = (char) codepoint;
            long nowNs = System.nanoTime();
            if (Character.toLowerCase(ch) == Character.toLowerCase(lastKeyAppendedChar)
                    && (nowNs - lastKeyAppendTimeNs) < 25_000_000L) {
                lastKeyAppendedChar = 0;
                return true;
            }
            appendFriendInputCharRaw(ch);
            return true;
        }
        if (!searchFocused) {
            return false;
        }
        if (codepoint < 32 || codepoint == 127) {
            return true;
        }
        char ch = (char) codepoint;
        long nowNs = System.nanoTime();
        if (Character.toLowerCase(ch) == Character.toLowerCase(lastKeyAppendedChar)
                && (nowNs - lastKeyAppendTimeNs) < 25_000_000L) {
            lastKeyAppendedChar = 0;
            return true;
        }
        appendSearchCharRaw(ch);
        return true;
    }

    private void appendFriendInputCharFromKey(char ch) {
        lastKeyAppendedChar = ch;
        lastKeyAppendTimeNs = System.nanoTime();
        appendFriendInputCharRaw(ch);
    }

    private void appendFriendInputCharRaw(char ch) {
        if (friendInputText.length() >= 36) {
            return;
        }
        if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == ' ') {
            friendInputText += ch;
        }
    }

    private void appendSearchCharFromKey(char ch) {
        lastKeyAppendedChar = ch;
        lastKeyAppendTimeNs = System.nanoTime();
        appendSearchCharRaw(ch);
    }

    private void appendSearchCharRaw(char ch) {
        if (searchQuery.length() >= 28) {
            return;
        }
        searchQuery += ch;
        searchQueryLower = searchQuery.toLowerCase(Locale.ROOT);
        targetScrollOffset = 0.0F;
        filterDirty = true;
    }

    private void deleteLastSearchChar() {
        if (!searchQuery.isEmpty()) {
            searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
            searchQueryLower = searchQuery.toLowerCase(Locale.ROOT);
            targetScrollOffset = 0.0F;
            filterDirty = true;
        }
    }

    private void pollGlfwInputAndInteractions(int mouseX, int mouseY) {
        ensureGlfwCallbacksHooked();
        long windowHandle = GLFW.glfwGetCurrentContext();
        if (windowHandle == 0L) {
            return;
        }

        if (listeningForGuiKeybind) {
            if (waitingForBindRelease) {
                if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS
                        && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
                    waitingForBindRelease = false;
                }
                return;
            }
            // Direct keyboard polling fallback (works even if GLFW key callback was overwritten)
            for (int k = GLFW.GLFW_KEY_SPACE; k <= GLFW.GLFW_KEY_LAST; k++) {
                if (GLFW.glfwGetKey(windowHandle, k) == GLFW.GLFW_PRESS) {
                    if (k == GLFW.GLFW_KEY_ESCAPE || k == GLFW.GLFW_KEY_BACKSPACE || k == GLFW.GLFW_KEY_DELETE) {
                        ClientThemeManager.setClickGuiKey(GLFW.GLFW_KEY_RIGHT_SHIFT);
                        SoundUtil.playTogglePop(false);
                    } else {
                        ClientThemeManager.setClickGuiKey(k);
                        SoundUtil.playTogglePop(true);
                    }
                    bentoCachedGuiKey = Integer.MIN_VALUE;
                    syncClickGuiKeyToBindings(ClientThemeManager.getClickGuiKey());
                    listeningForGuiKeybind = false;
                    lastClickGuiToggleMs = System.currentTimeMillis();
                    wasClickGuiKeyDownInGame = true;
                    saveConfigNow();
                    HudManager.pushNotification("ClickGUI", "Bind set to " + formatBoundKeyDisplayName(ClientThemeManager.getClickGuiKey()), HudManager.TYPE_ENABLED);
                    return;
                }
            }
            // Mouse buttons Middle..8
            for (int btn = GLFW.GLFW_MOUSE_BUTTON_MIDDLE; btn <= GLFW.GLFW_MOUSE_BUTTON_8; btn++) {
                if (GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS) {
                    ClientThemeManager.setClickGuiKey(-100 - btn);
                    bentoCachedGuiKey = Integer.MIN_VALUE;
                    syncClickGuiKeyToBindings(ClientThemeManager.getClickGuiKey());
                    listeningForGuiKeybind = false;
                    lastClickGuiToggleMs = System.currentTimeMillis();
                    wasClickGuiKeyDownInGame = true;
                    saveConfigNow();
                    SoundUtil.playTogglePop(true);
                    return;
                }
            }
            return;
        }

        if (listeningForFriendBind) {
            if (waitingForBindRelease) {
                if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS
                        && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
                    waitingForBindRelease = false;
                }
                return;
            }
            for (int btn = GLFW.GLFW_MOUSE_BUTTON_MIDDLE; btn <= GLFW.GLFW_MOUSE_BUTTON_8; btn++) {
                if (GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS) {
                    FriendManager.setFriendBindKey(-100 - btn);
                    listeningForFriendBind = false;
                    SoundUtil.playTogglePop(true);
                    return;
                }
            }
            return;
        }

        if (bindingRow != null) {
            if (waitingForBindRelease) {
                if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS
                        && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
                    waitingForBindRelease = false;
                }
                return;
            }
            for (int btn = GLFW.GLFW_MOUSE_BUTTON_MIDDLE; btn <= GLFW.GLFW_MOUSE_BUTTON_8; btn++) {
                if (GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS) {
                    assignModuleBind(bindingRow, -100 - btn);
                    bindingRow = null;
                    SoundUtil.playTogglePop(true);
                    return;
                }
            }
            return;
        }

        boolean leftDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean cbLeft = pendingLeftClickFromCallback;
        boolean cbRight = pendingRightClickFromCallback;
        pendingLeftClickFromCallback = false;
        pendingRightClickFromCallback = false;
        boolean leftClicked = (leftDown && !wasLeftMouseDown) || cbLeft;
        boolean rightClicked = (rightDown && !wasRightMouseDown) || cbRight;
        // Synchronize pressed state immediately when callback fired so next polled frame never double-triggers
        wasLeftMouseDown = leftDown || cbLeft;
        wasRightMouseDown = rightDown || cbRight;

        if (leftClicked || rightClicked) {
            long nowClickMs = System.currentTimeMillis();
            if (nowClickMs - lastClickTime < 50L) {
                leftClicked = false;
                rightClicked = false;
            } else {
                lastClickTime = nowClickMs;
            }
        }

        if (HudManager.isLayoutEditorActive()) {
            if (leftClicked) {
                if (HudManager.handleLayoutEditorClick(this.width, this.height, mouseX, mouseY)) {
                    SoundUtil.playClick();
                    saveConfigNow();
                }
            } else if (leftDown) {
                HudManager.handleLayoutEditorDrag(this.width, this.height, mouseX, mouseY);
            } else {
                if (HudManager.handleLayoutEditorRelease()) {
                    saveConfigNow();
                }
            }
            return;
        }

        if (leftDown) {
            if (activeDraggingSliderEntry != null && activeSliderTrackW > 0) {
                NumberSetting ns = (NumberSetting) activeDraggingSliderEntry.setting;
                double ratio = Mth.clamp((double) (mouseX - activeSliderTrackX) / (double) activeSliderTrackW, 0.0D, 1.0D);
                double rawVal = ns.getMin() + ratio * (ns.getMax() - ns.getMin());
                double inc = activeDraggingSliderEntry.step;
                if (inc > 0.0D) {
                    rawVal = Math.round(rawVal / inc) * inc;
                }
                ns.setValue(Mth.clamp(rawVal, ns.getMin(), ns.getMax()));
                updateCachedSliderStringIfNeeded(activeDraggingSliderEntry);
                return;
            }
            if (draggingPanelOpacitySlider && clientOpacitySliderW > 0) {
                float r = Mth.clamp((float) (mouseX - clientOpacitySliderX) / (float) clientOpacitySliderW, 0.0F, 1.0F);
                ClientThemeManager.setPanelOpacity(0.55F + r * 0.40F);
                return;
            }
            if (draggingFloatingSizeSlider && clientFloatSizeSliderW > 0) {
                float r = Mth.clamp((float) (mouseX - clientFloatSizeSliderX) / (float) clientFloatSizeSliderW, 0.0F, 1.0F);
                ClientThemeManager.setFloatingButtonSize(20.0F + r * 16.0F);
                return;
            }
            if (draggingScrollbar && maxScrollOffset > 0.0F) {
                float deltaY = mouseY - scrollbarDragStartMouseY;
                float scrollScale = maxScrollOffset / (float) Math.max(1, listHeight - 30);
                targetScrollOffset = Mth.clamp(scrollbarDragStartOffset + deltaY * scrollScale, 0.0F, maxScrollOffset);
                scrollOffset = targetScrollOffset;
                return;
            }
        } else {
            if (activeDraggingSliderEntry != null) {
                saveConfigNow();
            }
            activeDraggingSliderEntry = null;
            hudDraggingWidgetIndex = -1;
            hudDraggingSliderType = -1;
            if (draggingPanelOpacitySlider || draggingFloatingSizeSlider) {
                saveConfigNow();
            }
            draggingPanelOpacitySlider = false;
            draggingFloatingSizeSlider = false;
            draggingScrollbar = false;
        }

        if (!leftClicked && !rightClicked) {
            return;
        }

        if (leftClicked) {
            for (int i = 0; i < TOP_TABS.length; i++) {
                if (isHovered(mouseX, mouseY, topTabX[i], navContainerY, topTabW[i], navContainerH)) {
                    if (activeTopTabIndex != i) {
                        activeTopTabIndex = i;
                        SoundUtil.playClick();
                    }
                    return;
                }
            }

            if (activeTopTabIndex == 0 && isHovered(mouseX, mouseY, searchBoxX, searchBoxY, searchBoxW, searchBoxH)) {
                if (searchFocused || !searchQuery.isEmpty()) {
                    if (isHovered(mouseX, mouseY, searchClearBtnX, searchBtnY, 14, 14)) {
                        if (!searchQuery.isEmpty()) {
                            searchQuery = "";
                            searchQueryLower = "";
                            targetScrollOffset = 0.0F;
                            filterDirty = true;
                        }
                        searchFocused = true;
                        SoundUtil.playClick();
                        return;
                    }
                    if (isHovered(mouseX, mouseY, searchBackspaceBtnX, searchBtnY, 16, 14)) {
                        deleteLastSearchChar();
                        searchFocused = true;
                        SoundUtil.playClick();
                        return;
                    }
                }
                if (!searchFocused) {
                    searchFocused = true;
                    SoundUtil.playClick();
                }
                return;
            } else {
                searchFocused = false;
            }

            if (activeTopTabIndex == 0) for (int i = 0; i < CATEGORY_PILLS.length; i++) {
                int px = categoryPillX[i];
                int pw = categoryPillW[i];
                if (px + pw <= listRight && isHovered(mouseX, mouseY, px, searchBoxY, pw, searchBoxH)) {
                    if (activeCategoryIndex != i) {
                        activeCategoryIndex = i;
                        targetScrollOffset = 0.0F;
                        scrollOffset = 0.0F;
                        filterDirty = true;
                        SoundUtil.playClick();
                    }
                    return;
                }
            }

            if (activeTopTabIndex == 0 && maxScrollOffset > 1.0F && isHovered(mouseX, mouseY, listRight - 6, listTop, 8, listHeight)) {
                draggingScrollbar = true;
                scrollbarDragStartMouseY = mouseY;
                scrollbarDragStartOffset = scrollOffset;
                return;
            }
        }

        if (activeTopTabIndex == 1) {
            handleConfigsTabClick(mouseX, mouseY, leftClicked, rightClicked);
            return;
        }
        if (activeTopTabIndex == 2) {
            handleHudTabClick(mouseX, mouseY, leftClicked, rightClicked);
            return;
        }
        if (activeTopTabIndex == 3) {
            handleFriendsTabClick(mouseX, mouseY, leftClicked, rightClicked);
            return;
        }
        if (activeTopTabIndex == 0 && isHovered(mouseX, mouseY, listLeft, listTop, listRight - listLeft, listHeight)) {
            handleModuleListClick(mouseX, mouseY, leftClicked, rightClicked);
        } else if (activeTopTabIndex == 4) {
            int bentoTop = navContainerY + navContainerH + 10;
            handleClientBentoClick(mouseX, mouseY, leftClicked, rightClicked, bentoTop);
        }
    }

    private void handleFriendsTabClick(int mouseX, int mouseY, boolean leftClicked, boolean rightClicked) {
        if (!leftClicked && !rightClicked) {
            return;
        }
        if (isHovered(mouseX, mouseY, friendInputBoxX, friendInputBoxY, friendInputBoxW, friendInputBoxH)) {
            friendInputFocused = true;
            searchFocused = false;
            SoundUtil.playClick();
            return;
        } else {
            friendInputFocused = false;
        }

        if (leftClicked && isHovered(mouseX, mouseY, friendAddBtnX, friendAddBtnY, friendAddBtnW, friendAddBtnH)) {
            submitFriendInputNow();
            return;
        }

        if (isHovered(mouseX, mouseY, friendBindBtnX, friendBindBtnY, friendBindBtnW, friendBindBtnH)) {
            if (rightClicked) {
                FriendManager.setFriendBindKey(-1);
                listeningForFriendBind = false;
                SoundUtil.playTogglePop(false);
            } else {
                listeningForFriendBind = true;
                waitingForBindRelease = true;
                SoundUtil.playClick();
            }
            return;
        }

        if (leftClicked && isHovered(mouseX, mouseY, listLeft, listTop, listRight - listLeft, listHeight)) {
            List<FriendRecord> friends = FriendManager.getSortedFriends();
            int friendRowH = 40;
            int scrollInt = Math.round(friendScrollOffset);
            for (int i = 0; i < friends.size(); i++) {
                FriendRecord rec = friends.get(i);
                int rowTop = listTop + i * friendRowH - scrollInt;
                int rowBottom = rowTop + friendRowH;
                if (rowBottom < listTop || rowTop > listBottom) {
                    continue;
                }
                int remW = 28;
                int remH = 18;
                int remX = listLeft + rowWidth - remW - 10;
                int remY = rowTop + (friendRowH - remH) / 2;
                if (isHovered(mouseX, mouseY, remX, remY, remW, remH)) {
                    FriendManager.removeFriend(rec.uuid);
                    SoundUtil.playTogglePop(false);
                    return;
                }
            }
        }
    }

    private void handleModuleListClick(int mouseX, int mouseY, boolean leftClicked, boolean rightClicked) {
        int scrollInt = Math.round(scrollOffset);
        int toggleW = TOGGLE_PILL_WIDTH;
        int toggleH = TOGGLE_PILL_HEIGHT;
        int toggleX = listLeft + rowWidth - toggleW - 12;
        int chevronX = toggleX - 14;

        for (int i = 0; i < filteredRowCount; i++) {
            CachedModuleRow row = filteredRows[i];
            int rowTop = listTop + row.cachedYOffset - scrollInt;
            int rowBottom = rowTop + row.cachedTotalHeight;
            if (rowBottom < listTop || rowTop > listBottom) {
                continue;
            }

            if (isHovered(mouseX, mouseY, listLeft, rowTop, rowWidth, ROW_HEIGHT)) {
                boolean isActionMacro = isActionMacroRow(row);
                int rowChevronX = isActionMacro ? (listLeft + rowWidth - 18) : chevronX;
                int toggleY = rowTop + (ROW_HEIGHT - toggleH) / 2;
                int bindPillW = row.cachedBindPillWidth;
                int bindPillH = 15;
                int bindPillX = rowChevronX - bindPillW - 8;
                int bindPillY = rowTop + (ROW_HEIGHT - bindPillH) / 2;

                if ((leftClicked || rightClicked) && isHovered(mouseX, mouseY, bindPillX, bindPillY, bindPillW, bindPillH)) {
                    bindingRow = row;
                    waitingForBindRelease = true;
                    SoundUtil.playClick();
                    return;
                }

                if (isActionMacro) {
                    // Action macro (AutoLunge) has no ON/OFF toggle; clicking the row expands/collapses settings
                    if ((leftClicked || rightClicked) && row.settings.length > 0) {
                        row.expanded = !row.expanded;
                        layoutDirty = true;
                        SoundUtil.playClick();
                    }
                    return;
                }

                // Clicking the chevron icon expands/collapses settings even on left-click
                if (leftClicked && row.settings.length > 0 && isHovered(mouseX, mouseY, rowChevronX - 4, rowTop + 8, 16, ROW_HEIGHT - 16)) {
                    row.expanded = !row.expanded;
                    layoutDirty = true;
                    SoundUtil.playClick();
                    return;
                }

                // Strict Button Separation: Left Click exclusively toggles module; Right Click exclusively toggles settings drawer
                if (leftClicked) {
                    row.module.toggle();
                    saveConfigNow();
                    SoundUtil.playTogglePop(row.module.isEnabled());
                    return;
                }

                if (rightClicked) {
                    if (row.settings.length > 0) {
                        row.expanded = !row.expanded;
                        layoutDirty = true;
                        SoundUtil.playClick();
                    }
                    return;
                }
            }

            if (row.expanded
                    && isHovered(mouseX, mouseY, listLeft, rowTop + ROW_HEIGHT, rowWidth, row.cachedTotalHeight - ROW_HEIGHT)) {
                int drawerX = listLeft + 4;
                int drawerW = rowWidth - 8;
                int curY = rowTop + ROW_HEIGHT + 5;

                for (int s = 0; s < row.settings.length; s++) {
                    CachedSettingEntry se = row.settings[s];
                    if (!se.visible) {
                        continue;
                    }
                    if (isHovered(mouseX, mouseY, drawerX, curY, drawerW, SETTING_ROW_HEIGHT)) {
                        handleSettingRowClick(se, drawerX, curY, drawerW, mouseX, mouseY, leftClicked);
                        layoutDirty = true;
                        return;
                    }
                    curY += SETTING_ROW_HEIGHT;
                }
            }
        }
    }

    private void handleSettingRowClick(
            CachedSettingEntry se,
            int drawerX,
            int curY,
            int drawerW,
            int mouseX,
            int mouseY,
            boolean leftClicked
    ) {
        switch (se.type) {
            case 0 -> {
                if (leftClicked) {
                    BooleanSetting bs = (BooleanSetting) se.setting;
                    Object raw = ((Setting<?>) bs).getValue();
                    boolean curState = (raw instanceof Boolean b) ? b : (raw instanceof Number n && n.intValue() > 0);
                    boolean nextState = !curState;
                    bs.setValue(nextState);
                    SoundUtil.playTogglePop(nextState);
                }
            }
            case 1 -> {
                if (leftClicked) {
                    NumberSetting ns = (NumberSetting) se.setting;
                    int trackW = 120;
                    int trackX = drawerX + drawerW - trackW - 14;
                    activeDraggingSliderEntry = se;
                    activeSliderTrackX = trackX;
                    activeSliderTrackW = trackW;
                    double ratio = Mth.clamp((double) (mouseX - trackX) / (double) trackW, 0.0D, 1.0D);
                    double rawVal = ns.getMin() + ratio * (ns.getMax() - ns.getMin());
                    double inc = se.step;
                    if (inc > 0.0D) {
                        rawVal = Math.round(rawVal / inc) * inc;
                    }
                    ns.setValue(Mth.clamp(rawVal, ns.getMin(), ns.getMax()));
                    updateCachedSliderStringIfNeeded(se);
                    SoundUtil.playClick();
                }
            }
            case 2 -> {
                ModeSetting ms = (ModeSetting) se.setting;
                int rightEdge = drawerX + drawerW - 14;
                for (int m = se.modeOptions.length - 1; m >= 0; m--) {
                    String modeLabel = se.modeOptions[m];
                    int pw = se.modePillWidths[m];
                    int px = rightEdge - pw;
                    int py = curY + 4;
                    if (isHovered(mouseX, mouseY, px, py, pw, 18)) {
                        ms.setValue(modeLabel);
                        SoundUtil.playClick();
                        return;
                    }
                    rightEdge = px - 4;
                }
                if (leftClicked && se.modeOptions.length > 0) {
                    List<String> modes = ms.getModes();
                    int idx = modes.indexOf(ms.getValue());
                    ms.setValue(modes.get((idx + 1) % modes.size()));
                    SoundUtil.playClick();
                }
            }
            case 3 -> {
                MultiModeSetting mms = (MultiModeSetting) se.setting;
                int rightEdge = drawerX + drawerW - 14;
                for (int m = se.modeOptions.length - 1; m >= 0; m--) {
                    String opt = se.modeOptions[m];
                    int pw = se.modePillWidths[m];
                    int px = rightEdge - pw;
                    int py = curY + 4;
                    if (isHovered(mouseX, mouseY, px, py, pw, 18)) {
                        toggleMultiModeOption(mms, opt);
                        SoundUtil.playTogglePop(mms.isSelected(opt));
                        return;
                    }
                    rightEdge = px - 3;
                }
            }
            case 4 -> {
                if (leftClicked) {
                    activeStringSettingEntry = se;
                    searchFocused = false;
                    friendInputFocused = false;
                    SoundUtil.playClick();
                }
            }
        }
    }

    private void refreshModuleBindLabelIfNeeded(CachedModuleRow row, boolean forceRecalcWidth) {
        int key = resolveEffectiveBindKey(row);
        if (!forceRecalcWidth && key == row.lastObservedKey) {
            return;
        }
        row.lastObservedKey = key;
        row.cachedBindText = formatBoundKeyDisplayName(key);
        row.cachedBindComponent = Component.literal(row.cachedBindText).withStyle(nunitoRegularStyle);
        if (this.font != null) {
            row.cachedBindTextWidth = this.font.width(row.cachedBindComponent);
            row.cachedBindPillWidth = Math.max(30, row.cachedBindTextWidth + 12);
        }
    }

    private static String formatBoundKeyDisplayName(int key) {
        if (key == GLFW.GLFW_KEY_UNKNOWN || key == 0 || key == -1) {
            return "NONE";
        }
        if (key < -1) {
            int btn = -100 - key;
            return "M" + (btn + 1);
        }
        switch (key) {
            case GLFW.GLFW_KEY_LEFT_SHIFT -> {
                return "LSHIFT";
            }
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> {
                return "RSHIFT";
            }
            case GLFW.GLFW_KEY_LEFT_CONTROL -> {
                return "LCTRL";
            }
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> {
                return "RCTRL";
            }
            case GLFW.GLFW_KEY_LEFT_ALT -> {
                return "LALT";
            }
            case GLFW.GLFW_KEY_RIGHT_ALT -> {
                return "RALT";
            }
            case GLFW.GLFW_KEY_SPACE -> {
                return "SPACE";
            }
            case GLFW.GLFW_KEY_TAB -> {
                return "TAB";
            }
            case GLFW.GLFW_KEY_CAPS_LOCK -> {
                return "CAPS";
            }
            default -> {
            }
        }
        try {
            String localized = InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
            if (localized != null && !localized.isBlank() && !localized.startsWith("key.keyboard.")) {
                return localized.trim().toUpperCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        try {
            String glfwName = GLFW.glfwGetKeyName(key, 0);
            if (glfwName != null && !glfwName.isBlank()) {
                return glfwName.trim().toUpperCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return "KEY " + key;
    }

    private static String formatSliderValue(double value, double increment) {
        if (increment >= 1.0D) {
            return Integer.toString((int) Math.round(value));
        }
        if (increment >= 0.1D) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static boolean isHovered(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX <= (x + w) && mouseY >= y && mouseY <= (y + h);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return !listeningForGuiKeybind && !listeningForFriendBind && bindingRow == null;
    }

    @Override
    public void onClose() {
        HudManager.setLayoutEditorActive(false, null);
        saveConfigNow();
        super.onClose();
    }

    private static Path getConfigFilePath() {
        return ClientThemeManager.getUnifiedConfigPath();
    }

    private static JsonObject readUnifiedRootOrEmpty() {
        try {
            Path cfg = getConfigFilePath();
            if (Files.exists(cfg)) {
                String raw = Files.readString(cfg, StandardCharsets.UTF_8);
                if (raw != null && !raw.isBlank()) {
                    JsonElement el = JsonParser.parseString(raw);
                    if (el != null && el.isJsonObject()) {
                        return el.getAsJsonObject();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new JsonObject();
    }

    private static void writeUnifiedRoot(JsonObject root) throws java.io.IOException {
        Path cfg = getConfigFilePath();
        Path parent = cfg.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(cfg, CONFIG_GSON.toJson(root), StandardCharsets.UTF_8);
    }

    private static String sanitizeConfigName(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim().replaceAll("[^a-zA-Z0-9_-]", "");
        if (cleaned.length() > 28) {
            cleaned = cleaned.substring(0, 28);
        }
        return cleaned;
    }

    private void setConfigStatus(String message) {
        this.configStatusMessage = message != null ? message : "";
        this.configStatusExpireMs = System.currentTimeMillis() + 3000L;
    }

    private void appendConfigInputCharFromKey(char ch) {
        lastKeyAppendedChar = ch;
        lastKeyAppendTimeNs = System.nanoTime();
        appendConfigInputCharRaw(ch);
    }

    private void appendConfigInputCharRaw(char ch) {
        if (configInputText.length() >= 28) {
            return;
        }
        if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
            configInputText += ch;
        }
    }

    private void refreshSavedConfigList(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastConfigListRefreshMs) < 1000L && !cachedConfigNames.isEmpty()) {
            return;
        }
        lastConfigListRefreshMs = now;
        cachedConfigNames.clear();
        cachedConfigNames.add("default");
        try {
            JsonObject root = readUnifiedRootOrEmpty();
            if (root.has("profiles") && root.get("profiles").isJsonObject()) {
                List<String> extras = new ArrayList<>();
                for (String key : root.getAsJsonObject("profiles").keySet()) {
                    if (key != null && !key.isEmpty() && !"default".equalsIgnoreCase(key)) {
                        extras.add(key);
                    }
                }
                extras.sort(String.CASE_INSENSITIVE_ORDER);
                cachedConfigNames.addAll(extras);
            }
        } catch (Throwable ignored) {
        }
    }

    private void submitConfigSaveFromInput() {
        String name = sanitizeConfigName(configInputText);
        if (name.isEmpty()) {
            name = activeConfigName != null && !activeConfigName.isEmpty() ? activeConfigName : "default";
        }
        if (saveNamedConfig(name)) {
            configInputText = "";
            setConfigStatus("Saved config '" + name + "'");
            SoundUtil.playTogglePop(true);
        }
    }

    private void enqueueConfigsTopInputBar(int mouseX, int mouseY) {
        int barY = winY + 42;
        int barH = 24;

        Component reloadComp = Component.literal("Reload").withStyle(nunitoRegularStyle);
        int reloadLabelW = this.font.width(reloadComp);
        configReloadBtnW = Math.max(54, reloadLabelW + 18);
        configReloadBtnH = barH;
        configReloadBtnX = listRight - configReloadBtnW;
        configReloadBtnY = barY;

        Component saveComp = Component.literal("Save").withStyle(nunitoRegularStyle);
        int saveLabelW = this.font.width(saveComp);
        configSaveBtnW = Math.max(48, saveLabelW + 18);
        configSaveBtnH = barH;
        configSaveBtnX = configReloadBtnX - configSaveBtnW - 6;
        configSaveBtnY = barY;

        configInputBoxX = listLeft;
        configInputBoxY = barY;
        configInputBoxW = Math.max(120, configSaveBtnX - configInputBoxX - 6);
        configInputBoxH = barH;

        int inputBg = configInputFocused ? COLOR_SEARCH_BG_FOCUSED : COLOR_SEARCH_BG;
        drawRoundedRect(configInputBoxX, configInputBoxY, configInputBoxW, configInputBoxH, SEARCH_BAR_RADIUS, inputBg);

        String displayInput;
        int inputTextCol;
        if (configInputText.isEmpty() && !configInputFocused) {
            displayInput = "Enter config name...";
            inputTextCol = COLOR_TEXT_DIMMED_GRAY;
        } else {
            boolean blink = (System.currentTimeMillis() / 500L) % 2L == 0L;
            displayInput = configInputText + (configInputFocused && blink ? "|" : "");
            inputTextCol = COLOR_TEXT_BRIGHT_WHITE;
        }
        if (!displayInput.equals(lastRenderedConfigInputString)) {
            lastRenderedConfigInputString = displayInput;
            cachedConfigInputComponent = Component.literal(displayInput).withStyle(nunitoRegularStyle);
        }
        enqueueComponentText(cachedConfigInputComponent, configInputBoxX + 10, configInputBoxY + 7, inputTextCol);

        boolean saveHover = isHovered(mouseX, mouseY, configSaveBtnX, configSaveBtnY, configSaveBtnW, configSaveBtnH);
        int saveBg = saveHover ? 0xFFE2E6F5 : COLOR_PILL_ACTIVE_BG;
        drawRoundedRect(configSaveBtnX, configSaveBtnY, configSaveBtnW, configSaveBtnH, CATEGORY_PILL_RADIUS, saveBg);
        enqueueComponentText(
                saveComp,
                configSaveBtnX + (configSaveBtnW - saveLabelW) / 2,
                configSaveBtnY + 7,
                COLOR_PILL_ACTIVE_TEXT
        );

        boolean reloadHover = isHovered(mouseX, mouseY, configReloadBtnX, configReloadBtnY, configReloadBtnW, configReloadBtnH);
        int reloadBg = reloadHover ? COLOR_BIND_BG_HOVER : COLOR_BIND_BG;
        drawRoundedRect(configReloadBtnX, configReloadBtnY, configReloadBtnW, configReloadBtnH, CATEGORY_PILL_RADIUS, reloadBg);
        enqueueComponentText(
                reloadComp,
                configReloadBtnX + (configReloadBtnW - reloadLabelW) / 2,
                configReloadBtnY + 7,
                COLOR_TEXT_BRIGHT_WHITE
        );
    }

    private void renderConfigsListPanel(GuiGraphics guiGraphics, float dt, int mouseX, int mouseY) {
        refreshSavedConfigList(false);

        int statusOffset = 0;
        if (!configStatusMessage.isEmpty() && System.currentTimeMillis() < configStatusExpireMs) {
            Component st = Component.literal(configStatusMessage).withStyle(nunitoRegularStyle);
            enqueueComponentText(st, listLeft + 4, listTop + 4, 0xFF9BE5A8);
            statusOffset = 18;
        }

        int cfgRowH = 40;
        int totalH = statusOffset + cachedConfigNames.size() * cfgRowH;
        configMaxScrollOffset = Math.max(0.0F, (float) (totalH - listHeight));
        configTargetScrollOffset = Mth.clamp(configTargetScrollOffset, 0.0F, configMaxScrollOffset);
        configScrollOffset += (configTargetScrollOffset - configScrollOffset) * Math.min(1.0F, dt * 16.0F);
        configScrollOffset = Mth.clamp(configScrollOffset, 0.0F, configMaxScrollOffset);

        int scrollInt = Math.round(configScrollOffset);
        for (int i = 0; i < cachedConfigNames.size(); i++) {
            String cfgName = cachedConfigNames.get(i);
            int rowTop = listTop + statusOffset + i * cfgRowH - scrollInt;
            int rowBottom = rowTop + cfgRowH;
            if (rowBottom < listTop || rowTop > listBottom) {
                continue;
            }

            boolean isActive = cfgName.equalsIgnoreCase(activeConfigName);
            boolean rowHover = isHovered(mouseX, mouseY, listLeft, rowTop, rowWidth, cfgRowH - 4);
            int rowBg = rowHover ? COLOR_ROW_HOVER_BG : (isActive ? 0x26FFFFFF : 0x14FFFFFF);
            drawRoundedRect(listLeft, rowTop, rowWidth, cfgRowH - 4, ROW_AND_SEARCH_RADIUS, rowBg);

            String label = isActive ? (cfgName + "  (Active)") : cfgName;
            Component nameComp = Component.literal(label).withStyle(nunitoRegularStyle);
            enqueueComponentText(nameComp, listLeft + 12, rowTop + 7, isActive ? COLOR_TEXT_BRIGHT_WHITE : 0xFFD6DCE8);

            String sub = "default".equalsIgnoreCase(cfgName)
                    ? "Unified client config (config/acc.json)"
                    : "Embedded profile in config/acc.json (" + cfgName + ")";
            Component subComp = Component.literal(sub).withStyle(nunitoRegularStyle);
            enqueueComponentText(subComp, listLeft + 12, rowTop + 20, COLOR_TEXT_DIMMED_GRAY);

            boolean isDefault = "default".equalsIgnoreCase(cfgName);
            int rightX = listLeft + rowWidth - 10;
            int btnH = 18;
            int btnY = rowTop + (cfgRowH - 4 - btnH) / 2;

            if (!isDefault) {
                int delW = 24;
                int delX = rightX - delW;
                boolean delHover = isHovered(mouseX, mouseY, delX, btnY, delW, btnH);
                drawRoundedRect(delX, btnY, delW, btnH, 6, delHover ? 0xCCF05454 : 0x66D64545);
                Component delComp = Component.literal("X").withStyle(nunitoRegularStyle);
                enqueueComponentText(delComp, delX + (delW - this.font.width(delComp)) / 2, btnY + 5, COLOR_TEXT_BRIGHT_WHITE);
                rightX = delX - 6;
            }

            int saveW = 42;
            int saveX = rightX - saveW;
            boolean saveHover = isHovered(mouseX, mouseY, saveX, btnY, saveW, btnH);
            drawRoundedRect(saveX, btnY, saveW, btnH, 6, saveHover ? COLOR_BIND_BG_HOVER : COLOR_BIND_BG);
            Component saveComp = Component.literal("Save").withStyle(nunitoRegularStyle);
            enqueueComponentText(saveComp, saveX + (saveW - this.font.width(saveComp)) / 2, btnY + 5, COLOR_TEXT_BRIGHT_WHITE);
            rightX = saveX - 6;

            int loadW = 44;
            int loadX = rightX - loadW;
            boolean loadHover = isHovered(mouseX, mouseY, loadX, btnY, loadW, btnH);
            drawRoundedRect(loadX, btnY, loadW, btnH, 6, loadHover ? 0xFFE2E6F5 : COLOR_PILL_ACTIVE_BG);
            Component loadComp = Component.literal("Load").withStyle(nunitoRegularStyle);
            enqueueComponentText(loadComp, loadX + (loadW - this.font.width(loadComp)) / 2, btnY + 5, COLOR_PILL_ACTIVE_TEXT);
        }

        flushBatchedRenderPasses(guiGraphics);
    }

    private void handleConfigsTabClick(int mouseX, int mouseY, boolean leftClicked, boolean rightClicked) {
        if (!leftClicked && !rightClicked) {
            return;
        }
        if (isHovered(mouseX, mouseY, configInputBoxX, configInputBoxY, configInputBoxW, configInputBoxH)) {
            configInputFocused = true;
            friendInputFocused = false;
            searchFocused = false;
            SoundUtil.playClick();
            return;
        } else {
            configInputFocused = false;
        }

        if (leftClicked && isHovered(mouseX, mouseY, configSaveBtnX, configSaveBtnY, configSaveBtnW, configSaveBtnH)) {
            submitConfigSaveFromInput();
            return;
        }

        if (leftClicked && isHovered(mouseX, mouseY, configReloadBtnX, configReloadBtnY, configReloadBtnW, configReloadBtnH)) {
            refreshSavedConfigList(true);
            loadNamedConfig(activeConfigName);
            setConfigStatus("Reloaded '" + activeConfigName + "'");
            SoundUtil.playClick();
            return;
        }

        if (leftClicked && isHovered(mouseX, mouseY, listLeft, listTop, listRight - listLeft, listHeight)) {
            refreshSavedConfigList(false);
            int statusOffset = (!configStatusMessage.isEmpty() && System.currentTimeMillis() < configStatusExpireMs) ? 18 : 0;
            int cfgRowH = 40;
            int scrollInt = Math.round(configScrollOffset);
            for (int i = 0; i < cachedConfigNames.size(); i++) {
                String cfgName = cachedConfigNames.get(i);
                int rowTop = listTop + statusOffset + i * cfgRowH - scrollInt;
                int rowBottom = rowTop + cfgRowH;
                if (rowBottom < listTop || rowTop > listBottom) {
                    continue;
                }
                boolean isDefault = "default".equalsIgnoreCase(cfgName);
                int rightX = listLeft + rowWidth - 10;
                int btnH = 18;
                int btnY = rowTop + (cfgRowH - 4 - btnH) / 2;

                if (!isDefault) {
                    int delW = 24;
                    int delX = rightX - delW;
                    if (isHovered(mouseX, mouseY, delX, btnY, delW, btnH)) {
                        deleteNamedConfig(cfgName);
                        setConfigStatus("Deleted '" + cfgName + "'");
                        SoundUtil.playTogglePop(false);
                        return;
                    }
                    rightX = delX - 6;
                }

                int saveW = 42;
                int saveX = rightX - saveW;
                if (isHovered(mouseX, mouseY, saveX, btnY, saveW, btnH)) {
                    if (saveNamedConfig(cfgName)) {
                        setConfigStatus("Saved '" + cfgName + "'");
                        SoundUtil.playTogglePop(true);
                    }
                    return;
                }
                rightX = saveX - 6;

                int loadW = 44;
                int loadX = rightX - loadW;
                if (isHovered(mouseX, mouseY, loadX, btnY, loadW, btnH)) {
                    if (loadNamedConfig(cfgName)) {
                        setConfigStatus("Loaded '" + cfgName + "'");
                        SoundUtil.playTogglePop(true);
                    }
                    return;
                }
            }
        }
    }

    private JsonObject serializeFullClientConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("activeProfile", activeConfigName != null ? activeConfigName : "default");
        root.addProperty("activeCategoryIndex", activeCategoryIndex);
        root.addProperty("friendBindKey", FriendManager.getFriendBindKey());
        if (clientTabUiVolumeEntry != null && clientTabUiVolumeEntry.setting instanceof NumberSetting volNs) {
            root.addProperty("uiVolume", volNs.getValue());
        }

        // Save FriendManager entries (uuid, username, alias)
        root.add("friends", FriendManager.toFriendsJsonArray());

        // Save ALL modules (enabled state, keybinds, expanded state, and every setting)
        JsonObject modulesObj = new JsonObject();
        for (CachedModuleRow row : allModuleRows) {
            if (row == null || row.module == null || row.name == null) {
                continue;
            }
            JsonObject mObj = new JsonObject();
            if (!isActionMacroRow(row)) {
                mObj.addProperty("enabled", row.module.isEnabled());
            }
            mObj.addProperty("key", resolveEffectiveBindKey(row));
            mObj.addProperty("expanded", row.expanded);

            JsonObject settingsObj = new JsonObject();
            for (CachedSettingEntry se : row.settings) {
                if (se == null || se.setting == null || se.name == null) {
                    continue;
                }
                if (se.type == 0 && se.setting instanceof BooleanSetting bs) {
                    Object raw = ((Setting<?>) bs).getValue();
                    boolean bVal = (raw instanceof Boolean b) ? b : (raw instanceof Number n && n.intValue() > 0);
                    settingsObj.addProperty(se.name, bVal);
                } else if (se.type == 1 && se.setting instanceof NumberSetting ns) {
                    settingsObj.addProperty(se.name, ns.getValue());
                } else if (se.type == 2 && se.setting instanceof ModeSetting ms) {
                    if (ms.getValue() != null) {
                        settingsObj.addProperty(se.name, ms.getValue());
                    }
                } else if (se.type == 3 && se.setting instanceof MultiModeSetting mms) {
                    JsonArray arr = new JsonArray();
                    for (String opt : se.modeOptions) {
                        if (mms.isSelected(opt)) {
                            arr.add(opt);
                        }
                    }
                    settingsObj.add(se.name, arr);
                } else if (se.type == 4 && se.setting instanceof StringSetting ss) {
                    settingsObj.addProperty(se.name, ss.getValue() != null ? ss.getValue() : "");
                }
            }
            // Fallback pass over module.getSettings() so hidden/position settings (e.g. HUD xRatio/yRatio) are also saved
            try {
                List<Setting<?>> rawAll = row.module.getSettings();
                if (rawAll != null) {
                    for (Setting<?> s : rawAll) {
                        if (s == null || s.getName() == null || settingsObj.has(s.getName())) {
                            continue;
                        }
                        if (s instanceof BooleanSetting bs) {
                            Object raw = ((Setting<?>) bs).getValue();
                            boolean bVal = (raw instanceof Boolean b) ? b : (raw instanceof Number n && n.intValue() > 0);
                            settingsObj.addProperty(s.getName(), bVal);
                        } else if (s instanceof NumberSetting ns) {
                            settingsObj.addProperty(s.getName(), ns.getValue());
                        } else if (s instanceof ModeSetting ms && ms.getValue() != null) {
                            settingsObj.addProperty(s.getName(), ms.getValue());
                        } else if (s instanceof StringSetting ss) {
                            settingsObj.addProperty(s.getName(), ss.getValue() != null ? ss.getValue() : "");
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            mObj.add("settings", settingsObj);
            modulesObj.add(row.name, mObj);
        }
        JsonObject clientObj = new JsonObject();
        clientObj.addProperty("accentIndex", ClientThemeManager.getAccentIndex());
        clientObj.addProperty("panelOpacity", ClientThemeManager.getPanelOpacity());
        clientObj.addProperty("backgroundDim", ClientThemeManager.isBackgroundDimEnabled());
        clientObj.addProperty("guiScaleIndex", ClientThemeManager.getGuiScaleIndex());
        clientObj.addProperty("clickGuiKey", ClientThemeManager.getClickGuiKey());
        clientObj.addProperty("floatingButtonEnabled", ClientThemeManager.isFloatingButtonEnabled());
        clientObj.addProperty("floatingButtonSize", ClientThemeManager.getFloatingButtonSize());
        clientObj.addProperty("floatingBtnXRatio", ClientThemeManager.getFloatingBtnXRatio());
        clientObj.addProperty("floatingBtnYRatio", ClientThemeManager.getFloatingBtnYRatio());
        clientObj.addProperty("uiSoundsEnabled", ClientThemeManager.isUiSoundsEnabled());
        root.add("clientTheme", clientObj);
        root.add("modules", modulesObj);
        return root;
    }

    private boolean applyConfigJsonObject(JsonObject root) {
        if (root == null || allModuleRows == null || allModuleRows.length == 0) {
            return false;
        }
        if (root.has("activeProfile") && root.get("activeProfile").isJsonPrimitive()) {
            String prof = sanitizeConfigName(root.get("activeProfile").getAsString());
            if (!prof.isEmpty()) {
                activeConfigName = prof;
            }
        }
        if (root.has("activeCategoryIndex") && root.get("activeCategoryIndex").isJsonPrimitive()) {
            int idx = root.get("activeCategoryIndex").getAsInt();
            if (idx >= 0 && idx < CATEGORY_PILLS.length) {
                activeCategoryIndex = idx;
            }
        }
        Integer loadedFriendBind = (root.has("friendBindKey") && root.get("friendBindKey").isJsonPrimitive())
                ? root.get("friendBindKey").getAsInt() : null;
        JsonArray loadedFriendsArr = (root.has("friends") && root.get("friends").isJsonArray())
                ? root.getAsJsonArray("friends") : null;
        if (loadedFriendBind != null || loadedFriendsArr != null) {
            FriendManager.applyFriendsFromJson(loadedFriendsArr, loadedFriendBind);
        }
        if (root.has("uiVolume") && clientTabUiVolumeEntry != null && clientTabUiVolumeEntry.setting instanceof NumberSetting volNs) {
            volNs.setValue(Mth.clamp(root.get("uiVolume").getAsDouble(), volNs.getMin(), volNs.getMax()));
            updateCachedSliderStringIfNeeded(clientTabUiVolumeEntry);
        }
        if (root.has("clientTheme") && root.get("clientTheme").isJsonObject()) {
            JsonObject co = root.getAsJsonObject("clientTheme");
            if (co.has("accentIndex") && co.get("accentIndex").isJsonPrimitive()) {
                ClientThemeManager.setAccentIndex(co.get("accentIndex").getAsInt());
            }
            if (co.has("panelOpacity") && co.get("panelOpacity").isJsonPrimitive()) {
                ClientThemeManager.setPanelOpacity(co.get("panelOpacity").getAsFloat());
            }
            if (co.has("backgroundDim") && co.get("backgroundDim").isJsonPrimitive()) {
                ClientThemeManager.setBackgroundDimEnabled(co.get("backgroundDim").getAsBoolean());
            }
            if (co.has("guiScaleIndex") && co.get("guiScaleIndex").isJsonPrimitive()) {
                ClientThemeManager.setGuiScaleIndex(co.get("guiScaleIndex").getAsInt());
            }
            if (co.has("clickGuiKey") && co.get("clickGuiKey").isJsonPrimitive()) {
                ClientThemeManager.setClickGuiKey(co.get("clickGuiKey").getAsInt());
                syncClickGuiKeyToBindings(ClientThemeManager.getClickGuiKey());
            }
            if (co.has("floatingButtonEnabled") && co.get("floatingButtonEnabled").isJsonPrimitive()) {
                ClientThemeManager.setFloatingButtonEnabled(co.get("floatingButtonEnabled").getAsBoolean());
            }
            if (co.has("floatingButtonSize") && co.get("floatingButtonSize").isJsonPrimitive()) {
                ClientThemeManager.setFloatingButtonSize(co.get("floatingButtonSize").getAsFloat());
            }
            if (co.has("floatingBtnXRatio") && co.get("floatingBtnXRatio").isJsonPrimitive()
                    && co.has("floatingBtnYRatio") && co.get("floatingBtnYRatio").isJsonPrimitive()) {
                ClientThemeManager.setFloatingBtnPosRatio(
                        co.get("floatingBtnXRatio").getAsFloat(),
                        co.get("floatingBtnYRatio").getAsFloat()
                );
            }
            if (co.has("uiSoundsEnabled") && co.get("uiSoundsEnabled").isJsonPrimitive()) {
                ClientThemeManager.setUiSoundsEnabled(co.get("uiSoundsEnabled").getAsBoolean());
            }
        }
        if (root.has("modules") && root.get("modules").isJsonObject()) {
            JsonObject modulesObj = root.getAsJsonObject("modules");
            for (CachedModuleRow row : allModuleRows) {
                if (row == null || row.module == null || row.name == null || !modulesObj.has(row.name)) {
                    continue;
                }
                JsonObject mObj = modulesObj.getAsJsonObject(row.name);
                if (mObj.has("key") && mObj.get("key").isJsonPrimitive()) {
                    int k = mObj.get("key").getAsInt();
                    row.boundKey = (k == 0 || k == GLFW.GLFW_KEY_UNKNOWN) ? -1 : k;
                    PERSISTENT_MODULE_BINDS.put(row.name.toLowerCase(Locale.ROOT), row.boundKey);
                    if (isActionMacroRow(row)) {
                        if (isElytraHelperRow(row)) {
                            invokeStaticMacroSetKey(row.module, row.boundKey);
                        } else {
                            LungeHelper.setMacroBoundKey(row.boundKey);
                        }
                    }
                    setModuleBindKey(row.module, row.boundKey);
                    refreshModuleBindLabelIfNeeded(row, true);
                }
                if (mObj.has("expanded") && mObj.get("expanded").isJsonPrimitive()) {
                    row.expanded = mObj.get("expanded").getAsBoolean();
                }
                if (!isActionMacroRow(row) && mObj.has("enabled") && mObj.get("enabled").isJsonPrimitive()) {
                    boolean wantEnabled = mObj.get("enabled").getAsBoolean();
                    if (row.module.isEnabled() != wantEnabled) {
                        row.module.toggle();
                    }
                }
                if (mObj.has("settings") && mObj.get("settings").isJsonObject()) {
                    JsonObject settingsObj = mObj.getAsJsonObject("settings");
                    for (CachedSettingEntry se : row.settings) {
                        if (se == null || se.setting == null || se.name == null || !settingsObj.has(se.name)) {
                            continue;
                        }
                        JsonElement valEl = settingsObj.get(se.name);
                        try {
                            if (se.type == 0 && se.setting instanceof BooleanSetting bs && valEl.isJsonPrimitive()) {
                                bs.setValue(valEl.getAsBoolean());
                            } else if (se.type == 1 && se.setting instanceof NumberSetting ns && valEl.isJsonPrimitive()) {
                                ns.setValue(Mth.clamp(valEl.getAsDouble(), ns.getMin(), ns.getMax()));
                                updateCachedSliderStringIfNeeded(se);
                            } else if (se.type == 2 && se.setting instanceof ModeSetting ms && valEl.isJsonPrimitive()) {
                                String modeVal = valEl.getAsString();
                                if (ms.getModes() != null && ms.getModes().contains(modeVal)) {
                                    ms.setValue(modeVal);
                                }
                            } else if (se.type == 3 && se.setting instanceof MultiModeSetting mms && valEl.isJsonArray()) {
                                java.util.Set<String> wanted = new java.util.HashSet<>();
                                for (JsonElement e : valEl.getAsJsonArray()) {
                                    if (e.isJsonPrimitive()) {
                                        wanted.add(e.getAsString());
                                    }
                                }
                                for (String opt : se.modeOptions) {
                                    boolean cur = mms.isSelected(opt);
                                    boolean should = wanted.contains(opt);
                                    if (cur != should) {
                                        toggleMultiModeOption(mms, opt);
                                    }
                                }
                            } else if (se.type == 4 && se.setting instanceof StringSetting ss && valEl.isJsonPrimitive()) {
                                ss.setValue(valEl.getAsString());
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    // Restore any extra/hidden settings from module.getSettings() (e.g., HUD xRatio/yRatio)
                    try {
                        List<Setting<?>> rawAll = row.module.getSettings();
                        if (rawAll != null) {
                            for (Setting<?> s : rawAll) {
                                if (s == null || s.getName() == null || !settingsObj.has(s.getName())) {
                                    continue;
                                }
                                JsonElement valEl = settingsObj.get(s.getName());
                                if (s instanceof BooleanSetting bs && valEl.isJsonPrimitive()) {
                                    bs.setValue(valEl.getAsBoolean());
                                } else if (s instanceof NumberSetting ns && valEl.isJsonPrimitive()) {
                                    ns.setValue(Mth.clamp(valEl.getAsDouble(), ns.getMin(), ns.getMax()));
                                } else if (s instanceof ModeSetting ms && valEl.isJsonPrimitive()) {
                                    String mv = valEl.getAsString();
                                    if (ms.getModes() != null && ms.getModes().contains(mv)) {
                                        ms.setValue(mv);
                                    }
                                } else if (s instanceof StringSetting ss && valEl.isJsonPrimitive()) {
                                    ss.setValue(valEl.getAsString());
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        filterDirty = true;
        layoutDirty = true;
        return true;
    }

    public boolean saveNamedConfig(String name) {
        if (allModuleRows == null || allModuleRows.length == 0) {
            return false;
        }
        String safe = sanitizeConfigName(name);
        if (safe.isEmpty()) {
            safe = "default";
        }
        try {
            activeConfigName = safe;
            JsonObject existingRoot = readUnifiedRootOrEmpty();
            JsonObject profilesObj = (existingRoot.has("profiles") && existingRoot.get("profiles").isJsonObject())
                    ? existingRoot.getAsJsonObject("profiles")
                    : new JsonObject();

            JsonObject snapshot = serializeFullClientConfig();
            if (!"default".equalsIgnoreCase(safe)) {
                profilesObj.add(safe, snapshot.deepCopy());
            }
            if (profilesObj.size() > 0) {
                snapshot.add("profiles", profilesObj);
            }
            writeUnifiedRoot(snapshot);
            refreshSavedConfigList(true);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean loadNamedConfig(String name) {
        String safe = sanitizeConfigName(name);
        if (safe.isEmpty()) {
            safe = "default";
        }
        try {
            JsonObject root = readUnifiedRootOrEmpty();
            if (root.size() == 0) {
                return false;
            }
            JsonObject targetObj;
            if ("default".equalsIgnoreCase(safe)) {
                targetObj = root;
            } else {
                if (!root.has("profiles") || !root.get("profiles").isJsonObject()) {
                    return false;
                }
                JsonObject profilesObj = root.getAsJsonObject("profiles");
                if (!profilesObj.has(safe) || !profilesObj.get(safe).isJsonObject()) {
                    return false;
                }
                targetObj = profilesObj.getAsJsonObject(safe);
            }
            activeConfigName = safe;
            boolean ok = applyConfigJsonObject(targetObj);
            if (ok) {
                // Persist active loaded state into the single config/acc.json while preserving profiles
                JsonObject updated = serializeFullClientConfig();
                if (root.has("profiles") && root.get("profiles").isJsonObject()) {
                    updated.add("profiles", root.getAsJsonObject("profiles"));
                }
                writeUnifiedRoot(updated);
            }
            return ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void deleteNamedConfig(String name) {
        String safe = sanitizeConfigName(name);
        if (safe.isEmpty() || "default".equalsIgnoreCase(safe)) {
            return;
        }
        try {
            JsonObject root = readUnifiedRootOrEmpty();
            if (root.has("profiles") && root.get("profiles").isJsonObject()) {
                root.getAsJsonObject("profiles").remove(safe);
                writeUnifiedRoot(root);
            }
            if (safe.equalsIgnoreCase(activeConfigName)) {
                activeConfigName = "default";
            }
            refreshSavedConfigList(true);
        } catch (Throwable ignored) {
        }
    }

    public boolean trySaveUnifiedConfigIfReady() {
        if (allModuleRows == null || allModuleRows.length == 0) {
            return false;
        }
        return saveNamedConfig(activeConfigName != null && !activeConfigName.isEmpty() ? activeConfigName : "default");
    }

    public void saveConfigNow() {
        saveNamedConfig(activeConfigName != null && !activeConfigName.isEmpty() ? activeConfigName : "default");
    }

    public void loadConfigIfPresent() {
        if (configLoadedOnce || allModuleRows == null || allModuleRows.length == 0) {
            return;
        }
        configLoadedOnce = true;
        try {
            Path cfg = getConfigFilePath();
            if (!Files.exists(cfg)) {
                return;
            }
            String json = Files.readString(cfg, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return;
            }
            applyConfigJsonObject(parsed.getAsJsonObject());
            refreshSavedConfigList(true);
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // Dedicated ClickGUI HUD Tab (activeTopTabIndex == 2) — Polished Glass Cards
    // =========================================================================
    private void ensureHudTabComponents() {
        if (hudHeaderTitleComp != null) return;
        hudHeaderTitleComp = Component.literal("HUD Widgets").withStyle(HudManager.getBoldStyle());
        hudEditLayoutComp = Component.literal("Edit Layout").withStyle(HudManager.getBoldStyle());
        hudResetAllComp = Component.literal("Reset").withStyle(nunitoRegularStyle);
        hudMovePillComp = Component.literal("Move").withStyle(nunitoRegularStyle);
    }

    private float computeHudCardProgress(int index) {
        float t = Mth.clamp((hudTabOpenTime - index * 0.032F) / 0.20F, 0.0F, 1.0F);
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv; // easeOutCubic
    }

    private void enqueueHudTopBar(int mouseX, int mouseY) {
        ensureHudTabComponents();
        int accent = ClientThemeManager.getAccentArgb();
        int barY = searchBoxY;
        int barH = searchBoxH;

        int activeCount = 0;
        int totalCount = 0;
        if (allModuleRows != null) {
            for (CachedModuleRow row : allModuleRows) {
                if (row != null && row.module instanceof HudModule) {
                    totalCount++;
                    if (row.module.isEnabled()) activeCount++;
                }
            }
        }
        if (activeCount != hudCachedActiveCount || totalCount != hudCachedTotalCount || hudCountPillComp == null) {
            hudCachedActiveCount = activeCount;
            hudCachedTotalCount = totalCount;
            hudCountPillComp = Component.literal(activeCount + " / " + totalCount + " Active").withStyle(nunitoRegularStyle);
        }

        enqueueComponentText(hudHeaderTitleComp, listLeft + 4, barY + 6, COLOR_TEXT_BRIGHT_WHITE);
        int titleW = this.font.width(hudHeaderTitleComp);
        int pillW = this.font.width(hudCountPillComp) + 12;
        int pillX = listLeft + 4 + titleW + 8;
        drawRoundedRect(pillX, barY + 3, pillW, barH - 6, 6, ClientThemeManager.getAccentWithAlpha(0.18F));
        enqueueComponentText(hudCountPillComp, pillX + 6, barY + 6, accent);

        // Right-aligned "Edit Layout" (accent) + "Reset" (glass) pills
        hudEditLayoutBtnW = 82;
        hudEditLayoutBtnH = barH;
        hudEditLayoutBtnX = listRight - hudEditLayoutBtnW;
        hudEditLayoutBtnY = barY;

        hudResetBtnW = 48;
        hudResetBtnH = barH;
        hudResetBtnX = hudEditLayoutBtnX - hudResetBtnW - 6;
        hudResetBtnY = barY;

        boolean hoverReset = isHovered(mouseX, mouseY, hudResetBtnX, hudResetBtnY, hudResetBtnW, hudResetBtnH);
        drawRoundedRect(hudResetBtnX, hudResetBtnY, hudResetBtnW, hudResetBtnH, 7,
                hoverReset ? 0x36FFFFFF : 0x1EFFFFFF);
        int rw = this.font.width(hudResetAllComp);
        enqueueComponentText(hudResetAllComp, hudResetBtnX + (hudResetBtnW - rw) / 2,
                hudResetBtnY + 6, hoverReset ? COLOR_TEXT_BRIGHT_WHITE : 0xFFD1D5DB);

        boolean hoverEdit = isHovered(mouseX, mouseY, hudEditLayoutBtnX, hudEditLayoutBtnY, hudEditLayoutBtnW, hudEditLayoutBtnH);
        int editBg = hoverEdit ? lerpColor(accent, 0xFFFFFFFF, 0.16F) : accent;
        drawRoundedRect(hudEditLayoutBtnX, hudEditLayoutBtnY, hudEditLayoutBtnW, hudEditLayoutBtnH, 7, editBg);
        int lw = this.font.width(hudEditLayoutComp);
        enqueueComponentText(hudEditLayoutComp, hudEditLayoutBtnX + (hudEditLayoutBtnW - lw) / 2,
                hudEditLayoutBtnY + 6, 0xFF0B0D10);
    }

    private void renderHudTabPanel(GuiGraphics guiGraphics, float dt, int mouseX, int mouseY) {
        ensureHudTabComponents();
        int accent = ClientThemeManager.getAccentArgb();
        int cardGap = 8;
        int headerH = ROW_HEIGHT + 2;
        boolean mouseInsideViewport = isHovered(mouseX, mouseY, listLeft, listTop, listRight - listLeft, listHeight);

        float totalH = 6.0F;
        for (CachedModuleRow row : allModuleRows) {
            if (row == null || !(row.module instanceof HudModule)) continue;
            row.toggleAnim = AnimationUtil.lerp(row.toggleAnim, row.module.isEnabled() ? 1.0F : 0.0F, 15.0F, dt);
            row.expandAnim = AnimationUtil.lerp(row.expandAnim, row.expanded ? 1.0F : 0.0F, 13.0F, dt);
            int visibleSettings = 0;
            for (CachedSettingEntry se : row.settings) {
                if (se != null && se.visible) visibleSettings++;
            }
            row.cachedVisibleSettingCount = visibleSettings;
            int drawerTargetH = visibleSettings > 0 ? (visibleSettings * SETTING_ROW_HEIGHT + 14) : 0;
            int curDrawerH = Math.round(drawerTargetH * row.expandAnim);
            row.cachedTotalHeight = headerH + curDrawerH;
            totalH += row.cachedTotalHeight + cardGap;
        }

        hudMaxScrollOffset = Math.max(0.0F, totalH - listHeight + 8.0F);
        hudTargetScrollOffset = Mth.clamp(hudTargetScrollOffset, 0.0F, hudMaxScrollOffset);
        hudScrollOffset = AnimationUtil.lerp(hudScrollOffset, hudTargetScrollOffset, 16.0F, dt);

        int cursorY = listTop + 4 - Math.round(hudScrollOffset);
        int cardIdx = 0;
        for (CachedModuleRow row : allModuleRows) {
            if (row == null || !(row.module instanceof HudModule hudMod)) continue;
            int cardH = row.cachedTotalHeight;
            float prog = computeHudCardProgress(cardIdx++);
            int drawY = cursorY + Math.round(10.0F * (1.0F - prog));

            if (drawY + cardH >= listTop - 12 && drawY <= listBottom + 12 && prog > 0.04F) {
                boolean hoveredCard = mouseInsideViewport && isHovered(mouseX, mouseY, listLeft, drawY, rowWidth, headerH);
                row.hoverAnim = AnimationUtil.lerp(row.hoverAnim, hoveredCard ? 1.0F : 0.0F, 14.0F, dt);

                // Smooth hover-elevated glass card (11px radius, live panel opacity)
                int baseCardCol = lerpColor(0xB813161F, 0xD81B202E, row.hoverAnim);
                int cardCol = multiplyAlpha(baseCardCol, prog);
                HudManager.drawRoundedRect(guiGraphics, listLeft, drawY, rowWidth, cardH, 11, cardCol);

                // Subtle left 3px animated active indicator pill
                if (row.toggleAnim > 0.03F) {
                    int pillH = Math.max(8, Math.round((headerH - 16) * row.toggleAnim));
                    int pillY = drawY + (headerH - pillH) / 2;
                    HudManager.drawRoundedRect(guiGraphics, listLeft + 2, pillY, 3, pillH, 1,
                            multiplyAlpha(accent, row.toggleAnim * prog));
                }

                // Soft 28x28 rounded icon badge with animated accent blend
                int iconSize = 28;
                int iconX = listLeft + 11;
                int iconY = drawY + (headerH - iconSize) / 2;
                int iconBg = lerpColor(0x1EFFFFFF, ClientThemeManager.getAccentWithAlpha(0.22F), row.toggleAnim);
                HudManager.drawRoundedRect(guiGraphics, iconX, iconY, iconSize, iconSize, 8, multiplyAlpha(iconBg, prog));
                Component iconComp = Component.literal(hudMod.getIconGlyph()).withStyle(HudManager.getBoldStyle());
                int icW = this.font.width(iconComp);
                int iconFg = multiplyAlpha(lerpColor(0xFF94A3B8, accent, row.toggleAnim), prog);
                if ((iconFg >>> 24) > 8) {
                    guiGraphics.drawString(this.font, iconComp, iconX + (iconSize - icW) / 2, iconY + 10, iconFg, false);
                }

                // Title & Description (crisp white when ON, muted slate when OFF)
                int textX = iconX + iconSize + 10;
                int titleCol = multiplyAlpha(lerpColor(0xFFCBD5E1, 0xFFFFFFFF, row.toggleAnim), prog);
                int descCol = multiplyAlpha(0xFF8B93A6, prog);
                if ((titleCol >>> 24) > 8) {
                    guiGraphics.drawString(this.font, row.nunitoTitle, textX, drawY + 10, titleCol, false);
                    guiGraphics.drawString(this.font, row.nunitoRegularDesc, textX, drawY + 24, descCol, false);
                }

                // Right-side Elastic iOS Pill Toggle (stretches mid-slide, live accent)
                int toggleW = 28;
                int toggleH = 15;
                int toggleX = listLeft + rowWidth - 12 - toggleW;
                int toggleY = drawY + (headerH - toggleH) / 2;
                drawElasticPillImmediate(guiGraphics, toggleX, toggleY, toggleW, toggleH, row.toggleAnim, prog);

                // Expand Chevron
                int chevX = toggleX - 15;
                int chevY = drawY + (headerH - 8) / 2;
                int chevCol = multiplyAlpha(row.expanded ? accent : 0xFF8B93A6, prog);
                if ((chevCol >>> 24) > 8) {
                    guiGraphics.drawString(this.font,
                            row.expanded ? chevronExpandedComponent : chevronCollapsedComponent,
                            chevX, chevY, chevCol, false);
                }

                // Sleek "Move" Pill Button
                int editW = 44;
                int editH = 18;
                int editX = chevX - editW - 8;
                int editY = drawY + (headerH - editH) / 2;
                boolean hoverEdit = mouseInsideViewport && isHovered(mouseX, mouseY, editX, editY, editW, editH);
                int editPillBg = hoverEdit ? ClientThemeManager.getAccentWithAlpha(0.28F * prog) : multiplyAlpha(0x1CFFFFFF, prog);
                HudManager.drawRoundedRect(guiGraphics, editX, editY, editW, editH, 6, editPillBg);
                int ew = this.font.width(hudMovePillComp);
                int editFg = multiplyAlpha(hoverEdit ? accent : 0xFFD1D5DB, prog);
                if ((editFg >>> 24) > 8) {
                    guiGraphics.drawString(this.font, hudMovePillComp, editX + (editW - ew) / 2, editY + 5, editFg, false);
                }

                // Smoothly expanded inline settings drawer with subtle top divider
                if (row.expandAnim > 0.05F && row.cachedVisibleSettingCount > 0) {
                    guiGraphics.fill(listLeft + 12, drawY + headerH, listLeft + rowWidth - 12, drawY + headerH + 1,
                            multiplyAlpha(0x1CFFFFFF, row.expandAnim * prog));
                    resetBatchQueues();
                    enqueueModuleSettingsDrawer(row, listLeft + 6, drawY + headerH + 2, rowWidth - 12,
                            mouseX, mouseY, mouseInsideViewport);
                    flushBatchedRenderPasses(guiGraphics);
                }
            }

            cursorY += cardH + cardGap;
        }
    }

    private void handleHudTabClick(int mouseX, int mouseY, boolean leftClicked, boolean rightClicked) {
        if (!leftClicked && !rightClicked) return;

        if (leftClicked && isHovered(mouseX, mouseY, hudEditLayoutBtnX, hudEditLayoutBtnY, hudEditLayoutBtnW, hudEditLayoutBtnH)) {
            HudManager.setLayoutEditorActive(true, null);
            SoundUtil.playClick();
            return;
        }
        if (leftClicked && isHovered(mouseX, mouseY, hudResetBtnX, hudResetBtnY, hudResetBtnW, hudResetBtnH)) {
            ClientThemeManager.resetLayout();
            saveConfigNow();
            SoundUtil.playTogglePop(true);
            return;
        }
if (!isHovered(mouseX, mouseY, listLeft, listTop, listRight - listLeft, listHeight)) {
            return;
        }

        int cardGap = 8;
        int headerH = ROW_HEIGHT + 2;
        int cursorY = listTop + 4 - Math.round(hudScrollOffset);

        for (CachedModuleRow row : allModuleRows) {
            if (row == null || !(row.module instanceof HudModule hudMod)) continue;
            int cardH = row.cachedTotalHeight;

            if (isHovered(mouseX, mouseY, listLeft, cursorY, rowWidth, cardH)) {
                int toggleW = 28;
                int toggleH = 15;
                int toggleX = listLeft + rowWidth - 12 - toggleW;
                int toggleY = cursorY + (headerH - toggleH) / 2;

                int chevX = toggleX - 15;
                int editW = 44;
                int editH = 18;
                int editX = chevX - editW - 8;
                int editY = cursorY + (headerH - editH) / 2;

                // Elastic Pill Toggle or Right-Click Header -> mutates exact same Module#toggle()
                if (isHovered(mouseX, mouseY, toggleX - 4, toggleY - 4, toggleW + 8, toggleH + 8)
                        || (rightClicked && isHovered(mouseX, mouseY, listLeft, cursorY, rowWidth, headerH))) {
                    row.module.toggle();
                    SoundUtil.playTogglePop(row.module.isEnabled());
                    saveConfigNow();
                    return;
                }

                // "Move" Pill -> enters Layout Editor focused on this HudModule
                if (leftClicked && isHovered(mouseX, mouseY, editX, editY, editW, editH)) {
                    if (!hudMod.isEnabled()) {
                        hudMod.setEnabled(true);
                    }
                    HudManager.setLayoutEditorActive(true, hudMod);
                    SoundUtil.playClick();
                    return;
                }

                // Header Left-Click -> expands/collapses all HudModule settings inline
                if (leftClicked && isHovered(mouseX, mouseY, listLeft, cursorY, rowWidth, headerH)) {
                    if (row.settings.length > 0) {
                        row.expanded = !row.expanded;
                        layoutDirty = true;
                        SoundUtil.playClick();
                    }
                    return;
                }

                // Inline Settings Drawer Clicks (ModeSetting, NumberSetting, BooleanSetting)
                if (row.expanded && isHovered(mouseX, mouseY, listLeft, cursorY + headerH, rowWidth, cardH - headerH)) {
                    int drawerX = listLeft + 4;
                    int drawerW = rowWidth - 8;
                    int curY = cursorY + headerH + 5;
                    for (int s = 0; s < row.settings.length; s++) {
                        CachedSettingEntry se = row.settings[s];
                        if (se == null || !se.visible) continue;
                        if (isHovered(mouseX, mouseY, drawerX, curY, drawerW, SETTING_ROW_HEIGHT)) {
                            handleSettingRowClick(se, drawerX, curY, drawerW, mouseX, mouseY, leftClicked);
                            layoutDirty = true;
                            saveConfigNow();
                            return;
                        }
                        curY += SETTING_ROW_HEIGHT;
                    }
                }
                return;
            }
            cursorY += cardH + cardGap;
        }
    }

    // =========================================================================
    // Bento-Style 3-Card Client Tab (Appearance & Theme Engine + Controls & Mobile)
    // =========================================================================
    private void ensureBentoComponents() {
        if (bentoBrandComp != null) return;
        bentoBrandComp = Component.literal("Attrium").withStyle(HudManager.getBoldStyle());
        bentoVerComp = Component.literal("1.21.11").withStyle(HudManager.getBoldStyle());
        bentoOpenFolderComp = Component.literal("Open Folder").withStyle(nunitoRegularStyle);
        bentoResetLayoutComp = Component.literal("Reset Layout").withStyle(nunitoRegularStyle);
        bentoAppearanceTitleComp = Component.literal("Appearance").withStyle(HudManager.getBoldStyle());
        bentoAccentLabelComp = Component.literal("Accent Theme").withStyle(nunitoRegularStyle);
        bentoOpacityLabelComp = Component.literal("Glass Opacity").withStyle(nunitoRegularStyle);
        bentoBgDimLabelComp = Component.literal("Background Dim").withStyle(nunitoRegularStyle);
        bentoControlsTitleComp = Component.literal("Controls & Mobile").withStyle(HudManager.getBoldStyle());
        bentoScaleLabelComp = Component.literal("GUI Scale").withStyle(nunitoRegularStyle);
        for (int i = 0; i < 4; i++) {
            bentoScaleOptComps[i] = Component.literal(ClientThemeManager.GUI_SCALE_LABELS[i]).withStyle(nunitoRegularStyle);
        }
        bentoKeybindLabelComp = Component.literal("ClickGUI Keybind").withStyle(nunitoRegularStyle);
        bentoGuiKeyListenComp = Component.literal("[ ... ]").withStyle(HudManager.getBoldStyle());
        bentoFloatingLabelComp = Component.literal("Floating Touch Button").withStyle(nunitoRegularStyle);
        bentoSoundsLabelComp = Component.literal("UI Feedback Sounds").withStyle(nunitoRegularStyle);
    }

    private void stepClientBentoAnimations(float dt) {
        float rate = 1.0F - (float) Math.exp(-14.0F * dt);
        int activeAccent = ClientThemeManager.getAccentIndex();
        for (int i = 0; i < 4; i++) {
            float target = (i == activeAccent) ? 1.0F : 0.0F;
            swatchRingAnim[i] += (target - swatchRingAnim[i]) * rate;
        }
        bgDimToggleAnim += ((ClientThemeManager.isBackgroundDimEnabled() ? 1.0F : 0.0F) - bgDimToggleAnim) * rate;
        floatingBtnToggleAnim += ((ClientThemeManager.isFloatingButtonEnabled() ? 1.0F : 0.0F) - floatingBtnToggleAnim) * rate;
        uiSoundsToggleAnim += ((ClientThemeManager.isUiSoundsEnabled() ? 1.0F : 0.0F) - uiSoundsToggleAnim) * rate;
    }

    private float computeBentoCardProgress(int cardIndex) {
        float t = Mth.clamp((clientTabOpenTime - cardIndex * 0.035F) / 0.22F, 0.0F, 1.0F);
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv; // easeOutCubic
    }

    private void renderClientBentoDashboard(GuiGraphics guiGraphics, float dt, int mouseX, int mouseY, int bentoTop) {
        ensureBentoComponents();
        Minecraft mc = Minecraft.getInstance();
        int accent = ClientThemeManager.getAccentArgb();

        int gap = 10;
        int fullW = rowWidth;
        int bannerH = 54;
        int bottomYBase = bentoTop + bannerH + gap;
        int bottomH = Math.max(165, listBottom - bottomYBase - 4);
        int leftCardW = (fullW - gap) / 2;
        int rightCardW = fullW - gap - leftCardW;
        int leftCardX = listLeft;
        int rightCardX = leftCardX + leftCardW + gap;

        // ---------------------------------------------------------------------
        // Card 0: Top Profile Banner (Full Width, 54px height, 12px radius)
        // ---------------------------------------------------------------------
        float p0 = computeBentoCardProgress(0);
        int y0 = bentoTop + Math.round(12.0F * (1.0F - p0));
        int bg0 = ClientThemeManager.getBentoCardBgColor(p0);
        HudManager.drawRoundedRect(guiGraphics, leftCardX, y0, fullW, bannerH, 12, bg0);

        if (p0 > 0.05F) {
            // Left: 34x34 rounded player avatar + 7px glowing online status dot
            int avSize = 34;
            int avX = leftCardX + 12;
            int avY = y0 + (bannerH - avSize) / 2;
            HudManager.drawRoundedRect(guiGraphics, avX - 1, avY - 1, avSize + 2, avSize + 2, 8,
                    ClientThemeManager.getAccentWithAlpha(0.28F * p0));
            if (mc != null && mc.player != null) {
                HudManager.drawPlayerAvatar(guiGraphics, mc, mc.player, avX, avY, avSize);
            } else {
                HudManager.drawRoundedRect(guiGraphics, avX, avY, avSize, avSize, 7, 0xFF1F2937);
            }
            // Live glowing 7px online status dot tucked into bottom-right corner
            HudManager.drawRoundedRect(guiGraphics, avX + avSize - 6, avY + avSize - 6, 9, 9, 4,
                    ClientThemeManager.getAccentWithAlpha(0.35F * p0));
            HudManager.drawRoundedRect(guiGraphics, avX + avSize - 5, avY + avSize - 5, 7, 7, 3,
                    multiplyAlpha(accent, p0));

            // Center: "Attrium" Bold + "1.21.11" cyan pill + "Active Config: <name>"
            int tx = avX + avSize + 12;
            int whiteCol = multiplyAlpha(0xFFFFFFFF, p0);
            int mutedCol = multiplyAlpha(0xFF9CA3AF, p0);
            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoBrandComp, tx, y0 + 13, whiteCol, false);
                int brandW = this.font.width(bentoBrandComp);
                int verW = this.font.width(bentoVerComp) + 10;
                int verX = tx + brandW + 7;
                HudManager.drawRoundedRect(guiGraphics, verX, y0 + 11, verW, 13, 5,
                        ClientThemeManager.getAccentWithAlpha(0.22F * p0));
                guiGraphics.drawString(this.font, bentoVerComp, verX + 5, y0 + 13, multiplyAlpha(accent, p0), false);

                String cfgLabel = "Active Config: " + (activeConfigName != null ? activeConfigName : "Default");
                if (!cfgLabel.equals(bentoCachedConfigStr) || bentoActiveCfgComp == null) {
                    bentoCachedConfigStr = cfgLabel;
                    bentoActiveCfgComp = Component.literal(cfgLabel).withStyle(nunitoRegularStyle);
                }
                guiGraphics.drawString(this.font, bentoActiveCfgComp, tx, y0 + 30, mutedCol, false);
            }

            // Right: Two 26px-tall, 8px-radius pill buttons ("Open Folder", "Reset Layout")
            int btnH = 26;
            int btnY = y0 + (bannerH - btnH) / 2;
            int resetW = 80;
            int folderW = 78;
            int resetX = leftCardX + fullW - resetW - 12;
            int folderX = resetX - folderW - 8;

            boolean hoverFolder = isHovered(mouseX, mouseY, folderX, btnY, folderW, btnH);
            boolean hoverReset = isHovered(mouseX, mouseY, resetX, btnY, resetW, btnH);

            HudManager.drawRoundedRect(guiGraphics, folderX, btnY, folderW, btnH, 8,
                    multiplyAlpha(hoverFolder ? 0x38FFFFFF : 0x20FFFFFF, p0));
            int fw = this.font.width(bentoOpenFolderComp);
            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoOpenFolderComp, folderX + (folderW - fw) / 2, btnY + 9, whiteCol, false);
            }

            HudManager.drawRoundedRect(guiGraphics, resetX, btnY, resetW, btnH, 8,
                    ClientThemeManager.getAccentWithAlpha((hoverReset ? 0.34F : 0.20F) * p0));
            int rw = this.font.width(bentoResetLayoutComp);
            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoResetLayoutComp, resetX + (resetW - rw) / 2, btnY + 9, multiplyAlpha(accent, p0), false);
            }

            // === ATTRIUM_UPDATER_RENDER_START ===
            float updStep = Math.min(1.0F, Math.max(0.05F, dt * 14.0F));
            updPulsePhase += dt * 3.8F;
            boolean updReady = AutoUpdater.updateReady;
            String updStatus = AutoUpdater.getStatusText();
            String checkText = AutoUpdater.isChecking() ? updStatus : "Updates";
            Component checkComp = Component.literal(checkText).withStyle(nunitoRegularStyle);
            int checkW = Math.max(66, this.font.width(checkComp) + 14);
            int checkX = folderX - checkW - 8;
            boolean hoverCheck = isHovered(mouseX, mouseY, checkX, btnY, checkW, btnH);
            updCheckHoverAnim = Mth.lerp(updStep, updCheckHoverAnim, hoverCheck ? 1.0F : 0.0F);
            int checkBg = multiplyAlpha(lerpColor(0x24FFFFFF, 0x4800E5FF, updCheckHoverAnim), p0);
            int checkFg = multiplyAlpha(lerpColor(0xFFFFFFFF, 0xFF00E5FF, updCheckHoverAnim * 0.85F), p0);
            HudManager.drawRoundedRect(guiGraphics, checkX, btnY, checkW, btnH, 8, checkBg);
            int cw = this.font.width(checkComp);
            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, checkComp, checkX + (checkW - cw) / 2, btnY + 9, checkFg, false);
            }
            updChangelogRevealAnim = Mth.lerp(updStep, updChangelogRevealAnim, updReady ? 1.0F : 0.0F);
            if (updReady) {
                Component restartComp = Component.literal("Restart").withStyle(nunitoRegularStyle);
                int restartW = Math.max(62, this.font.width(restartComp) + 14);
                int restartX = checkX - restartW - 8;
                boolean hoverRestart = isHovered(mouseX, mouseY, restartX, btnY, restartW, btnH);
                updRestartHoverAnim = Mth.lerp(updStep, updRestartHoverAnim, hoverRestart ? 1.0F : 0.0F);
                float pulse = 0.5F + 0.5F * (float) Math.sin(updPulsePhase);
                int baseGlow = lerpColor(0x3400E5FF, 0x6200E5FF, Math.max(pulse * 0.65F, updRestartHoverAnim));
                HudManager.drawRoundedRect(guiGraphics, restartX, btnY, restartW, btnH, 8, multiplyAlpha(baseGlow, p0));
                int rsw = this.font.width(restartComp);
                if ((whiteCol >>> 24) > 8) {
                    guiGraphics.drawString(this.font, restartComp, restartX + (restartW - rsw) / 2, btnY + 9, multiplyAlpha(0xFF00E5FF, p0), false);
                }
            }
            // === ATTRIUM_UPDATER_RENDER_END ===


            }

        // ---------------------------------------------------------------------
        // Card 1: Bottom-Left — Appearance & Theme Engine (12px radius)
        // ---------------------------------------------------------------------
        float p1 = computeBentoCardProgress(1);
        int y1 = bottomYBase + Math.round(12.0F * (1.0F - p1));
        HudManager.drawRoundedRect(guiGraphics, leftCardX, y1, leftCardW, bottomH, 12,
                ClientThemeManager.getBentoCardBgColor(p1));

        if (p1 > 0.05F) {
            int pad = 14;
            int cx = leftCardX + pad;
            int innerW = leftCardW - pad * 2;
            int whiteCol = multiplyAlpha(0xFFFFFFFF, p1);
            int mutedCol = multiplyAlpha(0xFF9CA3AF, p1);

            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoAppearanceTitleComp, cx, y1 + 13, whiteCol, false);
                guiGraphics.drawString(this.font, bentoAccentLabelComp, cx, y1 + 34, mutedCol, false);
            }

            // 4 Circular 22px Accent Swatch Orbs with animated outer selection ring
            int orbSize = 22;
            int orbY = y1 + 50;
            int orbStride = 34;
            for (int i = 0; i < 4; i++) {
                int ox = cx + i * orbStride;
                int swatchCol = ClientThemeManager.ACCENT_SWATCHES[i];
                float ringA = swatchRingAnim[i];
                if (ringA > 0.02F) {
                    int exp = Math.round(3.0F * ringA);
                    int ringCol = (Mth.clamp(Math.round(ringA * p1 * 180.0F), 0, 180) << 24) | (swatchCol & 0x00FFFFFF);
                    HudManager.drawRoundedRect(guiGraphics, ox - exp, orbY - exp, orbSize + exp * 2, orbSize + exp * 2, (orbSize + exp * 2) / 2, ringCol);
                }
                HudManager.drawRoundedRect(guiGraphics, ox, orbY, orbSize, orbSize, orbSize / 2, multiplyAlpha(swatchCol, p1));
            }

            // Panel Opacity Slider (55% - 95%, rounded 6px track with glowing accent thumb)
            int opLabelY = y1 + 88;
            int opPct = Math.round(ClientThemeManager.getPanelOpacity() * 100.0F);
            if (opPct != bentoCachedOpacityPct || bentoOpacityValComp == null) {
                bentoCachedOpacityPct = opPct;
                bentoOpacityValComp = Component.literal(opPct + "%").withStyle(nunitoRegularStyle);
            }
            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoOpacityLabelComp, cx, opLabelY, mutedCol, false);
                int vw = this.font.width(bentoOpacityValComp);
                guiGraphics.drawString(this.font, bentoOpacityValComp, cx + innerW - vw, opLabelY, multiplyAlpha(accent, p1), false);
            }

            int trackY = opLabelY + 15;
            int trackH = 6;
            clientOpacitySliderX = cx;
            clientOpacitySliderW = innerW;
            float opRatio = Mth.clamp((ClientThemeManager.getPanelOpacity() - 0.55F) / 0.40F, 0.0F, 1.0F);
            HudManager.drawRoundedRect(guiGraphics, cx, trackY, innerW, trackH, 3, multiplyAlpha(0xFF232735, p1));
            int fillW = Math.max(6, Math.round(innerW * opRatio));
            HudManager.drawRoundedRect(guiGraphics, cx, trackY, fillW, trackH, 3, multiplyAlpha(accent, p1));
            int thumbX = cx + fillW - 5;
            HudManager.drawRoundedRect(guiGraphics, thumbX - 1, trackY - 3, 12, 12, 6, ClientThemeManager.getAccentWithAlpha(0.35F * p1));
            HudManager.drawRoundedRect(guiGraphics, thumbX, trackY - 2, 10, 10, 5, multiplyAlpha(0xFFFFFFFF, p1));

            // Background Dim Toggle
            int dimY = y1 + 128;
            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoBgDimLabelComp, cx, dimY + 2, whiteCol, false);
            }
            drawElasticPillImmediate(guiGraphics, cx + innerW - 26, dimY, 26, 14, bgDimToggleAnim, p1);
        }

        // ---------------------------------------------------------------------
        // Card 2: Bottom-Right — Controls & Mobile Experience (12px radius)
        // ---------------------------------------------------------------------
        float p2 = computeBentoCardProgress(2);
        int y2 = bottomYBase + Math.round(12.0F * (1.0F - p2));
        HudManager.drawRoundedRect(guiGraphics, rightCardX, y2, rightCardW, bottomH, 12,
                ClientThemeManager.getBentoCardBgColor(p2));

        if (p2 > 0.05F) {
            int pad = 14;
            int cx = rightCardX + pad;
            int innerW = rightCardW - pad * 2;
            int whiteCol = multiplyAlpha(0xFFFFFFFF, p2);
            int mutedCol = multiplyAlpha(0xFF9CA3AF, p2);

            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoControlsTitleComp, cx, y2 + 13, whiteCol, false);
                guiGraphics.drawString(this.font, bentoScaleLabelComp, cx, y2 + 32, mutedCol, false);
            }

            // Segmented GUI Scale Pill Bar (0.85x | 1.0x | 1.15x | 1.30x) with sliding highlight
            int segY = y2 + 45;
            int segH = 20;
            HudManager.drawRoundedRect(guiGraphics, cx, segY, innerW, segH, 7, multiplyAlpha(0xFF1A1E2A, p2));
            int segW = innerW / 4;
            int activeScaleIdx = ClientThemeManager.getGuiScaleIndex();
            float targetSegX = cx + activeScaleIdx * segW + 2;
            float targetSegW = segW - 4;
            float slideFactor = 1.0F - (float) Math.exp(-16.0F * dt);
            if (guiScalePillX < 0.0F) {
                guiScalePillX = targetSegX;
                guiScalePillW = targetSegW;
            } else {
                guiScalePillX += (targetSegX - guiScalePillX) * slideFactor;
                guiScalePillW += (targetSegW - guiScalePillW) * slideFactor;
            }
            HudManager.drawRoundedRect(guiGraphics, Math.round(guiScalePillX), segY + 2, Math.round(guiScalePillW), segH - 4, 5,
                    multiplyAlpha(accent, p2));
            for (int i = 0; i < 4; i++) {
                int sx = cx + i * segW;
                int lw = this.font.width(bentoScaleOptComps[i]);
                int tc = (i == activeScaleIdx) ? multiplyAlpha(0xFF0B0D10, p2) : mutedCol;
                if ((tc >>> 24) > 8) {
                    guiGraphics.drawString(this.font, bentoScaleOptComps[i], sx + (segW - lw) / 2, segY + 6, tc, false);
                }
            }

            // ClickGUI Keybind Pill ([ RSHIFT ]) with soft pulsing accent while listening
            int kbY = y2 + 76;
            int guiKey = ClientThemeManager.getClickGuiKey();
            if (guiKey != bentoCachedGuiKey || bentoGuiKeyValComp == null) {
                bentoCachedGuiKey = guiKey;
                bentoGuiKeyValComp = Component.literal("[ " + formatBoundKeyDisplayName(guiKey) + " ]").withStyle(HudManager.getBoldStyle());
            }
            Component kbComp = listeningForGuiKeybind ? bentoGuiKeyListenComp : bentoGuiKeyValComp;
            int kbPillW = Math.max(54, this.font.width(kbComp) + 14);
            int kbPillX = cx + innerW - kbPillW;
            float pulse = listeningForGuiKeybind ? (0.35F + 0.30F * (float) Math.sin(System.currentTimeMillis() * 0.008D)) : 0.16F;
            HudManager.drawRoundedRect(guiGraphics, kbPillX, kbY - 3, kbPillW, 18, 6,
                    ClientThemeManager.getAccentWithAlpha(pulse * p2));
            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoKeybindLabelComp, cx, kbY + 2, whiteCol, false);
                int kw = this.font.width(kbComp);
                guiGraphics.drawString(this.font, kbComp, kbPillX + (kbPillW - kw) / 2, kbY + 2,
                        multiplyAlpha(listeningForGuiKeybind ? accent : 0xFFFFFFFF, p2), false);
            }

            // Floating Touch Button Toggle + Size Slider (20px - 36px)
            int flY = y2 + 104;
            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoFloatingLabelComp, cx, flY + 2, whiteCol, false);
            }
            drawElasticPillImmediate(guiGraphics, cx + innerW - 26, flY, 26, 14, floatingBtnToggleAnim, p2);

            int flSizeY = y2 + 123;
            int flSizeInt = Math.round(ClientThemeManager.getFloatingButtonSize());
            if (flSizeInt != bentoCachedFloatSize || bentoFloatSizeValComp == null) {
                bentoCachedFloatSize = flSizeInt;
                bentoFloatSizeValComp = Component.literal(flSizeInt + "px").withStyle(nunitoRegularStyle);
            }
            int flValW = this.font.width(bentoFloatSizeValComp);
            int flTrackW = Math.max(40, innerW - flValW - 10);
            clientFloatSizeSliderX = cx;
            clientFloatSizeSliderW = flTrackW;
            float flRatio = Mth.clamp((ClientThemeManager.getFloatingButtonSize() - 20.0F) / 16.0F, 0.0F, 1.0F);
            HudManager.drawRoundedRect(guiGraphics, cx, flSizeY + 2, flTrackW, 5, 2, multiplyAlpha(0xFF232735, p2));
            int flFillW = Math.max(5, Math.round(flTrackW * flRatio));
            HudManager.drawRoundedRect(guiGraphics, cx, flSizeY + 2, flFillW, 5, 2, multiplyAlpha(accent, p2));
            HudManager.drawRoundedRect(guiGraphics, cx + flFillW - 4, flSizeY, 9, 9, 4, multiplyAlpha(0xFFFFFFFF, p2));
            if ((mutedCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoFloatSizeValComp, cx + innerW - flValW, flSizeY, mutedCol, false);
            }

            // UI Feedback Sounds Toggle
            int sndY = y2 + 142;
            if ((whiteCol >>> 24) > 8) {
                guiGraphics.drawString(this.font, bentoSoundsLabelComp, cx, sndY + 2, whiteCol, false);
            }
            drawElasticPillImmediate(guiGraphics, cx + innerW - 26, sndY, 26, 14, uiSoundsToggleAnim, p2);
        }
    
        
        // === ATTRIUM_UPDATER_OVERLAY_START ===
        // Rendered LAST in renderClientBentoDashboard so it always floats ON TOP of Card 1 & Card 2
        if (updChangelogRevealAnim > 0.04F) {
            java.util.List<String> changes = AutoUpdater.getChangelogLines();
            int lineCount = Math.min(3, changes.size());
            int cardH = 24 + lineCount * 11;
            int cardW = Math.min(fullW, 310);
            int cardX = leftCardX + fullW - cardW;
            int cardY = y0 + bannerH + 6 - Math.round(6.0F * (1.0F - updChangelogRevealAnim));
            float ca = p0 * updChangelogRevealAnim;
            HudManager.drawRoundedRect(guiGraphics, cardX - 1, cardY - 1, cardW + 2, cardH + 2, 11, multiplyAlpha(0x8800E5FF, ca));
            HudManager.drawRoundedRect(guiGraphics, cardX, cardY, cardW, cardH, 10, multiplyAlpha(0xF510141F, ca));
            Component hdr = Component.literal("What's New in v" + AutoUpdater.getLatestVersion()).withStyle(nunitoRegularStyle);
            if (((int) (255 * ca)) > 10) {
                guiGraphics.drawString(this.font, hdr, cardX + 10, cardY + 7, multiplyAlpha(0xFF00E5FF, ca), false);
                for (int ci = 0; ci < lineCount; ci++) {
                    Component lineComp = Component.literal("• " + changes.get(ci)).withStyle(nunitoRegularStyle);
                    guiGraphics.drawString(this.font, lineComp, cardX + 10, cardY + 20 + ci * 11, multiplyAlpha(0xFFE5E7EB, ca), false);
                }
            }
        }
        // === ATTRIUM_UPDATER_OVERLAY_END ===
    }

    private void drawElasticPillImmediate(GuiGraphics guiGraphics, int x, int y, int w, int h, float anim, float alpha) {
        float t = Mth.clamp(anim, 0.0F, 1.0F);
        int trackCol = multiplyAlpha(lerpColor(COLOR_TOGGLE_OFF_BG, ClientThemeManager.getAccentArgb(), t), alpha);
        HudManager.drawRoundedRect(guiGraphics, x, y, w, h, h / 2, trackCol);
        int knobH = Math.max(6, h - 4);
        int stretch = Math.round(4.0F * (float) Math.sin(t * Math.PI));
        int knobW = knobH + stretch;
        int knobX = Math.round(Mth.lerp(t, x + 2, x + w - knobW - 2));
        HudManager.drawRoundedRect(guiGraphics, knobX, y + (h - knobH) / 2, knobW, knobH, knobH / 2, multiplyAlpha(0xFFFFFFFF, alpha));
    }

    private void handleClientBentoClick(int mouseX, int mouseY, boolean leftClicked, boolean rightClicked, int bentoTop) {
        if (!leftClicked && !rightClicked) return;

        int gap = 10;
        int fullW = rowWidth;
        int bannerH = 54;
        int bottomYBase = bentoTop + bannerH + gap;
        int leftCardW = (fullW - gap) / 2;
        int rightCardW = fullW - gap - leftCardW;
        int leftCardX = listLeft;
        int rightCardX = leftCardX + leftCardW + gap;
        int pad = 14;

        if (leftClicked) {
        // Top Banner Buttons: "Open Folder" & "Reset Layout"
        int btnH = 26;
        int btnY = bentoTop + (bannerH - btnH) / 2;
        int resetW = 80;
        int folderW = 78;
        int resetX = leftCardX + fullW - resetW - 12;
        int folderX = resetX - folderW - 8;
        if (isHovered(mouseX, mouseY, folderX, btnY, folderW, btnH)) {
            ClientThemeManager.openConfigFolder();
            SoundUtil.playClick();
            return;
        }
        if (isHovered(mouseX, mouseY, resetX, btnY, resetW, btnH)) {
            ClientThemeManager.resetLayout();
            saveConfigNow();
            SoundUtil.playTogglePop(true);
            return;
        }
        // === ATTRIUM_UPDATER_CLICK_START ===
        String checkTextClick = AutoUpdater.isChecking() ? AutoUpdater.getStatusText() : "Updates";
        int checkW = Math.max(66, this.font.width(Component.literal(checkTextClick).withStyle(nunitoRegularStyle)) + 14);
        int checkX = folderX - checkW - 8;
        if (isHovered(mouseX, mouseY, checkX, btnY, checkW, btnH)) {
            AutoUpdater.checkForUpdatesAsync(true);
            SoundUtil.playClick();
            return;
        }
        if (AutoUpdater.updateReady) {
            int restartW = Math.max(62, this.font.width(Component.literal("Restart").withStyle(nunitoRegularStyle)) + 14);
            int restartX = checkX - restartW - 8;
            if (isHovered(mouseX, mouseY, restartX, btnY, restartW, btnH)) {
                SoundUtil.playClick();
                AutoUpdater.restartToApply();
                return;
            }
        }
        // === ATTRIUM_UPDATER_CLICK_END ===

        // Bottom-Left Card (Appearance)
        int lcx = leftCardX + pad;
        int lInnerW = leftCardW - pad * 2;
        int orbY = bottomYBase + 50;
        for (int i = 0; i < 4; i++) {
            int ox = lcx + i * 34;
            if (isHovered(mouseX, mouseY, ox - 3, orbY - 3, 28, 28)) {
                ClientThemeManager.setAccentIndex(i);
                saveConfigNow();
                SoundUtil.playClick();
                return;
            }
        }
        int opTrackY = bottomYBase + 103;
        if (isHovered(mouseX, mouseY, lcx - 4, opTrackY - 6, lInnerW + 8, 18)) {
            draggingPanelOpacitySlider = true;
            clientOpacitySliderX = lcx;
            clientOpacitySliderW = lInnerW;
            float r = Mth.clamp((float) (mouseX - lcx) / (float) Math.max(1, lInnerW), 0.0F, 1.0F);
            ClientThemeManager.setPanelOpacity(0.55F + r * 0.40F);
            SoundUtil.playClick();
            return;
        }
        int dimY = bottomYBase + 128;
        if (isHovered(mouseX, mouseY, lcx, dimY - 4, lInnerW, 22)) {
            boolean next = !ClientThemeManager.isBackgroundDimEnabled();
            ClientThemeManager.setBackgroundDimEnabled(next);
            saveConfigNow();
            SoundUtil.playTogglePop(next);
            return;
        }

        }

        // Bottom-Right Card (Controls & Mobile)
        int rcx = rightCardX + pad;
        int rInnerW = rightCardW - pad * 2;
        int segY = bottomYBase + 45;
        if (leftClicked && isHovered(mouseX, mouseY, rcx, segY, rInnerW, 20)) {
            int idx = Mth.clamp((mouseX - rcx) * 4 / Math.max(1, rInnerW), 0, 3);
            ClientThemeManager.setGuiScaleIndex(idx);
            saveConfigNow();
            SoundUtil.playClick();
            return;
        }
        int kbY = bottomYBase + 76;
        if (leftClicked && isHovered(mouseX, mouseY, rcx - 4, kbY - 6, rInnerW + 8, 24)) {
            listeningForGuiKeybind = true;
            waitingForBindRelease = true;
            SoundUtil.playClick();
            return;
        }
        if (!leftClicked) return;
        int flY = bottomYBase + 104;
        if (isHovered(mouseX, mouseY, rcx, flY - 4, rInnerW, 20)) {
            boolean next = !ClientThemeManager.isFloatingButtonEnabled();
            ClientThemeManager.setFloatingButtonEnabled(next);
            saveConfigNow();
            SoundUtil.playTogglePop(next);
            return;
        }
        int flSizeY = bottomYBase + 123;
        if (isHovered(mouseX, mouseY, clientFloatSizeSliderX - 4, flSizeY - 4, clientFloatSizeSliderW + 8, 16)) {
            draggingFloatingSizeSlider = true;
            float r = Mth.clamp((float) (mouseX - clientFloatSizeSliderX) / (float) Math.max(1, clientFloatSizeSliderW), 0.0F, 1.0F);
            ClientThemeManager.setFloatingButtonSize(20.0F + r * 16.0F);
            SoundUtil.playClick();
            return;
        }
        int sndY = bottomYBase + 142;
        if (isHovered(mouseX, mouseY, rcx, sndY - 4, rInnerW, 22)) {
            boolean next = !ClientThemeManager.isUiSoundsEnabled();
            ClientThemeManager.setUiSoundsEnabled(next);
            saveConfigNow();
            SoundUtil.playTogglePop(next);
            return;
        }
    }

}
