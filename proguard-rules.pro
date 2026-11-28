# Attrium Maximum Flat-Package Obfuscation Rules (Runs on Mojang-mapped JAR before Loom remapJar)
-dontshrink
-dontoptimize
-dontpreverify
-dontwarn **
-ignorewarnings
-useuniqueclassmembernames
-overloadaggressively
-allowaccessmodification

# Fresh randomized dictionaries generated every build by Gradle
-obfuscationdictionary build/proguard-dict.txt
-classobfuscationdictionary build/proguard-class-dict.txt

# Strip ALL debug metadata (SourceFile, LineNumberTable, LocalVariableTable, MethodParameters)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 1. Keep Fabric Entrypoints (required by fabric.mod.json)
-keep class * implements net.fabricmc.api.ModInitializer { *; }
-keep class * implements net.fabricmc.api.ClientModInitializer { *; }
-keep class * implements net.fabricmc.api.DedicatedServerModInitializer { *; }

# 2. Keep Mixin stubs (required by SpongePowered Mixin; all called managers/modules ARE flattened!)
-keep @org.spongepowered.asm.mixin.Mixin class * { *; }
-keep class attrium.ru.mixin.** { *; }

# 3. Keep minimal reflection & EventBus anchors
-keep class attrium.ru.manager.Manager { *; }
-keep class attrium.ru.api.module.ModuleManager { *; }
-keep class attrium.ru.api.module.Module { public *; protected *; }
-keep class attrium.ru.api.module.ModuleCategory { *; }
-keep class attrium.ru.api.settings.** { *; }
-keep class attrium.ru.api.events.** { *; }

# Keep @EventHandler / custom-annotated event listener methods while still flattening the module classes
-keepclassmembers class * {
    @attrium.ru.** <methods>;
}

# Keep reflection-invoked static macro methods & ArrayList suffix hooks
-keepclassmembers class * {
    public static void tickMacroGlobal(...);
    public static void setMacroBoundKey(int);
    public static int getMacroBoundKey();
    public static void onMacroKeyPressedFromCallback(int);
    public java.lang.String getArrayListSuffix();
    public java.lang.String getSuffix();
    public java.lang.String getDisplayInfo();
}

# Keep enum values()/valueOf() for any flattened internal enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
