package releon.ru.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import releon.ru.api.module.impl.visual.NoRender;

@Mixin(targets = "net.minecraft.client.gui.Gui$HeartType")
public abstract class GuiHeartTypeMixin {
    @WrapOperation(method = "forPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;hasEffect(Lnet/minecraft/core/Holder;)Z"), require = 0)
    private static boolean releon$witherHearts(Player player, Holder<MobEffect> effect, Operation<Boolean> original) {
        if (effect == MobEffects.WITHER && NoRender.isActive("Wither Hearts")) {
            return false;
        }
        return original.call(player, effect);
    }
}
