package releon.ru.utils.render.animation.modernfx;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Easings {
    public final Easing LINEAR = new Easing() {
        @Override
        public double ease(double value) {
            return value;
        }
    };

    public final Easing QUAD_OUT = new Easing() {
        @Override
        public double ease(double value) {
            return 1.0 - Math.pow(1.0 - value, 2);
        }
    };

    public final Easing CUBIC_OUT = new Easing() {
        @Override
        public double ease(double value) {
            return 1.0 - Math.pow(1.0 - value, 3);
        }
    };

    public final Easing EXPO_IN = new Easing() {
        @Override
        public double ease(double value) {
            return value == 0 ? 0 : Math.pow(2.0, 10.0 * value - 10.0);
        }
    };

    public final Easing EXPO_OUT = new Easing() {
        @Override
        public double ease(double value) {
            return value == 1 ? 1 : 1.0 - Math.pow(2.0, -10.0 * value);
        }
    };

    public final Easing EXPO_IN_OUT = new Easing() {
        @Override
        public double ease(double value) {
            if (value == 0 || value == 1) {
                return value;
            }
            return value < 0.5
                    ? Math.pow(2.0, 20.0 * value - 10.0) / 2.0
                    : (2.0 - Math.pow(2.0, -20.0 * value + 10.0)) / 2.0;
        }
    };

    public final Easing SINE_OUT = new Easing() {
        @Override
        public double ease(double value) {
            return Math.sin(value * Math.PI / 2.0);
        }
    };

    public final Easing BACK_OUT = new Easing() {
        @Override
        public double ease(double value) {
            double c1 = 1.70158;
            double c3 = c1 + 1;
            return 1.0 + c3 * Math.pow(value - 1.0, 3.0) + c1 * Math.pow(value - 1.0, 2.0);
        }
    };
}
