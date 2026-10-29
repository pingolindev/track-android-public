# Pingolin Track ProGuard Rules

# Keep UniFFI generated code
-keep class uniffi.** { *; }
-keep class uniffi.pingolin_mobile.** { *; }

# Keep JNA classes
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
# JNA references java.awt.* for desktop AWT integration — not available on Android
-dontwarn java.awt.**

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Trust (util/CompromisedKeyTrust.kt)
# The platform calls these by reflection, and the app never calls them from
# outside, so R8 would rename or drop them: X509TrustManagerExtensions finds the
# trust manager's host-aware checks and isSameTrustConfiguration with getMethod
# whenever it is given ours (OkHttp's chain cleaner makes one).
-keepclassmembers class com.pingolin.track.util.CompromisedKeyTrustManager {
    public java.util.List checkServerTrusted(java.security.cert.X509Certificate[], java.lang.String, java.lang.String);
    public java.util.List checkServerTrusted(java.security.cert.X509Certificate[], byte[], byte[], java.lang.String, java.lang.String);
    public boolean isSameTrustConfiguration(java.lang.String, java.lang.String);
}

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Hilt
-dontwarn dagger.hilt.**
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager { *; }

# Keep DTOs
-keep class com.pingolin.track.data.remote.dto.** { *; }
-keepclassmembers class com.pingolin.track.data.remote.dto.** { *; }

# Keep Entities
-keep class com.pingolin.track.data.local.entity.** { *; }
-keepclassmembers class com.pingolin.track.data.local.entity.** { *; }


# MapLibre
-keep class org.maplibre.android.** { *; }
-dontwarn org.maplibre.android.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep R8 from removing interfaces
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Strip verbose/debug logging from release builds. R8 treats these Log.d/Log.v
# calls as side-effect free and removes them (along with their string args, so
# no plaintext coordinates or other debug data survive in release logcat).
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
