package releon.ru.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.module.impl.visual.Ambience;
import releon.ru.api.module.impl.visual.NoRender;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Shadow
    @Final
    private GpuBuffer emptyBuffer;

    @Shadow
    @Final
    private static int FOG_UBO_SIZE;

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$getFogBuffer(FogRenderer.FogMode fogType, CallbackInfoReturnable<GpuBufferSlice> cir) {
        if (NoRender.isActive("Fog")) {
            cir.setReturnValue(this.emptyBuffer.slice(0, FOG_UBO_SIZE));
        }
    }

    @Inject(method = "getFogType", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$getFogType(Camera camera, CallbackInfoReturnable<FogType> cir) {
        if (camera != null && camera.getFluidInCamera() == FogType.LAVA && NoRender.isActive("Lava")) {
            cir.setReturnValue(FogType.ATMOSPHERIC);
        }
    }

    @ModifyArg(method = "setupFog", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"), index = 2, require = 0)
    private Vector4f releon$modifyCustomFogColor(Vector4f color) {
        Ambience ambience = Ambience.getInstance();
        if (ambience == null || !ambience.hasCustomFog() || color == null) {
            return color;
        }

        int customColor = ambience.getCustomFogColor();
        return new Vector4f(
                ((customColor >> 16) & 0xFF) / 255.0f,
                ((customColor >> 8) & 0xFF) / 255.0f,
                (customColor & 0xFF) / 255.0f,
                color.w
        );
    }

    @Inject(method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lorg/joml/Vector4f;", at = @At("RETURN"), cancellable = true, require = 0)
    private void releon$setupFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float tickProgress, ClientLevel level, CallbackInfoReturnable<Vector4f> cir) {
        Ambience ambience = Ambience.getInstance();
        Vector4f fogColor = cir.getReturnValue();
        if (ambience == null || !ambience.hasCustomFog() || fogColor == null) {
            return;
        }

        int customColor = ambience.getCustomFogColor();
        cir.setReturnValue(new Vector4f(
                ((customColor >> 16) & 0xFF) / 255.0f,
                ((customColor >> 8) & 0xFF) / 255.0f,
                (customColor & 0xFF) / 255.0f,
                fogColor.w
        ));
    }
}
