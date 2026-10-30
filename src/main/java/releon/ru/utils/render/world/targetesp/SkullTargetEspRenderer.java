package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.pipeline.ClientPipelines;

public final class SkullTargetEspRenderer {
    private static final Identifier SKULL_TEXTURE =
            Identifier.fromNamespaceAndPath("releon", "textures/features/targetesp/skull.png");

    private SkullTargetEspRenderer() {
    }

    public static void render(PoseStack stack, MultiBufferSource.BufferSource provider, TargetEspRenderContext context) {
        VertexConsumer consumer = provider.getBuffer(ClientPipelines.ROMB_ESP.apply(SKULL_TEXTURE));
        Quaternionf camRot = Minecraft.getInstance().gameRenderer.getMainCamera().rotation();

        float impact = TargetEspMath.easeOutCubic(context.chainImpactProgress());
        float scale = 0.48f * (1.0f - 0.14f * impact);
        float verticalOffset = context.target().getBbHeight() * 0.50f;
        float alpha = Math.min(1.0f, context.alpha() * 1.85f);

        int c1 = ColorUtil.withAlpha(context.primaryColor(), (int) (255.0f * alpha));
        int c2 = ColorUtil.withAlpha(context.secondaryColor(), (int) (255.0f * alpha));
        int c3 = ColorUtil.withAlpha(context.primaryColor(), (int) (250.0f * alpha));
        int c4 = ColorUtil.withAlpha(context.secondaryColor(), (int) (250.0f * alpha));

        stack.pushPose();
        stack.translate(0.0f, verticalOffset, 0.0f);
        stack.mulPose(camRot);
        stack.mulPose(Axis.ZP.rotationDegrees(180.0f));
        stack.scale(scale, scale, 1.0f);

        PoseStack.Pose entry = stack.last();
        consumer.addVertex(entry, -0.8f, -0.8f, 0.0f).setUv(0.0f, 0.0f).setColor(c1);
        consumer.addVertex(entry, -0.8f, 0.8f, 0.0f).setUv(0.0f, 1.0f).setColor(c2);
        consumer.addVertex(entry, 0.8f, 0.8f, 0.0f).setUv(1.0f, 1.0f).setColor(c3);
        consumer.addVertex(entry, 0.8f, -0.8f, 0.0f).setUv(1.0f, 0.0f).setColor(c4);
        stack.popPose();
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.ROMB_ESP.apply(SKULL_TEXTURE));
    }
}
