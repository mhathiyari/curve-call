# CurveCall ProGuard Rules

# ---- Hilt / Dagger ----
# Keep Hilt-generated components
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclasseswithmembers class * {
    @dagger.* <methods>;
}
-keepclasseswithmembers class * {
    @javax.inject.* <methods>;
}
-keepclasseswithmembers class * {
    @javax.inject.* <fields>;
}

# ---- OkHttp ----
-dontwarn okhttp3.internal.platform.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ---- Kotlin Serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Kotlin Coroutines ----
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ---- GraphHopper (on-device routing) ----
# Keep core routing classes (uses reflection for profile/config loading)
-keep class com.graphhopper.** { *; }
-keep class com.carrotsearch.hppc.** { *; }
-keep class org.locationtech.jts.** { *; }
# Jackson is used by GraphHopper for custom model JSON parsing
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
# Suppress warnings for excluded/unavailable dependencies
-dontwarn java.awt.**
-dontwarn javax.xml.stream.**
-dontwarn org.apache.xmlgraphics.**
-dontwarn org.openstreetmap.osmosis.**
-dontwarn org.codehaus.stax2.**
-dontwarn com.ctc.wstx.**

# ---- SLF4J / Logback Android ----
-keep class org.slf4j.** { *; }
-keep class ch.qos.logback.** { *; }
-dontwarn ch.qos.logback.core.net.**

# ---- CurveCall engine and narration types (data classes used across modules) ----
-keep class com.curvecall.engine.types.** { *; }
-keep class com.curvecall.narration.types.** { *; }
-keep class com.curvecall.data.routing.** { *; }

# ---- Compose ----
-keep class androidx.compose.** { *; }

# ---- General ----
-keepattributes Signature
-keepattributes Exceptions
-keepattributes SourceFile,LineNumberTable
