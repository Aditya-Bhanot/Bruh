package releon.ru.utils.render.ui.rectangle.recthalficon;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.List;

final class HalfIconRectangleRenderState implements GuiElementRenderState {
    private final Matrix3x2f pose;
    private final TextureSetup textureSetup;
    private final List<PatternBatch> batches = new ArrayList<>(8);
    private float minX = Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;

    HalfIconRectangleRenderState(Matrix3x2f pose, TextureSetup textureSetup) {
        this.pose = pose;
        this.textureSetup = textureSetup;
    }

    void add(BuiltHalfIconRectangle rectangle, List<IconQuad> quads) {
        if (quads.isEmpty()) {
            return;
        }

        batches.add(new PatternBatch(rectangle, quads));
        minX = Math.min(minX, rectangle.x());
        minY = Math.min(minY, rectangle.y());
        maxX = Math.max(maxX, rectangle.x() + rectangle.width());
        maxY = Math.max(maxY, rectangle.y() + rectangle.height());
    }

    boolean empty() {
        return batches.isEmpty();
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        for (PatternBatch batch : batches) {
            int batchIndex = HalfIconRectangleRenderer.getInstance().reserve(batch.rectangle());
            if (batchIndex < 0) {
                continue;
            }

            for (IconQuad quad : batch.quads()) {
                vertex(consumer, quad.x0(), quad.y0(), quad.u0(), quad.v0(), quad.color(), batchIndex);
                vertex(consumer, quad.x1(), quad.y1(), quad.u1(), quad.v1(), quad.color(), batchIndex);
                vertex(consumer, quad.x2(), quad.y2(), quad.u2(), quad.v2(), quad.color(), batchIndex);
                vertex(consumer, quad.x3(), quad.y3(), quad.u3(), quad.v3(), quad.color(), batchIndex);
            }
        }
    }

    private void vertex(VertexConsumer consumer, float x, float y, float u, float v, int color, int batchIndex) {
        consumer.addVertexWith2DPose(pose, x, y)
                .setUv(u, v)
                .setColor(color)
                .setLineWidth((float) (batchIndex + 1));
    }

    @Override
    public RenderPipeline pipeline() {
        return HalfIconRectangleRenderer.HALF_ICON_RECTANGLE_PIPELINE;
    }

    @Override
    public TextureSetup textureSetup() {
        return textureSetup;
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }

    @Override
    public ScreenRectangle bounds() {
        if (batches.isEmpty()) {
            return new ScreenRectangle(0, 0, 1, 1);
        }

        return new ScreenRectangle(
                (int) Math.floor(minX),
                (int) Math.floor(minY),
                Math.max(1, (int) Math.ceil(maxX - minX)),
                Math.max(1, (int) Math.ceil(maxY - minY))
        ).transformMaxBounds(pose);
    }

    record IconQuad(
            float x0,
            float y0,
            float u0,
            float v0,
            float x1,
            float y1,
            float u1,
            float v1,
            float x2,
            float y2,
            float u2,
            float v2,
            float x3,
            float y3,
            float u3,
            float v3,
            int color
    ) {
    }

    private record PatternBatch(BuiltHalfIconRectangle rectangle, List<IconQuad> quads) {
    }
}
