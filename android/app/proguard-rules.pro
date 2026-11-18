# UniFFI & JNA keep rules
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-dontwarn com.sun.jna.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
