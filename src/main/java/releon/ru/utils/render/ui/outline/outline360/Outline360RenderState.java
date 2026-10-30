package releon.ru.utils.render.ui.outline.outline360;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.joml.Matrix3x2f;

final class Outline360RenderState implements GuiElementRenderState {
    private final BuiltOutline360 outline;
    private final Matrix3x2f pose;
    private final ScreenRectangle bounds;

    Outline360RenderState(Matrix3x2f pose, BuiltOutline360 outline) {
        this.outline = outline;
        this.pose = pose;
        this.bounds = new ScreenRectangle(
                Math.round(outline.x()),
                Math.round(outline.y()),
                Math.round(outline.width()),
                Math.round(outline.height())
        ).transformMaxBounds(this.pose);
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        int batchIndex = Outline360Renderer.getInstance().reserve(outline);
        if (batchIndex < 0) {
            return;
        }

        float x0 = outline.x();
        float y0 = outline.y();
        float x1 = outline.x() + outline.width();
        float y1 = outline.y() + outline.height();

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
        return Outline360Renderer.OUTLINE_360_PIPELINE;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }

    @Override
    public ScreenRectangle bounds() {
        return bounds;
    }
}
