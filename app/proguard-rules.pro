# R8 / ProGuard rules for Curbstomp

# Keep model classes & generic signatures for Gson reflection serialization
# (can be removed once Gson is replaced with explicit org.json serialization)
-keepattributes Signature
-keep class com.mhm.curbstomp.data.models.** { *; }

# Strip debug/verbose Log calls in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}