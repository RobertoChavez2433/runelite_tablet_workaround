# Add project specific ProGuard rules here.

# -------------------------------------------------------------------------
# Strip verbose/debug log calls in release builds
# -------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# -------------------------------------------------------------------------
# kotlinx-serialization: keep @Serializable classes and their generated serializers
# -------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.runelitetablet.**$$serializer { *; }
-keepclassmembers class com.runelitetablet.** {
    *** Companion;
}

# -------------------------------------------------------------------------
# OkHttp: keep platform-specific classes
# -------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# -------------------------------------------------------------------------
# Google Tink / ErrorProne annotations (used by androidx.security:security-crypto)
# -------------------------------------------------------------------------
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# -------------------------------------------------------------------------
# Compose: keep stability annotations
# -------------------------------------------------------------------------
-keep class androidx.compose.runtime.** { *; }

# -------------------------------------------------------------------------
# Keep BroadcastReceiver and Service subclasses (referenced from manifest)
# -------------------------------------------------------------------------
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Service
