package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;
import releon.ru.utils.move.MoveUtil;

public final class NoWeb extends Module {
    private final ModeSetting webMode = register(new ModeSetting("Mode", "Cobweb bypass mode.", "Grim", "Grim"));

    public NoWeb() {
        super("No Web", "Reduces cobweb slowdown.", ModuleCategory.MOVEMENT);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        if (!webMode.is("Grim") || !PlayerInteractionHelper.isPlayerInBlock(Blocks.COBWEB)) {
            return;
        }
        double[] speed = MoveUtil.calculateDirection(0.35D);
        client.player.push(speed[0], 0.0D, speed[1]);
        client.player.setDeltaMovement(
                speed[0],
                client.options.keyJump.isDown() ? 0.65D : client.options.keyShift.isDown() ? -0.65D : 0.0D,
                speed[1]
        );
    }
}
