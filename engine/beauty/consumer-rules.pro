# MediaPipe Tasks: the native graph calls back into Java (result/error listeners, packet getters) through JNI
# and parses its options and results as protobuf-lite messages via reflection. The AAR ships no consumer rules.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**
