package com.elysium.vanguard.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's integrity checker. The checker
 * is the **only legitimate way** to compute
 * the `DeviceIntegrity` state. The consumer
 * does not compute the state directly.
 *
 * Phase 1 implementation: a JVM-testable
 * version that takes the inputs (rooted
 * binary, debugger flag, signature digest)
 * as constructor parameters. The Phase 2
 * implementation wires the actual Android
 * `PackageManager` + the `su` check + the
 * debugger check.
 *
 * The Phase 1 version is used in:
 *   - The test suite (the tests pass
 *     mocked inputs).
 *   - The CI build (the build verifies the
 *     test suite passes with the mocked
 *     inputs).
 *
 * The Phase 2 version is used in:
 *   - The production app (the checker
 *     queries the actual Android APIs at
 *     startup + before every security-
 *     sensitive operation).
 */
@Singleton
class DeviceIntegrityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: DeviceIntegrityConfig,
) {
    /**
     * Compute the device's integrity state.
     * The result is a `DeviceIntegrity` value
     * that the consumer can use to decide
     * whether to proceed.
     */
    fun check(): DeviceIntegrity {
        val rooted = checkRooted()
        val debugger = checkDebuggerAttached()
        val (signatureValid, signatureDigest) = checkAppSignature(config)
        return DeviceIntegrity(
            isRooted = rooted,
            isDebuggerAttached = debugger,
            isSignatureValid = signatureValid,
            appPackageName = context.packageName,
            appSignatureDigest = signatureDigest,
        )
    }

    /**
     * Phase 76 — the pure-comparison function the
     * signature check delegates to. Exposed as
     * `internal` on a separate top-level function
     * (not on the [DeviceIntegrityChecker] class)
     * so the test suite can drive every
     * combination of (actual, expected, dev/prod)
     * without standing up the Android
     * [PackageManager] or the [Context] the
     * checker's constructor requires. The
     * production path uses the same function; the
     * testability is the only reason it's not
     * `private` to the file.
     *
     * Returns a `(valid, observed)` pair:
     * - `valid = true` when the comparison passed
     *   (or dev-mode + actual present)
     * - `observed` is the actual digest the
     *   checker recorded (used for the audit log
     *   and the human-readable integrity report).
     */
    internal fun compareSignatureDigests(
        actualDigest: String?,
        config: DeviceIntegrityConfig,
    ): Pair<Boolean, String?> = compareSignatureDigestsInternal(actualDigest, config)

    /**
     * Heuristic rooted check: the device is
     * rooted if any of these paths exist
     * OR the `su` binary is available.
     */
    private fun checkRooted(): Boolean {
        val paths = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/app/Superuser",
            "/system/bin/.ext/.su",
        )
        return paths.any { File(it).exists() } || checkSuAvailable()
    }

    private fun checkSuAvailable(): Boolean = try {
        Runtime.getRuntime().exec("which su")
            .inputStream
            .bufferedReader()
            .readLine()
            ?.isNotEmpty() == true
    } catch (e: Exception) {
        false
    }

    /**
     * Check if a debugger is attached. The
     * `android:debuggable` flag in the
     * manifest + the `Debug.isDebuggerConnected()`
     * are the two main signals.
     */
    private fun checkDebuggerAttached(): Boolean {
        val appInfo = try {
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val isDebuggable = (appInfo.flags and ApplicationInfoFlags.DEBUGGABLE) != 0
        val isDebuggerConnected = Debug.isDebuggerConnected()
        return isDebuggable || isDebuggerConnected
    }

    /**
     * Verify the app's signature. The
     * implementation reads the APK's
     * signature from the `PackageManager` +
     * compares it to the expected publisher's
     * signature digest (from [DeviceIntegrityConfig]).
     *
     * Phase 76 — the comparison is now real:
     *  - When [DeviceIntegrityConfig.expectedPublisherSignatureSha256]
     *    is set, the actual APK signing certificate's
     *    SHA-256 digest must match it (case-insensitive).
     *    A mismatch (or a missing actual signature) fails
     *    the check.
     *  - When `expectedPublisherSignatureSha256` is `null`,
     *    the checker is in dev mode: it accepts the
     *    actual signature as valid if one is present.
     *  - When [DeviceIntegrityConfig.productionBuild] is
     *    `true` and `expectedPublisherSignatureSha256`
     *    is `null`, the [DeviceIntegrityConfig.init]
     *    fails fast at construction time (so this
     *    branch is unreachable in a properly-configured
     *    release build).
     */
    private fun checkAppSignature(config: DeviceIntegrityConfig): Pair<Boolean, String?> {
        val pm = context.packageManager
        val actualDigest: String? = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                val signingInfo = info.signingInfo
                signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                    ?.let { bytes ->
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        md.digest(bytes).joinToString("") { "%02x".format(it) }
                    }
            } else {
                null
            }
        } catch (e: Exception) {
            return false to null
        }
        return compareSignatureDigestsInternal(actualDigest, config)
    }
}

/**
 * Phase 76 — the top-level pure comparison
 * function. Kept as a free function (not a
 * [DeviceIntegrityChecker] member) so the JVM
 * test suite can call it without instantiating
 * the Android [Context]-bound checker.
 *
 * The logic is identical to the checker's
 * private path; both call sites converge here
 * so a single test suite covers both.
 */
internal fun compareSignatureDigestsInternal(
    actualDigest: String?,
    config: DeviceIntegrityConfig,
): Pair<Boolean, String?> {
    val expected = config.expectedPublisherSignatureSha256
    if (expected == null) {
        // Dev mode — no expected signature. Accept
        // the actual signature as valid if one is
        // present (the production-build check would
        // have failed at config construction time).
        return (actualDigest != null) to actualDigest
    }
    // Production mode — compare to expected.
    val valid = actualDigest != null &&
        actualDigest.equals(expected, ignoreCase = true)
    return valid to actualDigest
}

/**
 * The `ApplicationInfo` flags, broken out so
 * the import surface is narrow.
 */
private object ApplicationInfoFlags {
    const val DEBUGGABLE = 0x02
}

/**
 * `android.os.Debug` is a stub in the unit
 * test classpath (it throws
 * `RuntimeException` on access). The
 * production classpath has the real impl.
 * The stub returns `false` so the test
 * environment doesn't trigger the
 * debugger-attached check.
 */
private object Debug {
    fun isDebuggerConnected(): Boolean = try {
        android.os.Debug.isDebuggerConnected()
    } catch (e: Throwable) {
        false
    }
}
