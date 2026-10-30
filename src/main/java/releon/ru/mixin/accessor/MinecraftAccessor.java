package releon.ru.mixin.accessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Invoker("startAttack")
    boolean releon$startAttack();

    @Invoker("startUseItem")
    void releon$startUseItem();

    @Invoker("updateLevelInEngines")
    void releon$updateLevelInEngines(ClientLevel level);

    @Accessor("rightClickDelay")
    void releon$setRightClickDelay(int delay);
}
