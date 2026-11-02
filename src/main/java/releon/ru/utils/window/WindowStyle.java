package releon.ru.utils.window;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.win32.StdCallLibrary;
import org.lwjgl.glfw.GLFWNativeWin32;

public final class WindowStyle {
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

    private WindowStyle() {
    }

    public interface DwmApi extends StdCallLibrary {
        DwmApi INSTANCE = Native.load("dwmapi", DwmApi.class);

        HRESULT DwmSetWindowAttribute(HWND hwnd, int dwAttribute, Pointer pvAttribute, int cbAttribute);
    }

    public static void setDarkMode(long windowHandle, boolean enabled) {
        if (!isWindows() || windowHandle == 0L) {
            return;
        }

        long hwnd = GLFWNativeWin32.glfwGetWin32Window(windowHandle);
        if (hwnd == 0L) {
            return;
        }

        Memory darkModeEnabled = new Memory(Integer.BYTES);
        darkModeEnabled.setInt(0, enabled ? 1 : 0);
        DwmApi.INSTANCE.DwmSetWindowAttribute(
                new HWND(new Pointer(hwnd)),
                DWMWA_USE_IMMERSIVE_DARK_MODE,
                darkModeEnabled,
                Integer.BYTES
        );
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
