package com.elysium.vanguard.core.runtime.distros

import com.elysium.vanguard.core.runtime.distros.introspector.InstalledPackage
import com.elysium.vanguard.core.runtime.distros.introspector.OsRelease
import com.elysium.vanguard.core.runtime.distros.introspector.RootfsEntry
import com.elysium.vanguard.core.runtime.distros.introspector.RootfsIntrospector
import com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherResolver
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherResolutionResolver
import com.elysium.vanguard.core.runtime.distros.snapshot.DistroSnapshot
import com.elysium.vanguard.core.runtime.distros.snapshot.RootfsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * PHASE 9.6.2 — Orchestrates every distro operation.
 *
 * Single public surface for the UI:
 *
 *   - install(id)            starts a download
 *   - listInstalled()       reads DistroStorage
 *   - findInstalled(id)     dist
 *   - remove(id)            deletes the on-disk tree
 *
 * Live state is exposed as a `StateFlow<List<DistroInstallation>>` that
 * the UI can collect and re-render from. Operations are in-flight
 * bookkeeping (one per id) so we don't double-install while another is
 * in progress.
 *
 * Network access is delegated to a [DistroHttpDownloader]; tests inject a
 * fake that points at a real tar.gz file on disk.
 *
 * Phase 9.6.2 — first build; intentionally minimal.
 */
