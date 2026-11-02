package releon.ru.utils.string.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import releon.ru.utils.string.chat.helper.TextHelper;

public final class ChatMessage {
    private ChatMessage() {
    }

    public static MutableComponent brandmessage() {
        return (MutableComponent) TextHelper.applyPredefinedGradient("Releon", "black_light_purple", true);
    }

    public static MutableComponent blockesp() {
        return (MutableComponent) TextHelper.applyPredefinedGradient("Block Esp", "black_light_purple", true);
    }

    public static MutableComponent autobuy() {
        return (MutableComponent) TextHelper.applyPredefinedGradient("Auto Buy", "black_light_purple", true);
    }

    public static MutableComponent autobuiparcer() {
        return (MutableComponent) TextHelper.applyPredefinedGradient("Parce price", "black_light_purple", true);
    }

    public static void brandmessage(String message) {
        brandmessage(Component.literal(message == null ? "" : message));
    }

    public static void brandmessage(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Component prefix = TextHelper.applyPredefinedGradient("Releon -> ", "black_light_purple", true);
            mc.player.displayClientMessage(prefix.copy().append(message == null ? Component.empty() : message), false);
        }
    }

    public static void trackerMessage(Component message) {
        brandmessage(message);
    }

    public static void autobuymessage(String message) {
        prefixed("AutoBuy -> ", Component.literal(message == null ? "" : message));
    }

    public static void autobuymessageSuccess(String message) {
        prefixed("AutoBuy -> ", Component.literal(message == null ? "" : message).setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
    }

    public static void autobuymessageError(String message) {
        prefixed("AutoBuy -> ", Component.literal(message == null ? "" : message).setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
    }

    public static void autobuymessageWarning(String message) {
        prefixed("AutoBuy -> ", Component.literal(message == null ? "" : message).setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
    }

    public static void ancientmessage(String message) {
        prefixed("Ancient Xray -> ", Component.literal(message == null ? "" : message));
    }

    public static void helpmessage(String message) {
        prefixed("Help -> ", Component.literal(message == null ? "" : message));
    }

    public static void swapmessage(String message) {
        prefixed("AutoSwap -> ", Component.literal(message == null ? "" : message));
    }

    private static void prefixed(String prefixText, Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Component prefix = TextHelper.applyPredefinedGradient(prefixText, "black_light_purple", true);
            mc.player.displayClientMessage(prefix.copy().append(message), false);
        }
    }
}
