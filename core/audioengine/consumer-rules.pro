# Keep the JNI bridge class + its native method signatures intact for the
# linker — R8 would otherwise rename the methods and break the JNI mapping.
-keep,includedescriptorclasses class com.vyzorix.audiorouter.audioengine.NativeAudioBridge {
    native <methods>;
}
