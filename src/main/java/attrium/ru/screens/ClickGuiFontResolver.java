package attrium.ru.screens;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Items;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * Isolated 1.21.11-safe Font Style Resolver extracted from GlassClickGuiScreen.
 * Resolves custom namespaced font assets (e.g., Inter Medium / SemiBold) once at startup
 * without hard-binding to version-specific ResourceLocation / FontDescription signatures.
 */
public final class ClickGuiFontResolver {
    private ClickGuiFontResolver() {
    }

    public static Style resolve(String namespace, String path) {
        if (namespace == null || path == null) {
            return null;
        }
        try {
            Object sampleRl = BuiltInRegistries.ITEM.getKey(Items.APPLE);
            if (sampleRl == null) {
                return null;
            }
            Class<?> rlClass = sampleRl.getClass();
            String safeNamespace = namespace.toLowerCase(Locale.ROOT);
            String safePath = path.toLowerCase(Locale.ROOT);
            String expectedString = safeNamespace + ":" + safePath;
            Object fontId = null;

            for (Method m : rlClass.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == String.class
                        && m.getParameterTypes()[1] == String.class
                        && rlClass.isAssignableFrom(m.getReturnType())) {
                    try {
                        m.setAccessible(true);
                        Object candidate = m.invoke(null, safeNamespace, safePath);
                        if (candidate != null && expectedString.equals(candidate.toString())) {
                            fontId = candidate;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (fontId == null) {
                for (Method m : rlClass.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == String.class
                            && rlClass.isAssignableFrom(m.getReturnType())) {
                        try {
                            m.setAccessible(true);
                            Object candidate = m.invoke(null, expectedString);
                            if (candidate != null && expectedString.equals(candidate.toString())) {
                                fontId = candidate;
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            if (fontId != null) {
                for (Method wm : Style.class.getDeclaredMethods()) {
                    if (!Modifier.isStatic(wm.getModifiers())
                            && wm.getParameterCount() == 1
                            && wm.getReturnType() == Style.class) {
                        Class<?> paramType = wm.getParameterTypes()[0];
                        if (paramType.isAssignableFrom(rlClass)) {
                            wm.setAccessible(true);
                            Style result = (Style) wm.invoke(Style.EMPTY, fontId);
                            if (result != null) {
                                return result.withBold(false);
                            }
                        } else if (!paramType.isPrimitive()
                                && paramType != String.class
                                && paramType != Boolean.class
                                && paramType != Integer.class) {
                            for (Constructor<?> ctor : paramType.getDeclaredConstructors()) {
                                if (ctor.getParameterCount() == 1
                                        && ctor.getParameterTypes()[0].isAssignableFrom(rlClass)) {
                                    ctor.setAccessible(true);
                                    Object fontDesc = ctor.newInstance(fontId);
                                    wm.setAccessible(true);
                                    Style result = (Style) wm.invoke(Style.EMPTY, fontDesc);
                                    if (result != null) {
                                        return result.withBold(false);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
