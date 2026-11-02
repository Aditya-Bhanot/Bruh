package releon.ru.mixin.accessor;

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
    void releon$setLevel(ClientLevel level);

    @Accessor("levelData")
    void releon$setLevelData(ClientLevel.ClientLevelData levelData);

    @Accessor("levelData")
    ClientLevel.ClientLevelData releon$getLevelData();

    @Accessor("serverChunkRadius")
    void releon$setServerChunkRadius(int serverChunkRadius);

    @Accessor("serverChunkRadius")
    int releon$getServerChunkRadius();

    @Accessor("serverSimulationDistance")
    void releon$setServerSimulationDistance(int serverSimulationDistance);

    @Accessor("serverSimulationDistance")
    int releon$getServerSimulationDistance();

    @Accessor("levels")
    void releon$setLevels(Set<ResourceKey<Level>> levels);
}
