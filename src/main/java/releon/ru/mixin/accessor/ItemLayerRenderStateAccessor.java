package releon.ru.mixin.accessor;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.block.model.ItemTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerRenderStateAccessor {
    @Accessor("foilType")
    ItemStackRenderState.FoilType releon$getFoilType();

    @Accessor("transform")
    ItemTransform releon$getItemTransform();

    @Accessor("usesBlockLight")
    boolean releon$getUsesBlockLight();

    @Accessor("specialRenderer")
    SpecialModelRenderer<Object> releon$getSpecialRenderer();

    @Accessor("tintLayers")
    int[] releon$getTintLayers();
}
