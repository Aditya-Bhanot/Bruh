package attrium.ru.mixin;

import attrium.ru.access.GuiRenderStateLayerAccessor;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin implements GuiRenderStateLayerAccessor {
    @Unique
    private int attrium$layerSerial;

    @Override
    public int attrium$getLayerSerial() {
        return attrium$layerSerial;
    }

    @Inject(method = "nextStratum", at = @At("RETURN"))
    private void attrium$trackNextStratum(CallbackInfo ci) {
        attrium$layerSerial++;
    }

    @Inject(method = "up", at = @At("RETURN"))
    private void attrium$trackUpLayer(CallbackInfo ci) {
        attrium$layerSerial++;
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void attrium$resetLayerSerial(CallbackInfo ci) {
        attrium$layerSerial = 0;
    }
}
