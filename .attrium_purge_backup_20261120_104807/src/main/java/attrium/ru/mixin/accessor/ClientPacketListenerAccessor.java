package attrium.ru.mixin.accessor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {
    @Accessor("level")
    void attrium$setLevel(ClientLevel level);

    @Accessor("levelData")
    void attrium$setLevelData(ClientLevel.ClientLevelData levelData);

    @Accessor("levelData")
    ClientLevel.ClientLevelData attrium$getLevelData();

    @Accessor("serverChunkRadius")
    void attrium$setServerChunkRadius(int serverChunkRadius);

    @Accessor("serverChunkRadius")
    int attrium$getServerChunkRadius();

    @Accessor("serverSimulationDistance")
    void attrium$setServerSimulationDistance(int serverSimulationDistance);

    @Accessor("serverSimulationDistance")
    int attrium$getServerSimulationDistance();

    @Accessor("levels")
    void attrium$setLevels(Set<ResourceKey<Level>> levels);
}
