package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.core.linux.ElysiumAbi
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
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 76 second half — the JVM tests for
 * [RuntimeDispatcher].
 *
 * The tests cover:
 *   - Native dispatch: the launch plan is the
 *     capsule's entrypoint + args verbatim.
 *   - Translated dispatch: the launch plan
 *     wraps the capsule's entrypoint with the
 *     translation layer (e.g. `box64
 *     /usr/bin/steam` for Box64 + Steam).
 *   - Unsupported dispatch: the dispatcher
 *     throws a typed error.
 *   - TranslationType → LaunchRuntime mapping.
 */
class RuntimeDispatcherTest {

    // ============================================================
    // Native dispatch
    // ============================================================

    @Test
    fun `native dispatch returns a plan with the capsule's entrypoint verbatim`() {
        val dispatcher = RuntimeDispatcher()
        val capsule = newCapsule(
            runtime = Runtime.LINUX,
            architecture = Architecture.ARM64,
            executable = "/usr/bin/elysium-pm",
            args = listOf("init"),
        )
        val selection = RuntimeSelection.Native(
            layer = com.elysium.vanguard.core.linux.ElysiumRuntimeLayer.Native(
                version = com.elysium.vanguard.core.linux.ElysiumPackageVersion(1, 0, 0),
            ),
        )
        val plan = dispatcher.dispatch(capsule, selection)
        assertEquals(LaunchRuntime.NATIVE, plan.runtime)
        assertEquals("/usr/bin/elysium-pm", plan.executable)
        assertEquals(listOf("init"), plan.args)
        assertEquals("/", plan.workingDirectory)
    }

    @Test
    fun `native dispatch for Elysium Linux capsule returns the elysium-pm init plan`() {
        // The canonical Elysium Linux scenario:
        // the native dispatch produces the
        // `elysium-pm init` plan.
        val dispatcher = RuntimeDispatcher()
        val capsule = com.elysium.vanguard.core.runtime.capsule.ElysiumLinuxCapsule.build()
        val selection = RuntimeSelection.Native(
            layer = com.elysium.vanguard.core.linux.ElysiumRuntimeLayer.Native(
                version = com.elysium.vanguard.core.linux.ElysiumPackageVersion(1, 0, 0),
            ),
        )
        val plan = dispatcher.dispatch(capsule, selection)
        assertEquals(LaunchRuntime.NATIVE, plan.runtime)
        assertEquals("/usr/bin/elysium-pm", plan.executable)
        assertEquals(listOf("init"), plan.args)
    }

    // ============================================================
    // Translated dispatch
    // ============================================================

    @Test
    fun `Box64 dispatch wraps the capsule's entrypoint with box64`() {
        val dispatcher = RuntimeDispatcher()
        val capsule = newCapsule(
            runtime = Runtime.LINUX,
            architecture = Architecture.X86_64,
            executable = "/opt/steam/steam",
            args = listOf("-gamepadui"),
        )
        val selection = RuntimeSelection.Translated(
            layer = com.elysium.vanguard.core.linux.ElysiumRuntimeLayer.Box64(
                version = com.elysium.vanguard.core.linux.ElysiumPackageVersion(0, 3, 2),
            ),
            translation = TranslationType.BOX64,
            targetAbi = ElysiumAbi.X86_64,
        )
        val plan = dispatcher.dispatch(capsule, selection)
        assertEquals(LaunchRuntime.BOX64, plan.runtime)
        // The plan is: `box64 /opt/steam/steam -gamepadui`.
        assertEquals("/usr/bin/box64", plan.executable)
        assertEquals(
            listOf("/usr/bin/box64", "/opt/steam/steam", "-gamepadui"),
            plan.args,
        )
    }

    @Test
    fun `Wine dispatch wraps the capsule's entrypoint with wine`() {
        val dispatcher = RuntimeDispatcher()
        val capsule = newCapsule(
            runtime = Runtime.WINDOWS,
            architecture = Architecture.X86_64,
            executable = "/opt/steam/Steam.exe",
            args = listOf("-silent"),
        )
        val selection = RuntimeSelection.Translated(
            layer = com.elysium.vanguard.core.linux.ElysiumRuntimeLayer.Wine(
                version = com.elysium.vanguard.core.linux.ElysiumPackageVersion(9, 0, 0),
            ),
            translation = TranslationType.WINE,
            targetAbi = ElysiumAbi.X86_64,
        )
        val plan = dispatcher.dispatch(capsule, selection)
        assertEquals(LaunchRuntime.WINE, plan.runtime)
        assertEquals("/usr/bin/wine", plan.executable)
        assertEquals(
            listOf("/usr/bin/wine", "/opt/steam/Steam.exe", "-silent"),
            plan.args,
        )
    }

