package releon.ru.mixin;

import releon.ru.access.GuiRenderStateLayerAccessor;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin implements GuiRenderStateLayerAccessor {
    @Unique
    private int releon$layerSerial;

    @Override
    public int releon$getLayerSerial() {
        return releon$layerSerial;
    }

    @Inject(method = "nextStratum", at = @At("RETURN"))
    private void releon$trackNextStratum(CallbackInfo ci) {
        releon$layerSerial++;
    }

    @Inject(method = "up", at = @At("RETURN"))
    private void releon$trackUpLayer(CallbackInfo ci) {
        releon$layerSerial++;
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void releon$resetLayerSerial(CallbackInfo ci) {
        releon$layerSerial = 0;
    }
}
