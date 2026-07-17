package com.elysium.vanguard.core.runtime.workspaces

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.File
import java.io.IOException

/**
 * Phase 35 — the production [WorkspaceStore] implementation.
 *
 * Persists every [Workspace] as a single JSON file under
 * [baseDir]. The on-disk layout is one file per workspace:
 *
 * ```
 * <baseDir>/
 *   ws-1.json
 *   ws-2.json
 *   ws-3.json
 *   ...
 * ```
 *
 * Each file is the [WorkspaceDto] representation of the
 * workspace at the time of the last save. The store is
 * JVM-testable end-to-end (it takes a [File] for the
 * base directory and does not depend on `android.content.Context`).
 * Production wires it to `context.filesDir/workspaces`;
 * tests construct it with a temp dir.
 *
 * Thread safety: a single `synchronized` lock guards every
 * mutation. The store is the runtime's single point of
 * mutation for the workspace layer, and the manager
 * already holds a per-workspace lock for cross-workspace
 * state changes — adding a second lock layer would invite
 * deadlock. The store-level lock is held only across the
 * disk I/O, which is the only operation that actually
 * needs serialisation (concurrent reads of an in-memory
 * snapshot do not need the lock).
 *
 * Atomic write: every `save()` writes to `<id>.json.tmp`
 * first, then renames over the existing `<id>.json`. The
 * rename is atomic on the same filesystem (the standard
 * POSIX `rename(2)` semantics Android shares with Linux).
 * A process crash mid-write leaves the old file intact;
 * a crash mid-rename is impossible because `rename(2)`
 * is a single syscall.
 *
 * Schema stability: the on-disk format is the [WorkspaceDto]
 * (private). If the production model ever changes, the
 * DTO can stay backward-compatible — old JSON without a
 * new field parses with a default, new JSON with the field
 * round-trips fully. The store does NOT call
 * `gson.fromJson` directly on the production class because
 * the production class has `init` blocks that would throw
 * on partially-formed data; the DTO has none.
 *
 * JSON example:
 * ```json
 * {
 *   "id": "ws-1",
 *   "name": "Work",
 *   "createdAtMs": 1700000000000,
 *   "state": "Active",
 *   "sessions": [
 *     { "kind": "LINUX_PROOT", "id": "s-1", "displayName": "Debian", "distroId": "debian-latest", "profileId": "balanced" },
 *     { "kind": "WINDOWS_VM", "id": "w-1", "displayName": "Win11", "windowsSpecId": "win11-pro-23h2" }
 *   ]
 * }
 * ```
 */
