# UniFFI & JNA keep rules
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# ONNX Runtime & AndroidSVG
-keep class ai.onnxruntime.** { *; }
-keep class com.caverock.androidsvg.** { *; }

# Keep all StandBy application & kotlinx.serialization classes intact (tree-shake unused material-icons-extended)
-keep class com.criperto.standby.** { *; }
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keep,includedescriptorclasses class com.criperto.standby.**$$serializer { *; }
