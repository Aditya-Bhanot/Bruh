package releon.ru.utils.render.item;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemStack;

public enum RenderItemGlintMode {
    AUTO,
    OFF,
    ON;

    boolean enabled(ItemStack stack, ItemStackRenderState.FoilType foilType) {
        return enabled(stack, foilType != ItemStackRenderState.FoilType.NONE);
    }

    boolean enabled(ItemStack stack, boolean foil) {
        return switch (this) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> stack != null && (stack.hasFoil() || foil);
        };
    }
}
