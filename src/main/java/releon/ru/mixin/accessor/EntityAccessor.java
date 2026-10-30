package releon.ru.mixin.accessor;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("horizontalCollision")
    boolean releon$isHorizontalCollision();

    @Accessor("verticalCollision")
    boolean releon$isVerticalCollision();

    @Accessor("fluidHeight")
    Object2DoubleMap<TagKey<Fluid>> releon$getFluidHeight();

    @Accessor("fluidOnEyes")
    Set<TagKey<Fluid>> releon$getSubmergedFluidTag();
}
