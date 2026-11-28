package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.ClientThemeManager;
import attrium.ru.utils.render.AnimationUtil;

/**
 * Smooth Glowing Block Selection Overlay (Minecraft 1.21.11 / FoldCraftLauncher Safe).
 *
 * Smoothly glides a translucent frosted-glass & neon-glow wireframe box between blocks
 * using exact VoxelShape bounds and zero-allocation 3D-to-2D camera-lens projection
 * (100% compatible with Sodium, ImmediatelyFast, MobileGlues, and Freelook).
 */
public class BlockOverlayModule extends Module {
    private static volatile BlockOverlayModule instance;

    private final ModeSetting style = register(new ModeSetting(
            "Style",
            "Visual outline style for the hovered block.",
            "Neon Pulse",
            "Neon Pulse", "Glow Wireframe", "Corners Only", "Filled Glass"
    ));

    private final ModeSetting colorMode = register(new ModeSetting(
            "Color Mode",
            "Color theme for the block overlay.",
            "Theme Accent",
            "Theme Accent", "Cyan-Blue Wave", "Pure White", "Rainbow"
    ));

    private final NumberSetting smoothSpeed = register(new NumberSetting(
            "Smooth Speed",
            "Glide interpolation speed when switching blocks (6 - 28).",
            16.0D, 6.0D, 28.0D, 1.0D
    ));

    private final NumberSetting fillOpacity = register(new NumberSetting(
            "Fill Opacity",
            "Translucent glass tint intensity inside the hovered block (0.0 - 0.45).",
            0.16D, 0.00D, 0.45D, 0.02D
    ));

    private final BooleanSetting lineGlow = register(new BooleanSetting(
            "Line Glow",
            "Adds a soft outer bloom halo around every wireframe edge.",
            true
    ));

    private final BooleanSetting breathingPulse = register(new BooleanSetting(
            "Breathing Pulse",
            "Subtle sinusoidal glow pulse on the selected block.",
            true
    ));

    // Zero-allocation interpolated AABB state
    private double curMinX, curMinY, curMinZ, curMaxX, curMaxY, curMaxZ;
    private boolean hasBox = false;
    private float fadeAlpha = 0.0F;
    private long lastFrameNanos = 0L;
    private float animClock = 0.0F;

    // Pre-allocated math scratch objects (zero GC)
    private static final Matrix4f PROJ_MAT = new Matrix4f();
    private static final Matrix4f VIEW_MAT = new Matrix4f();
    private static final Matrix4f MVP_MAT = new Matrix4f();
    private static final Vector4f VEC4 = new Vector4f();
    private static final float[] CORNER_X = new float[8];
    private static final float[] CORNER_Y = new float[8];
    private static final boolean[] CORNER_OK = new boolean[8];

    private static final int[][] BOX_EDGES = new int[][]{
            {0, 1}, {1, 3}, {3, 2}, {2, 0}, // bottom
            {4, 5}, {5, 7}, {7, 6}, {6, 4}, // top
            {0, 4}, {1, 5}, {2, 6}, {3, 7}  // pillars
    };

    public BlockOverlayModule() {
        super("BlockOverlay", "Smoothly glides a glowing glass outline between hovered blocks", ModuleCategory.VISUAL);
        instance = this;
    }

    public static BlockOverlayModule getInstance() {
        return instance;
    }

    public static boolean isActive() {
        BlockOverlayModule mod = instance;
        return mod != null && mod.isEnabled();
    }

    public static void renderOverlay(Minecraft mc, GuiGraphics g, float partialTick) {
        BlockOverlayModule mod = instance;
        if (mod == null || !mod.isEnabled() || mc == null || mc.player == null || mc.level == null || g == null) {
            return;
        }
        mod.renderInternal(mc, g, partialTick);
    }

    private void renderInternal(Minecraft mc, GuiGraphics g, float partialTick) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0.016F : AnimationUtil.clampDelta((now - lastFrameNanos) * 1.0e-9F);
        lastFrameNanos = now;
        animClock += dt;
        if (animClock > 86400.0F) animClock -= 86400.0F;

        HitResult hr = mc.hitResult;
        boolean validHit = (hr != null && hr.getType() == HitResult.Type.BLOCK && hr instanceof BlockHitResult);
        float speed = Mth.clamp(smoothSpeed.getValue().floatValue(), 6.0F, 28.0F);

