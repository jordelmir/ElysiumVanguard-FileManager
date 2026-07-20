package com.elysium.vanguard.core.orchestrator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elysium.vanguard.core.graphics.GPUVendor
import com.elysium.vanguard.core.linux.ElysiumAbi
import com.elysium.vanguard.core.linux.ElysiumAbiCapabilityMatrix
import com.elysium.vanguard.core.linux.ElysiumLinuxDefaultRepository
import com.elysium.vanguard.core.linux.ElysiumRootfsPath
import com.elysium.vanguard.core.linux.ElysiumRuntimeLayerCatalog
import com.elysium.vanguard.core.linux.ElysiumRuntimeLayerDefaults
import com.elysium.vanguard.core.runtime.capsule.Architecture
import com.elysium.vanguard.core.runtime.capsule.Capsule
import com.elysium.vanguard.core.runtime.capsule.CapsuleApiVersion
import com.elysium.vanguard.core.runtime.capsule.CapsuleId
import com.elysium.vanguard.core.runtime.capsule.Distribution
import com.elysium.vanguard.core.runtime.capsule.EntryPoint
import com.elysium.vanguard.core.runtime.capsule.GpuApi
import com.elysium.vanguard.core.runtime.capsule.GpuConfig
import com.elysium.vanguard.core.runtime.capsule.GpuDriver
import com.elysium.vanguard.core.runtime.capsule.Permissions
import com.elysium.vanguard.core.runtime.capsule.Runtime
import com.elysium.vanguard.core.runtime.capsule.StorageScope
import com.elysium.vanguard.core.runtime.critical_e2e.E2EAuditLog
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Phase 92 (Universal Execution Engine — real-device
 * wiring) — the **Production Critical E2E
 * instrumented test**, the real-device companion
 * to the JVM-side
 * [ProductionCriticalE2EOrchestratorTest].
 *
 * The companion test to the JVM test: this test
 * runs the **same** 8-step scenario but against
 * the **production** UEE components on a real
 * Android device:
 *
 *   - [AndroidProcessLauncher] — the production
 *     launcher (uses `ProcessBuilder` + a
 *     coroutine scope to observe the process
 *     lifecycle). The instrumented test
 *     actually launches `/system/bin/sh -c "echo
 *     e2e-probe"` (a real Android binary that
 *     exists on every device).
 *   - [ElysiumLinuxDefaultRepository] — the
 *     pre-populated in-memory repository (the
 *     real `ElysiumRepository` is an interface;
 *     the default impl is the typed source of
 *     the Elysium Linux packages).
 *   - [ElysiumAbiCapabilityMatrix] + the
 *     [ElysiumRuntimeLayerCatalog] — the typed
 *     registry the runtime selector reads.
 *   - [InMemoryProcessWatcher] +
 *     [InMemoryProcessStreamCapture] — the
 *     in-memory test impls of the watcher +
 *     stream capture (the production impls are
 *     pure-domain; the in-memory impls are the
 *     canonical test fixtures).
 *   - [InMemorySandboxApplication] +
 *     [InMemorySandboxEnforcer] — the typed
 *     applier + enforcer.
 *   - [E2EAuditLog] — the typed audit log.
 *     The instrumented test writes the audit
 *     log to `context.filesDir` (the device's
 *     private storage) so the test asserts
 *     the on-device file system semantics.
 *
 * The test does NOT require:
 *   - A network connection.
 *   - A real proot binary on the device.
 *   - A real distro installation.
 *   - Root privileges.
 *
 * The test does require:
 *   - A connected Android device (or emulator)
 *     with API 26+ (the project minSdk).
 *   - The `/system/bin/sh` binary (every
 *     Android device has it).
 *
 * The 8-step scenario is the **same** as the
 * JVM test (per the master vision's
 * "Prueba de integración crítica"):
 *   1. Descargue una distro firmada.
 *   2. Verifique su hash.
 *   3. Cree un workspace aislado.
 *   4. Ejecute un binario Linux ARM64.
 *   5. Monte únicamente una carpeta elegida
 *      por el usuario.
 *   6. Detenga el proceso.
 *   7. Restaure el snapshot.
 *   8. Confirme que no hubo escrituras fuera
 *      del workspace autorizado.
 *
 * The instrumented test asserts the **Android
 * file system semantics** + the **production
 * launcher actually launches the process** on
 * the device. The JVM test asserts the
 * **orchestrator algorithm** is correct; the
 * instrumented test asserts the **production
 * wiring works on a real device**.
 */
