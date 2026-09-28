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

        testInstrumentationRunner = "com.elysium.vanguard.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // PHASE 76 (App signature check): the Security Zero Trust
        // surface needs the actual publisher's signing certificate
        // SHA-256 digest at build time. The default value (empty
        // string + PRODUCTION_BUILD = false) is the "dev mode"
        // config — the integrity checker's signature path accepts
        // the actual signature as valid if one is present.
        //
        // To publish a real release: set the `release` build type's
        // `buildConfigField` for `EXPECTED_PUBLISHER_SIG_SHA256` to
        // the actual publisher's digest (query with `apksigner
        // verify --print-certs <apk>`) and flip `PRODUCTION_BUILD`
        // to `true`. The `DeviceIntegrityConfig.init` block fails
        // fast at construction time if PRODUCTION_BUILD = true is
        // shipped without an expected — that's the fail-secure
        // default.
        buildConfigField("String", "EXPECTED_PUBLISHER_SIG_SHA256", "\"\"")
        buildConfigField("boolean", "PRODUCTION_BUILD", "false")
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

    // PHASE 140 — the bundled rootfs is shipped as a
    // plain `.tar` (not gzipped). AAPT2's default is to
    // *decompress* `.gz` assets at build time (it stores
    // the `.tar.gz` as a `.tar` inside the APK), but the
    // storage cost of the uncompressed 9.1 MB is worse
    // than the runtime cost of the gzip decompression
    // (AssetManager already returns raw bytes via a
    // memory-mapped FD for compressed assets; the gzip
    // happens once at first use). So we ship the
    // already-decompressed tar; AAPT2's deflate reduces
    // the 9.1 MB to ~3 MB on disk.
    //
    // The old .tar.gz approach (Phase 140 first build)
    // cost us 5 MB more APK size than the current .tar
    // approach; the .tar.gz + AAPT2-decompress path is
    // strictly worse for storage AND a wasted
    // decompression step at first use. See
    // docs/changelogs/PHASE_140_BUNDLED_ROOTFS.md for
    // the data.

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
                "/META-INF/spring.tooling",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/versions/9/module-info.class"
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
    // Exclude commons-logging globally (conflicts with jcl-over-slf4j from SSHD)
    modules {
        module("commons-logging:commons-logging") {
            replacedBy("org.slf4j:jcl-over-slf4j", "Use SLF4J's JCL bridge instead of commons-logging")
        }
    }

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    // PHASE 11.3: process-wide lifecycle owner so the DNS tracker
    // can subscribe to ON_START / ON_STOP without per-Activity glue.
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
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
    // Also exclude commons-logging (conflicts with jcl-over-slf4j)
    implementation("org.apache.sshd:apache-sshd:2.10.0") {
        exclude(group = "org.apache.sshd", module = "sshd-osgi")
        exclude(group = "org.apache.sshd", module = "sshd-spring-sftp")
        exclude(group = "org.springframework", module = "spring-jcl")
        exclude(group = "commons-logging", module = "commons-logging")
    }
    implementation("org.apache.sshd:sshd-sftp:2.10.0") {
        exclude(group = "org.apache.sshd", module = "sshd-osgi")
        exclude(group = "org.apache.sshd", module = "sshd-spring-sftp")
        exclude(group = "org.springframework", module = "spring-jcl")
        exclude(group = "commons-logging", module = "commons-logging")
    }

    // PHASE 3.11: ML Kit on-device OCR (text recognition in images).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // PHASE 3.10: ML Kit image labeling (auto-tag photos).
    implementation("com.google.mlkit:image-labeling:17.0.8")

    // Cloud Storage Clients
    // Google Drive API v3
    implementation("com.google.apis:google-api-services-drive:v3-rev20260428-2.0.0")
    // Microsoft Graph (OneDrive, SharePoint)
    implementation("com.microsoft.graph:microsoft-graph:6.42.0")
    // Dropbox API v2
    implementation("com.dropbox.core:dropbox-core-sdk:7.0.0")
    // Box API
    implementation("com.box:box-java-sdk:4.16.1")
    // NextCloud/ownCloud (WebDAV-based, uses existing WebDAV client)
    // Mega.nz - no official Maven artifact, use REST API
    // Yandex Disk (WebDAV)
    // pCloud (WebDAV)
    // MediaFire (no official SDK, use REST)
    // OAuth2 for all cloud providers
    implementation("com.google.auth:google-auth-library-oauth2-http:1.48.0")

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
    // XAR (macOS PKG) support via SpryLab's pure-Java implementation.
    implementation("com.sprylab.xar:xar:0.9.11")
    // Pure-Java LZMA2 / XZ codec. Required for .tar.xz round-trip and
    // for the 7Z LZMA2 compression method.
    implementation("org.tukaani:xz:1.10")
    // Zstandard JNI binding. Required for .tar.zst round-trip.
    implementation("com.github.luben:zstd-jni:1.5.6-1")
    // JUnrar for RAR/RAR5 extraction (pure Java, no native libs needed)
    implementation("com.github.junrar:junrar:7.5.5")
    // Apache Commons Compress 1.26+ supports CAB, ARJ, CHM, CPIO, DMG, ISO, etc.
    // For LZ4/LZ5/Lizard: use lz4-java
    implementation("org.lz4:lz4-java:1.8.0")
    // For WIM: wimlib-java (if available) or skip for now
    // Note: Some formats (ISO, DMG, VHD, etc.) need native libs or are read-only via 7z
    // commons-codec is a runtime dep of commons-compress 1.26's
    // Charsets helper (org/apache/commons/codec/Charsets). The Android
    // build pulls it transitively but the JVM test runtime does not —
    // declare it explicitly so the unit tests don't NoClassDefFound.
    testImplementation("commons-codec:commons-codec:1.16.0")
    // org.json is a stub on the Android classpath that returns
    // default values (0, null, false) under isReturnDefaultValues.
    // The runtime uses the real org.json via the Android API, but
    // the JVM unit tests need the real implementation so JSON
    // parse + read paths work end-to-end.
    testImplementation("org.json:json:20231013")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Coroutines test utilities (runTest, UnconfinedTestDispatcher,
    // advanceUntilIdle). Version pinned to the same 1.7.3 that the
    // rest of the module resolves to, so the BOM-aligned artifacts
    // stay consistent.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.0.0")
    // org.json is on Android at runtime; the JVM test path needs
    // it as an explicit test dep so the workspace JSON parser
    // tests can run on the JVM (the production parser does not
    // need it because Android bundles the class).
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // PHASE 44 — Compose UI test + Hilt test. Lets the
    // androidTest/ source set drive the Compose UI
    // (assertions, click actions) and inject a Hilt
    // graph (HiltAndroidRule + HiltTestApplication).
    // The Compose UI test brings the full runtime
    // collector; the Hilt test wires the production
    // graph from the @HiltViewModel-annotated
    // ViewModels. Versions pulled from the Compose
    // BOM (2024.02.02); the platform() call above
    // resolves them.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.02"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.48")
    // Phase 44 — the Compose test rule needs a debug
    // manifest (the test runner must declare
    // `debuggable=true` and the activity must be
    // launchable). ui-test-manifest provides a debug
    // AndroidManifest.xml under src/debug/AndroidManifest.xml.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
