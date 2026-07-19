package com.elysium.vanguard.core.security

import com.elysium.vanguard.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 76 — the Hilt module for the security
 * subsystem.
 *
 * Until Phase 76 the
 * [com.elysium.vanguard.core.security.DeviceIntegrityChecker]
 * had a constructor that took only the Android
 * [android.content.Context] and used a hard-coded
 * "dev mode" stub for the signature check. The
 * stub made the Security Zero Trust surface
 * — one of the master vision's required controls
 * — silently succeed on every install, even a
 * tampered re-signed APK.
 *
 * Phase 76 replaces the stub with a typed
 * [DeviceIntegrityConfig] + this module. The
 * module builds the config from
 * [com.elysium.vanguard.BuildConfig] fields
 * the build script generates:
 *
 * - `BuildConfig.EXPECTED_PUBLISHER_SIG_SHA256` —
 *   the SHA-256 hex digest of the expected
 *   publisher's signing certificate. Empty
 *   string in dev; populated in release.
 * - `BuildConfig.PRODUCTION_BUILD` — a boolean
 *   flag the release build type sets to `true`.
 *   Debug builds leave it `false`.
 *
 * The release build's `buildConfigField` is
 * generated at build time by the `app/build.gradle.kts`
 * `buildTypes.release` block. The release block
 * also has a `failSecure` check (in
 * [com.elysium.vanguard.core.security.DeviceIntegrityConfig.init])
 * that throws at construction time when a
 * release build is published with an empty
 * expected signature.
 *
 * **Dev mode** (debug build): the config has
 * `expectedPublisherSignatureSha256 = null` +
 * `productionBuild = false`. The checker's
 * signature path accepts the actual signature
 * as valid if one is present (the typical
 * "debug keystore signed" case). The init
 * block's fail-secure check does NOT fire.
 *
 * **Release mode** (release build): the config
 * has the configured expected digest +
 * `productionBuild = true`. The checker's
 * signature path compares the actual APK
 * signing certificate's SHA-256 to the
 * expected; a mismatch fails the integrity
 * check. The init block's fail-secure check
 * fires if the build script forgot to set the
 * expected.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    /**
     * The [DeviceIntegrityConfig] the checker
     * reads at every `check()` call. The
     * production value is computed from the
     * build-time `BuildConfig` fields:
     *
     * - Debug build: `null` expected +
     *   `productionBuild = false`. The
     *   integrity check accepts the actual
     *   signature as valid if one is present.
     * - Release build: the configured expected
     *   digest + `productionBuild = true`. The
     *   integrity check requires the actual
     *   signature to match.
     *
     * The `String → String?` conversion
     * collapses the empty-string dev value
     * (`""`) back to `null` so the data class's
     * `init` block's `check(...)` doesn't fire
     * in dev (the check is reserved for
     * production misconfiguration).
     */
    @Provides
    @Singleton
    fun provideDeviceIntegrityConfig(): DeviceIntegrityConfig = DeviceIntegrityConfig(
        expectedPublisherSignatureSha256 = BuildConfig.EXPECTED_PUBLISHER_SIG_SHA256
            .takeIf { it.isNotEmpty() },
        productionBuild = BuildConfig.PRODUCTION_BUILD,
    )
}
