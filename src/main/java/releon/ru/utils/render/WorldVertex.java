package releon.ru.utils.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

public final class WorldVertex {
    private static final int FULL_BRIGHT = 0x00F000F0;

    private WorldVertex() {
    }

    public static void textured(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int color) {
        consumer.addVertex(pose, x, y, z)
                .setUv(u, v)
                .setColor(color)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }
}
