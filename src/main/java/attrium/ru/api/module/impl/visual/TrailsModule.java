package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
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
 * Movement & Elytra Trails Module (Minecraft 1.21.11 / FoldCraftLauncher Safe).
 *
 * Zero-allocation 256-node ring buffer rendering 4 cinematic 3D-projected trail styles:
 *  - Neon Ribbon: silky tapering multi-layer glowing ribbon with white-hot core filament
 *  - Dual Helix: two counter-rotating neon energy strands spiraling behind your flight/sprint
 *  - Glowing Orbs: perspective-scaled floating orbs with outer halos drifting softly upward
 *  - Elytra Spark: speed-reactive high-energy streaks that flare during dives & Wind Charge boosts
 */
public class TrailsModule extends Module {
    private static volatile TrailsModule instance;

    private final ModeSetting style = register(new ModeSetting(
            "Style",
            "Visual trail style rendered behind your movement.",
            "Neon Ribbon",
            "Neon Ribbon", "Dual Helix", "Glowing Orbs", "Elytra Spark"
    ));

    private final ModeSetting colorMode = register(new ModeSetting(
            "Color Mode",
            "Color gradient palette for the trail.",
            "Theme Accent",
            "Theme Accent", "Cyan-Blue Wave", "Sunset Gold", "Sakura Pink", "Rainbow"
    ));

    private final NumberSetting trailLength = register(new NumberSetting(
            "Trail Length",
            "How long each trail segment stays visible in seconds (0.4 - 3.0).",
            1.30D, 0.40D, 3.00D, 0.10D
    ));

    private final BooleanSetting elytraOnly = register(new BooleanSetting(
            "Elytra Only",
            "Only emits trails while gliding with an Elytra.",
            false
    ));

    private final BooleanSetting firstPerson = register(new BooleanSetting(
            "First Person",
            "Shows trails in first-person view (off keeps screen clear until 3rd-person/Freelook).",
            false
    ));

    // Pre-allocated 256-slot zero-GC ring buffer
    private static final int MAX_NODES = 256;
    private final double[] nodeX = new double[MAX_NODES];
    private final double[] nodeY = new double[MAX_NODES];
    private final double[] nodeZ = new double[MAX_NODES];
    private final long[] nodeBornMs = new long[MAX_NODES];
    private final float[] nodeSpeed = new float[MAX_NODES];
    private final float[] nodePhase = new float[MAX_NODES];
    private int head = 0;
    private int count = 0;

    private double lastSampleX = Double.NaN;
    private double lastSampleY = Double.NaN;
    private double lastSampleZ = Double.NaN;
    private ClientLevel lastLevelRef = null;
    private float phaseClock = 0.0F;
    private long lastFrameNanos = 0L;

    // Pre-allocated camera-lens projection scratch objects
    private static final Matrix4f PROJ_MAT = new Matrix4f();
    private static final Matrix4f VIEW_MAT = new Matrix4f();
    private static final Matrix4f MVP_MAT = new Matrix4f();
    private static final Vector4f VEC4 = new Vector4f();

    public TrailsModule() {
        super("Trails", "Renders silky glowing neon ribbons and energy trails behind movement", ModuleCategory.VISUAL);
        instance = this;
    }

    public static TrailsModule getInstance() {
        return instance;
    }

    @Override
    protected void onDisable() {
        count = 0;
        head = 0;
        lastSampleX = Double.NaN;
    }

    public static void renderTrails(Minecraft mc, GuiGraphics g, float partialTick) {
        TrailsModule mod = instance;
        if (mod == null || !mod.isEnabled() || mc == null || mc.player == null || mc.level == null || g == null) {
            return;
        }
        mod.renderInternal(mc, g, partialTick);
    }

