# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.tripnav.app.**$$serializer { *; }
-keepclassmembers class com.tripnav.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# MapLibre uses JNI
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
