package releon.ru.api.module.impl.combat.aura;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;

public class AngleConfig {
    public static final AngleConfig DEFAULT = new AngleConfig(new LinearConstructor(), true, true);

    private final RotateConstructor angleSmooth;
    private final boolean moveCorrection;
    private final boolean freeCorrection;
    private final int resetThreshold = 1;

    public AngleConfig(boolean moveCorrection, boolean freeCorrection) {
        this(new LinearConstructor(), moveCorrection, freeCorrection);
    }

    public AngleConfig(RotateConstructor angleSmooth, boolean moveCorrection, boolean freeCorrection) {
        this.angleSmooth = angleSmooth;
        this.moveCorrection = moveCorrection;
        this.freeCorrection = freeCorrection;
    }

    public AngleConstructor createRotationPlan(Angle angle, Vec3 vec, Entity entity, int reset) {
        return new AngleConstructor(angle, vec, entity, angleSmooth, reset, resetThreshold, moveCorrection, freeCorrection);
    }
}
