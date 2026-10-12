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
# valhalla-mobile: JNI + Moshi reflection over its config/request models
-keep class com.valhalla.** { *; }
-keep class com.stadiamaps.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class * { @com.squareup.moshi.* <methods>; @com.squareup.moshi.* <fields>; }
-dontwarn com.valhalla.**
