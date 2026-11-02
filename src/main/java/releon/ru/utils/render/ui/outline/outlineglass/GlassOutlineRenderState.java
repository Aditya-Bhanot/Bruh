package releon.ru.utils.render.ui.outline.outlineglass;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import releon.ru.utils.render.ui.blur.BlurFramebuffer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.joml.Matrix3x2f;

final class GlassOutlineRenderState implements GuiElementRenderState {
    private final BuiltGlassOutline outline;
    private final Matrix3x2f pose;
    private final ScreenRectangle scissorArea;
    private final ScreenRectangle bounds;

    GlassOutlineRenderState(Matrix3x2f pose, BuiltGlassOutline outline, ScreenRectangle scissorArea) {
        this.outline = outline;
        this.pose = pose;
        this.scissorArea = scissorArea;
        ScreenRectangle transformedBounds = new ScreenRectangle(
                Math.round(outline.x()),
                Math.round(outline.y()),
                Math.round(outline.width()),
                Math.round(outline.height())
        ).transformMaxBounds(this.pose);
        this.bounds = scissorArea == null ? transformedBounds : scissorArea.intersection(transformedBounds);
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        int batchIndex = GlassOutlineRenderer.getInstance().reserve(outline);
        if (batchIndex < 0) {
            return;
        }

        float x0 = outline.x();
        float y0 = outline.y();
        float x1 = outline.x() + outline.width();
        float y1 = outline.y() + outline.height();

        vertex(consumer, x0, y0, batchIndex);
        vertex(consumer, x0, y1, batchIndex);
        vertex(consumer, x1, y1, batchIndex);
        vertex(consumer, x1, y0, batchIndex);
    }

    private void vertex(VertexConsumer consumer, float x, float y, int batchIndex) {
        consumer.addVertexWith2DPose(pose, x, y)
                .setColor(outline.color())
                .setLineWidth((float) (batchIndex + 1));
    }

    @Override
    public RenderPipeline pipeline() {
        return GlassOutlineRenderer.GLASS_OUTLINE_PIPELINE;
    }

    @Override
    public TextureSetup textureSetup() {
        return BlurFramebuffer.getInstance().textureSetup();
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
