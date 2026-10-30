package releon.ru.utils.render.world.targetesp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.pipeline.ClientPipelines;

public final class GhostTargetEspRenderer {
    private static final Identifier GHOST_TEXTURE = Identifier.fromNamespaceAndPath("releon", "textures/particles/ghost-glow.png");


    private GhostTargetEspRenderer() {
    }

    public static void render(PoseStack stack, MultiBufferSource.BufferSource provider, TargetEspRenderContext context) {
        VertexConsumer consumer = provider.getBuffer(ClientPipelines.GHOSTS_ESP.apply(GHOST_TEXTURE));

        stack.pushPose();
        stack.translate(0.0f, context.target().getBbHeight() * 0.60f, 0.0f);
        particle(stack, consumer, context, TransformationType.FIRST);
        particle(stack, consumer, context, TransformationType.SECOND);
        particle(stack, consumer, context, TransformationType.THIRD);
        stack.popPose();
    }

    private static void particle(PoseStack stack, VertexConsumer consumer, TargetEspRenderContext context, TransformationType transformation) {
        double radius = 0.8f;
        double distance = 20.0;
        float particleSize = 1;
        int alphaFactor = 1;

        int particleCount = Math.max(4, Math.round(10 * context.alpha()));
        for (int i = 0; i < particleCount; i++) {
            stack.pushPose();

            double angle = 0.2 * ((context.frameTimeMs() * 0.55) - (i * distance)) / 40.0;
            double sin = Math.sin(angle) * radius;
            double cos = Math.cos(angle) * radius;

            Vec3 trans = transformation.make(sin, cos);
            stack.translate(trans.x, trans.y, trans.z);
            stack.mulPose(Minecraft.getInstance().gameRenderer.getMainCamera().rotation());

            float spinRotation = (context.frameTimeMs() * 0.1f) - (i * 10.0f);
            stack.mulPose(Axis.ZP.rotationDegrees(spinRotation));
            stack.translate(particleSize / 2.0f, particleSize / 2.0f, 0.0f);

            int currentAlpha = (int) ((255 - i * alphaFactor) * context.alpha());
            int startColor = ColorUtil.withAlpha(context.primaryColor(), currentAlpha);
            int endColor = ColorUtil.withAlpha(context.secondaryColor(), currentAlpha);

            PoseStack.Pose entry = stack.last();
            consumer.addVertex(entry, 0.0f, -particleSize, 0.0f).setUv(0.0f, 0.0f).setColor(endColor);
            consumer.addVertex(entry, -particleSize, -particleSize, 0.0f).setUv(0.0f, 1.0f).setColor(endColor);
            consumer.addVertex(entry, -particleSize, 0.0f, 0.0f).setUv(1.0f, 1.0f).setColor(startColor);
            consumer.addVertex(entry, 0.0f, 0.0f, 0.0f).setUv(1.0f, 0.0f).setColor(startColor);

            stack.popPose();
        }
    }

    public static void endBatch(MultiBufferSource.BufferSource provider) {
        provider.endBatch(ClientPipelines.GHOSTS_ESP.apply(GHOST_TEXTURE));
    }

    private enum TransformationType {
        FIRST {
            @Override
            Vec3 make(double sin, double cos) {
                return new Vec3(sin, cos, -cos);
            }
        },
        SECOND {
            @Override
            Vec3 make(double sin, double cos) {
                return new Vec3(-sin, sin, -cos);
            }
        },
        THIRD {
            @Override
            Vec3 make(double sin, double cos) {
                return new Vec3(-sin, -sin, cos);
            }
        };

        abstract Vec3 make(double sin, double cos);
    }
}
