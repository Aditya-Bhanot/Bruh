package releon.ru.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.module.impl.visual.NoRender;

@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true, require = 0)
    private static void releon$renderFire(PoseStack matrices, MultiBufferSource vertexConsumers, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (NoRender.isActive("Fire")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderTex", at = @At("HEAD"), cancellable = true, require = 0)
    private static void releon$renderBlockOverlay(TextureAtlasSprite sprite, PoseStack matrices, MultiBufferSource vertexConsumers, CallbackInfo ci) {
        if (NoRender.isActive("Block Overlay") || NoRender.isActive("Lava")) {
            ci.cancel();
        }
    }

    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$displayItemActivation(ItemStack floatingItem, RandomSource random, CallbackInfo ci) {
        if (floatingItem != null && floatingItem.is(Items.TOTEM_OF_UNDYING) && NoRender.isActive("Totem Animation")) {
            ci.cancel();
        }
    }
}