        if (validHit) {
            BlockPos pos = ((BlockHitResult) hr).getBlockPos();
            BlockState state = mc.level.getBlockState(pos);
            if (!state.isAir()) {
                VoxelShape shape = state.getShape(mc.level, pos);
                AABB local = (shape != null && !shape.isEmpty()) ? shape.bounds() : new AABB(0, 0, 0, 1, 1, 1);
                double pad = 0.004D;
                double tMinX = pos.getX() + local.minX - pad;
                double tMinY = pos.getY() + local.minY - pad;
                double tMinZ = pos.getZ() + local.minZ - pad;
                double tMaxX = pos.getX() + local.maxX + pad;
                double tMaxY = pos.getY() + local.maxY + pad;
                double tMaxZ = pos.getZ() + local.maxZ + pad;

                if (!hasBox || fadeAlpha < 0.03F) {
                    curMinX = tMinX; curMinY = tMinY; curMinZ = tMinZ;
                    curMaxX = tMaxX; curMaxY = tMaxY; curMaxZ = tMaxZ;
                    hasBox = true;
                } else {
                    double k = 1.0D - Math.exp(-speed * dt);
                    curMinX += (tMinX - curMinX) * k;
                    curMinY += (tMinY - curMinY) * k;
                    curMinZ += (tMinZ - curMinZ) * k;
                    curMaxX += (tMaxX - curMaxX) * k;
                    curMaxY += (tMaxY - curMaxY) * k;
                    curMaxZ += (tMaxZ - curMaxZ) * k;
                }
                fadeAlpha = AnimationUtil.lerp(fadeAlpha, 1.0F, 16.0F, dt);
            } else {
                fadeAlpha = AnimationUtil.lerp(fadeAlpha, 0.0F, 14.0F, dt);
            }
        } else {
            fadeAlpha = AnimationUtil.lerp(fadeAlpha, 0.0F, 14.0F, dt);
        }

        if (!hasBox || fadeAlpha <= 0.02F) {
            return;
        }

        Camera cam = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        if (cam == null || mc.getWindow() == null) {
            return;
        }
        Vec3 camPos = cam.position();
        float pitch = cam.xRot();
        float yaw = cam.yRot();

        int fbW = Math.max(1, mc.getWindow().getWidth());
        int fbH = Math.max(1, mc.getWindow().getHeight());
        float guiW = (float) mc.getWindow().getGuiScaledWidth();
        float guiH = (float) mc.getWindow().getGuiScaledHeight();
        float aspect = (float) fbW / (float) fbH;
        double baseFov = mc.options != null ? Mth.clamp(mc.options.fov().get(), 30.0D, 170.0D) : 70.0D;

