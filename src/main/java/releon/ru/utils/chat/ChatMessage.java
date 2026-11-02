package releon.ru.utils.chat;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class ChatMessage {
    private ChatMessage() {
    }

    public static MutableComponent brandmessage() {
        return Component.literal("[Releon] ");
    }

    public static void brandmessage(String message) {
        brandmessage(Component.literal(message));
    }

    public static void brandmessage(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && message != null) {
            mc.player.displayClientMessage(brandmessage().append(message), false);
        }
    }
}
