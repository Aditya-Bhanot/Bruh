package releon.ru.utils.player;

import net.minecraft.world.phys.Vec3;

public interface Simulation {
    Vec3 pos();

    void tick();
}
