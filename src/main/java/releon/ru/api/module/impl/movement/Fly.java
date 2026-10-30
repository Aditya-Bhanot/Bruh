package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.move.MoveUtil;

public final class Fly extends Module {
    private final ModeSetting mode = register(new ModeSetting("Mode", "Flight mode.", "Vanilla", "Vanilla", "Dragon"));
    private final NumberSetting horizontal = register(new NumberSetting("Horizontal", "Horizontal speed.", 1.0, 0.1, 5.0, 0.1));
    private final NumberSetting vertical = register(new NumberSetting("Vertical", "Vertical speed.", 0.6, 0.1, 3.0, 0.1));

    public Fly() {
        super("Fly", "Flight movement module.", ModuleCategory.MOVEMENT);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null) {
            return;
        }
        if (mode.is("Dragon") && (!client.player.getAbilities().mayfly || !client.player.getAbilities().flying)) {
            return;
        }
        double[] dir = MoveUtil.calculateDirection(horizontal.getValue());
        double y = 0.0D;
        if (client.options.keyJump.isDown()) {
            y += vertical.getValue();
        }
        if (client.options.keyShift.isDown()) {
            y -= vertical.getValue();
        }
        if (!MoveUtil.hasPlayerMovement()) {
            dir[0] = 0.0D;
            dir[1] = 0.0D;
        }
        client.player.setDeltaMovement(new Vec3(dir[0], y, dir[1]));
    }
}
