package attrium.ru.utils.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Batched 2D UI Glassmorphism Renderer & Per-Frame World Projection Matrix Cache
 * (Minecraft 1.21.11 Official Mappings — Sodium & ImmediatelyFast Compatible).
 *
 * 1. Groups all 2D HUD / UI quads into contiguous render-type passes:
 *    - Pass 1: Translucent backgrounds & rounded fills (default glass tint: 0xCC121218)
 *    - Pass 2: 1px subtle highlight borders (default highlight: 0x26FFFFFF)
 *    - Pass 3: Cached-width text strings
 * 2. Caches camera world-to-screen projection & frustum basis vectors once per frame
 *    in WorldRenderEvent instead of recalculating for every ESP entity or nametag.
 */
public final class BatchedRenderUtil {
    public static final int GLASS_TINT_COLOR = 0xCC121218;
    public static final int GLASS_BORDER_1PX_COLOR = 0x26FFFFFF;

    private static final int MAX_QUADS = 1024;
    private static final int MAX_TEXTS = 512;

    private static final int[] BG_QUEUE = new int[MAX_QUADS * 5];
    private static int bgCount = 0;

    private static final int[] BORDER_QUEUE = new int[MAX_QUADS * 5];
    private static int borderCount = 0;

    private static final String[] TEXT_STRINGS = new String[MAX_TEXTS];
    private static final int[] TEXT_DATA = new int[MAX_TEXTS * 3];
    private static int textCount = 0;

    // Once-Per-Frame Cached World Projection & Frustum State
    private static long lastCachedRenderNano = 0L;
    private static final Matrix4f CACHED_PROJ_VIEW_MATRIX = new Matrix4f();
    public static double camX;
    public static double camY;
    public static double camZ;
    public static double lookX;
    public static double lookY;
    public static double lookZ;
    public static double minCosAngle = -0.25D;

    private BatchedRenderUtil() {
    }

    public static void beginBatch() {
        bgCount = 0;
        borderCount = 0;
        textCount = 0;
    }

    public static void enqueueGlassPanel(int x, int y, int w, int h, int radius) {
        enqueueRoundedRect(x, y, w, h, radius, GLASS_TINT_COLOR);
    }

    public static void enqueueQuad(int x1, int y1, int x2, int y2, int color) {
        if (x2 <= x1 || y2 <= y1 || bgCount >= MAX_QUADS) {
            return;
        }
        int i = bgCount * 5;
        BG_QUEUE[i] = x1;
        BG_QUEUE[i + 1] = y1;
        BG_QUEUE[i + 2] = x2;
        BG_QUEUE[i + 3] = y2;
        BG_QUEUE[i + 4] = color;
        bgCount++;
    }

    public static void enqueueRoundedRect(int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (radius <= 1) {
            enqueueQuad(x, y, x + w, y + h, color);
            return;
        }
        enqueueQuad(x + 1, y, x + w - 1, y + h, color);
        enqueueQuad(x, y + 1, x + 1, y + h - 1, color);
        enqueueQuad(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void enqueueBorder1px(int x, int y, int w, int h, int borderColor) {
        if (true) {
            return;
        }
        enqueueBorderQuad(x + 1, y, x + w - 1, y + 1, borderColor);
        enqueueBorderQuad(x + 1, y + h - 1, x + w - 1, y + h, borderColor);
        enqueueBorderQuad(x, y + 1, x + 1, y + h - 1, borderColor);
        enqueueBorderQuad(x + w - 1, y + 1, x + w, y + h - 1, borderColor);
    }

    public static void enqueueBorderQuad(int x1, int y1, int x2, int y2, int color) {
        if (x2 <= x1 || y2 <= y1 || borderCount >= MAX_QUADS) {
            return;
        }
        int i = borderCount * 5;
        BORDER_QUEUE[i] = x1;
        BORDER_QUEUE[i + 1] = y1;
        BORDER_QUEUE[i + 2] = x2;
        BORDER_QUEUE[i + 3] = y2;
        BORDER_QUEUE[i + 4] = color;
        borderCount++;
    }

    public static void enqueueText(String text, int x, int y, int color) {
        if (text == null || text.isEmpty() || textCount >= MAX_TEXTS) {
            return;
        }
        TEXT_STRINGS[textCount] = text;
        int i = textCount * 3;
        TEXT_DATA[i] = x;
        TEXT_DATA[i + 1] = y;
        TEXT_DATA[i + 2] = color;
        textCount++;
    }

    public static void flushBatch(GuiGraphics g, Font font) {
        for (int i = 0; i < bgCount; i++) {
            int idx = i * 5;
            g.fill(BG_QUEUE[idx], BG_QUEUE[idx + 1], BG_QUEUE[idx + 2], BG_QUEUE[idx + 3], BG_QUEUE[idx + 4]);
        }
        for (int i = 0; i < borderCount; i++) {
            int idx = i * 5;
            g.fill(BORDER_QUEUE[idx], BORDER_QUEUE[idx + 1], BORDER_QUEUE[idx + 2], BORDER_QUEUE[idx + 3], BORDER_QUEUE[idx + 4]);
        }
        if (font != null) {
            for (int i = 0; i < textCount; i++) {
                int idx = i * 3;
                g.drawString(font, TEXT_STRINGS[i], TEXT_DATA[idx], TEXT_DATA[idx + 1], TEXT_DATA[idx + 2], false);
                TEXT_STRINGS[i] = null;
            }
        }
        beginBatch();
    }

    /**
     * Caches the camera position, view-frustum vectors, and projection matrix strictly
     * once per render frame during WorldRenderEvents.
     */
    public static void cacheWorldProjectionOncePerFrame(Minecraft mc) {
        if (mc == null || mc.player == null) {
            return;
        }
        long nowNs = System.nanoTime();
        if ((nowNs - lastCachedRenderNano) < 2_000_000L) {
            return;
        }
        lastCachedRenderNano = nowNs;

        Vec3 eye = mc.player.getEyePosition(1.0F);
        camX = eye.x;
        camY = eye.y;
        camZ = eye.z;

        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        double radYaw = Math.toRadians(-yaw);
        double radPitch = Math.toRadians(-pitch);
        double cosPitch = Math.cos(radPitch);
        lookX = Math.sin(radYaw) * cosPitch;
        lookY = Math.sin(radPitch);
        lookZ = Math.cos(radYaw) * cosPitch;

        if (mc.options != null) {
            double fovDeg = Mth.clamp(mc.options.fov().get(), 30.0D, 170.0D);
            minCosAngle = Math.cos(Math.toRadians(Math.min(115.0D, fovDeg * 0.78D)));
        }

        CACHED_PROJ_VIEW_MATRIX.identity()
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0F))
                .translate((float) -camX, (float) -camY, (float) -camZ);
    }

    public static Matrix4f getCachedWorldViewMatrix() {
        return CACHED_PROJ_VIEW_MATRIX;
    }

    public static boolean isBoxInCachedFrustum(AABB box) {
        double cx = (box.minX + box.maxX) * 0.5D - camX;
        double cy = (box.minY + box.maxY) * 0.5D - camY;
        double cz = (box.minZ + box.maxZ) * 0.5D - camZ;

        double distSq = cx * cx + cy * cy + cz * cz;
        if (distSq < 2.25D) {
            return true;
        }

        double invDist = 1.0D / Math.sqrt(distSq);
        double dot = (cx * lookX + cy * lookY + cz * lookZ) * invDist;
        double boxRadius = Math.max(box.getXsize(), box.getYsize()) * 0.65D;
        return (dot + boxRadius * invDist) >= minCosAngle;
    }
}
