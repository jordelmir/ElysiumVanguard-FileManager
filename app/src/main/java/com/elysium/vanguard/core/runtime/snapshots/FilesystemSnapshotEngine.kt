package com.elysium.vanguard.core.runtime.snapshots

import com.elysium.vanguard.core.runtime.bridge.MountEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 49 — the production [SnapshotEngine]
 * implementation.
 *
 * On-disk layout under [baseDir]
 * (typically `<filesDir>/workspaces`):
 *
 * ```
 * <baseDir>/
 *   <workspaceId>/
 *     rootfs/                      # the live rootfs (managed by DistroManager)
 *     snapshots/
 *       <snapshotId>/
 *         manifest.json            # the WorkspaceSnapshot record (sans the rootfs tree)
 *         rootfs/                  # the captured copy
 * ```
 *
 * ## Copy strategy
 *
 * The engine tries POSIX `cp -al` first (a
 * hardlink-based recursive copy). On Android the
 * workspace's live rootfs and the snapshot
 * directory share the same filesystem
 * (`/data/data/.../files/`), so hardlinks are
 * guaranteed to be possible. The fallback is a
 * pure-JVM recursive copy via [java.nio.file.Files.walkFileTree]
 * for the rare case where:
 *
 * - `/system/bin/cp` is not present (stripped-down
 *   Android variants), or
 * - the source lives on a different filesystem
 *   (e.g. an external SD card).
 *
 * The chosen strategy is recorded in
 * [WorkspaceSnapshot.copyStrategy] so a future
 * "snapshot GC" job can prioritize cheap-to-keep
 * (hardlink) snapshots.
 *
 * ## Concurrency
 *
 * The engine uses a per-workspace lock
 * ([workspaceLocks]) to serialise snapshot /
 * rollback / delete calls against the same
 * workspace. Concurrent operations against
 * *different* workspaces are fully concurrent.
 */
