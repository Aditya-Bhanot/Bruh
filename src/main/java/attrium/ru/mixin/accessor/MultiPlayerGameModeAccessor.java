package attrium.ru.mixin.accessor;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("isDestroying")
    void attrium$setDestroying(boolean isDestroying);

    @Accessor("isDestroying")
    boolean attrium$isDestroying();

    @Accessor("destroyBlockPos")
    BlockPos attrium$getDestroyBlockPos();

    @Invoker("ensureHasSentCarriedItem")
    void attrium$ensureHasSentCarriedItem();

    @Accessor("destroyDelay")
    void attrium$setDestroyDelay(int destroyDelay);

    @Accessor("destroyProgress")
    float attrium$getDestroyProgress();

    @Accessor("destroyProgress")
    void attrium$setDestroyProgress(float destroyProgress);
}