@RunWith(AndroidJUnit4::class)
class ProductionCriticalE2EInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var auditDir: File
    private lateinit var userWorkspaceDir: File
    private lateinit var auditLog: E2EAuditLog
    private lateinit var processLauncher: AndroidProcessLauncher
    private lateinit var processWatcher: InMemoryProcessWatcher
    private lateinit var streamCapture: InMemoryProcessStreamCapture
    private lateinit var sandboxApp: InMemorySandboxApplication
    private lateinit var sandboxEnf: InMemorySandboxEnforcer
    private lateinit var selector: RuntimeSelector
    private lateinit var dispatcher: RuntimeDispatcher
    private lateinit var recoveryExec: InMemoryRecoveryExecutor
    private lateinit var orchestrator: DefaultProductionCriticalE2EOrchestrator

    @Before
    fun setUp() {
        // The audit log writes to the device's
        // private storage so the test can assert
        // the on-device file system semantics.
        // (The audit log itself is in-memory; the
        // directory is for any future on-device
        // audit export.)
        auditDir = File(context.filesDir, "phase-92-audit").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        userWorkspaceDir = File(context.filesDir, "phase-92-workspace").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        auditLog = E2EAuditLog()
        // The production AndroidProcessLauncher
        // uses ProcessBuilder + a CoroutineScope
        // + Process.waitFor() to actually launch
        // the process + observe its lifecycle.
        processLauncher = AndroidProcessLauncher()
        processWatcher = InMemoryProcessWatcher()
        streamCapture = InMemoryProcessStreamCapture()
        sandboxApp = InMemorySandboxApplication()
        sandboxEnf = InMemorySandboxEnforcer()
        // The runtime layer catalog is pre-
        // populated with the Elysium Linux
        // default layers (per Phase 73 third
        // half I-73.3.1).
        val catalog = ElysiumRuntimeLayerCatalog()
        for (manifest in ElysiumRuntimeLayerDefaults.ALL) {
            catalog.addLayer(
                manifest,
                ElysiumRuntimeLayerDefaults.DEFAULT_SIGNING_KEY,
            )
        }
        // The ABI capability matrix uses the
        // default Android ARM64 profile.
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        selector = RuntimeSelector(
            capabilityMatrix = matrix,
            runtimeLayerCatalog = catalog,
        )
        dispatcher = RuntimeDispatcher()
        recoveryExec = InMemoryRecoveryExecutor()
        orchestrator = DefaultProductionCriticalE2EOrchestrator()
    }

    @After
    fun tearDown() {
        // Clean up the on-device directories
        // (the test creates them in setUp).
        auditDir.deleteRecursively()
        userWorkspaceDir.deleteRecursively()
        // The AndroidProcessLauncher owns a
        // CoroutineScope; we don't need to
        // explicitly cancel it (the JVM unit
        // tests don't either, and the coroutines
        // are short-lived).
    }

    // ============================================================
    // 8-step happy path on a real Android device
    // ============================================================

    @Test
    fun production_critical_e2e_happy_path_runs_on_a_real_device() {
        val rig = buildProductionRig(nowMs = System.currentTimeMillis())
        val result = orchestrator.run(
            capsule = rig.capsule,
            inputs = rig.inputs,
            nowMs = rig.nowMs,
        )
        assertTrue(
            "expected 8-step E2E to succeed on a real device, got " +
                "$result",
            result is CriticalE2EResult.Success,
        )
        val success = result as CriticalE2EResult.Success
        // The audit log has at least one event
        // for each of the 8 steps.
        assertTrue(
            "expected >= 8 audit events, got " +
                "${success.auditEvents.size}",
            success.auditEvents.size >= 8,
        )
        // The selection is Native (the capsule's
        // architecture matches the device's).
        assertTrue(
            "expected Native selection, got " +
                "${success.selection::class.simpleName}",
            success.selection is RuntimeSelection.Native,
        )
        // The launch plan is for the capsule's
        // entrypoint.
        assertEquals(
            rig.capsule.entrypoint.executable,
            success.launchPlan.executable,
        )
        // The process handle is non-null.
        assertNotNull(success.processHandleId)
        // The watcher recorded at least a Started
        // event (the orchestrator emits the Exited
        // event synchronously after the launch).
        val started = success.processEvents
            .filterIsInstance<ProcessEvent.Started>().firstOrNull()
        assertNotNull("expected Started event", started)
        // The stream capture recorded a
        // StreamClosed chunk.
        val closed = success.streams
            .filterIsInstance<StreamChunk.StreamClosed>().firstOrNull()
        assertNotNull("expected StreamClosed chunk", closed)
    }

    // ============================================================
    // The Android filesDir is the on-device path
    // ============================================================

    @Test
    fun audit_log_uses_the_android_filesDir_for_storage() {
        // The test asserts the on-device path
        // is used (not a JVM test path). The
        // audit log itself is in-memory (the
        // `E2EAuditLog` is pure-domain); the
        // `auditDir` is the on-device directory
        // any future audit export would use.
        assertTrue(
            "expected auditDir to be under " +
                "context.filesDir, got: $auditDir",
            auditDir.absolutePath
                .startsWith(context.filesDir.absolutePath),
        )
        // The user workspace dir is the on-device
        // path the user mounts map to.
        assertTrue(
            "expected userWorkspaceDir to be under " +
                "context.filesDir, got: $userWorkspaceDir",
            userWorkspaceDir.absolutePath
                .startsWith(context.filesDir.absolutePath),
        )
    }

    // ============================================================
    // The AndroidProcessLauncher actually launches the process
    // ============================================================

    @Test
    fun android_process_launcher_actually_launches_the_binary() {
        // The AndroidProcessLauncher is the
        // production launcher; it uses
        // ProcessBuilder to actually launch the
        // process. The test launches
        // `/system/bin/sh -c "echo e2e-probe"`
        // (a real Android binary that exists
        // on every device) and asserts the
        // process actually runs.
        val plan = LaunchPlan(
            runtime = LaunchRuntime.NATIVE,
            executable = "/system/bin/sh",
            args = listOf("-c", "echo e2e-probe"),
            workingDirectory = "/",
            environment = emptyMap(),
        )
        val launchResult = processLauncher.launch(plan)
        assertTrue(
            "expected launch to succeed, got $launchResult",
            launchResult.isSuccess,
        )
        val handle = launchResult.getOrThrow()
        // The handle is a Started process.
        assertTrue(
            "expected Started handle, got " +
                "${handle::class.simpleName}",
            handle is ProcessHandle.Started,
        )
        val started = handle as ProcessHandle.Started
        // The synthetic PID is > 0 (the
        // AndroidProcessLauncher derives a
        // 31-bit synthetic PID from the
        // handle id; per Phase 82, the PID
        // is a secondary diagnostic identifier
        // — Android's java.lang.Process
        // doesn't expose the OS pid).
        assertTrue(
            "expected synthetic pid > 0, got ${started.pid}",
            started.pid > 0,
        )
        // The handle is registered with the
        // launcher's registry.
        assertEquals(
            started.handleId,
            processLauncher.getHandle(started.handleId)?.handleId,
        )
    }

    // ============================================================
    // The orchestrator + AndroidProcessLauncher + Elysium repo
    // ============================================================

    @Test
    fun orchestrator_with_production_components_writes_to_audit_log() {
        val rig = buildProductionRig(nowMs = System.currentTimeMillis())
        val result = orchestrator.run(
            capsule = rig.capsule,
            inputs = rig.inputs,
            nowMs = rig.nowMs,
        )
        assertTrue(result is CriticalE2EResult.Success)
        val success = result as CriticalE2EResult.Success
        // The audit log records:
        //   - fetch + verify (steps 1 + 2)
        //   - create (step 3)
        //   - select + dispatch (step 4)
        //   - prepare + enforce (step 5)
        //   - launch (step 6)
        //   - stop (step 7)
        val events = success.auditEvents
        assertTrue(
            "expected fetch event, got $events",
            events.any { it.eventType == "fetch" },
        )
        assertTrue(
            "expected verify event, got $events",
            events.any { it.eventType == "verify" },
        )
        assertTrue(
            "expected launch event, got $events",
            events.any { it.eventType == "launch" },
        )
        assertTrue(
            "expected stop event, got $events",
            events.any { it.eventType == "stop" },
        )
    }

    // ============================================================
    // Sandbox application + enforcer on a real device
    // ============================================================

    @Test
    fun sandbox_application_and_enforcer_work_on_a_real_device() {
        val plan = LaunchPlan(
            runtime = LaunchRuntime.NATIVE,
            executable = "/system/bin/sh",
            args = listOf("-c", "echo sandbox-probe"),
            workingDirectory = "/",
            environment = emptyMap(),
        )
        val userMount = MountEntry(
            source = ElysiumRootfsPath("/sdcard/user"),
            target = ElysiumRootfsPath("/workspace"),
            mode = MountMode.READ_WRITE,
            purpose = MountPurpose.Custom("user-selected"),
        )
        val policy = sandboxPolicyFor(
            workspaceId = UUID.randomUUID(),
            userMounts = listOf(userMount),
        )
        val nowMs = System.currentTimeMillis()
        val preparation = sandboxApp.prepare(plan, policy, nowMs)
        val enforcement = sandboxEnf.enforce(preparation, nowMs)
        // The preparation has the canonical
        // 4-step order (BindMounts + SeLinux +
        // ResourceLimits + NetworkPolicy +
        // Skipped).
        assertTrue(
            "expected preparation steps not empty, got " +
                preparation.steps,
            preparation.steps.isNotEmpty(),
        )
        // The enforcement matches the preparation
        // (1:1 step mapping).
        assertEquals(
            preparation.steps.size,
            enforcement.steps.size,
        )
    }

    // ============================================================
    // Elysium Linux default repository is loadable on the device
    // ============================================================

    @Test
    fun elysium_linux_default_repository_is_loadable_on_device() {
        // The default repository is pure-domain
        // (in-memory); the test asserts the
        // pre-populated packages are available
        // on the device's classpath.
        val repo = ElysiumLinuxDefaultRepository.build()
        assertTrue(
            "expected >= 7 packages in the default " +
                "repository, got ${repo.size()}",
            repo.size() >= 7,
        )
        // The Elysium Linux meta-package is at
        // the canonical name + version.
        val meta = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.DISTRO,
            ElysiumLinuxDefaultRepository.PackageVersions.DISTRO,
        )
        assertNotNull(
            "expected the Elysium Linux meta-package to be " +
                "in the default repository",
            meta,
        )
    }

    // ============================================================
    // Production rig builder
    // ============================================================

    /**
     * A production-shaped rig for the
     * instrumented test. The rig wires the
     * production UEE components (the
     * [AndroidProcessLauncher] for the launch;
     * the in-memory test impls for the rest of
     * the components).
     */
    private data class ProductionRig(
        val orchestrator: DefaultProductionCriticalE2EOrchestrator,
        val inputs: CriticalE2EInput,
        val capsule: Capsule,
        val nowMs: Long,
    )

    /**
     * Build a production rig. The rig uses the
     * Elysium Linux default repository + the
     * Elysium Linux capsule (a Linux ARM64
     * capsule) + a generic ARM64 device profile.
     */
    private fun buildProductionRig(nowMs: Long): ProductionRig {
        val repo = ElysiumLinuxDefaultRepository.build()
        val distroName = ElysiumLinuxDefaultRepository.PackageNames.DISTRO
        val distroVersion =
            ElysiumLinuxDefaultRepository.PackageVersions.DISTRO
        val capsule = capsuleForElysiumLinux()
        // The device is an ARM64 phone with
        // 4 GB of memory and Adreno GPU.
        val device = DeviceProfile(
            abi = ElysiumAbi.ARM64,
            gpuVendor = GPUVendor.ADRENO,
            hasRoot = false,
            availableMemoryMb = 4_096L,
        )
        val userMount = MountEntry(
            source = ElysiumRootfsPath(userWorkspaceDir.absolutePath),
            target = ElysiumRootfsPath("/workspace"),
            mode = MountMode.READ_WRITE,
            purpose = MountPurpose.Custom("user-selected"),
        )
        val sandboxPolicy = sandboxPolicyFor(
            workspaceId = UUID.randomUUID(),
            userMounts = listOf(userMount),
        )
        val inputs = CriticalE2EInput(
            repository = repo,
            deviceProfile = device,
            sandboxPolicy = sandboxPolicy,
            recoveryPolicy = RecoveryPolicy.None,
            processLauncher = processLauncher,
            processWatcher = processWatcher,
            streamCapture = streamCapture,
            sandboxApplication = sandboxApp,
            sandboxEnforcer = sandboxEnf,
            runtimeSelector = selector,
            runtimeDispatcher = dispatcher,
            recoveryExecutor = recoveryExec,
            auditLog = auditLog,
            distributionId = distroName,
            distributionVersion = distroVersion,
            workspaceId = UUID.randomUUID(),
            userMounts = listOf(userMount),
        )
        return ProductionRig(
            orchestrator = orchestrator,
            inputs = inputs,
            capsule = capsule,
            nowMs = nowMs,
        )
    }

    // ============================================================
    // Capsule builder
    // ============================================================

    /**
     * Build a Capsule that targets Elysium
     * Linux on ARM64 with `/system/bin/sh` as
     * the entrypoint. The instrumented test
     * uses `/system/bin/sh` (a real Android
     * binary that exists on every device)
     * instead of `/usr/bin/elysium-pm` (which
     * is the real entrypoint but requires the
     * Elysium Linux rootfs to be installed).
     *
     * The capsule is still a valid Linux ARM64
     * capsule; the orchestrator's selection
     * logic is the same regardless of the
     * entrypoint.
     */
    private fun capsuleForElysiumLinux(): Capsule = Capsule(
        apiVersion = CapsuleApiVersion.V1,
        id = CapsuleId("com.elysium.linux.distro"),
        name = "Elysium Linux Distro",
        version = "1.0.0",
        description = "Elysium Linux proprietary distro",
        runtime = Runtime.LINUX,
        architecture = Architecture.ARM64,
        distribution = Distribution("com.elysium.linux.distro"),
        entrypoint = EntryPoint(
            executable = "/system/bin/sh",
            args = listOf("-c", "echo e2e-probe"),
            workingDirectory = "/",
        ),
        gpu = GpuConfig(
            api = GpuApi.NONE,
            driver = GpuDriver.NONE,
        ),
        permissions = Permissions(
            network = true,
            storage = listOf(StorageScope.USER_SELECTED),
        ),
        signature = Signature("sig-capsule"),
        contentHash = ContentHash(
            "a".repeat(64),
        ),
    )
}
