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

# ----- Tink (Phase 2.1: vault encryption) -----
# Tink loads keyset classes reflectively; keep primitives + KeysetHandle + AndroidKeysetManager.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
# AndroidKeysetManager / AndroidKeystoreAead use reflection on its own packages.
-keep class com.google.crypto.tink.integration.android.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }
-dontwarn com.google.errorprone.annotations.**

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

# ----- Apache MINA SSHD (Phase 2.4: SFTP server) -----
# SSH server classes are loaded reflectively by the protocol negotiation layer.
-keep class org.apache.sshd.** { *; }
-keepclassmembers class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**
# JGit is a transitive dep of sshd-git (we don't use it but it's pulled in).
# Silence its MBean references — the JMX MBean server isn't available on
# Android, and the JGit code path that calls it is unreachable in our app.
-dontwarn org.eclipse.jgit.**
-dontwarn java.lang.management.**
# MINA pulls in a few javax.* classes that don't exist on Android. They're
# only referenced from optional code paths (PEM key parsing via JCA, JMX
# error instrumentation). The SFTP server doesn't exercise those paths, so
# we can safely tell R8 to ignore the unresolved references.
-dontwarn javax.management.**
-dontwarn javax.security.auth.login.**
# Netty is the underlying transport for MINA; we don't need to keep its
# internals but do need to silence the Log4J bindings that MINA references.
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn io.netty.**
# BlockHound is a reactor-netty testing tool; it's pulled in transitively
# by some MINA sub-modules but never used at runtime in our app.
-dontwarn reactor.blockhound.**
# BC / Conscrypt / OpenJSSE are optional JCA providers; SFTP works fine
# without them on Android (the platform provider covers what we need).
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ----- ML Kit (Phase 3.10/3.11: OCR + image labeling) -----
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**

# ----- ZXing (Phase 3.7: QR code generation) -----
-dontwarn com.google.zxing.**

# ----- Suppress noisy warnings from optional deps -----
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn javax.annotation.**