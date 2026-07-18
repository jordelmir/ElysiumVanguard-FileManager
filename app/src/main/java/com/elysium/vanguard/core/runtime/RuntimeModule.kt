package com.elysium.vanguard.core.runtime

import android.content.Context
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.observability.BusToLogAdapter
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.observability.RuntimeEventLog
import com.elysium.vanguard.core.runtime.observability.SynchronizedEventBus
import com.elysium.vanguard.core.runtime.runner.AndroidProcessLauncher
import com.elysium.vanguard.core.runtime.runner.LinuxProotSessionRunner
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import com.elysium.vanguard.core.runtime.runner.SessionRunner
import com.elysium.vanguard.core.runtime.runner.SessionRunnerRegistry
import com.elysium.vanguard.core.runtime.runner.WindowsVmSessionBackend
import com.elysium.vanguard.core.runtime.runner.WindowsVmSessionRunner
import com.elysium.vanguard.core.runtime.snapshots.FilesystemSnapshotEngine
import com.elysium.vanguard.core.runtime.snapshots.SnapshotEngine
import com.elysium.vanguard.core.runtime.windows.WindowsVmBackend
import com.elysium.vanguard.core.runtime.windows.WindowsVmManager
import com.elysium.vanguard.core.runtime.windows.qemu.QemuWindowsVmBackend
import com.elysium.vanguard.core.runtime.workspaces.FileWorkspaceStore
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import java.io.File

/**
 * Phase 36 — Hilt module for the runtime's process-scoped
 * collaborators.
 *
 * Until Phase 36 the runtime's collaborators
 * ([WorkspaceManager], [WindowsVmManager],
 * [RuntimeEventBus], [ProcessLauncher],
 * [LinuxProotSessionRunner], [WindowsVmSessionRunner],
 * [SessionRunner]) were all JVM-testable but not wired
 * into the app's DI graph. The two new ViewModels
 * ([com.elysium.vanguard.core.runtime.ui.MainScreenViewModel]
 * + [com.elysium.vanguard.core.runtime.ui.WorkspacesViewModel])
 * cannot be instantiated without this module.
 *
 * The module deliberately does NOT include the
 * Compose-side adapters (the [com.elysium.vanguard.core.runtime.ui]
 * ViewModels are `@HiltViewModel`-annotated, not
 * `@Singleton`-provided). The DI graph is:
 *
 *   @ApplicationContext ──┐
 *                         ▼
 *   WorkspaceStore ← FileWorkspaceStore
 *   WorkspaceManager ← WorkspaceStore
 *
 *   WindowsVmBackend ← QemuWindowsVmBackend
 *   WindowsVmManager ← baseDir + WindowsVmBackend
 *   WindowsVmSessionBackend ← WindowsVmManager (it implements
 *                              the runner-side interface)
 *
 *   RuntimeEventLog ← file at <filesDir>/runtime/audit.ndjson
 *   RuntimeEventBus ← SynchronizedEventBus
 *   BusToLogAdapter ← bus + log (subscribes; the bus is the
 *                      single shared instance)
 *
 *   ProcessLauncher ← AndroidProcessLauncher
 *
 *   LinuxProotSessionRunner ← DistroManager + ProcessLauncher
 *                              + RuntimeEventBus
 *   WindowsVmSessionRunner ← WindowsVmManager + RuntimeEventBus
 *   SessionRunner ← SessionRunnerRegistry(linux, windows)
 *
 * The DistroManager is provided by
 * [com.elysium.vanguard.core.runtime.distros.DistroModule]
 * (Phase 9.6.3).
 */
@Module
@InstallIn(SingletonComponent::class)
object RuntimeModule {

    // --- Workspaces (Phase 24 + Phase 35) ---

    @Provides
    @Singleton
    fun provideWorkspaceStore(@ApplicationContext context: Context): WorkspaceStore {
        val baseDir = File(context.filesDir, "workspaces").apply {
            if (!exists()) mkdirs()
        }
        return FileWorkspaceStore(baseDir)
    }

    @Provides
    @Singleton
    fun provideWorkspaceManager(
        store: WorkspaceStore,
        eventBus: RuntimeEventBus,
        snapshotEngine: SnapshotEngine,
        @WallClock clock: () -> Long
    ): WorkspaceManager = WorkspaceManager(
        store = store,
        eventBus = eventBus,
        snapshotEngine = snapshotEngine,
        clock = clock
    )

    // --- Snapshots (Phase 49) ---

    /**
     * The on-disk snapshot engine. Stores under
     * `<filesDir>/workspaces/<workspaceId>/snapshots/<snapshotId>/`.
     * The base dir is the same as the
     * [FileWorkspaceStore]'s base dir; the engine's
     * `<workspaceId>/snapshots/` paths are
     * disjoint from the store's
     * `<workspaceId>.json` files.
     */
    @Provides
    @Singleton
    fun provideSnapshotEngine(@ApplicationContext context: Context): SnapshotEngine {
        val baseDir = File(context.filesDir, "workspaces").apply {
            if (!exists()) mkdirs()
        }
        return FilesystemSnapshotEngine(baseDir = baseDir)
    }

