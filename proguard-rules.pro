# Attrium Fabric 1.21.11 ProGuard Rules (Java 21 + FoldCraftLauncher Safe)
-dontshrink
-dontoptimize
-dontwarn **
-ignorewarnings
-useuniqueclassmembernames
-overloadaggressively
-repackageclasses 'attrium.ru.z'
-allowaccessmodification
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 1. Keep Fabric Entrypoints
-keep class * implements net.fabricmc.api.ModInitializer { *; }
-keep class * implements net.fabricmc.api.ClientModInitializer { *; }
-keep class * implements net.fabricmc.api.DedicatedServerModInitializer { *; }

# 2. Keep All Mixins untouched (required by SpongePowered Mixin runtime)
-keep @org.spongepowered.asm.mixin.Mixin class * { *; }
-keep class attrium.ru.mixin.** { *; }

# 3. Keep Modules, Settings & Reflection-Accessed Classes
-keep class attrium.ru.api.module.** { *; }
-keep class attrium.ru.api.settings.** { *; }
-keep class attrium.ru.screens.GlassClickGuiScreen { *; }
-keep class attrium.ru.manager.** { *; }
-keep class attrium.ru.utils.** { *; }

# 4. Obfuscate attrium.ru.auth.** (DeviceFingerprint, AuthService, AuthLoginScreen)
#    All class names, method names, and field names inside attrium.ru.auth are renamed
#    and repackaged into attrium.ru.z.* with debug line/variable tables stripped!
-keepclassmembers class * extends net.minecraft.client.gui.screens.Screen {
    public void render(...);
    public void renderBackground(...);
    protected void init();
    public void tick();
    public boolean shouldCloseOnEsc();
    public boolean isPauseScreen();
}