        PROJ_MAT.identity().setPerspective((float) Math.toRadians(baseFov), aspect, 0.05F, 512.0F);
        VIEW_MAT.identity()
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0F));
        PROJ_MAT.mul(VIEW_MAT, MVP_MAT);

        float minScreenX = Float.MAX_VALUE;
        float minScreenY = Float.MAX_VALUE;
        float maxScreenX = -Float.MAX_VALUE;
        float maxScreenY = -Float.MAX_VALUE;
        int validCorners = 0;

        for (int i = 0; i < 8; i++) {
            double wx = ((i & 1) == 0) ? curMinX : curMaxX;
            double wy = ((i & 4) == 0) ? curMinY : curMaxY;
            double wz = ((i & 2) == 0) ? curMinZ : curMaxZ;

            VEC4.set((float) (wx - camPos.x), (float) (wy - camPos.y), (float) (wz - camPos.z), 1.0F);
            MVP_MAT.transform(VEC4);
            if (VEC4.w <= 0.05F) {
                CORNER_OK[i] = false;
                continue;
            }
            float ndcX = VEC4.x / VEC4.w;
            float ndcY = VEC4.y / VEC4.w;
            float sx = (ndcX * 0.5F + 0.5F) * guiW;
            float sy = (0.5F - ndcY * 0.5F) * guiH;
            CORNER_X[i] = sx;
            CORNER_Y[i] = sy;
            CORNER_OK[i] = true;
            validCorners++;
            if (sx < minScreenX) minScreenX = sx;
            if (sy < minScreenY) minScreenY = sy;
            if (sx > maxScreenX) maxScreenX = sx;
            if (sy > maxScreenY) maxScreenY = sy;
        }

        if (validCorners < 4) {
            return;
        }

        float pulse = Boolean.TRUE.equals(breathingPulse.getValue())
                ? (0.82F + 0.18F * (float) Math.sin(animClock * 4.2F))
                : 1.0F;
        float alpha = fadeAlpha * pulse;
        int baseCol = resolveColor(animClock);
        String st = style.getValue();
        boolean doGlow = Boolean.TRUE.equals(lineGlow.getValue());
        float fillOp = Mth.clamp(fillOpacity.getValue().floatValue(), 0.0F, 0.45F);

        // Soft translucent frosted fill inside projected bounds for Filled Glass / Neon Pulse
        if (fillOp > 0.01F && ("Filled Glass".equalsIgnoreCase(st) || "Neon Pulse".equalsIgnoreCase(st))) {
            int fx1 = Mth.clamp(Math.round(minScreenX), 0, (int) guiW);
            int fy1 = Mth.clamp(Math.round(minScreenY), 0, (int) guiH);
            int fx2 = Mth.clamp(Math.round(maxScreenX), 0, (int) guiW);
            int fy2 = Mth.clamp(Math.round(maxScreenY), 0, (int) guiH);
            if (fx2 > fx1 + 2 && fy2 > fy1 + 2 && (fx2 - fx1) < guiW * 0.85F) {
                int fillCol = AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(baseCol, Math.round(fillOp * 255.0F)), alpha);
                g.fill(fx1, fy1, fx2, fy2, fillCol);
            }
        }

        int coreCol = AnimationUtil.withAlpha(baseCol, alpha);
        int glowCol = AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(baseCol, 0x44), alpha);
        int whiteHotCol = AnimationUtil.withAlpha(0xCCFFFFFF, alpha);
        boolean cornersOnly = "Corners Only".equalsIgnoreCase(st);

        for (int e = 0; e < 12; e++) {
            int a = BOX_EDGES[e][0];
            int b = BOX_EDGES[e][1];
            if (!CORNER_OK[a] || !CORNER_OK[b]) continue;

            float x1 = CORNER_X[a];
            float y1 = CORNER_Y[a];
            float x2 = CORNER_X[b];
            float y2 = CORNER_Y[b];

            if (cornersOnly) {
                float mx1 = Mth.lerp(0.24F, x1, x2);
                float my1 = Mth.lerp(0.24F, y1, y2);
                float mx2 = Mth.lerp(0.76F, x1, x2);
                float my2 = Mth.lerp(0.76F, y1, y2);
                drawGlowLine(g, x1, y1, mx1, my1, coreCol, glowCol, doGlow);
                drawGlowLine(g, mx2, my2, x2, y2, coreCol, glowCol, doGlow);
            } else {
                drawGlowLine(g, x1, y1, x2, y2, coreCol, glowCol, doGlow);
                if ("Neon Pulse".equalsIgnoreCase(st)) {
                    // Subtle white-hot center highlight along top edges
                    if (e >= 4 && e <= 7) {
                        drawThinLine(g, x1, y1, x2, y2, whiteHotCol);
                    }
                }
            }
        }
    }

    // [ATTRIUM-STEP2-SPAN-BATCH-BLOCK]
    private static final int MAX_LINE_SPANS = 256;
    private static final int[] SPAN_X1 = new int[MAX_LINE_SPANS];
    private static final int[] SPAN_Y1 = new int[MAX_LINE_SPANS];
    private static final int[] SPAN_X2 = new int[MAX_LINE_SPANS];
    private static final int[] SPAN_Y2 = new int[MAX_LINE_SPANS];

    private static int collectMergedLineSpans(int ix1, int iy1, int ix2, int iy2) {
        int adx = Math.abs(ix2 - ix1);
        int ady = Math.abs(iy2 - iy1);
        int count = 0;

        if (adx >= ady) {
            if (ix1 > ix2) {
                int tx = ix1; ix1 = ix2; ix2 = tx;
                int ty = iy1; iy1 = iy2; iy2 = ty;
            }
            int sy = iy1 <= iy2 ? 1 : -1;
            int err = adx >> 1;
            int y = iy1;
            int runStartX = ix1;
            int runY = y;

            for (int x = ix1; x <= ix2; x++) {
                if (y != runY) {
                    if (count < MAX_LINE_SPANS) {
                        SPAN_X1[count] = runStartX;
                        SPAN_Y1[count] = runY;
                        SPAN_X2[count] = x - 1;
                        SPAN_Y2[count] = runY;
                        count++;
                    }
                    runStartX = x;
                    runY = y;
                }
                err -= ady;
                if (err < 0) {
                    y += sy;
                    err += adx;
                }
            }
            if (count < MAX_LINE_SPANS) {
                SPAN_X1[count] = runStartX;
                SPAN_Y1[count] = runY;
                SPAN_X2[count] = ix2;
                SPAN_Y2[count] = runY;
                count++;
            }
        } else {
            if (iy1 > iy2) {
                int tx = ix1; ix1 = ix2; ix2 = tx;
                int ty = iy1; iy1 = iy2; iy2 = ty;
            }
            int sx = ix1 <= ix2 ? 1 : -1;
            int err = ady >> 1;
            int x = ix1;
            int runStartY = iy1;
            int runX = x;

            for (int y = iy1; y <= iy2; y++) {
                if (x != runX) {
                    if (count < MAX_LINE_SPANS) {
                        SPAN_X1[count] = runX;
                        SPAN_Y1[count] = runStartY;
                        SPAN_X2[count] = runX;
                        SPAN_Y2[count] = y - 1;
                        count++;
                    }
                    runStartY = y;
                    runX = x;
                }
                err -= adx;
                if (err < 0) {
                    x += sx;
                    err += ady;
                }
            }
            if (count < MAX_LINE_SPANS) {
                SPAN_X1[count] = runX;
                SPAN_Y1[count] = runStartY;
                SPAN_X2[count] = runX;
                SPAN_Y2[count] = iy2;
                count++;
            }
        }
        return count;
    }

    private void drawGlowLine(GuiGraphics g, float x1, float y1, float x2, float y2, int coreCol, int glowCol, boolean glow) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float distSq = dx * dx + dy * dy;
        if (distSq < 0.25F || distSq > 810000.0F) return;

        int ix1 = Math.round(x1);
        int iy1 = Math.round(y1);
        int ix2 = Math.round(x2);
        int iy2 = Math.round(y2);
        int spans = collectMergedLineSpans(ix1, iy1, ix2, iy2);

        if (glow && ((glowCol >>> 24) > 2)) {
            for (int i = 0; i < spans; i++) {
                g.fill(SPAN_X1[i] - 1, SPAN_Y1[i] - 1, SPAN_X2[i] + 2, SPAN_Y2[i] + 2, glowCol);
            }
        }
        if ((coreCol >>> 24) > 2) {
            for (int i = 0; i < spans; i++) {
                g.fill(SPAN_X1[i], SPAN_Y1[i], SPAN_X2[i] + 1, SPAN_Y2[i] + 1, coreCol);
            }
        }
    }

    private void drawThinLine(GuiGraphics g, float x1, float y1, float x2, float y2, int col) {
        if ((col >>> 24) <= 2) return;
        float dx = x2 - x1;
        float dy = y2 - y1;
        float distSq = dx * dx + dy * dy;
        if (distSq < 0.25F || distSq > 810000.0F) return;

        int spans = collectMergedLineSpans(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2));
        for (int i = 0; i < spans; i++) {
            g.fill(SPAN_X1[i], SPAN_Y1[i], SPAN_X2[i] + 1, SPAN_Y2[i] + 1, col);
        }
    }

    private int resolveColor(float clockSec) {
        String cm = colorMode.getValue();
        if ("Pure White".equalsIgnoreCase(cm)) {
            return 0xFFFFFFFF;
        }
        if ("Cyan-Blue Wave".equalsIgnoreCase(cm)) {
            float w = 0.5F + 0.5F * (float) Math.sin(clockSec * 2.2F);
            return AnimationUtil.lerpColor(ClientThemeManager.getAccentArgb(), 0xFF3B82F6, w);
        }
        if ("Rainbow".equalsIgnoreCase(cm)) {
            return hsbToArgb((clockSec * 0.14F) % 1.0F, 0.65F, 1.0F);
        }
        return ClientThemeManager.getAccentArgb();
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
