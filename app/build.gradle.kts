plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.elysium.vanguard"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.elysium.vanguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-TITAN"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    testOptions {
        unitTests {
            // Return default values for Android stubs (StatFs, Environment,
            // etc.) so unit tests that touch those APIs don't throw
            // "Method not mocked" exceptions. The downside is that the
            // tests can pass against fakes; for true Android integration
            // coverage, add androidTest/ cases that hit the real Android
            // runtime.
            isReturnDefaultValues = true
        }
    }

    // PHASE 7.7 (Security Hardening): real release signing config.
    // Reads from gradle.properties (which is .gitignored) or env vars.
    // Falls back to the debug keystore so `./gradlew assembleRelease`
    // keeps working out of the box for local smoke tests, but Play Store
    // uploads require the env vars to be set in CI.
    signingConfigs {
        create("release") {
            val storeFileProp = providers.gradleProperty("RELEASE_STORE_FILE").orNull
                ?: System.getenv("RELEASE_STORE_FILE")
            if (storeFileProp != null) {
                storeFile = file(storeFileProp)
                storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
                    ?: ""
                keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
                    ?: System.getenv("RELEASE_KEY_ALIAS")
                    ?: ""
                keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
                    ?: System.getenv("RELEASE_KEY_PASSWORD")
                    ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true  // PHASE 7.7: remove unused resources → smaller APK
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use the real release keystore when configured; otherwise
            // fall back to the debug keystore so local builds still produce
            // an installable APK (annotated in build log).
            signingConfig = run {
                val storeFilePath = providers.gradleProperty("RELEASE_STORE_FILE").orNull
                    ?: System.getenv("RELEASE_STORE_FILE")
                if (storeFilePath != null && file(storeFilePath).exists()) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // PHASE 7.4 (Security Hardening): enable BuildConfig generation so
        // we can guard log calls with `BuildConfig.DEBUG` and prevent PII
        // (full user paths) from reaching logcat in release builds.
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        jniLibs {
            // PRoot is shipped as a PIE executable under lib/<abi> so
            // Android grants it executable permissions. It must be
            // extracted to applicationInfo.nativeLibraryDir; a binary
            // stored only inside the APK zip cannot be started by
            // ProcessBuilder.
            useLegacyPackaging = true
        }
        resources {
            // META-INF conflicts come from multi-jar deps like Apache SSHD
            // (cli + contrib + sftp all publish overlapping META-INF entries).
            // Excluding the standard "noise" files keeps the build green
            // without dropping anything the app actually needs at runtime.
            excludes += setOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/license.txt",
                "/META-INF/NOTICE.md",
                "/META-INF/LICENSE.md",
                "/META-INF/ASL2.0",
                "/META-INF/spring.tooling"
            )
        }
    }
}

val buildRustRuntime by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the ARM64 Rust PTY runtime with the pinned Android NDK."
    val runtimeDir = rootProject.layout.projectDirectory.dir("native/runtime")
    inputs.file(runtimeDir.file("Cargo.toml"))
    inputs.file(runtimeDir.file("Cargo.lock"))
    inputs.file(runtimeDir.file("build.rs"))
    inputs.dir(runtimeDir.dir("src"))
    inputs.file(runtimeDir.file("build-android.sh"))
    outputs.file(layout.buildDirectory.file("generated/rustJniLibs/arm64-v8a/libelysium_runtime.so"))
    commandLine("bash", runtimeDir.file("build-android.sh").asFile.absolutePath)
}

android.sourceSets.getByName("main").jniLibs.srcDir(
    layout.buildDirectory.dir("generated/rustJniLibs")
)

