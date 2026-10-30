package releon.ru.api.module.impl.player;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.NumberSetting;

import java.util.ArrayDeque;
import java.util.Deque;

public final class FakePing extends Module {
    private final NumberSetting pingDelay = register(new NumberSetting(
            "Ping Delay",
            "Delays keep alive packets sent back to the server.",
            1000.0,
            0.0,
            10000.0,
            50.0
    ));

    private final Deque<PendingKeepAlive> pendingKeepAlives = new ArrayDeque<>();
    private boolean releasing;

    public FakePing() {
        super("Fake Ping", "Artificially delays keep alive packets.", ModuleCategory.PLAYER);
    }

    @Override
    protected void onEnable() {
        pendingKeepAlives.clear();
        releasing = false;
    }

    @Override
    protected void onDisable() {
        flushPendingKeepAlives();
        pendingKeepAlives.clear();
        releasing = false;
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isSend() || releasing || mc.player == null) {
            return;
        }

        if (event.getPacket() instanceof ServerboundKeepAlivePacket || event.getPacket() instanceof ServerboundPongPacket) {
            queuePacket(event.getPacket());
            event.cancel();
        }
    }

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.getConnection() == null) {
            pendingKeepAlives.clear();
            return;
        }

        long now = System.currentTimeMillis();
        while (!pendingKeepAlives.isEmpty() && pendingKeepAlives.peekFirst().sendAtMs() <= now) {
            sendQueuedPacket(pendingKeepAlives.removeFirst().packet());
        }
    }

    private void queuePacket(Packet<?> packet) {
        long sendAtMs = System.currentTimeMillis() + Math.max(0L, Math.round(pingDelay.getValue()));
        pendingKeepAlives.addLast(new PendingKeepAlive(packet, sendAtMs));
    }

    private void flushPendingKeepAlives() {
        if (mc.getConnection() == null) {
            return;
        }

        while (!pendingKeepAlives.isEmpty()) {
            sendQueuedPacket(pendingKeepAlives.removeFirst().packet());
        }
    }

    private void sendQueuedPacket(Packet<?> packet) {
        releasing = true;
        try {
            mc.getConnection().send(packet);
        } finally {
            releasing = false;
        }
    }

    private record PendingKeepAlive(Packet<?> packet, long sendAtMs) {
    }
}
