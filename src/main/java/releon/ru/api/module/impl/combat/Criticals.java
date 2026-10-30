package releon.ru.api.module.impl.combat;

import com.adl.nativeprotect.Native;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.block.Blocks;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.AttackEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.settings.impl.ModeSetting;

import java.util.concurrent.ThreadLocalRandom;

public class Criticals extends Module {
    private static Criticals instance;
    private final ModeSetting mode = register(new ModeSetting("Mode", "Critical bypass mode.", "ReallyWorld", "ReallyWorld"));

    public Criticals() {
        super("Criticals", "Forces critical hits.", ModuleCategory.COMBAT);
        instance = this;
    }

    public static Criticals getInstance() {
        return instance;
    }

    @SubscribeEvent
    @Native
    private void onAttack(AttackEvent event) {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.player == null || client.level == null || client.player.isInWater() || !mode.is("ReallyWorld")) {
            return;
        }
        if (!client.level.getBlockState(client.player.blockPosition()).is(Blocks.COBWEB)) {
            return;
        }
        if (!client.player.onGround() && client.player.fallDistance == 0.0F) {
            client.player.fallDistance = random(1.0E-5F, 1.0E-4F);
            client.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
                    client.player.getX(),
                    client.player.getY() - client.player.fallDistance,
                    client.player.getZ(),
                    AngleConnection.INSTANCE.getPacketYaw(),
                    AngleConnection.INSTANCE.getPacketPitch(),
                    false,
                    false
            ));
        }
    }

    private static float random(float min, float max) {
        return (float) ThreadLocalRandom.current().nextDouble(min, max);
    }
}