tasks.configureEach {
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn(buildRustRuntime)
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Room Database
    val roomVersion = "2.6.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // AI & Media
    // PHASE 7.8 (Security Hardening): ffmpeg-kit-full (~50 MB) was dead code
    // (its only consumer, MediaIntelligenceManager, was unused). Removed.
    // mediapipe-tasks-genai is still wired into FileManagerViewModel's
    // `performSemanticSearch` flow so we keep it.
    implementation("com.google.mediapipe:tasks-genai:0.10.32")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-video:2.5.0")
    
    // Media3 (ExoPlayer)
    val media3Version = "1.2.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // JSON Persistence
    implementation("com.google.code.gson:gson:2.10.1")

    // PHASE 1.2: SAF DocumentFile support for trash restore across folders.
    implementation("androidx.documentfile:documentfile:1.0.1")

    // PHASE 1.3: WorkManager for daily trash auto-purge.
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // PHASE 1.3: Hilt integration for WorkManager workers.
    implementation("androidx.hilt:hilt-work:1.1.0")
    ksp("androidx.hilt:hilt-compiler:1.1.0")

    // PHASE 2.1: Tink for vault encryption (Android Keystore-backed AES-256-GCM).
    implementation("com.google.crypto.tink:tink-android:1.13.0")

    // PHASE 3.7: ZXing core for QR code generation (transfer URL → QR).
    implementation("com.google.zxing:core:3.5.3")

    // PHASE 2.4: Apache MINA SSHD for SFTP server (real SSH/SFTP from any client).
    // We only need the core + sftp modules. Exclude osgi (duplicate classes with core)
    // and spring (pulls in spring-jcl which clashes with jcl-over-slf4j).
    implementation("org.apache.sshd:apache-sshd:2.10.0") {
        exclude(group = "org.apache.sshd", module = "sshd-osgi")
        exclude(group = "org.apache.sshd", module = "sshd-spring-sftp")
        exclude(group = "org.springframework", module = "spring-jcl")
    }
    implementation("org.apache.sshd:sshd-sftp:2.10.0") {
        exclude(group = "org.apache.sshd", module = "sshd-osgi")
        exclude(group = "org.apache.sshd", module = "sshd-spring-sftp")
        exclude(group = "org.springframework", module = "spring-jcl")
    }

    // PHASE 3.11: ML Kit on-device OCR (text recognition in images).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // PHASE 3.10: ML Kit image labeling (auto-tag photos).
    implementation("com.google.mlkit:image-labeling:17.0.8")

    // PHASE 9.6.3.2: Apache Commons Compress — used by the custom rootfs
    // installer to handle .tar.xz / .tar.bz2 / .tar.zst decompress streams
    // before they hit our POSIX-tar extractor. xz is the big one we
    // couldn't decode in pure Kotlin.
    //
    // PHASE 10.3: also the foundation of the ZArchiver-grade compression
    // engine. commons-compress 1.26 has pure-Java support for ZIP, TAR,
    // TAR.GZ, TAR.BZ2, BZ2, GZIP, and 7Z (read + write + password). For
    // LZMA2 / XZ / Zstandard we lean on the transitive deps below — they
    // are tiny pure-Java wrappers (xz) or the official JNI binding (zstd-jni).
    implementation("org.apache.commons:commons-compress:1.26.0")
    // Pure-Java LZMA2 / XZ codec. Required for .tar.xz round-trip and
    // for the 7Z LZMA2 compression method.
    implementation("org.tukaani:xz:1.10")
    // Zstandard JNI binding. Required for .tar.zst round-trip.
    implementation("com.github.luben:zstd-jni:1.5.6-1")
    // commons-codec is a runtime dep of commons-compress 1.26's
    // Charsets helper (org/apache/commons/codec/Charsets). The Android
    // build pulls it transitively but the JVM test runtime does not —
    // declare it explicitly so the unit tests don't NoClassDefFound.
    testImplementation("commons-codec:commons-codec:1.16.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Coroutines test utilities (runTest, UnconfinedTestDispatcher,
    // advanceUntilIdle). Version pinned to the same 1.7.3 that the
    // rest of the module resolves to, so the BOM-aligned artifacts
    // stay consistent.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
