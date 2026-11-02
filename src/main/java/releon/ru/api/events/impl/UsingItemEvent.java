package releon.ru.api.events.impl;

import net.minecraft.world.InteractionHand;
import releon.ru.api.events.CancellableEvent;

public final class UsingItemEvent extends CancellableEvent {
    public static final byte ON = 0;
    public static final byte POST = 1;

    private final byte type;
    private final InteractionHand hand;

    public UsingItemEvent(byte type) {
        this(type, null);
    }

    public UsingItemEvent(byte type, InteractionHand hand) {
        this.type = type;
        this.hand = hand;
    }

    public byte getType() {
        return type;
    }

    public InteractionHand getHand() {
        return hand;
    }
}
