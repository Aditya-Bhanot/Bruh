package releon.ru.mixin.accessor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GuiGraphics.class)
public interface GuiGraphicsExtractorAccessor {
    @Accessor("guiRenderState")
    GuiRenderState releon$getGuiRenderState();
}
