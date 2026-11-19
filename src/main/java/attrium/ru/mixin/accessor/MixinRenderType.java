package attrium.ru.mixin.accessor;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderType.class)
public interface MixinRenderType {
    @Invoker("create")
    static RenderType attrium$create(String name, RenderSetup setup) {
        throw new AssertionError();
    }
}
