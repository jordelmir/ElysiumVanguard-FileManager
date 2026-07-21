package com.elysium.vanguard.features.rooted

import android.content.Context
import android.content.SharedPreferences
import com.elysium.vanguard.core.runtime.distros.launcher.CgroupSpec
import com.elysium.vanguard.core.runtime.distros.launcher.NamespaceSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 102 — production implementation of [RootedModePrefs]
 * backed by a private [SharedPreferences] file.
 *
 * **Keys** are namespaced with `rooted_mode.` so the file
 * can be shared with other settings in the future.
 *
 * **Namespace + cgroup specs are serialized as
 * `key=value;key=value` strings** — small enough that
 * we don't need JSON; the format is human-readable in
 * `adb shell run-as` dumps.
 */
@Singleton
class SharedPreferencesRootedModePrefs @Inject constructor(
    @ApplicationContext context: Context,
) : RootedModePrefs {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isRootedModeEnabled(): Boolean =
        prefs.getBoolean(KEY_ENABLED, false)

    override fun setRootedModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override fun namespaceSpec(): NamespaceSpec {
        val raw = prefs.getString(KEY_NAMESPACE, null) ?: return NamespaceSpec.FULL_SANDBOX
        return decodeNamespace(raw) ?: NamespaceSpec.FULL_SANDBOX
    }

    override fun setNamespaceSpec(spec: NamespaceSpec) {
        prefs.edit().putString(KEY_NAMESPACE, encodeNamespace(spec)).apply()
    }

    override fun cgroupSpec(): CgroupSpec {
        val raw = prefs.getString(KEY_CGROUP, null) ?: return CgroupSpec.NONE
        return decodeCgroup(raw) ?: CgroupSpec.NONE
    }

    override fun setCgroupSpec(spec: CgroupSpec) {
        prefs.edit().putString(KEY_CGROUP, encodeCgroup(spec)).apply()
    }

    private fun encodeNamespace(spec: NamespaceSpec): String =
        "user=${spec.user}"

    private fun decodeNamespace(raw: String): NamespaceSpec? {
        // Default to FULL_SANDBOX; only `user` is toggleable.
        var user = false
        for (token in raw.split(';')) {
            val parts = token.split('=', limit = 2)
            if (parts.size != 2) continue
            if (parts[0] == "user") user = parts[1].toBooleanStrictOrNull() ?: false
        }
        return NamespaceSpec.FULL_SANDBOX.copy(user = user)
    }

    private fun encodeCgroup(spec: CgroupSpec): String {
        val parts = ArrayList<String>(5)
        spec.cpuWeight?.let { parts += "cpuWeight=$it" }
        spec.memoryHighBytes?.let { parts += "memoryHighBytes=$it" }
        spec.memoryMaxBytes?.let { parts += "memoryMaxBytes=$it" }
        spec.ioWeight?.let { parts += "ioWeight=$it" }
        spec.pidsMax?.let { parts += "pidsMax=$it" }
        return parts.joinToString(";")
    }

    private fun decodeCgroup(raw: String): CgroupSpec? {
        var cpuWeight: Int? = null
        var memoryHighBytes: Long? = null
        var memoryMaxBytes: Long? = null
        var ioWeight: Int? = null
        var pidsMax: Int? = null
        for (token in raw.split(';')) {
            if (token.isBlank()) continue
            val parts = token.split('=', limit = 2)
            if (parts.size != 2) continue
            try {
                when (parts[0]) {
                    "cpuWeight" -> cpuWeight = parts[1].toInt()
                    "memoryHighBytes" -> memoryHighBytes = parts[1].toLong()
                    "memoryMaxBytes" -> memoryMaxBytes = parts[1].toLong()
                    "ioWeight" -> ioWeight = parts[1].toInt()
                    "pidsMax" -> pidsMax = parts[1].toInt()
                }
            } catch (_: NumberFormatException) {
                // Bad value: skip it. The next read will
                // try the persisted string again; if it
                // keeps failing, the user can toggle.
                return null
            }
        }
        return CgroupSpec(
            cpuWeight = cpuWeight,
            memoryHighBytes = memoryHighBytes,
            memoryMaxBytes = memoryMaxBytes,
            ioWeight = ioWeight,
            pidsMax = pidsMax,
        )
    }

    companion object {
        const val PREFS_NAME = "elysium_rooted_mode"
        const val KEY_ENABLED = "rooted_mode.enabled"
        const val KEY_NAMESPACE = "rooted_mode.namespace"
        const val KEY_CGROUP = "rooted_mode.cgroup"
    }
}