class DistroManager(
    private val baseDir: File,
    private val downloader: DistroHttpDownloader,
    /**
     * Strategy that picks the right [DistroLauncher] for a given distro
     * id / rootfs. Tests inject a deterministic resolver; production
     * uses [LauncherResolutionResolver] which probes the device for ABI
     * support and prefers [com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind.NATIVE_PROOT]
     * when its binary is available, falling back to the jailed shell.
     */
    private val launcherResolver: LauncherResolver = LauncherResolutionResolver.DEFAULT,
    /**
     * Storage accessor. Phase 9.6.3.3 makes this mutable to support
     * custom manifest parsing without rebuilding the manager.
     */
    private val storageProvider: () -> DistroStorage = {
        DistroStorage(baseDir)
    }
) {
    private val _installed = MutableStateFlow(loadInstalled())
    val installed: StateFlow<List<DistroInstallation>> = _installed.asStateFlow()

    private val _installing = MutableStateFlow<Set<String>>(emptySet())
    val installing: StateFlow<Set<String>> = _installing.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()
    private val _progress = MutableStateFlow<Map<String, DistroInstallProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DistroInstallProgress>> = _progress.asStateFlow()
    private val installLock = Any()

    /**
     * Synchronous install: blocks the calling thread until the download
     * finishes. Used from coroutines via `withContext(Dispatchers.IO)`.
     */
    @Throws(IOException::class)
    fun installBlocking(id: String): Result<File> {
        val distro = DistroCatalog.find(id)
            ?: return Result.failure(IOException("Unknown distro: $id"))
        if (!beginInstall(id)) {
            return Result.failure(IOException("Already installing $id"))
        }
        val installer = DistroInstaller(downloader, onProgress = { updateProgress(id, it) })
        return try {
            val rootfs = installer.install(distro, baseDir)
            finishInstall(id, error = null)
            refreshInstalled()
            Result.success(rootfs)
        } catch (io: IOException) {
            finishInstall(id, error = io.message ?: "install failed")
            refreshInstalled()
            Result.failure(io)
        }
    }

    fun remove(id: String): Boolean {
        val removed = storage().remove(id)
        refreshInstalled()
        return removed
    }

    fun refreshInstalled() {
        _installed.value = loadInstalled()
    }

    fun findInstalled(id: String): DistroInstallation? =
        installed.value.firstOrNull { it.distro.id == id }

    /**
     * PHASE 9.6.3 — Resolve the [DistroLauncher] for the given id, or
     * `null` when the distro is not installed or unhealthy.
     *
     * Always returns a non-null launcher when [findInstalled] returns a
     * healthy installation; on resolution failure the launcher is the
     * jailed-shell fallback so the UI still gets something to launch.
     */
    fun launcherFor(id: String): LauncherPick? {
        val installation = findInstalled(id) ?: return null
        if (!installation.isHealthy) return null
        return launcherResolver.resolve(installation.rootfsDir)
    }

    /**
     * PHASE 9.6.3.2 — Snapshot capability for the UI. Calls into the
     * snapshot subsystem against the manager's baseDir.
     */
    fun captureSnapshot(sourceId: String): DistroSnapshot? {
        findInstalled(sourceId) ?: return null
        val snap = RootfsSnapshot(baseDir)
        return try {
            snap.capture(sourceId)
        } catch (_: IOException) {
            null
        }
    }

    /**
     * PHASE 9.6.3.2 — List snapshots that originated from [sourceId].
     * Each item is a [DistroSnapshot]; only those whose `sourceId`
     * matches come through. The list is returned newest-first.
     */
    fun snapshotsFor(sourceId: String): List<DistroSnapshot> {
        return RootfsSnapshot(baseDir).list().filter { it.sourceId == sourceId }
    }

    fun removeSnapshot(snapshotId: String): Boolean =
        RootfsSnapshot(baseDir).remove(snapshotId)

    /**
     * PHASE 9.6.3.2 — Introspect a single distro's installed rootfs.
     * The [block] runs on the calling thread; the manager doesn't
     * synchronize against concurrent installs because introspector is
     * read-only against `filesDir/distros/<id>/rootfs/`.
     */
    fun introspect(id: String, block: (RootfsIntrospectorSnapshot) -> Unit): Boolean {
        val installation = findInstalled(id) ?: return false
        if (!installation.isHealthy) return false
        val introspector = RootfsIntrospector(installation.rootfsDir)
        block(
            RootfsIntrospectorSnapshot(
                osRelease = introspector.osRelease(),
                entries = introspector.entries(maxDepth = 3),
                packages = introspector.installedPackages()
            )
        )
        return true
    }

    private fun storage(): DistroStorage = storageProvider()

    private fun loadInstalled(): List<DistroInstallation> = storage().listInstalled()

    /**
     * PHASE 9.6.3.3 — The merged catalog: catalog-official distros plus
     * any custom rootfs installed under [baseDir]. Each row carries an
     * `isInstalled` flag the UI uses to drive the install/open button.
     *
     * This is what the runtime screen renders in 9.6.3.3; it supersedes
     * the implicit "only show catalog" behavior of 9.6.2.
     */
    fun effectiveCatalog(): List<EffectiveCatalogRow> {
        val installed = installed.value.toList()
        val installedById = installed.associateBy { it.distro.id }
        val catalogRows = DistroCatalog.ALL.map { distro ->
            val install = installedById[distro.id]
            EffectiveCatalogRow(
                distro = distro,
                isInstalled = install != null,
                isHealthy = install?.isHealthy == true,
                isCustom = false,
                installation = install
            )
        }
        val customRows = installed
            .filter { (it.distro.id !in DistroCatalog.ALL.map(Distro::id)) }
            .map { install ->
                EffectiveCatalogRow(
                    distro = install.distro,
                    isInstalled = true,
                    isHealthy = install.isHealthy,
                    isCustom = true,
                    installation = install
                )
            }
        return catalogRows + customRows
    }

    private fun beginInstall(id: String): Boolean = synchronized(installLock) {
        if (id in _installing.value) return@synchronized false
        _installing.value = _installing.value + id
        _progress.value = _progress.value + (id to DistroInstallProgress(DistroInstallStage.PREFLIGHT))
        true
    }

    private fun updateProgress(id: String, progress: DistroInstallProgress) = synchronized(installLock) {
        if (id in _installing.value) _progress.value = _progress.value + (id to progress)
    }

    private fun finishInstall(id: String, error: String?) = synchronized(installLock) {
        _installing.value = _installing.value - id
        _progress.value = _progress.value - id
        _errors.value = if (error == null) _errors.value - id else _errors.value + (id to error)
    }
}
