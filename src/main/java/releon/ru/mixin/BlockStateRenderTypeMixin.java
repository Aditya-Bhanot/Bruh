package releon.ru.mixin;

import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.module.impl.visual.NoRender;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateRenderTypeMixin {
    @Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$getRenderShape(CallbackInfoReturnable<RenderShape> cir) {
        NoRender noRender = NoRender.getInstance();
        if (noRender != null && noRender.shouldHidePlants() && noRender.isPlantState((BlockState) (Object) this)) {
            cir.setReturnValue(RenderShape.INVISIBLE);
        }
    }
}
