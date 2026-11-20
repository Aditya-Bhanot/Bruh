package attrium.ru.mixin.accessor;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("jumping")
    boolean attrium$isJumping();

    @Accessor("noJumpDelay")
    int attrium$getJumpingCooldown();

    @Accessor("noJumpDelay")
    void attrium$setJumpingCooldown(int value);
}
