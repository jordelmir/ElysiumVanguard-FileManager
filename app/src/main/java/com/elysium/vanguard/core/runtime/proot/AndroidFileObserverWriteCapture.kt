package com.elysium.vanguard.core.runtime.proot

import android.os.FileObserver
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 72 — the production [WriteCapture] backed by
 * `android.os.FileObserver`.
 *
 * One `FileObserver` is started per host path. Each observer
 * listens for `CREATE`, `MODIFY`, `MOVED_TO`, and `CLOSE_WRITE`
 * events; every event appends the absolute file path to the
 * captured list. The capture is the **write-audit half** of
 * the master vision's Definition of Done — without it the
 * `CriticalE2EOrchestrator` had no way to know what the proot
 * process actually wrote to the bind-mounted host paths.
 *
 * ## Limitations (documented + tested)
 *
 * 1. **Non-recursive**. `FileObserver` watches a single
 *    directory + its direct children. Sub-directories are not
 *    watched. The orchestrator's bindMounts are typically
 *    single user-selected directories, so this is sufficient
 *    for the common case. A follow-up can add a recursive
 *    walker that starts a new `FileObserver` per sub-directory.
 * 2. **`FileObserver` is deprecated in API 29+**. The modern
 *    replacement is `androidx.core.content.FileObserver` (the
 *    AndroidX wrapper) or `StorageManager` (for scoped storage).
 *    The deprecated API still works on every supported API
 *    level (compileSdk=34, minSdk=26). A follow-up can swap in
 *    the modern API; the [WriteCapture] interface is the seam
 *    that makes the swap non-breaking.
 * 3. **Symlink races**. If the proot process resolves a path
 *    through a symlink that escapes the watched directory, the
 *    `FileObserver` won't see the write. The mount policy
 *    enforcer (Phase 50) is the upstream defense; this capture
 *    is the audit, not the policy.
 *
 * Thread-safety: `start` / `stop` are `@Synchronized`. The
 * captured-writes list is a [CopyOnWriteArrayList] so a
 * concurrent `writes()` from another thread sees a consistent
 * snapshot.
 */
class AndroidFileObserverWriteCapture : WriteCapture {

    private val observers = mutableListOf<FileObserver>()
    private val capturedWrites = CopyOnWriteArrayList<String>()

    @Synchronized
    override fun start(watching: Set<String>) {
        stop()
        for (path in watching) {
            val dir = File(path)
            // Skip non-existent or non-directory paths silently.
            // A missing mount is a user-authorized no-op (the
            // orchestrator's mount policy decides whether the
            // path is legal; the capture just records what the
            // process actually wrote).
            if (!dir.isDirectory) continue
            val observer = object : FileObserver(path) {
                override fun onEvent(event: Int, file: String?) {
                    if (file == null) return
                    // Only the write-class events: CREATE / MODIFY /
                    // MOVED_TO / CLOSE_WRITE. ACCESS / OPEN / ATTRIB
                    // are excluded — they're reads + metadata, not
                    // destructive writes.
                    val isWrite = (event and (CREATE or MODIFY or MOVED_TO or CLOSE_WRITE)) != 0
                    if (!isWrite) return
                    capturedWrites.add("$path/$file")
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
    }

    @Synchronized
    override fun stop() {
        for (observer in observers) {
            try {
                observer.stopWatching()
            } catch (_: Throwable) {
                // Best-effort. The observer may have been GC'd, or
                // its underlying inotify watch may already be torn
                // down. The captured writes are preserved regardless.
            }
        }
        observers.clear()
    }

    override fun writes(): List<String> = capturedWrites.toList()
}
