package releon.ru.api.module.impl.visual.particles;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.WorldVertex;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.pipeline.ClientPipelines;

import java.util.HashSet;
import java.util.Set;

final class ParticleRenderer {
    private final Set<RenderType> usedRenderTypes = new HashSet<>();

    void clear() {
        usedRenderTypes.clear();
    }

    void flush(MultiBufferSource.BufferSource provider) {
        for (RenderType type : usedRenderTypes) {
            provider.endBatch(type);
        }
        usedRenderTypes.clear();
    }

    void drawTexture(PoseStack stack, MultiBufferSource.BufferSource provider, Identifier texture, Vec3 pos, Vec3 cameraPos, Quaternionf cameraRotation, float size, float rotation, int color, boolean bloom) {
        RenderType renderType = bloom
                ? ClientPipelines.WORLD_PARTICLES_GLOW.apply(texture)
                : RenderTypes.entityTranslucent(texture);
        renderBillboard(stack, provider, renderType, pos, cameraPos, cameraRotation, size, rotation, color);
    }

    void drawBox(Vec3 renderPos, float scale, int color) {
        float boxScale = scale / 4.5F;
        AABB box = new AABB(
                renderPos.x - boxScale,
                renderPos.y - boxScale,
                renderPos.z - boxScale,
                renderPos.x + boxScale,
                renderPos.y + boxScale,
                renderPos.z + boxScale
        );
        Render3D.drawBox(box, color, 0.1F, true, true, true);
    }

    private void renderBillboard(PoseStack stack, MultiBufferSource.BufferSource provider, RenderType renderType, Vec3 pos, Vec3 cameraPos, Quaternionf cameraRotation, float size, float rotation, int color) {
        if (size <= 0.001F || ColorUtil.getAlpha(color) <= 0) {
            return;
        }

        usedRenderTypes.add(renderType);
        VertexConsumer consumer = provider.getBuffer(renderType);

        stack.pushPose();
        stack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);
        stack.mulPose(cameraRotation);
        stack.mulPose(Axis.ZP.rotationDegrees(rotation));
        PoseStack.Pose pose = stack.last();
        float half = size / 2.0F;
        WorldVertex.textured(consumer, pose, -half, -half, 0.0F, 0.0F, 0.0F, color);
        WorldVertex.textured(consumer, pose, half, -half, 0.0F, 1.0F, 0.0F, color);
        WorldVertex.textured(consumer, pose, half, half, 0.0F, 1.0F, 1.0F, color);
        WorldVertex.textured(consumer, pose, -half, half, 0.0F, 0.0F, 1.0F, color);
        stack.popPose();
    }
}
