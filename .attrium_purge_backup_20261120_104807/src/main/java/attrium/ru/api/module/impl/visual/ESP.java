package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.events.impl.WorldRenderEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.module.impl.combat.AntiBot;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.MultiModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.TargetManager;
import attrium.ru.utils.render.BatchedRenderUtil;
import attrium.ru.utils.render.color.ColorUtil;
import attrium.ru.utils.repository.friend.FriendUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attrium ESP Module: 2D Screen-Space Box (with 0x40 Translucent Inner Fill & 3rd-Person Camera Lens Projection),
 * Customizable Native Vanilla Glow, and Mobile-Friendly Predefined Color Themes.
 *
 * 1. 2D ESP Enhancement (Translucent Inner Fill & 3rd-Person Camera Projection):
 *    - Draws a crisp outer border around the projected 2D bounding box AND fills the interior
 *      with the same RGB color clamped to 0x40 (~25% opacity) for a glassmorphic overlay.
 *    - Extracts the exact Camera lens position (`camX, camY, camZ`) and lens angles (`pitch, yaw`)
 *      from `mc.gameRenderer.getMainCamera()` (supporting 1st-person, 3rd-person back, and 3rd-person front)
 *      and subtracts the camera lens position from each target's interpolated 3D bounding box before
 *      multiplying by the ModelView and Projection matrices.
 * 2. ClickGUI UX (ESP Color Theme Dropdown & Conditional RGB Sliders):
 *    - Replaces raw top-level RGB sliders with an `ESP Color Theme` dropdown offering predefined
 *      aesthetic hex colors: `White`, `Red`, `Purple`, `Blue`, `Green`, and `Custom`.
 *    - The `Red`, `Green`, and `Blue` sliders use `.visibleWhen(...)` so they remain completely hidden
 *      (with 0 height in ClickGUI) unless `Custom` is selected.
 */
public class ESP extends Module {
    private static ESP instance;

    // Hardcoded Friend Priority Override Color: Bright Ally Cyan (0x00FFCC -> R=0, G=255, B=204, A=255)
    public static final int FRIEND_PRIORITY_BRIGHT_GREEN_RGBA = ColorUtil.rgba(0, 255, 204, 255);
    public static final int FRIEND_PRIORITY_BRIGHT_GREEN_RGB = 0x00FFCC;

    // Translucent 2D Box Inner Fill Alpha (~25% Opacity = 0x40)
    public static final int BOX_INNER_FILL_ALPHA = 0x40;

    // Predefined Aesthetic ESP Color Themes (0xRRGGBB)
    public static final int THEME_WHITE_RGB = 0xFFFFFF;
    public static final int THEME_RED_RGB = 0xFF4B5C;
    public static final int THEME_PURPLE_RGB = 0xA864FF;
    public static final int THEME_BLUE_RGB = 0x4DA6FF;
    public static final int THEME_GREEN_RGB = 0x38F28D;

    // 10Hz Entity Cache Update Interval (Every 2 ticks at 20 TPS = 10Hz)
    private static final int CACHE_UPDATE_INTERVAL_TICKS = 2;
    private static final int MAX_POOLED_ENTITIES = 256;

    // Pre-allocated JOML math scratch objects for zero-allocation 3D-to-2D projection
    private static final Matrix4f SCRATCH_PROJ_MATRIX = new Matrix4f();
    private static final Matrix4f SCRATCH_VIEW_MATRIX = new Matrix4f();
    private static final Matrix4f SCRATCH_MVP_MATRIX = new Matrix4f();
    private static final Vector4f SCRATCH_CLIP_VEC = new Vector4f();
    private static final double[] SCRATCH_CAM_POS = new double[3];
    private static final float[] SCRATCH_CAM_ROT = new float[2];

    // Cached reflection handles for Camera lens position & rotation (1.21.11 Mojang/Yarn safe)
    private static boolean cameraReflectionInitialized = false;
    private static Method cachedCameraPosMethod = null;
    private static Field cachedCameraPosField = null;
    private static Method cachedCameraXRotMethod = null;
    private static Method cachedCameraYRotMethod = null;
    private static Field cachedCameraXRotField = null;
    private static Field cachedCameraYRotField = null;

    // Cached reflection handles for extracting GuiGraphics from DrawEvent if needed
    private static boolean drawEventReflectionInitialized = false;
    private static Method cachedDrawEventGraphicsGetter = null;
    private static Field cachedDrawEventGraphicsField = null;
    private static long lastHudRenderNano = 0L;

