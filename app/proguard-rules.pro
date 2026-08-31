# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontobfuscate

# sherpa-onnx's Android JNI bridge reads recognizer/VAD configuration fields by
# their Java names. Keep the public API members intact in minified release
# builds; otherwise native model creation fails with NoSuchFieldError.
-keep class com.k2fsa.sherpa.onnx.** { *; }

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# AGP 9
-keep class com.google.mlkit.nl.languageid.** { *; }
-keep class com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar { *; }
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar { *; }

-keep class androidx.work.impl.WorkDatabase_Impl {
    <init>();
}

-keep class androidx.work.OverwritingInputMerger {
    <init>();
}

# Needed for Android 6 when using AGP 9
-keep class androidx.navigation.fragment.NavHostFragment {
    *;
}