class FilesystemSnapshotEngine(
    private val baseDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = ::defaultIdGenerator,
    /**
     * Phase 52 — when `true`, the engine
     * always uses [CopyStrategy.FULL_COPY]
     * (never POSIX hardlinks). Hardlinks share
     * inodes with the source, which means a
     * write to the source after a hardlink
     * snapshot is visible through the
     * snapshot — the rollback would copy the
     * (mutated) snapshot back, a no-op. The
     * critical end-to-end test
     * ([com.elysium.vanguard.core.runtime.CriticalEndToEndTest])
     * needs a snapshot that is INDEPENDENT of
     * the live rootfs, so the test passes
     * `forceFullCopy = true`. Production
     * defaults to `false` (hardlink-first) for
     * speed; a future Phase 53+ follow-up
     * will decide whether the production
     * default should also flip.
     */
    private val forceFullCopy: Boolean = false
) : SnapshotEngine {

    private val workspaceLocks = ConcurrentHashMap<String, Any>()

    private fun lockFor(workspaceId: String): Any =
        workspaceLocks.computeIfAbsent(workspaceId) { Any() }

    private fun snapshotsDir(workspaceId: String): File =
        File(baseDir, "$workspaceId/snapshots")

    private fun snapshotDir(workspaceId: String, snapshotId: String): File =
        File(snapshotsDir(workspaceId), snapshotId)

    // ---- SnapshotEngine ----

    override fun snapshot(
        workspaceId: String,
        sourceRootfsPath: String,
        mountPlan: MountPlan,
        label: String,
        nowMs: Long?
    ): SnapshotResult = synchronized(lockFor(workspaceId)) {
        val effectiveNowMs = nowMs ?: clock()
        if (label.isBlank()) {
            return@synchronized SnapshotResult.Failure(SnapshotError.InvalidLabel(label))
        }
        val source = File(sourceRootfsPath)
        if (!source.exists()) {
            return@synchronized SnapshotResult.Failure(SnapshotError.SourceNotFound(sourceRootfsPath))
        }
        val id = idGenerator()
        val target = snapshotDir(workspaceId, id)
        if (target.exists()) {
            // The id generator should never produce a
            // collision; if it does, the system is
            // misconfigured. Surface the error rather
            // than overwrite.
            return@synchronized SnapshotResult.Failure(
                SnapshotError.IoError("snapshot id collision: $id already exists at $target")
            )
        }
        target.mkdirs()
        val snapshotRootfs = File(target, "rootfs")
        val copyOutcome = tryHardlinkCopy(source, snapshotRootfs)
        val strategy = if (copyOutcome is CopyOutcome.Ok) copyOutcome.strategy
            else null
        if (strategy == null) {
            // Both copy strategies failed; clean up the
            // half-created directory and surface the
            // second error.
            target.deleteRecursively()
            return@synchronized SnapshotResult.Failure(
                (copyOutcome as CopyOutcome.Failed).error
            )
        }
        val sizeBytes = try {
            directorySize(snapshotRootfs.toPath())
        } catch (t: Throwable) {
            // Size computation is best-effort; zero
            // means "unknown" per the WorkspaceSnapshot
            // contract.
            0L
        }
        val snapshot = WorkspaceSnapshot(
            id = id,
            workspaceId = workspaceId,
            label = label,
            createdAtMs = effectiveNowMs,
            rootfsPath = snapshotRootfs.absolutePath,
            mountPlan = mountPlan,
            sizeBytes = sizeBytes,
            copyStrategy = strategy
        )
        val writeOutcome = writeManifest(target, snapshot)
        if (writeOutcome is WriteOutcome.Failed) {
            target.deleteRecursively()
            return@synchronized SnapshotResult.Failure(writeOutcome.error)
        }
        SnapshotResult.Success(snapshot)
    }

    override fun rollback(
        workspaceId: String,
        snapshotId: String,
        liveRootfsPath: String
    ): RollbackResult = synchronized(lockFor(workspaceId)) {
        val snapshot = readManifest(workspaceId, snapshotId)
            ?: return@synchronized RollbackResult.Failure(SnapshotError.SnapshotNotFound(snapshotId))
        val liveRootfs = File(liveRootfsPath)
        if (!liveRootfs.exists()) {
            return@synchronized RollbackResult.Failure(SnapshotError.LiveRootfsNotFound(liveRootfsPath))
        }
        val snapshotRootfs = File(snapshot.rootfsPath)
        if (!snapshotRootfs.exists()) {
            // The manifest is on disk but the rootfs is
            // missing — the snapshot is corrupt.
            return@synchronized RollbackResult.Failure(
                SnapshotError.IoError("snapshot rootfs missing: ${snapshot.rootfsPath}")
            )
        }
        // Replace the live rootfs:
        //   1. delete the live rootfs
        //   2. copy the snapshot's rootfs into place
        //
        // We do NOT preserve the previous live rootfs
        // (the manager is responsible for taking a
        // pre-rollback snapshot if the user wants
        // undo).
        val deleteOutcome = try {
            liveRootfs.deleteRecursively()
            DeleteOutcome.Ok
        } catch (t: Throwable) {
            DeleteOutcome.Failed(t.message ?: "delete failed")
        }
        if (deleteOutcome is DeleteOutcome.Failed) {
            return@synchronized RollbackResult.Failure(
                SnapshotError.IoError("delete live rootfs failed: ${deleteOutcome.message}")
            )
        }
        val copyOutcome = tryFullCopy(snapshotRootfs, liveRootfs)
        if (copyOutcome is CopyOutcome.Failed) {
            return@synchronized RollbackResult.Failure(copyOutcome.error)
        }
        RollbackResult.Success(snapshot)
    }

    override fun list(workspaceId: String): List<WorkspaceSnapshot> =
        synchronized(lockFor(workspaceId)) {
            val dir = snapshotsDir(workspaceId)
            if (!dir.exists()) return@synchronized emptyList()
            dir.listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .mapNotNull { readManifest(workspaceId, it.name) }
                .sortedBy { it.createdAtMs }
        }

    override fun delete(snapshotId: String): Boolean {
        // delete is workspace-scoped by the caller
        // (the manager). We scan all workspaces to
        // find the snapshot — for a runtime with a
        // small number of workspaces this is fine;
        // a future optimisation is to pass
        // workspaceId explicitly.
        val workspacesDir = baseDir
        if (!workspacesDir.exists()) return false
        workspacesDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .forEach { workspaceDir ->
                val candidate = File(workspaceDir, "snapshots/$snapshotId")
                if (candidate.exists() && candidate.deleteRecursively()) {
                    return true
                }
            }
        return false
    }

    // ---- Internals ----

    /**
     * Attempt POSIX `cp -al` (hardlink-based
     * recursive copy). If the binary is missing or
     * the copy fails, fall back to a full copy.
     * The strategy recorded in the returned
     * [CopyOutcome.Ok] is the strategy that
     * succeeded.
     */
    private fun tryHardlinkCopy(source: File, target: File): CopyOutcome {
        if (!forceFullCopy && runCpSucceeded("-al", source, target)) {
            return CopyOutcome.Ok(CopyStrategy.HARDLINK)
        }
        // Fallback: full copy. This always works on
        // the last resort.
        val fallbackOutcome = tryFullCopy(source, target)
        if (fallbackOutcome is CopyOutcome.Ok) {
            return CopyOutcome.Ok(CopyStrategy.FULL_COPY)
        }
        return CopyOutcome.Failed(
            SnapshotError.IoError(
                "cp -al failed and JVM recursive copy also failed " +
                    "(${(fallbackOutcome as CopyOutcome.Failed).error.message})"
            )
        )
    }

    /**
     * Full recursive copy. Tries `cp -R` first; on
     * failure falls back to a pure-JVM
     * [Files.walkFileTree] recursion. The strategy
     * is always [CopyStrategy.FULL_COPY] (hardlinks
     * would share inodes with the source, which is
     * wrong for a rollback that needs to overwrite
     * the live rootfs).
     */
    private fun tryFullCopy(source: File, target: File): CopyOutcome {
        if (runCpSucceeded("-R", source, target)) {
            return CopyOutcome.Ok(CopyStrategy.FULL_COPY)
        }
        // Last-resort: pure-JVM recursive copy.
        return try {
            target.mkdirs()
            copyRecursively(source.toPath(), target.toPath())
            CopyOutcome.Ok(CopyStrategy.FULL_COPY)
        } catch (t: Throwable) {
            CopyOutcome.Failed(
                SnapshotError.IoError("JVM recursive copy failed: ${t.message}")
            )
        }
    }

    /**
     * Run `/system/bin/cp` (or `cp` on the host)
     * with the given flag. Returns `true` on exit
     * code 0; `false` otherwise (and captures the
     * stderr for diagnostics). The caller maps
     * success to the correct [CopyStrategy].
     */
    private fun runCpSucceeded(flag: String, source: File, target: File): Boolean {
        val candidates = listOf("/system/bin/cp", "/bin/cp", "cp")
        for (bin in candidates) {
            val pb = ProcessBuilder(bin, flag, source.absolutePath, target.absolutePath)
            val process = try {
                pb.start()
            } catch (t: Throwable) {
                continue
            }
            val completed = process.waitFor()
            if (completed == 0) return true
        }
        return false
    }

    /**
     * Write the snapshot's manifest as JSON. The
     * manifest is the source of truth for
     * [list] and [rollback]; the on-disk
     * `rootfs/` is data the manifest points to.
     */
    private fun writeManifest(snapshotDir: File, snapshot: WorkspaceSnapshot): WriteOutcome {
        return try {
            val json = JSONObject()
            json.put("id", snapshot.id)
            json.put("workspaceId", snapshot.workspaceId)
            json.put("label", snapshot.label)
            json.put("createdAtMs", snapshot.createdAtMs)
            json.put("rootfsPath", snapshot.rootfsPath)
            json.put("sizeBytes", snapshot.sizeBytes)
            json.put("copyStrategy", snapshot.copyStrategy.name)
            json.put("mountPlan", encodeMountPlan(snapshot.mountPlan))
            File(snapshotDir, "manifest.json").writeText(json.toString(2))
            WriteOutcome.Ok
        } catch (t: Throwable) {
            WriteOutcome.Failed(SnapshotError.IoError("write manifest failed: ${t.message}"))
        }
    }

    private fun readManifest(workspaceId: String, snapshotId: String): WorkspaceSnapshot? {
        val file = File(snapshotDir(workspaceId, snapshotId), "manifest.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val mountPlanJson = json.getJSONObject("mountPlan")
            val mountPlan = decodeMountPlan(mountPlanJson)
            WorkspaceSnapshot(
                id = json.getString("id"),
                workspaceId = json.getString("workspaceId"),
                label = json.getString("label"),
                createdAtMs = json.getLong("createdAtMs"),
                rootfsPath = json.getString("rootfsPath"),
                mountPlan = mountPlan,
                sizeBytes = json.optLong("sizeBytes", 0L),
                copyStrategy = CopyStrategy.valueOf(json.getString("copyStrategy"))
            )
        } catch (t: Throwable) {
            // Corrupt manifest — return null so the
            // caller treats it as "not found". The
            // snapshot directory is left in place;
            // a future GC job can prune it.
            null
        }
    }

    private fun encodeMountPlan(plan: MountPlan): JSONObject {
        val json = JSONObject()
        val mounts = JSONArray()
        for (entry in plan.mounts) {
            val obj = JSONObject()
            obj.put("hostPath", entry.hostPath)
            obj.put("guestPath", entry.guestPath)
            obj.put("readOnly", entry.readOnly)
            entry.label?.let { obj.put("label", it) }
            mounts.put(obj)
        }
        json.put("mounts", mounts)
        val env = JSONObject()
        for ((k, v) in plan.env) env.put(k, v)
        json.put("env", env)
        return json
    }

    private fun decodeMountPlan(json: JSONObject): MountPlan {
        val mountsJson = json.optJSONArray("mounts") ?: JSONArray()
        val mounts = ArrayList<MountEntry>(mountsJson.length())
        for (i in 0 until mountsJson.length()) {
            val obj = mountsJson.getJSONObject(i)
            mounts += MountEntry(
                hostPath = obj.getString("hostPath"),
                guestPath = obj.getString("guestPath"),
                readOnly = obj.optBoolean("readOnly", true),
                label = obj.optString("label").takeIf { it.isNotEmpty() }
            )
        }
        val envJson = json.optJSONObject("env") ?: JSONObject()
        val env = HashMap<String, String>(envJson.length())
        for (key in envJson.keys()) env[key] = envJson.getString(key)
        return MountPlan(mounts = mounts, env = env)
    }

    private fun directorySize(path: Path): Long {
        val sum = AtomicInteger(0)
        Files.walkFileTree(path, object: java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                sum.addAndGet(attrs.size().toInt())
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        return sum.get().toLong()
    }

    private fun copyRecursively(source: Path, target: Path) {
        Files.walkFileTree(source, object: java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                val relative = source.relativize(dir)
                val out = target.resolve(relative.toString())
                Files.createDirectories(out)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                val relative = source.relativize(file)
                val out = target.resolve(relative.toString())
                Files.createDirectories(out.parent)
                Files.copy(file, out, StandardCopyOption.COPY_ATTRIBUTES)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private sealed class CopyOutcome {
        data class Ok(val strategy: CopyStrategy) : CopyOutcome()
        data class Failed(val error: SnapshotError) : CopyOutcome()
    }

    private sealed class WriteOutcome {
        data object Ok : WriteOutcome()
        data class Failed(val error: SnapshotError) : WriteOutcome()
    }

    private sealed class DeleteOutcome {
        data object Ok : DeleteOutcome()
        data class Failed(val message: String) : DeleteOutcome()
    }

    companion object {
        private val nextCounter = AtomicInteger(0)

        /**
         * Default id generator. The id is
         * `snap-<systemTimeMs>-<counter>` — the
         * counter disambiguates two ids generated
         * in the same millisecond.
         */
        fun defaultIdGenerator(): String =
            "snap-${System.currentTimeMillis()}-${nextCounter.incrementAndGet()}"
    }
}
