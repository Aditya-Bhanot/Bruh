package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.pipeline.ClientPipelines;

public final class ChainTargetEspRenderer {
    private static final Identifier CHAIN_TEXTURE =
            Identifier.fromNamespaceAndPath("releon", "textures/features/targetesp/chain.png");

    private ChainTargetEspRenderer() {
    }

    public static void render(PoseStack stack, MultiBufferSource.BufferSource provider, TargetEspRenderContext context) {
        VertexConsumer consumer = provider.getBuffer(ClientPipelines.CHAIN_ESP.apply(CHAIN_TEXTURE));

        float sizeScale = 1.0f;
        float speedScale = 1.0f;
        float animationTime = (context.target().tickCount + context.partialTicks()) * speedScale;
        float impact = TargetEspMath.easeOutCubic(context.chainImpactProgress());
        float baseRadius = context.target().getBbWidth() * 1.35f * sizeScale;
        float bandHeight = Math.max(0.90f, context.target().getBbHeight() * 0.54f * sizeScale);
        float compressedRadius = baseRadius * (1.0f - impact * 0.18f);
        float primaryBandHeight = bandHeight * (1.0f - impact * 0.08f);
        float secondaryBandHeight = bandHeight * 0.80f * (1.0f - impact * 0.12f);
        float roll = Mth.sin(animationTime * 0.22f) * 18.0f;
        float pitch = Mth.cos(animationTime * 0.18f) * 18.0f;
        float spin = animationTime * 5.0f;

        int startColor = ColorUtil.tintForDamage(context.primaryColor(), context.hurtProgress());
        int endColor = ColorUtil.tintForDamage(context.secondaryColor(), context.hurtProgress());

        stack.pushPose();
        stack.translate(0.0f, context.target().getBbHeight() * 0.52f, 0.0f);

        renderChainBand(stack, consumer, compressedRadius * 0.90f, primaryBandHeight, roll, pitch, spin, context.alpha(), startColor, endColor, 0.0f, speedScale);
        renderChainBand(stack, consumer, compressedRadius * 0.98f, secondaryBandHeight, -roll, -pitch, -spin * 0.92f, context.alpha() * 0.92f, endColor, startColor, 0.38f, speedScale);

        stack.popPose();
    }

    private static void renderChainBand(PoseStack stack, VertexConsumer consumer, float radius, float bandHeight, float roll, float pitch, float spin, float alpha, int startColor, int endColor, float uvOffset, float speedScale) {
        stack.pushPose();
        stack.mulPose(Axis.ZP.rotationDegrees(roll));
        stack.mulPose(Axis.XP.rotationDegrees(pitch));

        PoseStack.Pose entry = stack.last();
        float bottomY = -bandHeight * 0.5f;
        float topY = bandHeight * 0.5f;
        int segments = 64;
        float uvRepeats = 4.0f;

        for (int i = 0; i < segments; i++) {
            float progress0 = (float) i / segments;
            float progress1 = (float) (i + 1) / segments;
            float angle0 = (progress0 * 360.0f) + spin;
            float angle1 = (progress1 * 360.0f) + spin;
            float rad0 = angle0 * Mth.DEG_TO_RAD;
            float rad1 = angle1 * Mth.DEG_TO_RAD;

            float x0 = Mth.sin(rad0) * radius;
            float z0 = Mth.cos(rad0) * radius;
            float x1 = Mth.sin(rad1) * radius;
            float z1 = Mth.cos(rad1) * radius;

            float wobble0 = Mth.sin((progress0 * Mth.TWO_PI) + spin * 0.05f) * (0.06f * speedScale);
            float wobble1 = Mth.sin((progress1 * Mth.TWO_PI) + spin * 0.05f) * (0.06f * speedScale);

            int color0 = ColorUtil.scaleAlpha(ColorUtil.lerpColor(startColor, endColor, progress0), alpha);
            int color1 = ColorUtil.scaleAlpha(ColorUtil.lerpColor(startColor, endColor, progress1), alpha);

            float u0 = (progress0 * uvRepeats) + uvOffset;
            float u1 = (progress1 * uvRepeats) + uvOffset;

            consumer.addVertex(entry, x0, bottomY + wobble0, z0).setUv(u0, 0.0f).setColor(color0);
            consumer.addVertex(entry, x1, bottomY + wobble1, z1).setUv(u1, 0.0f).setColor(color1);
            consumer.addVertex(entry, x1, topY + wobble1, z1).setUv(u1, 1.0f).setColor(color1);
            consumer.addVertex(entry, x0, topY + wobble0, z0).setUv(u0, 1.0f).setColor(color0);
        }

        stack.popPose();
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.CHAIN_ESP.apply(CHAIN_TEXTURE));
    }
}