    // Lightweight server scoreboard health cache for TargetHud & Renderer Mixins
    private static final Map<Integer, Float> SERVER_HEALTH_CACHE = new ConcurrentHashMap<>();
    private static final Pattern HEALTH_NUMBER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    public static final class FriendManager {
        private FriendManager() {
        }

        public static boolean isFriend(String name) {
            return name != null && !name.isEmpty()
                    && (attrium.ru.manager.FriendManager.isFriend(name) || FriendUtils.isFriend(name));
        }

        public static boolean isFriend(Component nameComponent) {
            return nameComponent != null && isFriend(nameComponent.getString());
        }

        public static boolean isFriend(Entity entity) {
            return attrium.ru.manager.FriendManager.isFriend(entity);
        }
    }

    private static final class PooledEspEntry {
        Entity entity;
        boolean isFriend;
    }

    // =========================================================================
    // 1. ClickGUI Settings (Mode, ESP Color Theme Dropdown, Conditional RGB, Glow/Line)
    // =========================================================================
    private final ModeSetting mode = register(new ModeSetting(
            "Mode",
            "ESP rendering mode: native Minecraft Shader Glow or 2D Screen-Space Box.",
            "2D Box",
            "Glow", "2D Box", "Corner Box"
    ));

    private final ModeSetting espColorTheme = register(new ModeSetting(
            "ESP Color Theme",
            "Predefined aesthetic color preset or Custom RGB sliders.",
            "Purple",
            "Theme Accent", "White", "Red", "Purple", "Blue", "Green", "Custom"
    ));

    private final NumberSetting espRed = register(new NumberSetting(
            "Red",
            "Custom Red channel (0 - 255) when ESP Color Theme is set to Custom.",
            168.0D, 0.0D, 255.0D, 1.0D
    ));

    private final NumberSetting espGreen = register(new NumberSetting(
            "Green",
            "Custom Green channel (0 - 255) when ESP Color Theme is set to Custom.",
            100.0D, 0.0D, 255.0D, 1.0D
    ));

    private final NumberSetting espBlue = register(new NumberSetting(
            "Blue",
            "Custom Blue channel (0 - 255) when ESP Color Theme is set to Custom.",
            255.0D, 0.0D, 255.0D, 1.0D
    ));

    private final NumberSetting glowIntensity = register(new NumberSetting(
            "Glow Intensity",
            "Adjusts the opacity and brightness multiplier of the native Glow effect (0.1 - 1.0).",
            1.0D, 0.1D, 1.0D, 0.05D
    ));

    private final NumberSetting lineThickness = register(new NumberSetting(
            "Line Thickness",
            "Controls the border width of the 2D Screen-Space Box (1.0 - 3.0).",
            1.0D, 1.0D, 3.0D, 0.1D
    ));

    // [ATTRIUM-VISUAL-POLISH-ESP]
    private final BooleanSetting healthBar = register(new BooleanSetting(
            "Health Bar",
            "Draws a compact 2px vertical health strip on the left edge of 2D/Corner boxes.",
            true
    ));

    private final MultiModeSetting targets = register(new MultiModeSetting(
            "Targets",
            "Entity categories to highlight from TargetManager.",
            new String[]{"Players", "Hostile", "Neutral / Passive"},
            "Players"
    ));

    private final NumberSetting renderDistance = register(new NumberSetting(
            "Render Distance",
            "Maximum distance in blocks (8 - 128) to render ESP.",
            64.0D, 8.0D, 128.0D, 1.0D
    ));

    private final BooleanSetting renderFriends = register(new BooleanSetting(
            "Render Friends",
            "Highlight whitelisted friends with a priority Bright Green color.",
            true
    ));

    // =========================================================================
    // Zero-Allocation 10Hz Target Pool State
    // =========================================================================
    private PooledEspEntry[] entityPool = null;
    private int activeCount = 0;
    private int lastCacheUpdateTick = -100;
    private int lastObservedPlayerId = -1;

    public ESP() {
        super("ESP", "Highlights players and entities through walls", ModuleCategory.VISUAL);
        instance = this;

        // Conditional Slider Visibility: Completely hide Red, Green, and Blue unless "Custom" is selected
        this.espRed.visibleWhen(this::isCustomColorThemeSelected);
        this.espGreen.visibleWhen(this::isCustomColorThemeSelected);
        this.espBlue.visibleWhen(this::isCustomColorThemeSelected);

        ensurePoolAllocated();
    }

