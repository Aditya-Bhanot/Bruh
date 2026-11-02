package releon.ru.mixin.accessor;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderPipelines.class)
public interface MixinRenderPipelines {
    @Accessor("LINES_SNIPPET")
    static RenderPipeline.Snippet releon$getLinesSnippet() {
        throw new AssertionError();
    }
}
