package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import releon.ru.utils.render.WorldVertex;

final class TargetEspQuadRenderer {
    private TargetEspQuadRenderer() {
    }

    static void billboard(PoseStack stack, MultiBufferSource.BufferSource provider, Identifier texture, float y, float scale, float rotation, int c1, int c2, int c3, int c4) {
        VertexConsumer consumer = provider.getBuffer(RenderTypes.entityTranslucent(texture));
        Quaternionf cameraRotation = Minecraft.getInstance().gameRenderer.getMainCamera().rotation();
        stack.pushPose();
        stack.translate(0.0f, y, 0.0f);
        stack.mulPose(cameraRotation);
        if (rotation != 0.0f) {
            stack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(rotation));
        }
        stack.scale(scale, scale, 1.0f);

        PoseStack.Pose pose = stack.last();
        WorldVertex.textured(consumer, pose, -1.0f, -1.0f, 0.0f, 0.0f, 0.0f, c1);
        WorldVertex.textured(consumer, pose, -1.0f, 1.0f, 0.0f, 0.0f, 1.0f, c2);
        WorldVertex.textured(consumer, pose, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f, c3);
        WorldVertex.textured(consumer, pose, 1.0f, -1.0f, 0.0f, 1.0f, 0.0f, c4);
        stack.popPose();
    }

    static void end(MultiBufferSource.BufferSource provider, Identifier texture) {
        provider.endBatch(RenderTypes.entityTranslucent(texture));
    }
}
