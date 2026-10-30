package releon.ru.utils.render.ui.arc;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.joml.Matrix3x2f;

final class ArcRenderState implements GuiElementRenderState {
    private final BuiltArc arc;
    private final Matrix3x2f pose;
    private final ScreenRectangle scissorArea;
    private final ScreenRectangle bounds;

    ArcRenderState(Matrix3x2f pose, BuiltArc arc, ScreenRectangle scissorArea) {
        this.arc = arc;
        this.pose = pose;
        this.scissorArea = scissorArea;
        ScreenRectangle transformedBounds = new ScreenRectangle(
                Math.round(arc.x()),
                Math.round(arc.y()),
                Math.round(arc.size()),
                Math.round(arc.size())
        ).transformMaxBounds(this.pose);
        this.bounds = scissorArea == null ? transformedBounds : scissorArea.intersection(transformedBounds);
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        int batchIndex = ArcRenderer.getInstance().reserve(arc);
        if (batchIndex < 0) {
            return;
        }

        float x0 = arc.x();
        float y0 = arc.y();
        float x1 = arc.x() + arc.size();
        float y1 = arc.y() + arc.size();

        vertex(consumer, x0, y0, 0, 0, batchIndex);
        vertex(consumer, x0, y1, 0, 255, batchIndex);
        vertex(consumer, x1, y1, 255, 255, batchIndex);
        vertex(consumer, x1, y0, 255, 0, batchIndex);
    }

    private void vertex(VertexConsumer consumer, float x, float y, int coordX, int coordY, int batchIndex) {
        consumer.addVertexWith2DPose(pose, x, y)
                .setColor(coordX, coordY, 255, 255)
                .setLineWidth((float) (batchIndex + 1));
    }

    @Override
    public RenderPipeline pipeline() {
        return ArcRenderer.ARC_PIPELINE;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return scissorArea;
    }

    @Override
    public ScreenRectangle bounds() {
        return bounds;
    }
}
