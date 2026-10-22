# Keep AnkiDroid API classes used at runtime via reflection-free API calls
-keep class com.ichi2.anki.api.** { *; }
-keep class com.ichi2.anki.FlashCardsContract { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.wilsonnet.droidsentenceminer.**$$serializer { *; }
-keepclassmembers class io.github.wilsonnet.droidsentenceminer.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.wilsonnet.droidsentenceminer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ONNX Runtime (meikiocr): native code looks up its Java classes/methods by name over JNI
-keep class ai.onnxruntime.** { *; }
