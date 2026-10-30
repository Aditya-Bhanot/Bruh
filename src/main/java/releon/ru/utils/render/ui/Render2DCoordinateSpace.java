package releon.ru.utils.render.ui;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2f;

public final class Render2DCoordinateSpace {
    private static final float DESIGN_GUI_SCALE = 2.0f;
    private static final ThreadLocal<PoseCache> POSE_CACHE = ThreadLocal.withInitial(PoseCache::new);

    private Render2DCoordinateSpace() {
    }

    public static Matrix3x2f pose(GuiGraphics graphics) {
        Matrix3x2f source = graphics.pose();
        float scale = guiIndependentScale();
        PoseCache cache = POSE_CACHE.get();
        if (cache.matches(graphics, source, scale)) {
            return cache.pose;
        }

        Matrix3x2f pose = new Matrix3x2f(source);
        if (scale != 1.0f) {
            pose.scale(scale);
        }
        cache.set(graphics, source, scale, pose);
        return pose;
    }

    public static void applyGuiScaleIndependence(Matrix3x2f pose) {
        float scale = guiIndependentScale();
        if (scale != 1.0f) {
            pose.scale(scale);
        }
    }

    public static float toGui(float value) {
        return value * guiIndependentScale();
    }

    public static int toGuiInt(float value) {
        return Math.round(toGui(value));
    }

    public static float guiIndependentScale() {
        return DESIGN_GUI_SCALE / guiScale();
    }

    public static float designGuiScale() {
        return DESIGN_GUI_SCALE;
    }

    public static int guiScale() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return 1;
        }

        Window window = minecraft.getWindow();
        if (window == null) {
            return 1;
        }
        return Math.max(1, window.getGuiScale());
    }

    private static final class PoseCache {
        private GuiGraphics graphics;
        private Matrix3x2f pose;
        private float scale;
        private float m00;
        private float m01;
        private float m10;
        private float m11;
        private float m20;
        private float m21;

        private boolean matches(GuiGraphics graphics, Matrix3x2f source, float scale) {
            return this.graphics == graphics
                    && pose != null
                    && this.scale == scale
                    && m00 == source.m00()
                    && m01 == source.m01()
                    && m10 == source.m10()
                    && m11 == source.m11()
                    && m20 == source.m20()
                    && m21 == source.m21();
        }

        private void set(GuiGraphics graphics, Matrix3x2f source, float scale, Matrix3x2f pose) {
            this.graphics = graphics;
            this.pose = pose;
            this.scale = scale;
            this.m00 = source.m00();
            this.m01 = source.m01();
            this.m10 = source.m10();
            this.m11 = source.m11();
            this.m20 = source.m20();
            this.m21 = source.m21();
        }
    }
}
