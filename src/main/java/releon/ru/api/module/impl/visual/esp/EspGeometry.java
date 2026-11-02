package releon.ru.api.module.impl.visual.esp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import releon.ru.utils.render.Render3D;

public final class EspGeometry {
    private static final double NEAR_PLANE = 0.05D;
    private static final double MAX_SAFE_COORD = 30_000_000.0D;
    private static final int[][] AABB_EDGES = {
            {0, 1}, {0, 2}, {0, 4},
            {1, 3}, {1, 5},
            {2, 3}, {2, 6},
            {3, 7},
            {4, 5}, {4, 6},
            {5, 7},
            {6, 7}
    };

    private EspGeometry() {
    }

    public static ProjectionContext createProjectionContext(Minecraft mc, float tickDelta) {
        if (mc.gameRenderer == null || mc.getWindow() == null) {
            return null;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) {
            return null;
        }

        Vec3 cameraPos = isFinite(Render3D.lastCameraPos) ? Render3D.lastCameraPos : camera.position();
        float resolvedTickDelta = Float.isFinite(tickDelta) ? tickDelta : Render3D.lastTickDelta;
        PoseStack stack = new PoseStack();
        stack.mulPose(Axis.XP.rotationDegrees(camera.xRot()));
        stack.mulPose(Axis.YP.rotationDegrees(camera.yRot() + 180.0F));

        return new ProjectionContext(
                cameraPos,
                new Matrix4f(Render3D.lastProjMat),
                new Matrix3f(stack.last().pose()),
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight(),
                resolvedTickDelta,
                createCorners(),
                new Vector3f(),
                new Vector3f(),
                new Vector4f(),
                new double[4]
        );
    }

    public static AABB interpolatedBox(Entity entity, float tickDelta) {
        Vec3 pos = entity.getPosition(tickDelta);
        return entity.getDimensions(entity.getPose()).makeBoundingBox(pos.x, pos.y, pos.z);
    }

    public static ScreenBox projectEntityBox(Entity entity, ProjectionContext context) {
        AABB box = interpolatedBox(entity, context.tickDelta()).inflate(0.025D, 0.0D, 0.025D);
        box = new AABB(box.minX, box.minY, box.minZ, box.maxX, box.maxY + 0.05D, box.maxZ);
        return projectBox(box, context);
    }

