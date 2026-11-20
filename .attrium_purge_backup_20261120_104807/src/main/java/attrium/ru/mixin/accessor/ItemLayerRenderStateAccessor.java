package attrium.ru.mixin.accessor;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.block.model.ItemTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerRenderStateAccessor {
    @Accessor("foilType")
    ItemStackRenderState.FoilType attrium$getFoilType();

    @Accessor("transform")
    ItemTransform attrium$getItemTransform();

    @Accessor("usesBlockLight")
    boolean attrium$getUsesBlockLight();

    @Accessor("specialRenderer")
    SpecialModelRenderer<Object> attrium$getSpecialRenderer();

    @Accessor("tintLayers")
    int[] attrium$getTintLayers();
}
