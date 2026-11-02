package releon.ru.mixin.accessor;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.KnownPacksManager;
import net.minecraft.client.multiplayer.RegistryDataCollector;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.flag.FeatureFlagSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientConfigurationPacketListenerImpl.class)
public interface ClientConfigurationPacketListenerAccessor {
    @Accessor("registryDataCollector")
    RegistryDataCollector releon$getRegistryDataCollector();

    @Accessor("receivedRegistries")
    RegistryAccess.Frozen releon$getReceivedRegistries();

    @Accessor("enabledFeatures")
    FeatureFlagSet releon$getEnabledFeatures();

    @Accessor("knownPacks")
    KnownPacksManager releon$getKnownPacks();

    @Accessor("chatState")
    ChatComponent.State releon$getChatState();
}
