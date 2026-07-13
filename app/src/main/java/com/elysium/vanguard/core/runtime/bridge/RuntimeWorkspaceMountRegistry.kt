package com.elysium.vanguard.core.runtime.bridge

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent, allow-listed mappings from user storage into a distro. Mounts
 * are evaluated when a PRoot command is created; active sessions are never
 * modified underneath a running process.
 */
@Singleton
class RuntimeWorkspaceMountRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val prefs by lazy { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }

    @Synchronized
    fun register(distroId: String, mountPath: String, readOnly: Boolean): RegisteredWorkspaceMount {
        require(DISTRO_ID.matches(distroId)) { "Invalid distro workspace id" }
        val host = File(mountPath).canonicalFile
        require(host.isDirectory) { "Workspace path must be an existing directory" }
        require(isUserAccessibleStorage(host)) { "Workspace path must be under user-accessible storage" }
        val guestPath = "/workspace/${host.name.ifBlank { "project" }.replace(GUEST_SEGMENT, "_")}" 
        val entries = load().toMutableList()
        entries.removeAll { it.distroId == distroId && it.guestPath == guestPath }
        val entry = RegisteredWorkspaceMount(distroId, host.absolutePath, guestPath, readOnly)
        entries += entry
        save(entries)
        return entry
    }

    @Synchronized
    fun primaryForDistro(distroId: String): RegisteredWorkspaceMount? =
        load().firstOrNull { it.distroId == distroId }

    @Synchronized
    fun mountsForRootfs(rootfsDir: File): List<MountEntry> {
        val distroId = rootfsDir.parentFile?.name ?: return emptyList()
        return load()
            .asSequence()
            .filter { it.distroId == distroId }
            .mapNotNull { entry ->
                val host = File(entry.hostPath)
                if (!host.isDirectory) null else MountEntry(
                    hostPath = host.absolutePath,
                    guestPath = entry.guestPath,
                    readOnly = entry.readOnly,
                    label = "workspace:${host.name}"
                )
            }
            .toList()
    }

    private fun isUserAccessibleStorage(path: File): Boolean {
        val roots = buildList {
            context.getExternalFilesDir(null)?.let(::add)
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory()?.let(::add)
        }.map { it.canonicalFile }
        return roots.any { root ->
            path == root || path.absolutePath.startsWith(root.absolutePath + File.separator)
        }
    }

    private fun load(): List<RegisteredWorkspaceMount> {
        val raw = prefs.getString(KEY_MOUNTS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<RegisteredWorkspaceMount>>(raw, MOUNTS_TYPE) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private fun save(entries: List<RegisteredWorkspaceMount>) {
        prefs.edit().putString(KEY_MOUNTS, gson.toJson(entries)).apply()
    }

    private companion object {
        const val PREFERENCES = "elysium_runtime_workspace_mounts"
        const val KEY_MOUNTS = "mounts"
        val DISTRO_ID = Regex("[A-Za-z0-9._-]{1,160}")
        val GUEST_SEGMENT = Regex("[^A-Za-z0-9._-]")
        val MOUNTS_TYPE = object : TypeToken<List<RegisteredWorkspaceMount>>() {}.type
    }
}

data class RegisteredWorkspaceMount(
    val distroId: String,
    val hostPath: String,
    val guestPath: String,
    val readOnly: Boolean
)
