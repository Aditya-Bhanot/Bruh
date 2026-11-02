package releon.ru.api.events.impl;

import net.minecraft.network.protocol.Packet;
import releon.ru.api.events.CancellableEvent;

public final class PacketEvent extends CancellableEvent {
    public enum Type {
        SEND,
        RECEIVE
    }

    private final Type type;
    private final Packet<?> packet;

    public PacketEvent(Type type, Packet<?> packet) {
        this.type = type;
        this.packet = packet;
    }

    public Type getType() {
        return type;
    }

    public Packet<?> getPacket() {
        return packet;
    }

    public boolean isSend() {
        return type == Type.SEND;
    }

    public boolean isReceive() {
        return type == Type.RECEIVE;
    }
}
