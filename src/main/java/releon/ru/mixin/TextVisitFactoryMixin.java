package releon.ru.mixin;

import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import releon.ru.api.events.impl.TextFactoryEvent;
import releon.ru.manager.Manager;

@Mixin(StringDecomposer.class)
public class TextVisitFactoryMixin {
    @ModifyArg(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StringDecomposer;iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z", ordinal = 0), index = 0, require = 0)
    private static String releon$adjustText(String text) {
        TextFactoryEvent event = Manager.postEvent(new TextFactoryEvent(text));
        return event.getText();
    }
}
