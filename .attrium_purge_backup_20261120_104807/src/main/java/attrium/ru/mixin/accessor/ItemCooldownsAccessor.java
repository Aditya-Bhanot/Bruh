package attrium.ru.mixin.accessor;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemCooldowns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(ItemCooldowns.class)
public interface ItemCooldownsAccessor {
    @Accessor("cooldowns")
    Map<Identifier, ?> attrium$getCooldowns();

    @Accessor("tickCount")
    int attrium$getTickCount();
}