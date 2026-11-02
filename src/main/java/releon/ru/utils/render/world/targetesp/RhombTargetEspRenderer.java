package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.pipeline.ClientPipelines;

public final class RhombTargetEspRenderer {
    private static final Identifier RHOMB_TEXTURE =
            Identifier.fromNamespaceAndPath("releon", "textures/world/cube.png");

    private RhombTargetEspRenderer() {
    }

    public static void render(PoseStack stack, MultiBufferSource.BufferSource provider, TargetEspRenderContext context) {
        VertexConsumer consumer = provider.getBuffer(ClientPipelines.ROMB_ESP.apply(RHOMB_TEXTURE));
        Quaternionf camRot = Minecraft.getInstance().gameRenderer.getMainCamera().rotation();

        stack.pushPose();
        stack.translate(0.0f, context.target().getBbHeight() / 2.0f, 0.0f);
        stack.mulPose(camRot);

        float timeRotation = (context.frameTimeMs() % 6283L) / 1000.0f;
        stack.mulPose(Axis.ZP.rotationDegrees(Mth.sin(timeRotation) * 360.0f));
        stack.scale(0.35f, 0.35f, 1.0f);

        int c1 = ColorUtil.withAlpha(context.primaryColor(), (int) (255.0f * context.alpha()));
        int c2 = ColorUtil.withAlpha(context.secondaryColor(), (int) (255.0f * context.alpha()));

        PoseStack.Pose entry = stack.last();
        consumer.addVertex(entry, -1.0f, -1.0f, 0.0f).setUv(0.0f, 0.0f).setColor(c2);
        consumer.addVertex(entry, -1.0f, 1.0f, 0.0f).setUv(0.0f, 1.0f).setColor(c1);
        consumer.addVertex(entry, 1.0f, 1.0f, 0.0f).setUv(1.0f, 1.0f).setColor(c2);
        consumer.addVertex(entry, 1.0f, -1.0f, 0.0f).setUv(1.0f, 0.0f).setColor(c1);
        stack.popPose();
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.ROMB_ESP.apply(RHOMB_TEXTURE));
    }
}
