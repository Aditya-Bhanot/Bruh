package releon.ru.mixin.accessor;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.client.gui.components.CommandSuggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mixin(CommandSuggestions.class)
public interface CommandSuggestionsAccessor {
    @Accessor("pendingSuggestions")
    CompletableFuture<Suggestions> getPendingSuggestions();

    @Accessor("pendingSuggestions")
    void setPendingSuggestions(CompletableFuture<Suggestions> value);

    @Accessor("currentParse")
    ParseResults<?> getCurrentParse();

    @Accessor("currentParse")
    void setCurrentParse(ParseResults<?> value);

    @Accessor("currentParseIsCommand")
    void setCurrentParseIsCommand(boolean value);

    @Accessor("currentParseIsMessage")
    void setCurrentParseIsMessage(boolean value);

    @Accessor("commandUsage")
    List<FormattedCharSequence> getCommandUsage();

    @Invoker("updateUsageInfo")
    void invokeUpdateUsageInfo(ParseResults<?> parseResults, Suggestions suggestions);
}
