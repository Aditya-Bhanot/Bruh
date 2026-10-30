package releon.ru.utils.string;

import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public final class KeyHelper {
    private KeyHelper() {
    }

    public static int getKeyCode(String name) {
        if (name == null || name.isBlank()) {
            return GLFW.GLFW_KEY_UNKNOWN;
        }
        String value = name.trim().toUpperCase(Locale.ROOT).replace(" ", "_");
        if (value.startsWith("MOUSE") || value.startsWith("M")) {
            String digits = value.replace("MOUSE", "").replace("M", "");
            try {
                int mouse = Integer.parseInt(digits);
                return GLFW.GLFW_MOUSE_BUTTON_1 + Math.max(0, mouse - 1);
            } catch (NumberFormatException ignored) {
            }
        }
        return switch (value) {
            case "LMB" -> GLFW.GLFW_MOUSE_BUTTON_LEFT;
            case "RMB" -> GLFW.GLFW_MOUSE_BUTTON_RIGHT;
            case "MMB" -> GLFW.GLFW_MOUSE_BUTTON_MIDDLE;
            case "SPACE" -> GLFW.GLFW_KEY_SPACE;
            case "TAB" -> GLFW.GLFW_KEY_TAB;
            case "ENTER" -> GLFW.GLFW_KEY_ENTER;
            case "SHIFT", "L_SHIFT", "LEFT_SHIFT" -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case "R_SHIFT", "RIGHT_SHIFT" -> GLFW.GLFW_KEY_RIGHT_SHIFT;
            case "CTRL", "CONTROL", "L_CTRL", "LEFT_CONTROL" -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case "R_CTRL", "RIGHT_CONTROL" -> GLFW.GLFW_KEY_RIGHT_CONTROL;
            case "ALT", "L_ALT", "LEFT_ALT" -> GLFW.GLFW_KEY_LEFT_ALT;
            case "R_ALT", "RIGHT_ALT" -> GLFW.GLFW_KEY_RIGHT_ALT;
            case "NONE" -> GLFW.GLFW_KEY_UNKNOWN;
            default -> lookupKeyboard(value);
        };
    }

    public static String getKeyName(int key) {
        if (key == GLFW.GLFW_KEY_UNKNOWN || key < 0) {
            return "None";
        }
        if (key >= GLFW.GLFW_MOUSE_BUTTON_1 && key <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
            return "M" + (key - GLFW.GLFW_MOUSE_BUTTON_1 + 1);
        }
        String name = GLFW.glfwGetKeyName(key, -1);
        if (name != null && !name.isBlank()) {
            return name.toUpperCase(Locale.ROOT);
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) {
            return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
        }
        return String.valueOf(key);
    }

    public static String[] getAllKeyNames() {
        String[] names = new String[26 + 10 + 25 + 8];
        int index = 0;
        for (char c = 'A'; c <= 'Z'; c++) {
            names[index++] = String.valueOf(c);
        }
        for (int i = 0; i <= 9; i++) {
            names[index++] = String.valueOf(i);
        }
        for (int i = 1; i <= 25; i++) {
            names[index++] = "F" + i;
        }
        names[index++] = "LMB";
        names[index++] = "RMB";
        names[index++] = "MMB";
        names[index++] = "M4";
        names[index++] = "M5";
        names[index++] = "SPACE";
        names[index++] = "SHIFT";
        names[index] = "CTRL";
        return names;
    }

    private static int lookupKeyboard(String value) {
        if (value.length() == 1) {
            char c = value.charAt(0);
            if (c >= 'A' && c <= 'Z') {
                return GLFW.GLFW_KEY_A + (c - 'A');
            }
            if (c >= '0' && c <= '9') {
                return GLFW.GLFW_KEY_0 + (c - '0');
            }
        }
        if (value.startsWith("F")) {
            try {
                int f = Integer.parseInt(value.substring(1));
                if (f >= 1 && f <= 25) {
                    return GLFW.GLFW_KEY_F1 + f - 1;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return GLFW.GLFW_KEY_UNKNOWN;
    }
}
