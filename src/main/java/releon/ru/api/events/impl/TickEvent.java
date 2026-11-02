package releon.ru.api.events.impl;

import net.minecraft.client.Minecraft;
import releon.ru.api.events.Event;
import releon.ru.api.events.types.EventPhase;

public abstract class TickEvent implements Event {
    private final Minecraft client;
    private final EventPhase phase;

    private TickEvent(Minecraft client, EventPhase phase) {
        this.client = client;
        this.phase = phase;
    }

    public Minecraft getClient() {
        return client;
    }

    public EventPhase getPhase() {
        return phase;
    }

    public boolean isPre() {
        return phase == EventPhase.PRE;
    }

    public boolean isPost() {
        return phase == EventPhase.POST;
    }

    public static final class Pre extends TickEvent {
        public Pre(Minecraft client) {
            super(client, EventPhase.PRE);
        }
    }

    public static final class Post extends TickEvent {
        public Post(Minecraft client) {
            super(client, EventPhase.POST);
        }
    }
}
