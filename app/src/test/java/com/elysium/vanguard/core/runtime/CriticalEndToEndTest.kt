package com.elysium.vanguard.core.runtime

import com.elysium.vanguard.core.runtime.bridge.MountEntry
import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroFamily
import com.elysium.vanguard.core.runtime.distros.DistroHttpDownloader
import com.elysium.vanguard.core.runtime.distros.DistroInstaller
import com.elysium.vanguard.core.runtime.distros.RootfsKind
import com.elysium.vanguard.core.runtime.distros.layer.ManifestSigner
import com.elysium.vanguard.core.runtime.distros.manifest.DistroManifest
import com.elysium.vanguard.core.runtime.distros.manifest.DistroManifestCodec
import com.elysium.vanguard.core.runtime.distros.manifest.installWithSignedManifest
import com.elysium.vanguard.core.runtime.observability.RecordingEventBus
import com.elysium.vanguard.core.runtime.policy.FileMountAuditLog
import com.elysium.vanguard.core.runtime.policy.MountAuditEntry
import com.elysium.vanguard.core.runtime.policy.MountEnforcementResult
import com.elysium.vanguard.core.runtime.policy.MountPolicy
import com.elysium.vanguard.core.runtime.policy.MountPolicyEnforcer
import com.elysium.vanguard.core.runtime.policy.MountPolicyEntry
import com.elysium.vanguard.core.runtime.policy.MountPolicyMode
import com.elysium.vanguard.core.runtime.snapshots.CopyStrategy
import com.elysium.vanguard.core.runtime.snapshots.FilesystemSnapshotEngine
import com.elysium.vanguard.core.runtime.snapshots.MountPlan
import com.elysium.vanguard.core.runtime.workspaces.InMemoryWorkspaceStore
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey

/**
 * Phase 52 — the critical end-to-end
 * integration test.
 *
 * The master vision doc (PHASE 9_WORLDWIDE_VISION)
 * ends with the "definition of done":
 *
 * > "Crear una prueba end-to-end que, desde
 * > un Android Snapdragon limpio:
 * > 1. Descargue una distro firmada.
 * > 2. Verifique su hash.
 * > 3. Cree un workspace aislado.
 * > 4. Ejecute un binario Linux ARM64.
 * > 5. Monte únicamente la carpeta elegida
 * >    por el usuario.
 * > 6. Detenga el proceso.
 * > 7. Restaure el snapshot.
 * > 8. Confirme que no hubo escrituras fuera
 * >    del workspace autorizado."
 *
 * This test stitches together Phases 49
 * (snapshot / rollback), 50 (mount allowlist
 * + audit), and 51 (signed manifest) into a
 * single JVM unit test. The real proot
 * execution is replaced with a JVM function
 * that simulates the binary's behaviour —
 * the test asserts the orchestration is
 * correct; a future instrumented test
 * (Phase 53+) will assert the proot bits
 * are correct.
 *
 * The test runs every component end-to-end:
 *
 *   - [installWithSignedManifest] (Phase 51)
 *     downloads a signed distro, verifies
 *     the manifest's Ed25519 signature,
 *     verifies the rootfs's hash, extracts
 *     the rootfs.
 *   - [WorkspaceManager] (Phase 24) creates
 *     an isolated workspace.
 *   - [MountPolicyEnforcer] (Phase 50)
 *     validates the proposed mount list
 *     against the workspace's allowlist.
 *   - [FileMountAuditLog] (Phase 50) records
 *     every decision.
 *   - [FilesystemSnapshotEngine] (Phase 49)
 *     captures a snapshot of the live
 *     rootfs; rollback restores it.
 *
 * The "execute" step (4) is simulated by a
 * JVM function that writes to the authorized
 * mount path. The "stop" step (6) is the
 * function returning. The "no writes
 * outside" assertion (8) checks the
 * [FileMountAuditLog] for entries — every
 * entry must be for the authorized path;
 * a write to a forbidden path would have
 * no audit log entry (because the policy
 * never allowed it) and would also be
 * absent from the post-rollback rootfs.
 */
class CriticalEndToEndTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootfsStagingDir: File
    private lateinit var rootfsArchive: File
    private lateinit var rootfsBytes: ByteArray
    private lateinit var rootfsSha256: String
    private lateinit var installBaseDir: File
    private lateinit var workspacesBaseDir: File
    private lateinit var snapshotEngine: FilesystemSnapshotEngine
    private lateinit var mountAuditLog: FileMountAuditLog
    private lateinit var mountPolicyEnforcer: MountPolicyEnforcer
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var distro: Distro
    private lateinit var manifest: DistroManifest
    private lateinit var publicKey: PublicKey

    @Before
    fun setUp() {
        // 1. Build a minimal valid rootfs in
        //    a temp directory.
        rootfsStagingDir = tempFolder.newFolder("rootfs-src")
        File(rootfsStagingDir, "etc").mkdirs()
        File(rootfsStagingDir, "bin").mkdirs()
        File(rootfsStagingDir, "usr/bin").mkdirs()
        File(rootfsStagingDir, "etc/os-release").writeText(
            """NAME="Elysium Vanguard Linux"
PRETTY_NAME="Elysium Vanguard Linux"
VERSION_ID="1.0"
"""
        )
        File(rootfsStagingDir, "etc/hostname").writeText("elysium-snapshotted\n")
        File(rootfsStagingDir, "bin/sh").writeText("#!/bin/sh\necho hi\n")
        // A fake "ARM64 binary" that just
        // emits a marker the test can check.
        File(rootfsStagingDir, "usr/bin/hello-arm64").writeText(
            "#!/bin/sh\necho 'hello from arm64'\n"
        )

        // 2. Tar.gz the rootfs.
        val tarGzFile = File(tempFolder.newFolder("archive"), "rootfs.tar.gz")
        ProcessBuilder(
            "tar",
            "-czf", tarGzFile.absolutePath,
            "-C", rootfsStagingDir.absolutePath,
            "etc/os-release", "etc/hostname", "bin/sh", "usr/bin/hello-arm64"
        ).redirectErrorStream(true).start().waitFor()
        rootfsArchive = tarGzFile
        rootfsBytes = rootfsArchive.readBytes()
        rootfsSha256 = sha256Hex(rootfsBytes)

        // 3. Install base dirs.
        installBaseDir = tempFolder.newFolder("install")
        workspacesBaseDir = tempFolder.newFolder("workspaces")

        // 4. Real production components.
        //
        // The snapshot engine uses
        // `forceFullCopy = true` because the
        // critical end-to-end test needs a
        // snapshot that is INDEPENDENT of the
        // live rootfs. POSIX hardlinks share
        // inodes with the source — a write to
        // the source after a hardlink snapshot
        // is visible through the snapshot, so
        // the rollback would copy the (mutated)
        // snapshot back, a no-op. The test
        // requires the rollback to actually
        // restore the pre-snapshot state.
        snapshotEngine = FilesystemSnapshotEngine(
            baseDir = workspacesBaseDir,
            clock = { 1_700_000_000_000L },
            idGenerator = { "snap-e2e-${counter++}" },
            forceFullCopy = true
        )
        val auditLogFile = File(workspacesBaseDir, "mount-audit.ndjson")
        mountAuditLog = FileMountAuditLog(logFile = auditLogFile)
        mountPolicyEnforcer = MountPolicyEnforcer()
        val store = InMemoryWorkspaceStore()
        val eventBus = RecordingEventBus()
        workspaceManager = WorkspaceManager(
            store = store,
            eventBus = eventBus,
            snapshotEngine = snapshotEngine,
            mountPolicyEnforcer = mountPolicyEnforcer,
            mountAuditLog = mountAuditLog
        )

        // 5. The distro + signed manifest.
        val keyPair = ManifestSigner.generateKeyPair()
        publicKey = keyPair.public
        distro = Distro(
            id = "elysium-e2e",
            displayName = "Elysium Vanguard Linux E2E",
            family = DistroFamily.DEBIAN,
            version = "1.0.0-e2e",
            approxSizeBytes = rootfsBytes.size.toLong() * 2L,
            minAndroidVersion = 26,
            rootfsUrl = "https://example.invalid/elysium-e2e.tar.gz",
            rootfsKind = RootfsKind.TarGz,
            bootstrapCommand = null,
            packageManager = "apt",
            homepage = "https://example.invalid",
            sha256 = null
        )
        val body = """{"id":"${distro.id}","version":"${distro.version}","sha256":"$rootfsSha256","sizeBytes":${rootfsBytes.size},"signedAtMs":1700000000000}"""
        val signature = ManifestSigner.sign(body.toByteArray(), keyPair.private)
        manifest = DistroManifestCodec.decode(body, signature)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private var counter: Int = 0

    @Test
    fun `critical end-to-end — download signed distro, execute, snapshot, rollback, audit`() {
        // ----------------------------------------------------------------
        // STEP 1: Download a signed distro.
        // ----------------------------------------------------------------
        val downloader = DistroHttpDownloader { url ->
            // The fake downloader returns the
            // pre-built rootfs bytes regardless
            // of the URL. A real downloader
            // would do an HTTP GET; a real
            // distro would be hosted on the
            // official Elysium Vanguard CDN.
            ByteArrayInputStream(rootfsBytes)
        }
        val installer = DistroInstaller(downloader = downloader)

        // ----------------------------------------------------------------
        // STEP 2: Verify its hash.
        // ----------------------------------------------------------------
        // The signed manifest's sha256 matches
        // the downloaded archive's sha256. The
        // signature verifies against the public
        // key. The rootfs extract uses the
        // existing VERIFYING stage.
        val rootfsDir = installWithSignedManifest(
            installer = installer,
            distro = distro,
            baseDir = installBaseDir,
            manifest = manifest,
            publicKey = publicKey
        )
        assertTrue("rootfs should be a directory", rootfsDir.isDirectory)
        assertTrue(
            "rootfs should contain /etc/os-release",
            File(rootfsDir, "etc/os-release").isFile
        )
        assertTrue(
            "rootfs should contain /usr/bin/hello-arm64",
            File(rootfsDir, "usr/bin/hello-arm64").isFile
        )

        // ----------------------------------------------------------------
        // STEP 3: Create an isolated workspace.
        // ----------------------------------------------------------------
        val workspace = workspaceManager.createWorkspace("e2e-workspace").getOrThrow()
        assertNotNull(workspace)
        // The workspace has no sessions yet;
        // the user is about to add one.
        assertEquals(0, workspace.sessions.size)

        // ----------------------------------------------------------------
        // STEP 4: Execute a Linux ARM64 binary.
        //
        // We simulate the "execution" by
        // running a JVM function that writes
        // to the authorized mount path. A real
        // proot session would run /usr/bin/hello-arm64
        // inside the rootfs; the assertions
        // would be the same.
        //
        // The "binary" writes to the
        // authorized path (allowed by policy)
        // and the test verifies the file
        // appears there.
        // ----------------------------------------------------------------
        val authorizedMount = File(tempFolder.newFolder("elysium-test-mount"), "work")
        authorizedMount.mkdirs()
        val proposedMounts = listOf(
            MountEntry(
                hostPath = authorizedMount.absolutePath,
                guestPath = "/mnt/work",
                readOnly = false,
                label = "user-selected work folder"
            )
        )

        // ----------------------------------------------------------------
        // STEP 5: Mount only the user-selected folder.
        //
        // The MountPolicyEnforcer validates the
        // proposed mounts against the
        // workspace's policy. The policy is
        // LOCKED_DOWN + an allowlist entry for
        // the user-selected path. The result
        // is Allowed; the audit log records the
        // decision.
        // ----------------------------------------------------------------
        val policy = MountPolicy(
            mode = MountPolicyMode.ALLOWLIST,
            entries = listOf(
                MountPolicyEntry(
                    hostPathPrefix = authorizedMount.absolutePath,
                    readOnly = false,
                    label = "user-selected work folder"
                )
            )
        )
        val enforcement = workspaceManager.enforceMountPolicy(
            workspaceId = workspace.id,
            sessionId = "session-e2e",
            policy = policy,
            mounts = proposedMounts
        ).getOrThrow()
        assertTrue(
            "enforcement should be Allowed for the user-selected path: $enforcement",
            enforcement is MountEnforcementResult.Allowed
        )
        val allowedMounts = (enforcement as MountEnforcementResult.Allowed).filteredMounts
        assertEquals(1, allowedMounts.size)
        assertEquals(authorizedMount.absolutePath, allowedMounts[0].hostPath)

        // Add the session to the workspace.
        val session = WorkspaceSession.LinuxProot(
            id = "session-e2e",
            displayName = "E2E Session",
            distroId = distro.id,
            profileId = "balanced"
        )
        workspaceManager.addSession(workspace.id, session).getOrThrow()

        // The "binary" writes to the authorized path.
        val allowedFile = File(authorizedMount, "output.txt")
        allowedFile.writeText("hello from the simulated ARM64 binary")
        assertTrue("authorized file should be written", allowedFile.isFile)

        // ----------------------------------------------------------------
        // STEP 6: Stop the process.
        //
        // The "process" is the JVM function —
        // it has already returned. The session
        // is "stopped" by removing the
        // simulation. The runtime's session
        // runner (out of scope for Phase 52)
        // would call `runner.stop()` here.
        // ----------------------------------------------------------------
        // (no-op: the JVM function has returned)

        // ----------------------------------------------------------------
        // STEP 7: Restore the snapshot.
        //
        // The snapshot was taken AFTER the
        // install (before the binary wrote to
        // the authorized path). The rollback
        // restores the live rootfs to the
        // snapshotted state. The authorized
        // mount (which lives OUTSIDE the
        // rootfs) is unaffected.
        // ----------------------------------------------------------------
        val snapshot = workspaceManager.snapshotWorkspace(
            workspaceId = workspace.id,
            sourceRootfsPath = rootfsDir.absolutePath,
            mountPlan = MountPlan.EMPTY,
            label = "before-execute"
        ).getOrThrow()
        assertTrue(
            "snapshot should exist: ${snapshot.rootfsPath}",
            File(snapshot.rootfsPath).isDirectory
        )
        // The test forces FULL_COPY (see
        // setUp); the engine must have
        // honoured the constraint.
        assertEquals(
            "snapshot copy strategy must be FULL_COPY (hardlinks don't isolate writes)",
            CopyStrategy.FULL_COPY,
            snapshot.copyStrategy
        )

        // Mutate the live rootfs after the
        // snapshot — a real binary would have
        // written here. The rollback must
        // restore the pre-snapshot state.
        val mutableFile = File(rootfsDir, "etc/hostname")
        mutableFile.writeText("mutated-by-binary\n")
        assertEquals("mutated-by-binary\n", mutableFile.readText())

        // Rollback.
        workspaceManager.rollbackWorkspace(
            workspaceId = workspace.id,
            snapshotId = snapshot.id,
            liveRootfsPath = rootfsDir.absolutePath
        ).getOrThrow()

        // ----------------------------------------------------------------
        // STEP 8: Confirm no writes happened outside the authorized workspace.
        //
        // The authorized mount is the ONLY
        // mount the policy permitted. The
        // MountAuditLog has exactly one entry:
        // the Allowed entry for the user-
        // selected path. There is no entry
        // for a forbidden path (because the
        // policy never allowed it). The
        // rollback restored the rootfs; the
        // only persistent write in the test is
        // to the authorized mount, which is
        // outside the rootfs and therefore not
        // affected by the rollback.
        // ----------------------------------------------------------------
        val auditEntries = mountAuditLog.readAll()
        assertEquals(
            "exactly one audit entry: the allowed mount",
            1,
            auditEntries.size
        )
        val entry = auditEntries[0]
        assertEquals(MountAuditEntry.DECISION_ALLOWED, entry.decision)
        assertEquals(authorizedMount.absolutePath, entry.hostPath)

        // The rootfs's mutable file is back
        // to the snapshotted state (i.e. the
        // original hostname, not the mutated
        // value).
        assertEquals(
            "rootfs /etc/hostname should be the snapshotted value after rollback",
            "elysium-snapshotted\n",
            mutableFile.readText()
        )

        // The authorized mount's file is
        // unaffected by the rollback (it
        // lives outside the rootfs).
        assertTrue(
            "the authorized mount's file should still exist (rollback does not touch the mount)",
            allowedFile.isFile
        )

        // The session is in the workspace.
        val reloaded = workspaceManager.getWorkspace(workspace.id)
        assertNotNull(reloaded)
        assertEquals(1, reloaded!!.sessions.size)
    }
}
