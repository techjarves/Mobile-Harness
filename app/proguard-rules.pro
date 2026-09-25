# Mobile Harness release shrinking rules (ISSUE-015, roadmap 2d).
#
# Shrinking and optimization are enabled; obfuscation stays OFF for now so the
# first R8 releases remain easy to symbolize and debug. Flip to full
# obfuscation once a release has baked in the field.

-dontobfuscate

# JNI (libpocketspawn.so): keep the classes carrying native methods and the
# native method names themselves, or Java-side lookup breaks at runtime.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Strip debug/verbose logging from release builds. This is belt-and-braces on
# top of AppLog — it also silences stray direct android.util.Log.d/v calls in
# dependencies. Warn/error logs are kept.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
