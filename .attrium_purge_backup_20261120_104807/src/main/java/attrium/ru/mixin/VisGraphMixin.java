package attrium.ru.mixin;

import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import attrium.ru.api.events.impl.ChunkOcclusionEvent;
import attrium.ru.manager.Manager;

@Mixin(VisGraph.class)
public abstract class VisGraphMixin {
    @Inject(method = "setOpaque", at = @At("HEAD"), cancellable = true)
    private void attrium$skipOpaqueOcclusion(BlockPos pos, CallbackInfo ci) {
        ChunkOcclusionEvent event = Manager.postEvent(new ChunkOcclusionEvent());
        if (event.isCancelled()) {
            ci.cancel();
        }
    }
}