    private void renderInternal(Minecraft mc, GuiGraphics g, float partialTick) {
        if (mc.level != lastLevelRef) {
            lastLevelRef = mc.level;
            count = 0;
            head = 0;
            lastSampleX = Double.NaN;
        }

        long nowNanos = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0.016F : AnimationUtil.clampDelta((nowNanos - lastFrameNanos) * 1.0e-9F);
        lastFrameNanos = nowNanos;
        phaseClock += dt * 7.5F;
        if (phaseClock > 10000.0F) phaseClock -= 10000.0F;

        long nowMs = System.currentTimeMillis();
        long lifeMs = Math.round(Mth.clamp(trailLength.getValue(), 0.40D, 3.00D) * 1000.0D);

        // 1. Sample player position when moving
        boolean gliding = mc.player.isFallFlying();
        if (!Boolean.TRUE.equals(elytraOnly.getValue()) || gliding) {
            double px = Mth.lerp(partialTick, mc.player.xo, mc.player.getX());
            double py = Mth.lerp(partialTick, mc.player.yo, mc.player.getY()) + (gliding ? 0.45D : 0.28D);
            double pz = Mth.lerp(partialTick, mc.player.zo, mc.player.getZ());

            if (Double.isNaN(lastSampleX)) {
                pushNode(px, py, pz, nowMs, 0.2F);
            } else {
                double dx = px - lastSampleX;
                double dy = py - lastSampleY;
                double dz = pz - lastSampleZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                double minStepSq = gliding ? 0.035D : 0.025D;
                if (distSq >= minStepSq) {
                    if (distSq > 64.0D) {
                        // Teleported -> reset trail cleanly
                        count = 0;
                        head = 0;
                    }
                    float spd = Mth.clamp((float) Math.sqrt(distSq) * 3.0F, 0.2F, 2.5F);
                    pushNode(px, py, pz, nowMs, spd);
                }
            }
        }

        if (count < 2) {
            return;
        }

        // Skip rendering in 1st-person unless First Person is enabled
        if (!Boolean.TRUE.equals(firstPerson.getValue())
                && mc.options != null
                && mc.options.getCameraType() == CameraType.FIRST_PERSON) {
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

        String st = style.getValue();
        if ("Dual Helix".equalsIgnoreCase(st)) {
            renderHelix(g, camPos, guiW, guiH, nowMs, lifeMs, 0.0F);
            renderHelix(g, camPos, guiW, guiH, nowMs, lifeMs, (float) Math.PI);
        } else if ("Glowing Orbs".equalsIgnoreCase(st)) {
            renderOrbs(g, camPos, guiW, guiH, nowMs, lifeMs);
        } else if ("Elytra Spark".equalsIgnoreCase(st)) {
            renderSparks(g, camPos, guiW, guiH, nowMs, lifeMs);
        } else {
            renderNeonRibbon(g, camPos, guiW, guiH, nowMs, lifeMs);
        }
    }

    private void pushNode(double x, double y, double z, long nowMs, float spd) {
        nodeX[head] = x;
        nodeY[head] = y;
        nodeZ[head] = z;
        nodeBornMs[head] = nowMs;
        nodeSpeed[head] = spd;
        nodePhase[head] = phaseClock;
        head = (head + 1) % MAX_NODES;
        if (count < MAX_NODES) {
            count++;
        }
        lastSampleX = x;
        lastSampleY = y;
        lastSampleZ = z;
    }

    private boolean projectPoint(double wx, double wy, double wz, Vec3 camPos, float guiW, float guiH, float[] outXYW) {
        VEC4.set((float) (wx - camPos.x), (float) (wy - camPos.y), (float) (wz - camPos.z), 1.0F);
        MVP_MAT.transform(VEC4);
        if (VEC4.w <= 0.08F) {
            return false;
        }
        float ndcX = VEC4.x / VEC4.w;
        float ndcY = VEC4.y / VEC4.w;
        if (ndcX < -1.35F || ndcX > 1.35F || ndcY < -1.35F || ndcY > 1.35F) {
            return false;
        }
        outXYW[0] = (ndcX * 0.5F + 0.5F) * guiW;
        outXYW[1] = (0.5F - ndcY * 0.5F) * guiH;
        outXYW[2] = VEC4.w;
        return true;
    }

    private final float[] ptA = new float[3];
    private final float[] ptB = new float[3];

    private void renderNeonRibbon(GuiGraphics g, Vec3 camPos, float guiW, float guiH, long nowMs, long lifeMs) {
        int startIdx = (head - count + MAX_NODES) % MAX_NODES;
        boolean hasPrev = false;

        for (int i = 0; i < count; i++) {
            int idx = (startIdx + i) % MAX_NODES;
            long age = nowMs - nodeBornMs[idx];
            if (age > lifeMs) {
                hasPrev = false;
                continue;
            }
            float lifeFrac = 1.0F - (float) age / (float) lifeMs;
            float smoothAlpha = lifeFrac * lifeFrac;
            if (!projectPoint(nodeX[idx], nodeY[idx], nodeZ[idx], camPos, guiW, guiH, ptB)) {
                hasPrev = false;
                continue;
            }
            if (hasPrev) {
                int segCol = resolveTrailColor(lifeFrac, i);
                int auraCol = AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(segCol, 0x38), smoothAlpha);
                int coreCol = AnimationUtil.withAlpha(segCol, smoothAlpha * 0.92F);
                int hotCol = AnimationUtil.withAlpha(0xDDFFFFFF, smoothAlpha * 0.75F);
                int radius = Math.max(1, Math.round(lifeFrac * 2.4F));
                drawRibbonSegment(g, ptA[0], ptA[1], ptB[0], ptB[1], radius, auraCol, coreCol, hotCol);
            }
            ptA[0] = ptB[0];
            ptA[1] = ptB[1];
            ptA[2] = ptB[2];
            hasPrev = true;
        }
    }