    public static ESP getInstance() {
        if (instance == null) {
            instance = new ESP();
        }
        return instance;
    }

    public boolean isCustomColorThemeSelected() {
        return "Custom".equalsIgnoreCase(espColorTheme.getValue());
    }

    private void ensurePoolAllocated() {
        if (entityPool == null) {
            entityPool = new PooledEspEntry[MAX_POOLED_ENTITIES];
            for (int i = 0; i < MAX_POOLED_ENTITIES; i++) {
                entityPool[i] = new PooledEspEntry();
            }
        }
    }

    @Override
    protected void onEnable() {
        ensurePoolAllocated();
        clearCache();
    }

    @Override
    protected void onDisable() {
        clearCache();
    }

    private void clearCache() {
        if (entityPool != null) {
            for (int i = 0; i < MAX_POOLED_ENTITIES; i++) {
                if (entityPool[i] != null) {
                    entityPool[i].entity = null;
                }
            }
        }
        activeCount = 0;
        lastCacheUpdateTick = -100;
        SERVER_HEALTH_CACHE.clear();
    }

    public boolean isGlowMode() {
        return "Glow".equalsIgnoreCase(mode.getValue());
    }

    public boolean is2DBoxMode() {
        String m = mode.getValue();
        return "2D Box".equalsIgnoreCase(m) || "Corner Box".equalsIgnoreCase(m);
    }

    public boolean isCornerBoxMode() {
        return "Corner Box".equalsIgnoreCase(mode.getValue());
    }

    public float getGlowIntensityValue() {
        return Mth.clamp(glowIntensity.getValue().floatValue(), 0.1F, 1.0F);
    }

    public float getLineThicknessValue() {
        return Mth.clamp(lineThickness.getValue().floatValue(), 1.0F, 3.0F);
    }

    /**
     * Resolves the active 24-bit RGB hex color (0xRRGGBB) from `ESP Color Theme` or Custom RGB sliders.
     */
    public int resolveSelectedThemeRGBHex() {
        String theme = espColorTheme.getValue();
        if (theme == null) {
            return THEME_PURPLE_RGB;
        }
        switch (theme.trim().toLowerCase(Locale.ROOT)) {
            case "theme accent" -> {
                return attrium.ru.manager.ClientThemeManager.getAccentArgb() & 0x00FFFFFF;
            }
            case "white" -> {
                return THEME_WHITE_RGB;
            }
            case "red" -> {
                return THEME_RED_RGB;
            }
            case "purple" -> {
                return THEME_PURPLE_RGB;
            }
            case "blue" -> {
                return THEME_BLUE_RGB;
            }
            case "green" -> {
                return THEME_GREEN_RGB;
            }
            case "custom" -> {
                int r = Mth.clamp(espRed.getValue().intValue(), 0, 255);
                int g = Mth.clamp(espGreen.getValue().intValue(), 0, 255);
                int b = Mth.clamp(espBlue.getValue().intValue(), 0, 255);
                return (r << 16) | (g << 8) | b;
            }
            default -> {
                return THEME_PURPLE_RGB;
            }
        }
    }

    public int resolveEntityColorARGB(Entity entity, boolean isFriend) {
        if (isFriend || FriendManager.isFriend(entity)) {
            return FRIEND_PRIORITY_BRIGHT_GREEN_RGBA;
        }
        int rgb = resolveSelectedThemeRGBHex();
        return (0xFF << 24) | (rgb & 0x00FFFFFF);
    }

    public int resolveEntityOutlineColorRGBHex(Entity entity, boolean isFriend) {
        int baseRgb = (isFriend || FriendManager.isFriend(entity))
                ? FRIEND_PRIORITY_BRIGHT_GREEN_RGB
                : resolveSelectedThemeRGBHex();

        float intensity = getGlowIntensityValue();
        int sr = Mth.clamp(Math.round(((baseRgb >> 16) & 0xFF) * intensity), 0, 255);
        int sg = Mth.clamp(Math.round(((baseRgb >> 8) & 0xFF) * intensity), 0, 255);
        int sb = Mth.clamp(Math.round((baseRgb & 0xFF) * intensity), 0, 255);
        return (sr << 16) | (sg << 8) | sb;
    }

    // =========================================================================
    // 2. Centralized TargetManager Discovery (10Hz Zero-Allocation Pool)
    // =========================================================================
    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        Minecraft mc = event != null ? event.getClient() : Minecraft.getInstance();
        if (!isEnabled() || mc == null || mc.player == null || mc.level == null) {
            clearCache();
            return;
        }

