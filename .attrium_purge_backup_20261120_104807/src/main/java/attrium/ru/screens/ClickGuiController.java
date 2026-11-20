package attrium.ru.screens;

/**
 * Static scroll & input bridge called by MouseMixin.
 */
public final class ClickGuiController {
    private ClickGuiController() {
    }

    public static void mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        GlassClickGuiScreen.getInstance().onMouseScroll(vertical);
    }
}