    private void renderHelix(GuiGraphics g, Vec3 camPos, float guiW, float guiH, long nowMs, long lifeMs, float phaseOffset) {
        int startIdx = (head - count + MAX_NODES) % MAX_NODES;
        boolean hasPrev = false;

        for (int i = 0; i < count; i++) {
            int idx = (startIdx + i) % MAX_NODES;
            long age = nowMs - nodeBornMs[idx];
            if (age > lifeMs) {
                hasPrev = false;
                continue;
            }
            float lifeFrac = 1.0F - (float) age / (float) lifeMs;
            float smoothAlpha = lifeFrac * lifeFrac;
            float ang = nodePhase[idx] + phaseOffset;
            double r = 0.34D * lifeFrac;
            double ox = Math.cos(ang) * r;
            double oz = Math.sin(ang) * r;
            double oy = 0.25D + Math.sin(ang * 0.5F) * 0.14D;

            if (!projectPoint(nodeX[idx] + ox, nodeY[idx] + oy, nodeZ[idx] + oz, camPos, guiW, guiH, ptB)) {
                hasPrev = false;
                continue;
            }
            if (hasPrev) {
                int segCol = resolveTrailColor(lifeFrac, i + (phaseOffset > 1.0F ? 18 : 0));
                int auraCol = AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(segCol, 0x3A), smoothAlpha);
                int coreCol = AnimationUtil.withAlpha(segCol, smoothAlpha);
                int hotCol = AnimationUtil.withAlpha(0xCCFFFFFF, smoothAlpha * 0.70F);
                drawRibbonSegment(g, ptA[0], ptA[1], ptB[0], ptB[1], 1, auraCol, coreCol, hotCol);
            }
            ptA[0] = ptB[0];
            ptA[1] = ptB[1];
            hasPrev = true;
        }
    }

    private void renderOrbs(GuiGraphics g, Vec3 camPos, float guiW, float guiH, long nowMs, long lifeMs) {
        int startIdx = (head - count + MAX_NODES) % MAX_NODES;
        for (int i = 0; i < count; i += 2) {
            int idx = (startIdx + i) % MAX_NODES;
            long age = nowMs - nodeBornMs[idx];
            if (age > lifeMs) continue;

            float lifeFrac = 1.0F - (float) age / (float) lifeMs;
            float alpha = lifeFrac * lifeFrac;
            double floatY = (1.0F - lifeFrac) * 0.45D;

            if (!projectPoint(nodeX[idx], nodeY[idx] + floatY, nodeZ[idx], camPos, guiW, guiH, ptB)) {
                continue;
            }
            int px = Math.round(ptB[0]);
            int py = Math.round(ptB[1]);
            float distScale = Mth.clamp(6.0F / Math.max(1.5F, ptB[2]), 0.6F, 2.2F);
            int rad = Math.max(1, Math.round(lifeFrac * 2.6F * distScale));

            int col = resolveTrailColor(lifeFrac, i);
            int outerCol = AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(col, 0x36), alpha);
            int midCol = AnimationUtil.withAlpha(col, alpha * 0.88F);
            int coreCol = AnimationUtil.withAlpha(0xEEFFFFFF, alpha);

            g.fill(px - rad - 1, py - rad - 1, px + rad + 2, py + rad + 2, outerCol);
            g.fill(px - rad, py - rad, px + rad + 1, py + rad + 1, midCol);
            g.fill(px, py, px + 1, py + 1, coreCol);
        }
    }

    private void renderSparks(GuiGraphics g, Vec3 camPos, float guiW, float guiH, long nowMs, long lifeMs) {
        int startIdx = (head - count + MAX_NODES) % MAX_NODES;
        boolean hasPrev = false;
        for (int i = 0; i < count; i++) {
            int idx = (startIdx + i) % MAX_NODES;
            long age = nowMs - nodeBornMs[idx];
            if (age > lifeMs) {
                hasPrev = false;
                continue;
            }
            float lifeFrac = 1.0F - (float) age / (float) lifeMs;
            float alpha = lifeFrac * lifeFrac;
            float jitter = (1.0F - lifeFrac) * 0.35F;
            double jx = Math.sin(nodePhase[idx] * 2.3F) * jitter;
            double jy = Math.cos(nodePhase[idx] * 1.9F) * jitter * 0.6D;
            double jz = Math.cos(nodePhase[idx] * 2.7F) * jitter;

            if (!projectPoint(nodeX[idx] + jx, nodeY[idx] + jy, nodeZ[idx] + jz, camPos, guiW, guiH, ptB)) {
                hasPrev = false;
                continue;
            }
            if (hasPrev) {
                int col = resolveTrailColor(lifeFrac, i);
                float boost = Mth.clamp(nodeSpeed[idx], 0.5F, 2.2F);
                int rad = Math.max(1, Math.round(lifeFrac * boost * 1.6F));
                int auraCol = AnimationUtil.withAlpha(AnimationUtil.withAlphaByte(col, 0x45), alpha);
                int coreCol = AnimationUtil.withAlpha(col, alpha);
                int hotCol = AnimationUtil.withAlpha(0xEEFFFFFF, alpha * 0.85F);
                drawRibbonSegment(g, ptA[0], ptA[1], ptB[0], ptB[1], rad, auraCol, coreCol, hotCol);
            }
            ptA[0] = ptB[0];
            ptA[1] = ptB[1];
            hasPrev = true;
        }
    }

    // [ATTRIUM-STEP2-SPAN-BATCH-TRAILS]
    private static final int MAX_SEG_SPANS = 128;
    private static final int[] SEG_X1 = new int[MAX_SEG_SPANS];
    private static final int[] SEG_Y1 = new int[MAX_SEG_SPANS];
    private static final int[] SEG_X2 = new int[MAX_SEG_SPANS];
    private static final int[] SEG_Y2 = new int[MAX_SEG_SPANS];

    private static int collectMergedSegmentSpans(int ix1, int iy1, int ix2, int iy2) {
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
                    if (count < MAX_SEG_SPANS) {
                        SEG_X1[count] = runStartX;
                        SEG_Y1[count] = runY;
                        SEG_X2[count] = x - 1;
                        SEG_Y2[count] = runY;
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
            if (count < MAX_SEG_SPANS) {
                SEG_X1[count] = runStartX;
                SEG_Y1[count] = runY;
                SEG_X2[count] = ix2;
                SEG_Y2[count] = runY;
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
                    if (count < MAX_SEG_SPANS) {
                        SEG_X1[count] = runX;
                        SEG_Y1[count] = runStartY;
                        SEG_X2[count] = runX;
                        SEG_Y2[count] = y - 1;
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
            if (count < MAX_SEG_SPANS) {
                SEG_X1[count] = runX;
                SEG_Y1[count] = runStartY;
                SEG_X2[count] = runX;
                SEG_Y2[count] = iy2;
                count++;
            }
        }
        return count;
    }

    private void drawRibbonSegment(GuiGraphics g, float x1, float y1, float x2, float y2, int rad, int auraCol, int coreCol, int hotCol) {
        boolean drawAura = (auraCol >>> 24) > 3;
        boolean drawCore = (coreCol >>> 24) > 3;
        boolean drawHot = (hotCol >>> 24) > 3;
        if (!drawAura && !drawCore && !drawHot) return;

        float dx = x2 - x1;
        float dy = y2 - y1;
        float distSq = dx * dx + dy * dy;
        if (distSq < 0.16F || distSq > 202500.0F) return;

        int spans = collectMergedSegmentSpans(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2));
        if (drawAura) {
            int rOuter = rad + 1;
            for (int i = 0; i < spans; i++) {
                g.fill(SEG_X1[i] - rOuter, SEG_Y1[i] - rOuter, SEG_X2[i] + rOuter + 1, SEG_Y2[i] + rOuter + 1, auraCol);
            }
        }
        if (drawCore) {
            for (int i = 0; i < spans; i++) {
                g.fill(SEG_X1[i] - rad, SEG_Y1[i] - rad, SEG_X2[i] + rad + 1, SEG_Y2[i] + rad + 1, coreCol);
            }
        }
        if (drawHot) {
            for (int i = 0; i < spans; i++) {
                g.fill(SEG_X1[i], SEG_Y1[i], SEG_X2[i] + 1, SEG_Y2[i] + 1, hotCol);
            }
        }
    }

    private int resolveTrailColor(float lifeFrac, int index) {
        String cm = colorMode.getValue();
        if ("Cyan-Blue Wave".equalsIgnoreCase(cm)) {
            float w = 0.5F + 0.5F * (float) Math.sin(phaseClock * 0.35F - index * 0.14F);
            return AnimationUtil.lerpColor(0xFF00E5FF, 0xFF6366F1, w);
        }
        if ("Sunset Gold".equalsIgnoreCase(cm)) {
            return AnimationUtil.lerpColor(0xFFFF4D6D, 0xFFFFC857, lifeFrac);
        }
        if ("Sakura Pink".equalsIgnoreCase(cm)) {
            return AnimationUtil.lerpColor(0xFFA855F7, 0xFFF9A8D4, lifeFrac);
        }
        if ("Rainbow".equalsIgnoreCase(cm)) {
            float hue = ((phaseClock * 0.025F) + index * 0.015F) % 1.0F;
            return hsbToArgb(hue, 0.72F, 1.0F);
        }
        // Theme Accent with subtle bright gradient toward player
        return AnimationUtil.lerpColor(ClientThemeManager.getAccentArgb(), 0xFFFFFFFF, lifeFrac * 0.25F);
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
