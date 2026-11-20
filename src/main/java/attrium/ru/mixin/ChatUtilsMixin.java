package attrium.ru.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import attrium.ru.api.module.impl.misc.ChatUtilsModule;

import java.util.List;

/**
 * ChatUtils Mixin for ChatComponent (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Hooks:
 *  - Smart Hide Chat filtering (Nearby Players, Mentions, Friends, Direct Messages)
 *  - [x2] Anti-Spam stacking + [HH:mm] timestamps
 *  - Clean transparent chat background (fill alpha -> 0x00000000)
 *  - 500-line extended history & no-clear on world/lobby switches
 *  - Click-to-Copy while ChatScreen is open
 */
@Mixin(ChatComponent.class)
public class ChatUtilsMixin {

    @Shadow
    @Final
    private List<GuiMessage> allMessages;

    @Shadow
    @Final
    private List<GuiMessage.Line> trimmedMessages;

    @Shadow
    private int chatScrollbarPos;

    @Inject(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void attrium$filterIncomingChat(
            Component message,
            @Nullable MessageSignature signature,
            @Nullable GuiMessageTag tag,
            CallbackInfo ci
    ) {
        if (!ChatUtilsModule.isActive()) {
            return;
        }
        if (!ChatUtilsModule.shouldAllowIncomingMessage(message)) {
            ci.cancel();
        }
    }

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0
    )
    private Component attrium$stackAndTimestampChat(Component message) {
        if (!ChatUtilsModule.isActive()) {
            return message;
        }
        return ChatUtilsModule.transformIncomingMessage(
                message,
                this.allMessages,
                this.trimmedMessages,
                (ChatComponent) (Object) this
        );
    }

    @ModifyConstant(
            method = {"addMessageToDisplayQueue", "addMessageToQueue"},
            constant = @Constant(intValue = 100),
            require = 0
    )
    private int attrium$extendChatHistoryLimit(int original) {
        return ChatUtilsModule.isExtendedHistoryEnabled() ? 500 : original;
    }

    @Inject(method = "clearMessages", at = @At("HEAD"), cancellable = true, require = 0)
    private void attrium$preventWorldSwitchChatClear(boolean clearSentMsgHistory, CallbackInfo ci) {
        if (!clearSentMsgHistory && ChatUtilsModule.isExtendedHistoryEnabled()) {
            ci.cancel();
        }
    }

    @ModifyArg(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"),
            index = 4,
            require = 0
    )
    private int attrium$cleanChatBackground(int color) {
        if (ChatUtilsModule.isCleanBackgroundEnabled()) {
            return 0x00000000;
        }
        return color;
    }

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void attrium$tickChatClickToCopy(CallbackInfo ci) {
        ChatUtilsModule.tickClickToCopy(this.trimmedMessages, this.chatScrollbarPos);
    }
}
