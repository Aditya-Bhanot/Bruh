package releon.ru.utils.render.ui.ripple;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.joml.Matrix3x2f;

public final class RippleRenderState implements GuiElementRenderState {
    private final Matrix3x2f pose;
    private final BuiltRipple ripple;
    private final ScreenRectangle scissorArea;

    public RippleRenderState(Matrix3x2f pose, BuiltRipple ripple, ScreenRectangle scissorArea) {
        this.pose = pose;
        this.ripple = ripple;
        this.scissorArea = scissorArea;
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        if (!RippleRenderer.getInstance().reserve(ripple)) {
            return;
        }

        consumer.addVertexWith2DPose(pose, ripple.x(), ripple.y()).setUv(0.0f, 0.0f);
        consumer.addVertexWith2DPose(pose, ripple.x(), ripple.y() + ripple.height()).setUv(0.0f, 1.0f);
        consumer.addVertexWith2DPose(pose, ripple.x() + ripple.width(), ripple.y() + ripple.height()).setUv(1.0f, 1.0f);
        consumer.addVertexWith2DPose(pose, ripple.x() + ripple.width(), ripple.y()).setUv(1.0f, 0.0f);
    }

    @Override
    public RenderPipeline pipeline() {
        return RippleRenderer.RIPPLE_PIPELINE;
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
        return new ScreenRectangle(
                Math.round(ripple.x()),
                Math.round(ripple.y()),
                Math.round(ripple.width()),
                Math.round(ripple.height())
        ).transformMaxBounds(pose);
    }
}