        int playerId = mc.player.getId();
        int currentTick = mc.player.tickCount;
        if (playerId != lastObservedPlayerId || currentTick < lastCacheUpdateTick) {
            lastObservedPlayerId = playerId;
            clearCache();
        }

        if (lastCacheUpdateTick != -100 && (currentTick - lastCacheUpdateTick) < CACHE_UPDATE_INTERVAL_TICKS) {
            return;
        }
        lastCacheUpdateTick = currentTick;

        rebuildEntityCacheFromTargetManager(mc);
    }

    private void rebuildEntityCacheFromTargetManager(Minecraft mc) {
        ensurePoolAllocated();
        int maxDistBlocks = renderDistance.getValue().intValue();
        if (maxDistBlocks <= 0) {
            clearCache();
            return;
        }
        double maxDistSq = (double) maxDistBlocks * (double) maxDistBlocks;

        boolean allowPlayers = targets.isSelected("Players");
        boolean allowHostile = targets.isSelected("Hostile");
        boolean allowNeutral = targets.isSelected("Neutral / Passive");
        boolean showFriends = renderFriends.getValue();

        TargetManager.updateOncePerTick(mc);
        LivingEntity[] sharedLiving = TargetManager.getValidLivingArray();
        int sharedCount = TargetManager.getValidLivingCount();

        int count = 0;

        for (int idx = 0; idx < sharedCount; idx++) {
            LivingEntity living = sharedLiving[idx];
            if (living == null || living == mc.player || !living.isAlive() || living.getHealth() <= 0.0F) {
                continue;
            }

            if (mc.player.distanceToSqr(living) > maxDistSq) {
                continue;
            }

            boolean isFriend = false;
            if (living instanceof Player player) {
                if (AntiBot.shouldIgnore(player)) {
                    continue;
                }
                isFriend = FriendManager.isFriend(player);
                if (isFriend) {
                    if (!showFriends) {
                        continue;
                    }
                } else if (!allowPlayers) {
                    continue;
                }
            } else if (isHostileEntity(living)) {
                if (!allowHostile) {
                    continue;
                }
            } else if (living instanceof Animal || living instanceof Mob) {
                if (!allowNeutral) {
                    continue;
                }
            } else {
                continue;
            }

            PooledEspEntry entry = entityPool[count];
            entry.entity = living;
            entry.isFriend = isFriend;
            count++;

            if (count >= MAX_POOLED_ENTITIES) {
                break;
            }
        }

        for (int i = count; i < activeCount; i++) {
            entityPool[i].entity = null;
        }
        activeCount = count;
    }

    private boolean isHostileEntity(Entity entity) {
        if (entity instanceof Enemy) {
            return true;
        }
        if (entity instanceof NeutralMob neutral) {
            return neutral.isAngry();
        }
        return false;
    }

    // =========================================================================
    // 3. WorldRenderEvent & 3rd-Person Camera Lens 2D Box Projection
    // =========================================================================
    @SubscribeEvent
    public void onWorldRender(WorldRenderEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || mc == null || mc.player == null || mc.level == null) {
            return;
        }
        BatchedRenderUtil.cacheWorldProjectionOncePerFrame(mc);
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        if (!isEnabled() || !is2DBoxMode() || event == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        GuiGraphics graphics = extractGuiGraphicsFromDrawEvent(event);
        if (graphics != null) {
            float partialTick = mc.getDeltaTracker() != null
                    ? mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)
                    : 1.0F;
            render2DBoxesOnHud(graphics, partialTick);
        }
    }

    /**
     * Renders 2D projected bounding boxes (with 0x40 translucent inner fill) onto the 2D HUD layer,
     * projecting from the actual Camera lens coordinates (`mc.gameRenderer.getMainCamera()`) so boxes
     * wrap targets accurately in 1st-person, 3rd-person back, and 3rd-person front views.
     */
    public static void render2DBoxesOnHud(GuiGraphics graphics, float partialTick) {
        ESP esp = getInstance();
        if (esp == null || !esp.isEnabled() || !esp.is2DBoxMode() || graphics == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.gameRenderer == null || mc.getWindow() == null) {
            return;
        }

        long nowNs = System.nanoTime();
        if ((nowNs - lastHudRenderNano) < 1_000_000L) {
            return;
        }
        lastHudRenderNano = nowNs;

        if (esp.activeCount <= 0 || esp.entityPool == null) {
            return;
        }

        // Fetch actual Camera lens position (camX, camY, camZ) and lens rotation (pitch, yaw)
        extractCameraLensPositionAndRotation(mc, partialTick, SCRATCH_CAM_POS, SCRATCH_CAM_ROT);
        double camX = SCRATCH_CAM_POS[0];
        double camY = SCRATCH_CAM_POS[1];
        double camZ = SCRATCH_CAM_POS[2];
        float pitch = SCRATCH_CAM_ROT[0];
        float yaw = SCRATCH_CAM_ROT[1];

        int fbWidth = Math.max(1, mc.getWindow().getWidth());
        int fbHeight = Math.max(1, mc.getWindow().getHeight());
        float guiWidth = (float) mc.getWindow().getGuiScaledWidth();
        float guiHeight = (float) mc.getWindow().getGuiScaledHeight();
        float aspect = (float) fbWidth / (float) fbHeight;

        double baseFov = mc.options != null ? Mth.clamp(mc.options.fov().get(), 30.0D, 170.0D) : 70.0D;
        float fovRad = (float) Math.toRadians(baseFov);

        // Build combined Perspective * ModelView matrix from the actual Camera lens orientation
        SCRATCH_PROJ_MATRIX.identity().setPerspective(fovRad, aspect, 0.05F, 1024.0F);
        SCRATCH_VIEW_MATRIX.identity()
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0F));
        SCRATCH_PROJ_MATRIX.mul(SCRATCH_VIEW_MATRIX, SCRATCH_MVP_MATRIX);

        // Camera lens forward unit vector for Z > 0 behind-camera frustum check
        double radYaw = Math.toRadians(-yaw);
        double radPitch = Math.toRadians(-pitch);
        double cosPitch = Math.cos(radPitch);
        double lookX = Math.sin(radYaw) * cosPitch;
        double lookY = Math.sin(radPitch);
        double lookZ = Math.cos(radYaw) * cosPitch;

        float thickness = esp.getLineThicknessValue();
        double maxDist = esp.renderDistance.getValue();
        double maxDistSq = maxDist * maxDist;
        boolean firstPerson = mc.options == null || mc.options.getCameraType().isFirstPerson();

        for (int i = 0; i < esp.activeCount; i++) {
            PooledEspEntry entry = esp.entityPool[i];
            Entity entity = entry.entity;
            if (entity == null || !entity.isAlive()) {
                continue;
            }

            if (entity == mc.player && firstPerson) {
                continue;
            }

            // Interpolate target's 3D world position and subtract Camera lens position (camX, camY, camZ)
            double interpX = Mth.lerp(partialTick, entity.xo, entity.getX());
            double interpY = Mth.lerp(partialTick, entity.yo, entity.getY());
            double interpZ = Mth.lerp(partialTick, entity.zo, entity.getZ());

            double dx = interpX - camX;
            double dy = (interpY + entity.getBbHeight() * 0.5D) - camY;
            double dz = interpZ - camZ;
            if ((dx * dx + dy * dy + dz * dz) > maxDistSq) {
                continue;
            }

            // Frustum Culling (Z > 0 check relative to Camera lens)
            double forwardDepth = dx * lookX + dy * lookY + dz * lookZ;
            if (forwardDepth <= 0.15D) {
                continue;
            }

            AABB rawBox = entity.getBoundingBox();
            double offsetX = interpX - entity.getX();
            double offsetY = interpY - entity.getY();
            double offsetZ = interpZ - entity.getZ();

            double bMinX = rawBox.minX + offsetX - camX;
            double bMinY = rawBox.minY + offsetY - camY;
            double bMinZ = rawBox.minZ + offsetZ - camZ;
            double bMaxX = rawBox.maxX + offsetX - camX;
            double bMaxY = rawBox.maxY + offsetY - camY;
            double bMaxZ = rawBox.maxZ + offsetZ - camZ;

            float minScreenX = Float.MAX_VALUE;
            float minScreenY = Float.MAX_VALUE;
            float maxScreenX = -Float.MAX_VALUE;
            float maxScreenY = -Float.MAX_VALUE;
            int validCorners = 0;
            boolean behindCameraCorner = false;

            for (int corner = 0; corner < 8; corner++) {
                float cx = (float) ((corner & 1) == 0 ? bMinX : bMaxX);
                float cy = (float) ((corner & 2) == 0 ? bMinY : bMaxY);
                float cz = (float) ((corner & 4) == 0 ? bMinZ : bMaxZ);

                SCRATCH_CLIP_VEC.set(cx, cy, cz, 1.0F);
                SCRATCH_MVP_MATRIX.transform(SCRATCH_CLIP_VEC);

                if (SCRATCH_CLIP_VEC.w <= 0.05F) {
                    behindCameraCorner = true;
                    break;
                }

                float invW = 1.0F / SCRATCH_CLIP_VEC.w;
                float ndcX = SCRATCH_CLIP_VEC.x * invW;
                float ndcY = SCRATCH_CLIP_VEC.y * invW;

                float screenX = (ndcX + 1.0F) * 0.5F * guiWidth;
                float screenY = (1.0F - ndcY) * 0.5F * guiHeight;

                if (screenX < minScreenX) minScreenX = screenX;
                if (screenY < minScreenY) minScreenY = screenY;
                if (screenX > maxScreenX) maxScreenX = screenX;
                if (screenY > maxScreenY) maxScreenY = screenY;
                validCorners++;
            }

            if (behindCameraCorner || validCorners < 8) {
                continue;
            }

            if (maxScreenX < 0.0F || minScreenX > guiWidth || maxScreenY < 0.0F || minScreenY > guiHeight) {
                continue;
            }

            int x1 = Math.round(minScreenX);
            int y1 = Math.round(minScreenY);
            int x2 = Math.round(maxScreenX);
            int y2 = Math.round(maxScreenY);

            if ((x2 - x1) < 2 || (y2 - y1) < 2) {
                continue;
            }

            int argbColor = esp.resolveEntityColorARGB(entity, entry.isFriend);
            if (esp.isCornerBoxMode()) {
                drawCornerBracketBox(graphics, x1, y1, x2, y2, thickness, argbColor);
            } else {
                draw2DBoxWithTranslucentFill(graphics, x1, y1, x2, y2, thickness, argbColor);
            }
            if (Boolean.TRUE.equals(esp.healthBar.getValue()) && entity instanceof LivingEntity living) {
                drawCompactHealthBar(graphics, x1, y1, y2, living);
            }
        }
    }

    /**
     * Draws the 2D ESP Box with a translucent inner fill (Alpha clamped to 0x40 / ~25% opacity)
     * and a solid outer border of configurable Line Thickness (1.0 - 3.0).
     */
    private static void drawCornerBracketBox(
            GuiGraphics g,
            int x1,
            int y1,
            int x2,
            int y2,
            float thickness,
            int argbColor
    ) {
        int corePx = Mth.clamp((int) Math.floor(thickness), 1, 3);
        int w = x2 - x1;
        int h = y2 - y1;
        int lenX = Math.max(corePx + 2, Math.min(18, Math.round(w * 0.26F)));
        int lenY = Math.max(corePx + 2, Math.min(22, Math.round(h * 0.24F)));
        int outline = 0xCC0A0D12;

        // 1px dark outline behind all 4 L-brackets for crisp readability
        // Top-Left
        g.fill(x1 - 1, y1 - 1, x1 + lenX + 1, y1 + corePx + 1, outline);
        g.fill(x1 - 1, y1 - 1, x1 + corePx + 1, y1 + lenY + 1, outline);
        // Top-Right
        g.fill(x2 - lenX - 1, y1 - 1, x2 + 1, y1 + corePx + 1, outline);
        g.fill(x2 - corePx - 1, y1 - 1, x2 + 1, y1 + lenY + 1, outline);
        // Bottom-Left
        g.fill(x1 - 1, y2 - corePx - 1, x1 + lenX + 1, y2 + 1, outline);
        g.fill(x1 - 1, y2 - lenY - 1, x1 + corePx + 1, y2 + 1, outline);
        // Bottom-Right
        g.fill(x2 - lenX - 1, y2 - corePx - 1, x2 + 1, y2 + 1, outline);
        g.fill(x2 - corePx - 1, y2 - lenY - 1, x2 + 1, y2 + 1, outline);

        // Foreground colored L-brackets
        // Top-Left
        g.fill(x1, y1, x1 + lenX, y1 + corePx, argbColor);
        g.fill(x1, y1, x1 + corePx, y1 + lenY, argbColor);
        // Top-Right
        g.fill(x2 - lenX, y1, x2, y1 + corePx, argbColor);
        g.fill(x2 - corePx, y1, x2, y1 + lenY, argbColor);
        // Bottom-Left
        g.fill(x1, y2 - corePx, x1 + lenX, y2, argbColor);
        g.fill(x1, y2 - lenY, x1 + corePx, y2, argbColor);
        // Bottom-Right
        g.fill(x2 - lenX, y2 - corePx, x2, y2, argbColor);
        g.fill(x2 - corePx, y2 - lenY, x2, y2, argbColor);
    }

    private static void drawCompactHealthBar(GuiGraphics g, int boxX1, int y1, int y2, LivingEntity living) {
        int h = y2 - y1;
        if (h < 6) return;
        float maxHp = Math.max(1.0F, living.getMaxHealth());
        float hp = Math.max(0.0F, living.getHealth());
        float ratio = Mth.clamp(hp / maxHp, 0.0F, 1.0F);
        int fillH = Math.max(1, Math.round(h * ratio));

        int barX1 = boxX1 - 5;
        int barX2 = boxX1 - 3;
        int hpCol = ratio > 0.60F ? 0xFF34D399 : (ratio > 0.30F ? 0xFFFBBF24 : 0xFFF87171);

        // 1px dark backing frame + 2px vertical health strip
        g.fill(barX1 - 1, y1 - 1, barX2 + 1, y2 + 1, 0xCC0A0D12);
        g.fill(barX1, y2 - fillH, barX2, y2, hpCol);
    }

    private static void draw2DBoxWithTranslucentFill(
            GuiGraphics g,
            int x1,
            int y1,
            int x2,
            int y2,
            float thickness,
            int argbColor
    ) {
        int corePx = Mth.clamp((int) Math.floor(thickness), 1, 3);
        float frac = thickness - corePx;

        // 1. Translucent Inner Fill (same RGB as the box, Alpha clamped to 0x40 = ~25% opacity)
        int fillX1 = x1 + corePx;
        int fillY1 = y1 + corePx;
        int fillX2 = x2 - corePx;
        int fillY2 = y2 - corePx;
        if (fillX2 > fillX1 && fillY2 > fillY1) {
            int translucentFillColor = (BOX_INNER_FILL_ALPHA << 24) | (argbColor & 0x00FFFFFF);
            g.fill(fillX1, fillY1, fillX2, fillY2, translucentFillColor);
        }

        // 2. Solid Outer Border
        g.fill(x1, y1, x2, y1 + corePx, argbColor);
        g.fill(x1, y2 - corePx, x2, y2, argbColor);
        g.fill(x1, y1 + corePx, x1 + corePx, y2 - corePx, argbColor);
        g.fill(x2 - corePx, y1 + corePx, x2, y2 - corePx, argbColor);

        // 3. Sub-pixel inner feather ring for fractional slider steps (e.g. 1.5, 2.5)
        if (frac > 0.1F && (x2 - x1) > (corePx * 2 + 2) && (y2 - y1) > (corePx * 2 + 2)) {
            int baseAlpha = (argbColor >>> 24) & 0xFF;
            int subAlpha = Mth.clamp(Math.round(baseAlpha * frac), 16, 255);
            int subColor = (subAlpha << 24) | (argbColor & 0x00FFFFFF);
            g.fill(fillX1, fillY1, fillX2, fillY1 + 1, subColor);
            g.fill(fillX1, fillY2 - 1, fillX2, fillY2, subColor);
            g.fill(fillX1, fillY1 + 1, fillX1 + 1, fillY2 - 1, subColor);
            g.fill(fillX2 - 1, fillY1 + 1, fillX2, fillY2 - 1, subColor);
        }
    }

    /**
     * Fetches the exact Camera lens position (`camX, camY, camZ`) and lens angles (`pitch, yaw`)
     * from `mc.gameRenderer.getMainCamera()` so 2D Box projection works in 1st-person,
     * 3rd-person back, and 3rd-person front views.
     */
    // [ATTRIUM-STEP1-DIRECT-CAMERA]
    private static void extractCameraLensPositionAndRotation(
            Minecraft mc,
            float partialTick,
            double[] outCamPos,
            float[] outPitchYaw
    ) {
        Camera camera = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        if (camera != null) {
            Vec3 pos = camera.position();
            if (pos != null) {
                outCamPos[0] = pos.x;
                outCamPos[1] = pos.y;
                outCamPos[2] = pos.z;
                outPitchYaw[0] = camera.xRot();
                outPitchYaw[1] = camera.yRot();
                return;
            }
        }

        float playerPitch = Mth.lerp(partialTick, mc.player.xRotO, mc.player.getXRot());
        float playerYaw = Mth.lerp(partialTick, mc.player.yRotO, mc.player.getYRot());
        CameraType camType = mc.options != null ? mc.options.getCameraType() : CameraType.FIRST_PERSON;

        float resolvedPitch = camType.isMirrored() ? -playerPitch : playerPitch;
        float resolvedYaw = camType.isMirrored() ? (playerYaw + 180.0F) : playerYaw;

        Vec3 eye = mc.player.getEyePosition(partialTick);
        if (!camType.isFirstPerson()) {
            double radYaw = Math.toRadians(-resolvedYaw);
            double radPitch = Math.toRadians(-resolvedPitch);
            double cosPitch = Math.cos(radPitch);
            double lookX = Math.sin(radYaw) * cosPitch;
            double lookY = Math.sin(radPitch);
            double lookZ = Math.cos(radYaw) * cosPitch;
            outCamPos[0] = eye.x - lookX * 4.0D;
            outCamPos[1] = eye.y - lookY * 4.0D;
            outCamPos[2] = eye.z - lookZ * 4.0D;
        } else {
            outCamPos[0] = eye.x;
            outCamPos[1] = eye.y;
            outCamPos[2] = eye.z;
        }
        outPitchYaw[0] = resolvedPitch;
        outPitchYaw[1] = resolvedYaw;
    }

    private static GuiGraphics extractGuiGraphicsFromDrawEvent(DrawEvent event) {
        if (!drawEventReflectionInitialized) {
            drawEventReflectionInitialized = true;
            try {
                for (Method m : DrawEvent.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && GuiGraphics.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        cachedDrawEventGraphicsGetter = m;
                        break;
                    }
                }
                if (cachedDrawEventGraphicsGetter == null) {
                    for (Field f : DrawEvent.class.getDeclaredFields()) {
                        if (GuiGraphics.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            cachedDrawEventGraphicsField = f;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            if (cachedDrawEventGraphicsGetter != null) {
                return (GuiGraphics) cachedDrawEventGraphicsGetter.invoke(event);
            }
            if (cachedDrawEventGraphicsField != null) {
                return (GuiGraphics) cachedDrawEventGraphicsField.get(event);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // =========================================================================
    // 4. Static Hooks for Native Shader Glow Mixins & TargetHud
    // =========================================================================
    public static boolean shouldRenderNativeGlowOutline(Entity entity) {
        ESP esp = getInstance();
        if (esp == null || !esp.isEnabled() || entity == null || !esp.isGlowMode()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return false;
        }
        if (entity == mc.player) {
            return false;
        }
        if (esp.entityPool == null || esp.activeCount <= 0) {
            return false;
        }
        for (int i = 0; i < esp.activeCount; i++) {
            if (esp.entityPool[i].entity == entity) {
                return true;
            }
        }
        return false;
    }

    public static int getNativeGlowOutlineColorRGB(Entity entity) {
        ESP esp = getInstance();
        if (esp == null || !esp.isEnabled() || entity == null) {
            return -1;
        }
        return esp.resolveEntityOutlineColorRGBHex(entity, FriendManager.isFriend(entity));
    }

    public static int getNativeGlowOutlineAlpha() {
        ESP esp = getInstance();
        if (esp == null || !esp.isEnabled()) {
            return 255;
        }
        return Mth.clamp(Math.round(255.0F * esp.getGlowIntensityValue()), 26, 255);
    }

    public static boolean shouldHideVanillaName(Entity entity) {
        return false;
    }

    public static boolean shouldSuppressServerTagEntity(Entity entity) {
        return false;
    }

    public static void captureServerHealth(Entity entity, Component scoreText) {
        if (entity == null || scoreText == null) {
            return;
        }
        String raw = scoreText.getString();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        Matcher matcher = HEALTH_NUMBER_PATTERN.matcher(raw);
        if (matcher.find()) {
            try {
                float parsed = Float.parseFloat(matcher.group(1));
                if (parsed > 0.0F && parsed <= 1024.0F) {
                    if (SERVER_HEALTH_CACHE.size() > 256) {
                        SERVER_HEALTH_CACHE.clear();
                    }
                    SERVER_HEALTH_CACHE.put(entity.getId(), parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public static float resolveHudHealth(LivingEntity entity, boolean includeAbsorption) {
        if (entity == null) {
            return 0.0F;
        }
        Float cached = SERVER_HEALTH_CACHE.get(entity.getId());
        float baseHealth = (cached != null && cached > 0.0F) ? cached : entity.getHealth();
        return includeAbsorption ? (baseHealth + entity.getAbsorptionAmount()) : baseHealth;
    }
}
