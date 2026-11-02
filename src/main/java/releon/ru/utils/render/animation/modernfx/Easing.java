package releon.ru.utils.render.animation.modernfx;

@FunctionalInterface
public interface Easing {
    double ease(double value);
}