package releon.ru.utils.render.animation.modernfx;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public class EasyBackIn extends Animation {
    private float easeAmount = 1.25F;

    public EasyBackIn() {
    }

    public EasyBackIn(int ms, double value, float easeAmount) {
        this.ms = ms;
        this.value = value;
        this.easeAmount = easeAmount;
    }

    @Override
    public double calculation(double value) {
        double x = value / ms;
        float shrink = easeAmount + 1.0F;
        return Math.max(0.0, 1.0 + shrink * Math.pow(x - 1.0, 3.0) + easeAmount * Math.pow(x - 1.0, 2.0));
    }
}
