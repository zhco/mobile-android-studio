# Mobile Android Studio ProGuard Rules

# Keep code-server binaries (loaded via JNI/process)
-keep class com.marvis.mas.server.** { *; }
-keep class com.marvis.mas.build.** { *; }

# WebView JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