    @Test
    fun `FEX dispatch wraps the capsule's entrypoint with FEXInterpreter`() {
        val dispatcher = RuntimeDispatcher()
        val capsule = newCapsule(
            runtime = Runtime.LINUX,
            architecture = Architecture.X86,
            executable = "/usr/bin/legacy-app",
            args = emptyList(),
        )
        val selection = RuntimeSelection.Translated(
            layer = com.elysium.vanguard.core.linux.ElysiumRuntimeLayer.Fex(
                version = com.elysium.vanguard.core.linux.ElysiumPackageVersion(2404, 0, 0),
            ),
            translation = TranslationType.FEX,
            targetAbi = ElysiumAbi.X86,
        )
        val plan = dispatcher.dispatch(capsule, selection)
        assertEquals(LaunchRuntime.FEX, plan.runtime)
        assertEquals("/usr/bin/FEXInterpreter", plan.executable)
    }

    // ============================================================
    // Unsupported dispatch
    // ============================================================

    @Test
    fun `unsupported dispatch throws an IllegalArgumentException`() {
        val dispatcher = RuntimeDispatcher()
        val capsule = newCapsule(
            runtime = Runtime.LINUX,
            architecture = Architecture.ARM64,
            executable = "/usr/bin/elysium-pm",
        )
        val selection = RuntimeSelection.Unsupported(
            reason = "missing capabilities for ARM64 + ADRENO",
        )
        try {
            dispatcher.dispatch(capsule, selection)
            fail("expected IllegalArgumentException for Unsupported selection")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'Unsupported', got: ${e.message}",
                e.message!!.contains("Unsupported"),
            )
        }
    }

    // ============================================================
    // TranslationType → LaunchRuntime mapping
    // ============================================================

    @Test
    fun `every TranslationType maps to a LaunchRuntime`() {
        for (type in TranslationType.values()) {
            val runtime = type.toLaunchRuntime()
            // The mapping is total: every
            // TranslationType has a corresponding
            // LaunchRuntime.
            assertTrue(
                "expected $type to map to a non-null LaunchRuntime",
                runtime != null,
            )
        }
    }

    @Test
    fun `every TranslationType has a wrapper executable`() {
        for (type in TranslationType.values()) {
            val wrapper = type.wrapperExecutable
            assertTrue(
                "expected $type to have a non-blank wrapper executable",
                wrapper.isNotBlank(),
            )
        }
    }

    // ============================================================
    // LaunchPlan invariants
    // ============================================================

    @Test
    fun `LaunchPlan rejects blank executable`() {
        try {
            LaunchPlan(
                runtime = LaunchRuntime.NATIVE,
                executable = "",
                args = emptyList(),
                workingDirectory = "/",
                environment = emptyMap(),
            )
            fail("expected IllegalArgumentException for blank executable")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("executable"))
        }
    }

    @Test
    fun `LaunchPlan fullCommandLine joins executable + args by space`() {
        val plan = LaunchPlan(
            runtime = LaunchRuntime.NATIVE,
            executable = "/bin/test",
            args = listOf("--foo", "bar"),
            workingDirectory = "/",
            environment = emptyMap(),
        )
        assertEquals("/bin/test --foo bar", plan.fullCommandLine)
    }

    @Test
    fun `LaunchPlan programAndArgs returns executable followed by args`() {
        val plan = LaunchPlan(
            runtime = LaunchRuntime.NATIVE,
            executable = "/bin/test",
            args = listOf("--foo", "bar"),
            workingDirectory = "/",
            environment = emptyMap(),
        )
        assertEquals(
            listOf("/bin/test", "--foo", "bar"),
            plan.programAndArgs,
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun newCapsule(
        runtime: Runtime,
        architecture: Architecture,
        executable: String,
        args: List<String> = emptyList(),
        gpuApi: GpuApi = GpuApi.NONE,
    ): Capsule = Capsule(
        apiVersion = CapsuleApiVersion.V1,
        id = CapsuleId("com.test.capsule"),
        name = "Test Capsule",
        version = "1.0.0",
        description = "Test",
        runtime = runtime,
        architecture = architecture,
        distribution = Distribution("test:1.0.0"),
        entrypoint = EntryPoint(
            executable = executable,
            args = args,
            workingDirectory = "/",
        ),
        gpu = GpuConfig(api = gpuApi, driver = GpuDriver.NONE),
        permissions = Permissions(
            network = false,
            storage = listOf(StorageScope.APP_PRIVATE),
        ),
        signature = Signature("test-signature"),
        contentHash = ContentHash("0".repeat(64)),
    )
}
