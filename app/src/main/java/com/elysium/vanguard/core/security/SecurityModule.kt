package com.elysium.vanguard.core.security

import android.content.Context
import com.elysium.vanguard.BuildConfig
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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

    /**
     * Phase 100 — the list of on-disk directories
     * the kill switch wipes. The list is
     * derived from the [Context.filesDir] sub-
     * directories the runtime uses. The list
     * is **writable** so tests can override it.
     */
    @Provides
    @Singleton
    fun provideWipeableDirectories(
        @ApplicationContext context: Context,
    ): WipeableDirectories = WipeableDirectories(
        listOf(
            File(context.filesDir, "workspaces"),
            File(context.filesDir, "distros"),
            File(context.filesDir, "fileaction-scratch"),
            File(context.filesDir, "elysium-vault"),
        )
    )

    /**
     * Phase 100 — the kill switch's process
     * inventory provider. The production
     * default returns an empty list (the
     * [com.elysium.vanguard.core.runtime.runner.ProcessWatcher]
     * is the canonical source; it lives in
     * Phase 100+ integration work). The kill
     * switch stops whatever the provider
     * returns before wiping the database +
     * directories.
     */
    @Provides
    @Singleton
    fun provideLaunchedProcessHandlesProvider(): LaunchedProcessHandlesProvider =
        LaunchedProcessHandlesProvider { emptyList() }

    /**
     * Phase 100 — the security audit log
     * (singleton; append-only). The kill
     * switch records its trigger here.
     */
    @Provides
    @Singleton
    fun provideSecurityAudit(): SecurityAudit = SecurityAudit()

    /**
     * Phase 100 — the secret store. The kill
     * switch calls `clear()` on this to wipe
     * the Tink-encrypted keys.
     */
    @Provides
    @Singleton
    fun provideSecretStore(audit: SecurityAudit): SecretStore = SecretStore(audit = audit)

    /**
     * Phase 100 — the production
     * [RuntimeDataWiper]. The class wraps the
     * Room database; the kill switch consumes
     * the interface.
     */
    @Provides
    @Singleton
    fun provideRuntimeDataWiper(
        database: com.elysium.vanguard.core.database.runtime.RuntimeDatabase,
    ): RuntimeDataWiper = RuntimeDataWiperImpl(database = database)
}

/**
 * Phase 100 — typed wrapper around the list of
 * on-disk directories the kill switch wipes.
 * A typed wrapper is needed because Hilt can't
 * bind `List<File>` (Hilt erases the element
 * type at injection time).
 */
data class WipeableDirectories(val dirs: List<File>)

/**
 * Phase 100 — typed wrapper around the
 * callback the kill switch uses to discover
 * the running processes. A typed wrapper is
 * needed because Hilt can't bind `Function0`
 * with a covariant return type.
 */
fun interface LaunchedProcessHandlesProvider {
    fun handles(): List<com.elysium.vanguard.core.runtime.runner.LaunchedProcess>
}
