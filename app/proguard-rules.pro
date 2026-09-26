# ---- RavanGo release rules ----

# Strip debug/verbose logging.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# kotlinx.serialization: keep generated serializers for @Serializable models and routes.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ravango.**$$serializer { *; }

# Anthropic Java SDK uses Jackson reflection on its model classes.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn com.anthropic.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn com.github.victools.**
-dontwarn io.swagger.**
-dontwarn com.standardwebhooks.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit / Media3 ship their own consumer rules.
