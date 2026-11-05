# The ZKTeco native libraries bind to Java classes by exact package and class
# name through JNI. Obfuscating or stripping any of them breaks the binding at
# runtime with an UnsatisfiedLinkError that gives no hint as to the cause.
-keep class com.zkteco.** { *; }
-keepclasseswithmembernames class com.zkteco.** {
    native <methods>;
}
-dontwarn com.zkteco.**

# Room entities are read reflectively by the generated DAO code.
-keep class com.example.attendance.data.** { *; }
