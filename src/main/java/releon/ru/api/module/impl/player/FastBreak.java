package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.BlockBreakingEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.mixin.accessor.MultiPlayerGameModeAccessor;

public final class FastBreak extends Module {
    private final NumberSetting multiplier = register(new NumberSetting(
            "Multiplier",
            "Block breaking speed multiplier.",
            1.5,
            1.0,
            3.0,
            0.05
    ));

    public FastBreak() {
        super("Fast Break", "Speeds up block breaking.", ModuleCategory.PLAYER);
    }

    @SubscribeEvent
    private void onBlockBreaking(BlockBreakingEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode == null || client.player == null || client.level == null) {
            return;
        }

        float multiplierValue = multiplier.getFloat();
        if (multiplierValue <= 1.0f) {
            return;
        }

        BlockPos blockPos = event.blockPos();
        BlockState state = client.level.getBlockState(blockPos);
        if (state.isAir()) {
            return;
        }

        MultiPlayerGameModeAccessor controller = (MultiPlayerGameModeAccessor) client.gameMode;
        controller.releon$setDestroyDelay(0);

        float delta = state.getDestroyProgress(client.player, client.player.level(), blockPos);
        float extraProgress = delta * (multiplierValue - 1.0f);
        float breakingProgress = controller.releon$getDestroyProgress() + extraProgress;
        controller.releon$setDestroyProgress(Math.min(1.0f, breakingProgress));
    }
}