    public static ScreenBox projectBox(AABB box, ProjectionContext context) {
        if (box == null || context == null) {
            return null;
        }

        fillViewSpaceCorners(box, context);
        double[] bounds = context.bounds();
        bounds[0] = Double.POSITIVE_INFINITY;
        bounds[1] = Double.POSITIVE_INFINITY;
        bounds[2] = Double.NEGATIVE_INFINITY;
        bounds[3] = Double.NEGATIVE_INFINITY;

        int projected = 0;
        for (Vector3f corner : context.corners()) {
            projected += accumulateProjectedBounds(bounds, corner, context);
        }
        for (int[] edge : AABB_EDGES) {
            projected += accumulateClippedEdgeBounds(bounds, context.corners()[edge[0]], context.corners()[edge[1]], context);
        }
        if (projected <= 0) {
            return null;
        }

        double minX = bounds[0];
        double minY = bounds[1];
        double maxX = bounds[2];
        double maxY = bounds[3];
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
            return null;
        }
        if (maxX <= minX || maxY <= minY || maxX - minX < 1.0D || maxY - minY < 1.0D) {
            return null;
        }
        return new ScreenBox(minX, minY, maxX, maxY);
    }

    public static ScreenAnchor resolveTopAnchor(Entity entity, ProjectionContext context, ScreenBox fallbackBox) {
        Vec3 interpolated = entity.getPosition(context.tickDelta());
        double height = entity.getDimensions(entity.getPose()).height();
        double topOffset = entity instanceof Player ? 0.38D : 0.06D;
        return resolveTopAnchor(interpolated, height, topOffset, context, fallbackBox);
    }

    public static ScreenAnchor resolveTopAnchor(Vec3 interpolated, double height, double topOffset, ProjectionContext context, ScreenBox fallbackBox) {
        if (!isFinite(interpolated)) {
            return fallbackBox != null ? new ScreenAnchor(fallbackBox.centerX(), fallbackBox.minY()) : null;
        }

        ScreenPoint projected = worldSpaceToScreenSpace(interpolated.x, interpolated.y + height + topOffset, interpolated.z, context);
        if (isUsableAnchorPoint(projected, context)) {
            double marginX = context.screenWidth() * 0.45D;
            double marginY = context.screenHeight() * 0.45D;
            return new ScreenAnchor(
                    Mth.clamp(projected.x(), -marginX, context.screenWidth() + marginX),
                    Mth.clamp(projected.y(), -marginY, context.screenHeight() + marginY)
            );
        }

        return fallbackBox != null ? new ScreenAnchor(fallbackBox.centerX(), fallbackBox.minY()) : null;
    }

    public static boolean isOnScreen(ScreenBox box, ProjectionContext context) {
        double marginX = context.screenWidth() * 0.35D;
        double marginY = context.screenHeight() * 0.35D;
        return box.maxX() >= -marginX
                && box.minX() <= context.screenWidth() + marginX
                && box.maxY() >= -marginY
                && box.minY() <= context.screenHeight() + marginY;
    }

    public static boolean isFinite(Vec3 pos) {
        return pos != null
                && Double.isFinite(pos.x)
                && Double.isFinite(pos.y)
                && Double.isFinite(pos.z)
                && Math.abs(pos.x) < MAX_SAFE_COORD
                && Math.abs(pos.y) < MAX_SAFE_COORD
                && Math.abs(pos.z) < MAX_SAFE_COORD;
    }

    public static ScreenPoint projectWorldToScreenSpace(Vec3 worldPos, ProjectionContext context) {
        if (!isFinite(worldPos)) {
            return null;
        }
        return projectWorldToScreenSpace(worldPos.x, worldPos.y, worldPos.z, context);
    }

    public static ScreenPoint projectWorldToScreenSpace(double worldX, double worldY, double worldZ, ProjectionContext context) {
        if (context == null) {
            return null;
        }
        return worldSpaceToScreenSpace(worldX, worldY, worldZ, context);
    }

    private static ScreenPoint worldSpaceToScreenSpace(double worldX, double worldY, double worldZ, ProjectionContext context) {
        Vector3f view = toCameraViewSpace(worldX, worldY, worldZ, context, context.pointScratch());
        if (!isVisibleViewPoint(view)) {
            return null;
        }

        Vector4f clip = context.projection().transform(context.clipScratch4().set(view.x, view.y, view.z, 1.0F));
        if (clip.w <= 1.0E-6F) {
            return null;
        }

        float invW = 1.0F / clip.w;
        float ndcX = clip.x * invW;
        float ndcY = clip.y * invW;
        float ndcZ = clip.z * invW;
        if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY) || !Float.isFinite(ndcZ)) {
            return null;
        }

        double screenX = (ndcX * 0.5D + 0.5D) * context.screenWidth();
        double screenY = (1.0D - (ndcY * 0.5D + 0.5D)) * context.screenHeight();
        double screenZ = ndcZ * 0.5D + 0.5D;
        if (!Double.isFinite(screenX) || !Double.isFinite(screenY) || !Double.isFinite(screenZ)) {
            return null;
        }
        return new ScreenPoint(screenX, screenY, screenZ);
    }

    private static boolean isUsableAnchorPoint(ScreenPoint point, ProjectionContext context) {
        if (point == null || point.z() < 0.0D || point.z() > 1.0D) {
            return false;
        }

        double marginX = context.screenWidth() * 0.5D;
        double marginY = context.screenHeight() * 0.5D;
        return point.x() >= -marginX
                && point.x() <= context.screenWidth() + marginX
                && point.y() >= -marginY
                && point.y() <= context.screenHeight() + marginY;
    }

    private static void fillViewSpaceCorners(AABB box, ProjectionContext context) {
        toCameraViewSpace(box.minX, box.minY, box.minZ, context, context.corners()[0]);
        toCameraViewSpace(box.maxX, box.minY, box.minZ, context, context.corners()[1]);
        toCameraViewSpace(box.minX, box.maxY, box.minZ, context, context.corners()[2]);
        toCameraViewSpace(box.maxX, box.maxY, box.minZ, context, context.corners()[3]);
        toCameraViewSpace(box.minX, box.minY, box.maxZ, context, context.corners()[4]);
        toCameraViewSpace(box.maxX, box.minY, box.maxZ, context, context.corners()[5]);
        toCameraViewSpace(box.minX, box.maxY, box.maxZ, context, context.corners()[6]);
        toCameraViewSpace(box.maxX, box.maxY, box.maxZ, context, context.corners()[7]);
    }

    private static Vector3f toCameraViewSpace(double worldX, double worldY, double worldZ, ProjectionContext context, Vector3f dest) {
        dest.set(
                (float) (worldX - context.cameraPos().x),
                (float) (worldY - context.cameraPos().y),
                (float) (worldZ - context.cameraPos().z)
        );
        context.viewRotation().transform(dest);
        return dest;
    }

    private static int accumulateProjectedBounds(double[] bounds, Vector3f viewPos, ProjectionContext context) {
        if (!isVisibleViewPoint(viewPos)) {
            return 0;
        }
        return projectViewPointToBounds(viewPos.x, viewPos.y, viewPos.z, context, bounds) ? 1 : 0;
    }

    private static int accumulateClippedEdgeBounds(double[] bounds, Vector3f start, Vector3f end, ProjectionContext context) {
        boolean startVisible = isVisibleViewPoint(start);
        boolean endVisible = isVisibleViewPoint(end);
        if (startVisible == endVisible) {
            return 0;
        }

        Vector3f clippedPoint = clipEdgeToNearPlane(start, end, context.clipScratch());
        if (clippedPoint == null) {
            return 0;
        }
        return projectViewPointToBounds(clippedPoint.x, clippedPoint.y, clippedPoint.z, context, bounds) ? 1 : 0;
    }

    private static Vector3f clipEdgeToNearPlane(Vector3f start, Vector3f end, Vector3f dest) {
        float deltaZ = end.z - start.z;
        if (Math.abs(deltaZ) <= 1.0E-6F) {
            return null;
        }

        float planeZ = (float) -NEAR_PLANE;
        float t = (planeZ - start.z) / deltaZ;
        if (!Float.isFinite(t) || t < 0.0F || t > 1.0F) {
            return null;
        }

        return dest.set(
                Mth.lerp(t, start.x, end.x),
                Mth.lerp(t, start.y, end.y),
                planeZ - 1.0E-3F
        );
    }

    private static boolean projectViewPointToBounds(float viewX, float viewY, float viewZ, ProjectionContext context, double[] bounds) {
        Vector4f clip = context.projection().transform(context.clipScratch4().set(viewX, viewY, viewZ, 1.0F));
        if (clip.w <= 1.0E-6F) {
            return false;
        }

        float invW = 1.0F / clip.w;
        float ndcX = clip.x * invW;
        float ndcY = clip.y * invW;
        float ndcZ = clip.z * invW;
        if (!Float.isFinite(ndcX) || !Float.isFinite(ndcY) || !Float.isFinite(ndcZ)) {
            return false;
        }
        if (ndcZ < -1.0F || ndcZ > 1.0F) {
            return false;
        }

        double screenX = (ndcX * 0.5D + 0.5D) * context.screenWidth();
        double screenY = (1.0D - (ndcY * 0.5D + 0.5D)) * context.screenHeight();
        if (!Double.isFinite(screenX) || !Double.isFinite(screenY)) {
            return false;
        }

        expandBounds(bounds, screenX, screenY, context.screenWidth(), context.screenHeight());
        return true;
    }

    private static void expandBounds(double[] bounds, double x, double y, float screenWidth, float screenHeight) {
        double clampedX = Mth.clamp(x, -screenWidth, screenWidth * 2.0D);
        double clampedY = Mth.clamp(y, -screenHeight, screenHeight * 2.0D);
        bounds[0] = Math.min(bounds[0], clampedX);
        bounds[1] = Math.min(bounds[1], clampedY);
        bounds[2] = Math.max(bounds[2], clampedX);
        bounds[3] = Math.max(bounds[3], clampedY);
    }

    private static boolean isVisibleViewPoint(Vector3f viewPos) {
        return viewPos != null
                && Float.isFinite(viewPos.x)
                && Float.isFinite(viewPos.y)
                && Float.isFinite(viewPos.z)
                && -viewPos.z > NEAR_PLANE;
    }

    private static Vector3f[] createCorners() {
        Vector3f[] result = new Vector3f[8];
        for (int i = 0; i < result.length; i++) {
            result[i] = new Vector3f();
        }
        return result;
    }

    public record ProjectionContext(
            Vec3 cameraPos,
            Matrix4f projection,
            Matrix3f viewRotation,
            float screenWidth,
            float screenHeight,
            float tickDelta,
            Vector3f[] corners,
            Vector3f pointScratch,
            Vector3f clipScratch,
            Vector4f clipScratch4,
            double[] bounds
    ) {
    }

    public record ScreenAnchor(double x, double y) {
    }

    public record ScreenPoint(double x, double y, double z) {
    }

    public record ScreenBox(double minX, double minY, double maxX, double maxY) {
        public double width() {
            return maxX - minX;
        }

        public double height() {
            return maxY - minY;
        }

        public double centerX() {
            return (minX + maxX) * 0.5D;
        }
    }
}
