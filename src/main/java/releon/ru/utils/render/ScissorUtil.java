package releon.ru.utils.render;

import net.minecraft.client.gui.navigation.ScreenRectangle;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ScissorUtil {
    private static final ThreadLocal<Deque<ScreenRectangle>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private ScissorUtil() {
    }

    public static void push(int left, int top, int right, int bottom) {
        int x0 = Math.min(left, right);
        int y0 = Math.min(top, bottom);
        int x1 = Math.max(left, right);
        int y1 = Math.max(top, bottom);
        ScreenRectangle next = new ScreenRectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        ScreenRectangle current = current();
        if (current != null) {
            next = current.intersection(next);
            if (next == null) {
                next = new ScreenRectangle(x0, y0, 0, 0);
            }
        }
        STACK.get().push(next);
    }

    public static void pop() {
        Deque<ScreenRectangle> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    public static ScreenRectangle current() {
        Deque<ScreenRectangle> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }
}
