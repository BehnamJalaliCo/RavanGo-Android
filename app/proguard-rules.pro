# ---- RavanGo release rules ----

# Crash reports (Settings → About → Crash reports) must be readable without a mapping file: keep file names, line
# numbers and our own class/method names (code is still shrunk and optimized; only renaming is skipped).
-keepattributes SourceFile,LineNumberTable
-keepnames class com.ravango.** { *; }

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

# MediaPipe tasks and Media3 ship their own consumer rules (engine:beauty adds MediaPipe-specific keeps).

# Cafe Bazaar Poolakey: talks to the Bazaar app over AIDL/Bundles; keep its classes intact.
-keep class ir.cafebazaar.poolakey.** { *; }
-keep class com.android.vending.billing.** { *; }
-dontwarn ir.cafebazaar.poolakey.**