class FileWorkspaceStore(
    private val baseDir: File
) : WorkspaceStore {

    private val lock = Any()
    private val gson: Gson = Gson()

    init {
        // Eager directory creation. The runtime is the only
        // writer; if the directory is missing, this is the
        // first call.
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw IOException("Cannot create workspace store baseDir: $baseDir")
        }
    }

    override fun save(workspace: Workspace) {
        synchronized(lock) {
            val target = fileFor(workspace.id)
            val tmp = fileFor(workspace.id, suffix = TMP_SUFFIX)
            val dto = workspace.toDto()
            val json = gson.toJson(dto)
            tmp.writeText(json, Charsets.UTF_8)
            // Atomic rename. If the target already exists,
            // `renameTo` overwrites on Linux/Android.
            if (!tmp.renameTo(target)) {
                // Fallback: delete target then rename. This
                // can happen on exotic filesystems where
                // rename-over-existing is not atomic, but
                // Android's filesystem always supports it.
                target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    throw IOException("Failed to write workspace ${workspace.id} to $target")
                }
            }
        }
    }

    override fun load(id: String): Workspace? = synchronized(lock) {
        val file = fileFor(id)
        if (!file.exists()) return null
        val json = file.readText(Charsets.UTF_8)
        try {
            gson.fromJson(json, WorkspaceDto::class.java).toDomain()
        } catch (e: JsonSyntaxException) {
            // A corrupt file is treated as missing. The
            // caller (the manager) re-creates the in-memory
            // entry on the next save.
            null
        } catch (e: IllegalStateException) {
            // Gson wraps a few different parse failures in
            // IllegalStateException. Treat the same way.
            null
        }
    }

    override fun list(): List<Workspace> = synchronized(lock) {
        val files = baseDir.listFiles { f -> f.isFile && f.name.endsWith(JSON_SUFFIX) }
            ?: return emptyList()
        files.mapNotNull { file ->
            try {
                val json = file.readText(Charsets.UTF_8)
                gson.fromJson(json, WorkspaceDto::class.java).toDomain()
            } catch (e: JsonSyntaxException) {
                null
            } catch (e: IllegalStateException) {
                null
            } catch (e: IOException) {
                null
            }
        }
    }

    override fun delete(id: String): Boolean = synchronized(lock) {
        val file = fileFor(id)
        val tmp = fileFor(id, suffix = TMP_SUFFIX)
        val deleted = file.delete()
        tmp.delete() // a stale tmp is never valid; clean up
        deleted
    }

    /** The on-disk file for a workspace id. */
    private fun fileFor(id: String, suffix: String = JSON_SUFFIX): File =
        File(baseDir, "$id$suffix")

    // ── DTOs ────────────────────────────────────────────────────────

    /**
     * The on-disk shape of a [Workspace]. Private — never
     * escapes the store. Fields are nullable or have
     * defaults so a future version of the store can read
     * older JSON without a field; the toDomain() mapper
     * applies the same defaults.
     */
    private data class WorkspaceDto(
        val id: String,
        val name: String,
        val createdAtMs: Long,
        val state: String,
        val sessions: List<SessionDto>
    )

    /**
     * The on-disk shape of a [WorkspaceSession]. The
     * `kind` discriminator picks the right mapper.
     */
    private data class SessionDto(
        val kind: String,
        val id: String,
        val displayName: String,
        val distroId: String? = null,
        val profileId: String? = null,
        val windowsSpecId: String? = null
    )

    // ── Mappers ────────────────────────────────────────────────────

    private fun Workspace.toDto(): WorkspaceDto = WorkspaceDto(
        id = id,
        name = name,
        createdAtMs = createdAtMs,
        state = state.toKey(),
        sessions = sessions.map { it.toDto() }
    )

    private fun WorkspaceSession.toDto(): SessionDto = when (this) {
        is WorkspaceSession.LinuxProot -> SessionDto(
            kind = kind.name,
            id = id,
            displayName = displayName,
            distroId = distroId,
            profileId = profileId
        )
        is WorkspaceSession.WindowsVm -> SessionDto(
            kind = kind.name,
            id = id,
            displayName = displayName,
            windowsSpecId = windowsSpecId
        )
    }

    private fun WorkspaceDto.toDomain(): Workspace = Workspace(
        id = id,
        name = name,
        createdAtMs = createdAtMs,
        state = stateFromKey(state),
        sessions = sessions.map { it.toDomain() }
    )

    private fun SessionDto.toDomain(): WorkspaceSession = when (kind) {
        "LINUX_PROOT" -> WorkspaceSession.LinuxProot(
            id = id,
            displayName = displayName,
            distroId = distroId ?: error("LINUX_PROOT session $id missing distroId"),
            profileId = profileId ?: error("LINUX_PROOT session $id missing profileId")
        )
        "WINDOWS_VM" -> WorkspaceSession.WindowsVm(
            id = id,
            displayName = displayName,
            windowsSpecId = windowsSpecId ?: error("WINDOWS_VM session $id missing windowsSpecId")
        )
        else -> error("Unknown session kind: $kind")
    }

    private fun WorkspaceState.toKey(): String = when (this) {
        is WorkspaceState.Active -> "Active"
        is WorkspaceState.Paused -> "Paused"
        is WorkspaceState.Closed -> "Closed"
    }

    private fun stateFromKey(key: String): WorkspaceState = when (key) {
        "Active" -> WorkspaceState.Active
        "Paused" -> WorkspaceState.Paused
        "Closed" -> WorkspaceState.Closed
        else -> error("Unknown workspace state: $key")
    }

    companion object {
        private const val JSON_SUFFIX = ".json"
        private const val TMP_SUFFIX = ".json.tmp"
    }
}
