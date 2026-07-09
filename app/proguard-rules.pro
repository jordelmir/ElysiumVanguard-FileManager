# ====================================================================
# ElysiumVanguard-FileManager — ProGuard / R8 rules
# Created in PHASE 0.6 to ensure release builds don't strip critical code.
# ====================================================================

# ----- Global optimizations -----
-allowaccessmodification
-repackageclasses ''

# Keep generic signatures for reflection-based code
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*, RuntimeVisibleAnnotations, AnnotationDefault
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ----- Kotlin -----
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$Companion { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ----- Hilt / Dagger -----
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.* { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.* <methods>;
}
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}
-keepclasseswithmembers class * {
    @javax.inject.Inject <fields>;
}
-dontwarn com.google.errorprone.annotations.**

# ----- Room -----
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }
-dontwarn androidx.room.paging.**

# ----- AndroidX / Compose -----
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class * implements androidx.compose.runtime.Composer { *; }
-dontwarn androidx.compose.**

# ----- Media3 / ExoPlayer -----
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keepclassmembers class androidx.media3.exoplayer.** { *; }

# ----- Gson (used by MusicHubViewModel for JSON persistence) -----
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes Signature
# Keep all POJOs annotated with @SerializedName
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ----- MediaPipe GenAI (Phase 0.5: kept, used by performSemanticSearch) -----
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ----- Coil (image loading) -----
-dontwarn coil.**

# ----- Java native methods -----
-keepclasseswithmembernames class * {
    native <methods>;
}

# ----- Parcelable -----
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ----- Enums (Hilt + serialization safety) -----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ----- Elysium Vanguard project classes (safety net) -----
# Keep all ViewModels (Hilt-injected)
-keep class com.elysium.vanguard.**.*ViewModel { *; }
-keep class com.elysium.vanguard.**.*Repository { *; }
# Keep all data classes (used by Room and Gson)
-keep class com.elysium.vanguard.**.*Entity { *; }
-keep class com.elysium.vanguard.**.*Track { *; }
-keep class com.elysium.vanguard.**.*Playlist { *; }

# ----- Suppress noisy warnings from optional deps -----
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**