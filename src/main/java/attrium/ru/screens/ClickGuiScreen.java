package attrium.ru.screens;

/**
 * Lightweight bridge routing legacy ClickGuiScreen references directly to GlassClickGuiScreen.
 */
public class ClickGuiScreen extends GlassClickGuiScreen {
    private static ClickGuiScreen instance;

    public ClickGuiScreen() {
        super();
        instance = this;
    }

    public static ClickGuiScreen getInstance() {
        if (instance == null) {
            instance = new ClickGuiScreen();
        }
        return instance;
    }
}
