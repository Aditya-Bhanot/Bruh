package releon.ru.api.events.impl;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import releon.ru.api.events.Event;

public record BlockBreakingEvent(BlockPos blockPos, Direction direction) implements Event {
}
