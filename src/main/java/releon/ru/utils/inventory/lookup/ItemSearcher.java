package releon.ru.utils.inventory.lookup;

import net.minecraft.world.item.ItemStack;

@FunctionalInterface
public interface ItemSearcher {
    boolean matches(ItemStack stack);
}