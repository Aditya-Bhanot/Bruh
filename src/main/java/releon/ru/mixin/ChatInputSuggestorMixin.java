package releon.ru.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.events.impl.TabCompleteEvent;
import releon.ru.manager.Manager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@Mixin(CommandSuggestions.class)
public abstract class ChatInputSuggestorMixin {
    @Shadow
    @Final
    EditBox input;

    @Shadow
    @Final
    private List<FormattedCharSequence> commandUsage;

    @Shadow
    private ParseResults<?> currentParse;

    @Shadow
    private CompletableFuture<Suggestions> pendingSuggestions;

    @Shadow
    private CommandSuggestions.SuggestionsList suggestions;

    @Shadow
    boolean keepSuggestions;

    @Shadow
    public abstract void showSuggestions(boolean narrateFirstSuggestion);

    @Inject(method = "updateCommandInfo", at = @At("HEAD"), cancellable = true)
    private void releon$updateCommandInfo(CallbackInfo ci) {
        String text = this.input.getValue();
        int cursor = this.input.getCursorPosition();
        String prefix = text.substring(0, Math.min(text.length(), cursor));

        TabCompleteEvent event = Manager.postEvent(new TabCompleteEvent(prefix));
        if (event.isCancelled()) {
            ci.cancel();
            return;
        }

        if (event.getCompletions() == null) {
            return;
        }

        ci.cancel();
        this.currentParse = null;

        if (this.keepSuggestions) {
            return;
        }

        this.input.setSuggestion(null);
        this.suggestions = null;
        this.commandUsage.clear();

        String[] completions = event.getCompletions();
        if (completions.length == 0) {
            this.pendingSuggestions = Suggestions.empty();
            return;
        }

        int lastSpace = prefix.lastIndexOf(' ');
        StringRange range = StringRange.between(lastSpace + 1, prefix.length());
        List<Suggestion> suggestionList = Stream.of(completions)
                .map(value -> new Suggestion(range, value))
                .toList();
        Suggestions suggestions = new Suggestions(range, suggestionList);
        this.pendingSuggestions = CompletableFuture.completedFuture(suggestions);
        this.showSuggestions(true);
    }
}
