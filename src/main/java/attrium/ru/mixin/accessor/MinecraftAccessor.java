package attrium.ru.mixin.accessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Invoker("startAttack")
    boolean attrium$startAttack();

    @Invoker("startUseItem")
    void attrium$startUseItem();

    @Invoker("updateLevelInEngines")
    void attrium$updateLevelInEngines(ClientLevel level);

    @Accessor("rightClickDelay")
    void attrium$setRightClickDelay(int delay);
}
