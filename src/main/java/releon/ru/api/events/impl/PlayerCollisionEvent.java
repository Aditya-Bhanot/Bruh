package releon.ru.api.events.impl;

import net.minecraft.world.level.block.Block;
import releon.ru.api.events.CancellableEvent;

public final class PlayerCollisionEvent extends CancellableEvent {
    private final Block block;

    public PlayerCollisionEvent(Block block) {
        this.block = block;
    }

    public Block getBlock() {
        return block;
    }
}
