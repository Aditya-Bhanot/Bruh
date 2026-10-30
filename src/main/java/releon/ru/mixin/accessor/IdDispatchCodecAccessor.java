package releon.ru.mixin.accessor;

import net.minecraft.network.codec.IdDispatchCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(IdDispatchCodec.class)
public interface IdDispatchCodecAccessor {
    @Accessor("byId")
    List<?> releon$getById();
}
