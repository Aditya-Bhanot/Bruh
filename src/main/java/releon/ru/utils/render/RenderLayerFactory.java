package releon.ru.utils.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import releon.ru.mixin.accessor.MixinRenderType;

public final class RenderLayerFactory {
    private RenderLayerFactory() {
    }

    public static RenderType create(String name, int expectedBufferSize, RenderPipeline pipeline) {
        return create(name, expectedBufferSize, pipeline, null);
    }

    public static RenderType create(String name, int expectedBufferSize, RenderPipeline pipeline, @Nullable Identifier texture) {
        RenderSetup.RenderSetupBuilder builder = RenderSetup.builder(pipeline).bufferSize(expectedBufferSize);
        if (texture != null) {
            builder.withTexture("Sampler0", texture);
        }
        return MixinRenderType.releon$create(name, builder.createRenderSetup());
    }
}
