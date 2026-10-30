package releon.ru.utils.render.animation.modernfx;

public interface AnimationCalculation {
    default double calculation(double value) {
        return 0;
    }
}