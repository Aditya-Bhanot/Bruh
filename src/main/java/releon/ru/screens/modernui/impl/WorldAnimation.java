package releon.ru.screens.modernui.impl;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import releon.ru.screens.modernui.ClickGuiScreen;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.ui.Render2D;

public final class WorldAnimation {
    private static final double DISTANCE = 7.5D;
    private static final float NEAR_PLANE = 0.05F;
    private static final float CLOSE_STEP = 0.08F;
    private static final float WORLD_SCALE = 0.016F;
    private static final float GUI_RENDERER_Z_OFFSET = 11000.0F;

    private static State state;
    private static PerspectiveProjectionMatrixBuffer projectionBuffer;
    private static boolean renderingDetached;
    private static GpuBufferSlice projectionOverride;

    private WorldAnimation() {
    }

    public static void start(ClickGuiScreen screen, float previousValue, float value) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null || screen == null) {
            stop();
            return;
        }

        var camera = client.gameRenderer.getMainCamera();
        float yaw = camera.yRot();
        float pitch = camera.xRot();
        Vec3 look = lookVector(yaw, pitch);
        double offsetDistance = DISTANCE * client.getWindow().getHeight() / 1080.0D;
        Vec3 anchor = camera.position().add(
                look.x * offsetDistance,
                look.y * offsetDistance,
                look.z * offsetDistance
        );

        float fixedWidth = Render2D.getFixedScaledWidth();
        float fixedHeight = Render2D.getFixedScaledHeight();
        state = new State(
                screen,
                anchor,
                yaw,
                pitch,
                resolveWorldScale(anchor, yaw, pitch, fixedWidth, fixedHeight),
                clamp01(previousValue),
                clamp01(value),
                true
        );
    }

    public static void stop() {
        state = null;
        renderingDetached = false;
        projectionOverride = null;
    }

    public static boolean isActive() {
        return state != null;
    }

    public static boolean isRenderingDetached() {
        return renderingDetached;
    }

    public static void tick() {
        State current = state;
        if (current == null) {
            return;
        }

        current.previousValue = current.value;
        if (current.skipNextTick) {
            current.skipNextTick = false;
            return;
        }

        current.value = Math.max(0.0F, current.value - CLOSE_STEP);
        if (current.value == 0.0F && current.previousValue == 0.0F) {
            stop();
        }
    }

    public static GpuBufferSlice projectionOverride() {
        return projectionOverride;
    }

    public static void render(GuiRenderer renderer, GuiGraphics graphics, GpuBufferSlice fogBuffer, float partialTicks) {
        State current = state;
        if (current == null || renderer == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null || client.options.hideGui) {
            stop();
            return;
        }

        if (client.screen != null) {
            stop();
            return;
        }

        float fixedWidth = Render2D.getFixedScaledWidth();
        float fixedHeight = Render2D.getFixedScaledHeight();
        float progress = interpolate(current.previousValue, current.value, partialTicks);
        Matrix4f projection = createDetachedProjection(current, progress, fixedWidth, fixedHeight);
        if (projection == null) {
            return;
        }

        MatrixProjector projector = new MatrixProjector(projection, fixedWidth, fixedHeight);
        if (!isPanelVisible(projector, fixedWidth, fixedHeight)) {
            return;
        }

        current.screen.renderDetachedBackground(graphics, progress, fixedWidth, fixedHeight);
        renderer.render(fogBuffer);

        renderingDetached = true;
        projectionOverride = projectionBuffer(new Matrix4f(projection).translate(0.0F, 0.0F, GUI_RENDERER_Z_OFFSET));
        RenderSystem.backupProjectionMatrix();
        try {
            Render2D.withProjection(projection, projector, () -> current.screen.renderDetached(graphics, partialTicks));
            renderer.render(fogBuffer);
        } finally {
            RenderSystem.restoreProjectionMatrix();
            projectionOverride = null;
            renderingDetached = false;
        }
    }

    private static GpuBufferSlice projectionBuffer(Matrix4f projection) {
        if (projectionBuffer == null) {
            projectionBuffer = new PerspectiveProjectionMatrixBuffer("releon_modern_ui_world");
        }
        return projectionBuffer.getBuffer(projection);
    }

    private static Matrix4f createDetachedProjection(State state, float progress, float fixedWidth, float fixedHeight) {
        float animationScale = scale(progress);
        if (animationScale <= 0.001F) {
            return null;
        }

        Vec3 cameraPos = Render3D.lastCameraPos;
        float centerX = fixedWidth * 0.5F;
        float centerY = fixedHeight * 0.5F;
        float relativeX = (float) (state.anchor.x - cameraPos.x);
        float relativeY = (float) (state.anchor.y - cameraPos.y);
        float relativeZ = (float) (state.anchor.z - cameraPos.z);

        Matrix4f model = new Matrix4f()
                .translate(relativeX, relativeY, relativeZ)
                .rotateY((float) Math.toRadians(-state.yaw + 180.0F))
                .rotateX((float) Math.toRadians(-state.pitch + 180.0F))
                .scale(state.worldScale, state.worldScale, state.worldScale)
                .scale(animationScale, animationScale, 1.0F)
                .translate(-centerX, -centerY, 0.0F);

        return new Matrix4f(Render3D.lastProjMat)
                .mul(Render3D.lastModMat)
                .mul(model);
    }

    private static float resolveWorldScale(Vec3 anchor, float yaw, float pitch, float fixedWidth, float fixedHeight) {
        float centerX = fixedWidth * 0.5F;
        float centerY = fixedHeight * 0.5F;
        Vec3 cameraPos = Render3D.lastCameraPos;
        float relativeX = (float) (anchor.x - cameraPos.x);
        float relativeY = (float) (anchor.y - cameraPos.y);
        float relativeZ = (float) (anchor.z - cameraPos.z);

        Matrix4f unitProjection = new Matrix4f(Render3D.lastProjMat)
                .mul(Render3D.lastModMat)
                .mul(new Matrix4f()
                        .translate(relativeX, relativeY, relativeZ)
                        .rotateY((float) Math.toRadians(-yaw + 180.0F))
                        .rotateX((float) Math.toRadians(-pitch + 180.0F))
                        .translate(-centerX, -centerY, 0.0F));

        MatrixProjector projector = new MatrixProjector(unitProjection, fixedWidth, fixedHeight);
        Render2D.ProjectedPoint left = projector.project(0.0F, centerY);
        Render2D.ProjectedPoint right = projector.project(fixedWidth, centerY);
        Render2D.ProjectedPoint top = projector.project(centerX, 0.0F);
        Render2D.ProjectedPoint bottom = projector.project(centerX, fixedHeight);

        float widthScale = Float.NaN;
        if (left != null && right != null) {
            widthScale = fixedWidth / Math.max(0.0001F, Math.abs(right.x() - left.x()));
        }

        float heightScale = Float.NaN;
        if (top != null && bottom != null) {
            heightScale = fixedHeight / Math.max(0.0001F, Math.abs(bottom.y() - top.y()));
        }

        float scaleSum = 0.0F;
        int scaleCount = 0;
        if (Float.isFinite(widthScale) && widthScale > 0.0F) {
            scaleSum += widthScale;
            scaleCount++;
        }
        if (Float.isFinite(heightScale) && heightScale > 0.0F) {
            scaleSum += heightScale;
            scaleCount++;
        }
        return scaleCount == 0 ? WORLD_SCALE : scaleSum / scaleCount;
    }

    private static boolean isPanelVisible(MatrixProjector projector, float fixedWidth, float fixedHeight) {
        return projector.project(0.0F, 0.0F) != null
                || projector.project(fixedWidth, 0.0F) != null
                || projector.project(fixedWidth, fixedHeight) != null
                || projector.project(0.0F, fixedHeight) != null;
    }

    private static Vec3 lookVector(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double pitchCos = Math.cos(pitchRadians);
        return new Vec3(
                -Math.sin(yawRadians) * pitchCos,
                -Math.sin(pitchRadians),
                Math.cos(yawRadians) * pitchCos
        );
    }

    private static float scale(float progress) {
        return Math.max(0.001F, easeOutBack(clamp01(progress)));
    }

    private static float interpolate(float previous, float current, float partialTicks) {
        float clampedPartial = clamp01(partialTicks);
        return previous + (current - previous) * clampedPartial;
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float easeOutBack(float value) {
        float overshoot = 1.70158F;
        float shifted = value - 1.0F;
        return 1.0F + (overshoot + 1.0F) * shifted * shifted * shifted + overshoot * shifted * shifted;
    }

    private static final class State {
        private final ClickGuiScreen screen;
        private final Vec3 anchor;
        private final float yaw;
        private final float pitch;
        private final float worldScale;
        private float previousValue;
        private float value;
        private boolean skipNextTick;

        private State(ClickGuiScreen screen, Vec3 anchor, float yaw, float pitch, float worldScale, float previousValue, float value, boolean skipNextTick) {
            this.screen = screen;
            this.anchor = anchor;
            this.yaw = yaw;
            this.pitch = pitch;
            this.worldScale = worldScale;
            this.previousValue = previousValue;
            this.value = value;
            this.skipNextTick = skipNextTick;
        }
    }

    private static final class MatrixProjector implements Render2D.PointProjector {
        private final Matrix4f matrix;
        private final float fixedWidth;
        private final float fixedHeight;

        private MatrixProjector(Matrix4f matrix, float fixedWidth, float fixedHeight) {
            this.matrix = matrix;
            this.fixedWidth = fixedWidth;
            this.fixedHeight = fixedHeight;
        }

        @Override
        public Render2D.ProjectedPoint project(float x, float y) {
            float clipX = matrix.m00() * x + matrix.m10() * y + matrix.m30();
            float clipY = matrix.m01() * x + matrix.m11() * y + matrix.m31();
            float clipW = matrix.m03() * x + matrix.m13() * y + matrix.m33();
            if (clipW <= NEAR_PLANE) {
                return null;
            }

            float inverseW = 1.0F / clipW;
            float ndcX = clipX * inverseW;
            float ndcY = clipY * inverseW;
            return new Render2D.ProjectedPoint(
                    (ndcX * 0.5F + 0.5F) * fixedWidth,
                    (1.0F - (ndcY * 0.5F + 0.5F)) * fixedHeight
            );
        }
    }
}
