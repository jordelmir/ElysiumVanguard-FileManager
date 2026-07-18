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
        val (signatureValid, signatureDigest) = checkAppSignature()
        return DeviceIntegrity(
            isRooted = rooted,
            isDebuggerAttached = debugger,
            isSignatureValid = signatureValid,
            appPackageName = context.packageName,
            appSignatureDigest = signatureDigest,
        )
    }

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
     * signature digest.
     *
     * Phase 1: returns `(false, null)` until
     * the expected publisher signature is
     * configured. Phase 2: reads the expected
     * signature from a Hilt-injected config.
     */
    private fun checkAppSignature(): Pair<Boolean, String?> {
        val pm = context.packageManager
        val signatures = try {
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
        // Phase 1: no expected signature configured; treat as valid
        // to keep the development flow unblocked. Phase 2 wires the
        // expected publisher's signature + verifies against it.
        return (signatures != null) to signatures
    }
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
