package releon.ru.mixin.accessor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientLevel.class)
public interface ClientWorldAccessor {
    @Accessor("blockStatePredictionHandler")
    BlockStatePredictionHandler getPendingUpdateManager();

    @Invoker("getBlockStatePredictionHandler")
    BlockStatePredictionHandler releon$pending();
}
