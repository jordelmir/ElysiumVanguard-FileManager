package com.elysium.vanguard.core.security

/**
 * Phase 76 — the runtime configuration for the
 * [DeviceIntegrityChecker].
 *
 * Until Phase 76 the [DeviceIntegrityChecker] had a
 * placeholder for the app-signature check
 * ("Phase 1: no expected signature configured; treat
 * as valid to keep the development flow unblocked. Phase 2
 * wires the expected publisher's signature + verifies
 * against it."). The placeholder made the
 * Security Zero Trust surface — one of the master vision's
 * required controls — silently succeed on every install.
 *
 * Phase 76 replaces the placeholder with a typed config:
 *
 * - [expectedPublisherSignatureSha256] — the SHA-256
 *   hex digest of the expected APK signing certificate
 *   bytes. When `null`, the checker is in "dev mode"
 *   and treats the signature as valid (no
 *   publisher-keyed check). When set, the checker
 *   compares the actual APK signing certificate's
 *   digest to this value; a mismatch fails the
 *   integrity check.
 * - [productionBuild] — when `true`, the checker
 *   requires an [expectedPublisherSignatureSha256];
 *   a `null` expected + `productionBuild = true` is a
 *   misconfiguration and the checker fails the
 *   integrity check at startup. This is the
 *   "fail-secure" default for release builds.
 *
 * The Hilt module provides a default for dev (both
 * fields permissive). The release build overrides the
 * default via a `buildConfigField` in `build.gradle.kts`
 * (the field is set to the actual publisher's
 * signature digest at build time).
 *
 * **Where the expected signature comes from.**
 *
 * 1. Generate the digest locally with `apksigner`:
 *    `apksigner verify --print-certs path/to/app.apk | grep SHA-256`
 * 2. Set the result as a `buildConfigField` in the
 *    `release` build type:
 *    `buildConfigField("String", "EXPECTED_PUBLISHER_SIG_SHA256", "\"<digest>\"")`
 * 3. The Hilt module reads the value from BuildConfig
 *    and constructs a [DeviceIntegrityConfig] with
 *    [productionBuild] = true.
 */
data class DeviceIntegrityConfig(
    /**
     * The SHA-256 hex digest of the expected APK
     * signing certificate bytes. Lowercase, no
     * `:` separators (the `MessageDigest.toHex()`
     * format). `null` = dev mode (no publisher
     * verification). When set, the actual APK
     * signing certificate's SHA-256 digest must match
     * this value byte-for-byte.
     */
    val expectedPublisherSignatureSha256: String? = null,

    /**
     * True when this is a release build. In a
     * production build a missing
     * [expectedPublisherSignatureSha256] is a
     * misconfiguration and the integrity check
     * fails. In dev (`false`), a missing expected
     * is fine.
     */
    val productionBuild: Boolean = false,
) {
    init {
        if (productionBuild) {
            // Fail-fast on misconfiguration: a release
            // build that forgot to set the expected
            // signature is a security regression.
            check(expectedPublisherSignatureSha256 != null) {
                "DeviceIntegrityConfig: productionBuild=true requires " +
                    "expectedPublisherSignatureSha256 to be set. Wire the " +
                    "release build's buildConfigField in app/build.gradle.kts."
            }
        }
    }
}