    // --- Windows VMs (Phase 22 + Phase 23) ---

    @Provides
    @Singleton
    fun provideWindowsVmBackend(@ApplicationContext context: Context): WindowsVmBackend {
        val baseDir = File(context.filesDir, "winvms").apply {
            if (!exists()) mkdirs()
        }
        return QemuWindowsVmBackend(baseDir = baseDir)
    }

    @Provides
    @Singleton
    fun provideWindowsVmManager(
        @ApplicationContext context: Context,
        backend: WindowsVmBackend
    ): WindowsVmManager {
        val baseDir = File(context.filesDir, "winvms").apply {
            if (!exists()) mkdirs()
        }
        return WindowsVmManager(baseDir = baseDir, backend = backend)
    }

    // --- Observability (Phase 25) ---

    @Provides
    @Singleton
    fun provideRuntimeEventLog(@ApplicationContext context: Context): RuntimeEventLog {
        val logFile = File(context.filesDir, "runtime/audit.ndjson")
        return RuntimeEventLog(logFile = logFile)
    }

    @Provides
    @Singleton
    fun provideRuntimeEventBus(): RuntimeEventBus = SynchronizedEventBus()

    /**
     * The bus-to-log adapter wires the in-memory bus to
     * the persistent log. The adapter subscribes once;
     * every event the bus receives is also written to
     * the log file. The adapter is a process-scope
     * singleton: its lifetime matches the process.
     */
    @Provides
    @Singleton
    fun provideBusToLogAdapter(bus: RuntimeEventBus, log: RuntimeEventLog): BusToLogAdapter =
        BusToLogAdapter(bus = bus, log = log)

    // --- Process launcher (Phase 30 + Phase 36) ---

    @Provides
    @Singleton
    fun provideProcessLauncher(): ProcessLauncher = AndroidProcessLauncher()

    // --- Session runners (Phase 30 + 31 + 32 + 36) ---

    @Provides
    @Singleton
    fun provideWindowsVmSessionBackend(manager: WindowsVmManager): WindowsVmSessionBackend =
        manager

    @Provides
    @Singleton
    fun provideLinuxProotSessionRunner(
        distroManager: DistroManager,
        processLauncher: ProcessLauncher,
        eventBus: RuntimeEventBus
    ): LinuxProotSessionRunner = LinuxProotSessionRunner(
        backend = distroManager,
        processLauncher = processLauncher,
        eventBus = eventBus
    )

    @Provides
    @Singleton
    fun provideWindowsVmSessionRunner(
        backend: WindowsVmSessionBackend,
        eventBus: RuntimeEventBus
    ): WindowsVmSessionRunner = WindowsVmSessionRunner(
        backend = backend,
        eventBus = eventBus
    )

    /**
     * The runner the UI sees: a [SessionRunnerRegistry]
     * that dispatches by [com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession.kind].
     * The registry is itself a [SessionRunner] (Phase 32)
     * so it slots into every call site the two
     * per-kind runners used to occupy.
     */
    @Provides
    @Singleton
    fun provideSessionRunner(
        linuxRunner: LinuxProotSessionRunner,
        windowsRunner: WindowsVmSessionRunner
    ): SessionRunner = SessionRunnerRegistry(
        linuxRunner = linuxRunner,
        windowsRunner = windowsRunner
    )

    // --- ViewModel configuration ---

    /**
     * Phase 36 — the [com.elysium.vanguard.core.runtime.ui.MainScreenViewModel]
     * reads its `recentEventsCapacity` from Hilt so the
     * production value (20) can be tuned without
     * recompiling every consumer. Tests inject the value
     * directly into the constructor.
     */
    @Provides
    @MainScreenRecentEventsCapacity
    fun provideMainScreenRecentEventsCapacity(): Int = 20

    /**
     * Phase 36 — the wall clock the two ViewModels
     * use to stamp events. Tests inject a fake
     * (a `() -> N` closure) directly; production
     * uses [System.currentTimeMillis].
     */
    @Provides
    @WallClock
    fun provideWallClock(): () -> Long = System::currentTimeMillis
}

/**
 * Phase 36 — qualifier for the
 * [com.elysium.vanguard.core.runtime.ui.MainScreenViewModel]
 * `recentEventsCapacity` Hilt binding. Keeps the
 * integer distinct from any other `Int` Hilt might
 * bind at the same site.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainScreenRecentEventsCapacity

/**
 * Phase 36 — qualifier for the `() -> Long` wall-
 * clock function the ViewModels use. Distinct from
 * any other `() -> Long` Hilt might bind (e.g. a
 * future monotonic clock for timing).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WallClock
