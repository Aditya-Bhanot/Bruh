package releon.ru.utils.render;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class WorldRenderMatrices {
    private static final Matrix4f projection = new Matrix4f();
    private static final Matrix4f view = new Matrix4f();
    private static Vec3 cameraPosition = Vec3.ZERO;

    private WorldRenderMatrices() {
    }

    public static void capture(Matrix4fc projectionMatrix, Matrix4fc viewMatrix, Vec3 cameraPos) {
        if (projectionMatrix != null) {
            projection.set(projectionMatrix);
        }
        if (viewMatrix != null) {
            view.set(viewMatrix);
        }
        cameraPosition = cameraPos == null ? Vec3.ZERO : cameraPos;
    }

    public static Matrix4f projection() {
        return new Matrix4f(projection);
    }

    public static Matrix4f view() {
        return new Matrix4f(view);
    }

    public static Vec3 cameraPosition() {
        return cameraPosition;
    }
}
