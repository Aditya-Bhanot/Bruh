# Attrium Maximum Flat-Package Obfuscation Rules (Fabric 1.21.11 + Java 21 + FoldCraftLauncher Safe)
# Your original source code in src/ is NEVER modified.
-dontshrink
-dontoptimize
-dontwarn **
-ignorewarnings
-useuniqueclassmembernames
-overloadaggressively
-allowaccessmodification

# Fresh randomized dictionaries + random flat package generated every build by Gradle
-obfuscationdictionary build/proguard-dict.txt
-classobfuscationdictionary build/proguard-class-dict.txt

# Strip ALL debug metadata (SourceFile, LineNumberTable, LocalVariableTable, MethodParameters)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 1. Keep ONLY Fabric Entrypoints (required by fabric.mod.json)
-keep class * implements net.fabricmc.api.ModInitializer { *; }
-keep class * implements net.fabricmc.api.ClientModInitializer { *; }
-keep class * implements net.fabricmc.api.DedicatedServerModInitializer { *; }

# 2. Keep ONLY Mixin stubs (required by SpongePowered Mixin runtime; all called managers/modules ARE flattened!)
-keep @org.spongepowered.asm.mixin.Mixin class * { *; }
-keep class attrium.ru.mixin.** { *; }

# 3. Keep ONLY minimal reflection anchors (so ClickGUI module/setting discovery & EventBus never break)
-keep class attrium.ru.Main { *; }
-keep class attrium.ru.manager.Manager { *; }
-keep class attrium.ru.api.module.ModuleManager { *; }
-keep class attrium.ru.manager.ModuleManager { *; }
-keep class attrium.ru.api.module.ModuleRepository { *; }
-keep class attrium.ru.api.module.Module { public *; protected *; }
-keep class attrium.ru.api.module.ModuleCategory { *; }
-keep class attrium.ru.api.settings.** { *; }

# Keep event handler methods annotated with any custom Event annotation (e.g. @EventTarget / @Subscribe)
-keepclassmembers class * {
    @attrium.ru.** <methods>;
}

# Keep ElytraHelper static macro methods & ArrayList suffix hook invoked via reflection
# (the classes themselves ARE STILL RENAMED & FLATTENED into the random package!)
-keepclassmembers class * {
    public static void tickMacroGlobal(...);
    public static void setMacroBoundKey(int);
    public static int getMacroBoundKey();
    public static void onMacroKeyPressedFromCallback(int);
    public java.lang.String getArrayListSuffix();
    public java.lang.String getSuffix();
    public java.lang.String getDisplayInfo();
}

# Keep Minecraft Screen override signatures on obfuscated screen classes (GlassClickGuiScreen, AuthLoginScreen)
-keepclassmembers class * extends net.minecraft.client.gui.screens.Screen {
    public void render(...);
    public void renderBackground(...);
    protected void init();
    public void tick();
    public void onClose();
    public boolean shouldCloseOnEsc();
    public boolean isPauseScreen();
    public boolean mouseScrolled(...);
}

# Keep enum values()/valueOf() for any internal enums that get flattened
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
