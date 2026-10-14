# BunTap release builds are not minified by default (isMinifyEnabled = false).
# If you enable R8, keep OpenCV's JNI-bound classes and kotlinx.serialization metadata.
-keep class org.opencv.** { *; }
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.buntap.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.buntap.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
