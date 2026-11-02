package releon.ru.mixin.accessor;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("isDestroying")
    void releon$setDestroying(boolean isDestroying);

    @Accessor("isDestroying")
    boolean releon$isDestroying();

    @Accessor("destroyBlockPos")
    BlockPos releon$getDestroyBlockPos();

    @Invoker("ensureHasSentCarriedItem")
    void releon$ensureHasSentCarriedItem();

    @Accessor("destroyDelay")
    void releon$setDestroyDelay(int destroyDelay);

    @Accessor("destroyProgress")
    float releon$getDestroyProgress();

    @Accessor("destroyProgress")
    void releon$setDestroyProgress(float destroyProgress);
}
