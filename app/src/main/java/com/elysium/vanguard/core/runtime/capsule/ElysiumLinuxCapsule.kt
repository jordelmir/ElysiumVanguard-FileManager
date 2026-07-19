package com.elysium.vanguard.core.runtime.capsule

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature

/**
 * Phase 74 (second half) — the **Elysium Linux
 * Capsule**, the runtime contract for the
 * Elysium Linux distro.
 *
 * The Capsule is the **typed manifest** that the
 * orchestrator reads to launch the Elysium Linux
 * distro. The Capsule is the **runtime contract**
 * (how the orchestrator runs the distro); the
 * listing (`ElysiumLinuxDistroListing`, Phase 74
 * first half) is the **distribution contract**
 * (what's in the catalog).
 *
 * The two are linked by `distribution.id`:
 *   - Listing: `id = "com.elysium.linux:distro:1.0.0"`.
 *   - Capsule: `distribution.id = "com.elysium.linux:distro:1.0.0"`.
 *
 * The orchestrator matches the listing + the
 * capsule by `distribution.id`; a mismatched pair
 * is a deployment error.
 *
 * The Capsule's fields are the **Elysium Linux
 * runtime contract**:
 *   - `runtime = LINUX` — the distro is a Linux
 *     binary.
 *   - `architecture = ARM64` — the dominant
 *     Android ABI.
 *   - `entrypoint.executable = "/usr/bin/elysium-pm"`
 *     — the Elysium Linux package manager binary.
 *   - `entrypoint.args = ["init"]` — the command
 *     the orchestrator runs to start the distro.
 *   - `gpu = VULKAN / TURNIP` — the GPU config;
 *     the orchestrator checks the device's actual
 *     Vulkan capability before launching.
 *   - `permissions.network = true` — the package
 *     manager needs network for repository
 *     downloads.
 *   - `permissions.storage = []` — storage is
 *     per-workspace; the user picks the storage
 *     paths at workspace creation time (the
 *     `WorkspaceDefinition.storage` field).
 */
object ElysiumLinuxCapsule {

    /**
     * The Capsule API version. The Capsule
     * format is `elysium.capsule/v1` (Phase 68).
     */
    val API_VERSION: CapsuleApiVersion = CapsuleApiVersion.V1

    /**
     * The Capsule's reverse-DNS id. The id is
     * the canonical reference to the Capsule in
     * the catalog + the orchestrator.
     */
    val ID: CapsuleId = CapsuleId("com.elysium.linux")

    /**
     * The Capsule's display name. The name is
     * what the UI shows in the catalog.
     */
    const val NAME: String = "Elysium Linux"

    /**
     * The Capsule's version. The version follows
     * the Elysium Linux rootfs version (per
     * Phase 73 third half I-73.3.4).
     */
    const val VERSION: String = "1.0.0"

    /**
     * The Capsule's description. The description
     * is the user-facing summary the catalog UI
     * shows.
     */
    const val DESCRIPTION: String =
        "The Elysium Linux runtime contract. The " +
            "Capsule declares the distro's runtime " +
            "(Linux + ARM64), entrypoint " +
            "(/usr/bin/elysium-pm init), GPU " +
            "config (Vulkan on Adreno via Turnip), " +
            "and permissions (network + per-workspace " +
            "storage)."

    /**
     * The Capsule's distribution id. The
     * distribution id matches the listing's id
     * (the listing + the capsule are linked by
     * distribution).
     */
    const val DISTRIBUTION_ID: String = "com.elysium.linux:distro:1.0.0"

    /**
     * The Capsule's runtime. The runtime is
     * `LINUX` — the distro is a Linux binary.
     */
    val RUNTIME: Runtime = Runtime.LINUX

    /**
     * The Capsule's architecture. The
     * architecture is `ARM64` — the dominant
     * Android ABI.
     */
    val ARCHITECTURE: Architecture = Architecture.ARM64

    /**
     * The Capsule's entrypoint. The entrypoint
     * is the Elysium Linux package manager
     * (`elysium-pm`) with the `init` subcommand
     * — the orchestrator runs the package
     * manager's init at launch to set up the
     * rootfs.
     */
    val ENTRYPOINT: EntryPoint = EntryPoint(
        executable = "/usr/bin/elysium-pm",
        args = listOf("init"),
        workingDirectory = "/",
    )

    /**
     * The Capsule's GPU config. The GPU config
     * declares the distro's default GPU API
     * (Vulkan) + the driver the distro prefers
     * (Mesa Turnip for Adreno). The orchestrator
     * checks the device's actual GPU capability
     * before launching (per Phase 62 + Phase 73
     * third half I-73.3.3 ABI capability matrix).
     */
    val GPU: GpuConfig = GpuConfig(
        api = GpuApi.VULKAN,
        driver = GpuDriver.TURNIP,
    )

    /**
     * The Capsule's permissions. The permissions
     * declare:
     *   - `network = true` — the package manager
     *     needs network for repository downloads.
     *   - `storage = []` — storage is per-workspace;
     *     the user picks the storage paths at
     *     workspace creation time. The Capsule
     *     itself does NOT need any storage (the
     *     Elysium Linux rootfs is installed at the
     *     device-level, not at the per-workspace
     *     level).
     */
    val PERMISSIONS: Permissions = Permissions(
        network = true,
        storage = emptyList(),
    )

    /**
     * The Capsule's content hash. The content
     * hash is the placeholder for the real
     * Capsule (a non-blank value; the real hash
     * is set when the Capsule is signed +
     * published).
     */
    val CONTENT_HASH: ContentHash = ContentHash.of("elysium-linux-capsule-placeholder")

    /**
     * The Capsule's signature. The signature is
     * the placeholder for the real Capsule
     * signature (a non-blank value; the real
     * signature is set when the Capsule is
     * signed by the publisher's key).
     */
    val SIGNATURE: Signature = Signature("elysium-linux-capsule-placeholder-signature")

    /**
     * Build a [Capsule] for the Elysium Linux
     * distro. The build is a **typed factory**
     * (no I/O, no Android dependencies) — the
     * publisher calls `build()` to get the
     * unsigned Capsule, then signs + publishes it.
     */
    fun build(): Capsule = Capsule(
        apiVersion = API_VERSION,
        id = ID,
        name = NAME,
        version = VERSION,
        description = DESCRIPTION,
        runtime = RUNTIME,
        architecture = ARCHITECTURE,
        distribution = Distribution(DISTRIBUTION_ID),
        entrypoint = ENTRYPOINT,
        gpu = GPU,
        permissions = PERMISSIONS,
        signature = SIGNATURE,
        contentHash = CONTENT_HASH,
    )
}
