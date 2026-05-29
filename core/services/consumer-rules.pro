# Consumer-side ProGuard rules for :core:services.
#
# AGP merges this into the rules of any application that consumes the
# library. Layer 3 adds a JNI-bridge audio anchor and a foreground service
# whose Service class name is referenced from the manifest — both surfaces
# must survive R8.

# JNI methods are looked up by name from native code. The audio anchor
# eventually feeds the native engine in Layer 4+, so keep all `native`
# methods unobfuscated.
-keepclasseswithmembernames class * {
    native <methods>;
}

# The foreground service and accessibility service are instantiated by the
# Android framework via class name; obfuscating the class would prevent the
# system from binding to them.
-keep class com.vyzorix.audiorouter.services.foreground.PersistentAudioService { *; }
-keep class com.vyzorix.audiorouter.services.foreground.BootReceiver { *; }
-keep class com.vyzorix.audiorouter.services.accessibility.RouterAccessibilityService { *; }
