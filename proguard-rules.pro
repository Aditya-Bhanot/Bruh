# Attrium Automated Release Hardening Rules (Fabric 1.21.11 + Java 21 + FoldCraftLauncher Safe)
# Original source files in src/ are NEVER modified.
-dontshrink
-dontoptimize
-dontwarn **
-ignorewarnings
-useuniqueclassmembernames
-overloadaggressively
-allowaccessmodification

# Randomized per-build identifier dictionaries (generated fresh every build by Gradle)
-obfuscationdictionary build/proguard-dict.txt
-classobfuscationdictionary build/proguard-class-dict.txt
-packageobfuscationdictionary build/proguard-pkg-dict.txt

# Strip SourceFile, LineNumberTable, LocalVariableTable, MethodParameters so decompilers
# lose all variable names, file names, and line mappings.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 1. Keep Fabric Entrypoints (required by fabric.mod.json)
-keep class * implements net.fabricmc.api.ModInitializer { *; }
-keep class * implements net.fabricmc.api.ClientModInitializer { *; }
-keep class * implements net.fabricmc.api.DedicatedServerModInitializer { *; }

# 2. Keep Mixin classes & hooks (required by SpongePowered Mixin runtime)
-keep @org.spongepowered.asm.mixin.Mixin class * { *; }
-keep class attrium.ru.mixin.** { *; }

# 3. Keep exact reflection-discovered names (ModuleManager, ClickGUI module/setting discovery)
-keep class attrium.ru.Main { *; }
-keep class attrium.ru.manager.Manager { *; }
-keep class attrium.ru.api.module.Module { *; }
-keep class attrium.ru.api.module.ModuleCategory { *; }
-keep class attrium.ru.api.module.ModuleManager { *; }
-keep class attrium.ru.manager.ModuleManager { *; }
-keep class attrium.ru.api.settings.** { *; }

# Keep module class names & Setting fields (used by ClickGUI reflection),
# while obfuscating all private/internal methods & fields inside every module!
-keep class * extends attrium.ru.api.module.Module {
    <init>(...);
    public *;
    attrium.ru.api.settings.** *;
}

# Keep public cross-mixin hooks in managers & ClickGUI while obfuscating private/internal internals
-keep class attrium.ru.screens.GlassClickGuiScreen {
    public *;
}
-keep class attrium.ru.manager.** {
    public *;
}
-keep class attrium.ru.utils.** {
    public *;
}

# 4. Aggressively randomize & rename attrium.ru.auth.** (classes, methods, fields)
-keepclassmembers class * extends net.minecraft.client.gui.screens.Screen {
    public void render(...);
    public void renderBackground(...);
    protected void init();
    public void tick();
    public boolean shouldCloseOnEsc();
    public boolean isPauseScreen();
}
