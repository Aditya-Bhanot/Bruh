package releon.ru.api.module.impl.combat.aura;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.aura.util.MathUtils;

public class Angle {
    public static final Angle DEFAULT = new Angle(0.0f, 0.0f);

    private float yaw;
    private float pitch;

    public Angle(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = Mth.clamp(pitch, -90.0f, 90.0f);
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setPitch(float pitch) {
        this.pitch = Mth.clamp(pitch, -90.0f, 90.0f);
    }

    public Angle adjustSensitivity() {
        double gcd = MathUtils.computeGcd();
        Angle previous = AngleConnection.INSTANCE.getServerAngle();
        return new Angle(
                adjustAxis(yaw, previous.getYaw(), gcd, true),
                Mth.clamp(adjustAxis(pitch, previous.getPitch(), gcd, false), -90.0f, 90.0f)
        );
    }

    public Angle random(float radius) {
        return new Angle(yaw + MathUtils.getRandom(-radius, radius), pitch + MathUtils.getRandom(-radius, radius));
    }

    public Angle addYaw(float yaw) {
        return new Angle(this.yaw + yaw, pitch);
    }

    public Angle addPitch(float pitch) {
        this.pitch = Mth.clamp(this.pitch + pitch, -90.0f, 90.0f);
        return this;
    }

    public Angle of(Angle angle) {
        return new Angle(angle.getYaw(), angle.getPitch());
    }

    public Vec3 toVector() {
        float pitchRadians = pitch * ((float) Math.PI / 180.0f);
        float yawRadians = -yaw * ((float) Math.PI / 180.0f);
        float yawCos = Mth.cos(yawRadians);
        float yawSin = Mth.sin(yawRadians);
        float pitchCos = Mth.cos(pitchRadians);
        float pitchSin = Mth.sin(pitchRadians);
        return new Vec3(yawSin * pitchCos, -pitchSin, yawCos * pitchCos);
    }

    private float adjustAxis(float axisValue, float previousValue, double gcd, boolean wrap) {
        if (gcd <= 0.0D) {
            return wrap ? Mth.wrapDegrees(axisValue) : axisValue;
        }
        float delta = wrap ? Mth.wrapDegrees(axisValue - previousValue) : axisValue - previousValue;
        return previousValue + Math.round(delta / gcd) * (float) gcd;
    }

    @Override
    public String toString() {
        return "Angle{yaw=" + yaw + ", pitch=" + pitch + '}';
    }

    public static final class VecRotation {
        private final Angle angle;
        private final Vec3 vec;

        public VecRotation(Angle angle, Vec3 vec) {
            this.angle = angle;
            this.vec = vec;
        }

        public Angle getAngle() {
            return angle;
        }

        public Vec3 getVec() {
            return vec;
        }
    }
}
