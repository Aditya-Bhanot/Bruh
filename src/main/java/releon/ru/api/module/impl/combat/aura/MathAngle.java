package releon.ru.api.module.impl.combat.aura;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public final class MathAngle {
    private MathAngle() {
    }

    public static Angle fromVec2f(Vec2 vector) {
        return new Angle(vector.y, vector.x);
    }

    public static Angle fromVec3d(Vec3 vector) {
        return new Angle(
                (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(vector.z, vector.x)) - 90.0),
                (float) Mth.wrapDegrees(Math.toDegrees(-Math.atan2(vector.y, Math.hypot(vector.x, vector.z))))
        );
    }

    public static Angle fromTo(Vec3 from, Vec3 to) {
        return fromVec3d(to.subtract(from));
    }

    public static Angle calculateDelta(Angle start, Angle end) {
        return new Angle(
                Mth.wrapDegrees(end.getYaw() - start.getYaw()),
                Mth.wrapDegrees(end.getPitch() - start.getPitch())
        );
    }

    public static Angle calculateAngle(Vec3 to) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return Angle.DEFAULT;
        }
        return fromVec3d(to.subtract(client.player.getEyePosition()));
    }

    public static Angle cameraAngle() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return Angle.DEFAULT;
        }
        return new Angle(client.player.getYRot(), client.player.getXRot());
    }

    public static double difference(Angle first, Angle second) {
        if (first == null || second == null) {
            return 0.0;
        }
        Angle delta = calculateDelta(first, second);
        return Math.hypot(delta.getYaw(), delta.getPitch());
    }
}
