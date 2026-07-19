package com.elysium.vanguard.core.linux

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature

/**
 * Phase 73 third half (I-73.3.1) — the **Elysium Runtime Layer**.
 *
 * Per sección 10 of the user's Elysium Linux vision
 * doc: the distro is not just a Linux rootfs — it
 * is the **composite runtime** that lets a single
 * Android device run native ARM64 binaries, Vulkan
 * GPU code (via Mesa/Turnip), x86_64 userland
 * binaries (via Box64), x86 userland binaries (via
 * FEX), and Windows binaries (via Wine).
 *
 * Each of these is a **runtime layer**: a typed
 * bundle of native libraries + the orchestrator
 * glue that loads them at process start. The Elysium
 * Linux distro ships a **catalog of runtime layers**;
 * the user picks which layers are installed; the
 * orchestrator composes them per launch.
 *
 * The catalog is **versioned**: every layer has a
 * version (the Mesa project ships ~monthly; Box64
 * ships weekly; Wine ships yearly; FEX ships
 * monthly). The user can pin a specific version
 * (for reproducibility) or accept the latest
 * (for the newest features).
 *
 * The layer is **content-addressed by composition**:
 * the same `id` + `version` + `abi` + `dependencies` +
 * `files` produces the same canonical form, which
 * has the same SHA-256 content hash. The signature
 * binds the manifest to the publisher (the Elysium
 * Linux distribution team).
 */
sealed class ElysiumRuntimeLayer {

    /**
     * The canonical id. Every layer has a stable,
     * lowercase, hyphen-separated id (e.g.
     * `"mesa-turnip"`, `"box64"`, `"wine"`).
     */
    abstract val id: ElysiumRuntimeLayerId

    /**
     * The layer version (semver). The user can pin
     * a specific version or accept the latest.
     */
    abstract val version: ElysiumPackageVersion

    /**
     * The ABI the layer is compiled for. A native
     * ARM64 layer is `ElysiumAbi.ARM64`; a Box64
     * layer that runs on ARM64 is `ElysiumAbi.ARM64`
     * (the host ABI) — the Box64 binary itself is
     * the host binary; it translates x86_64 guest
     * binaries.
     */
    abstract val hostAbi: ElysiumAbi

    /**
     * The human-readable name. The name is what the
     * UI shows in the runtime layer list.
     */
    abstract val displayName: String

    /**
     * The capabilities the layer provides. The
     * capabilities are the **typed features** the
     * orchestrator uses to decide whether a capsule
     * can run with this layer (e.g. a Vulkan
     * capsule needs a Vulkan-capable layer).
     */
    abstract val capabilities: Set<ElysiumRuntimeCapability>

    /**
     * The native ARM64 baseline. Every Elysium Linux
     * install has the native layer (the rootfs
     * itself is the native layer; it is the default
     * runtime when no translation is needed).
     *
     * The native layer has no dependencies (the
     * rootfs is the base) and provides the
     * `EXECUTE_NATIVE` capability.
     */
    data class Native(
        override val version: ElysiumPackageVersion,
        override val hostAbi: ElysiumAbi = ElysiumAbi.ARM64,
    ) : ElysiumRuntimeLayer() {
        override val id: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId.NATIVE
        override val displayName: String = "Native (${ElysiumAbi.canonicalName(hostAbi)})"
        override val capabilities: Set<ElysiumRuntimeCapability> = setOf(
            ElysiumRuntimeCapability.EXECUTE_NATIVE,
        )
    }

    /**
     ** Mesa Turnip — the open-source Vulkan driver
     ** for Qualcomm Adreno GPUs. Turnip is the
     ** GPU acceleration layer for Vulkan capsules
     ** on Adreno devices.
     **
     ** The Turnip layer provides the
     ** `GPU_VULKAN_TURNIP` capability + the
     ** `GPU_VULKAN` super-capability.
     */
    data class MesaTurnip(
        override val version: ElysiumPackageVersion,
        override val hostAbi: ElysiumAbi = ElysiumAbi.ARM64,
    ) : ElysiumRuntimeLayer() {
        override val id: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId.MESA_TURNIP
        override val displayName: String = "Mesa Turnip ${version.canonical}"
        override val capabilities: Set<ElysiumRuntimeCapability> = setOf(
            ElysiumRuntimeCapability.GPU_VULKAN,
            ElysiumRuntimeCapability.GPU_VULKAN_TURNIP,
        )
    }

    /**
     ** Box64 — the user-mode x86_64 binary
     ** translator for ARM64. Box64 lets the
     ** orchestrator launch x86_64 Linux binaries
     ** (e.g. Steam, Blender x86_64, etc.) on an
     ** ARM64 device without a full VM.
     **
     ** The Box64 layer provides the
     ** `EXECUTE_X86_64` capability.
     */
    data class Box64(
        override val version: ElysiumPackageVersion,
        override val hostAbi: ElysiumAbi = ElysiumAbi.ARM64,
    ) : ElysiumRuntimeLayer() {
        override val id: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId.BOX64
        override val displayName: String = "Box64 ${version.canonical}"
        override val capabilities: Set<ElysiumRuntimeCapability> = setOf(
            ElysiumRuntimeCapability.EXECUTE_X86_64,
        )
    }

    /**
     ** FEX-Emu — the user-mode x86 (32-bit) binary
     ** translator for ARM64. FEX is the smaller-
     ** ISA sibling of Box64; it runs legacy 32-bit
     ** x86 binaries.
     **
     ** The FEX layer provides the
     ** `EXECUTE_X86` capability.
     */
    data class Fex(
        override val version: ElysiumPackageVersion,
        override val hostAbi: ElysiumAbi = ElysiumAbi.ARM64,
    ) : ElysiumRuntimeLayer() {
        override val id: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId.FEX
        override val displayName: String = "FEX-Emu ${version.canonical}"
        override val capabilities: Set<ElysiumRuntimeCapability> = setOf(
            ElysiumRuntimeCapability.EXECUTE_X86,
        )
    }

    /**
     ** Wine — the Windows API re-implementation
     ** for Linux. Wine lets the orchestrator launch
     ** Windows PE binaries (`.exe`, `.msi`).
     **
     ** The Wine layer is **versioned per major
     ** release** (Wine 9.0, Wine 10.0, etc.). The
     ** user can have multiple Wine versions
     ** installed side-by-side (a Wine 9.0 prefix
     ** is incompatible with a Wine 10.0 prefix
     ** without a migration).
     **
     ** The Wine layer provides the
     ** `EXECUTE_WINDOWS` capability. With DXVK
     ** installed (a separate runtime layer in a
     ** future phase), Wine can also provide
     ** `GPU_VULKAN_DXVK` (Direct3D 9-11 over
     ** Vulkan).
     */
    data class Wine(
        override val version: ElysiumPackageVersion,
        override val hostAbi: ElysiumAbi = ElysiumAbi.ARM64,
    ) : ElysiumRuntimeLayer() {
        override val id: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId.WINE
        override val displayName: String = "Wine ${version.canonical}"
        override val capabilities: Set<ElysiumRuntimeCapability> = setOf(
            ElysiumRuntimeCapability.EXECUTE_WINDOWS,
        )
    }
}

/**
 * The canonical id of a runtime layer. The id is
 * a stable, lowercase, hyphen-separated string
 * (e.g. `"mesa-turnip"`, `"box64"`, `"wine"`).
 *
 * The id is **distinct from the package name**:
 * the layer is a typed Elysium Linux concept, not
 * a package. The package manager may ship the
 * layer as a package (e.g.
 * `com.elysium.runtime.mesa-turnip`), but the
 * layer's identity is the layer id, not the
 * package name.
 */
data class ElysiumRuntimeLayerId(val value: String) {
    init {
        require(value.isNotBlank()) {
            "ElysiumRuntimeLayerId.value must not be blank"
        }
        require(value.matches(Regex(PATTERN))) {
            "ElysiumRuntimeLayerId.value must match $PATTERN, got: $value"
        }
    }

    /** The string form. The string is the id. */
    override fun toString(): String = value

    companion object {
        /** The id pattern. Lowercase letters, digits,
         *  hyphens. Must start with a letter. */
        const val PATTERN: String = "^[a-z][a-z0-9-]*$"

        /** The native ARM64 baseline layer. */
        val NATIVE: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId("native")

        /** Mesa Turnip (Vulkan driver for Adreno). */
        val MESA_TURNIP: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId("mesa-turnip")

        /** Box64 (x86_64 to ARM64 user-mode translation). */
        val BOX64: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId("box64")

        /** FEX-Emu (x86 to ARM64 user-mode translation). */
        val FEX: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId("fex")

        /** Wine (Windows API re-implementation). */
        val WINE: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId("wine")
    }
}

/**
 * The typed capabilities a runtime layer provides.
 * The capabilities are the **machine-readable
 * features** the orchestrator uses to decide
 * whether a capsule can run with this layer.
 *
 * The capabilities form a **set**: a layer either
 * provides a capability or it doesn't. The
 * orchestrator computes the **capability set** of
 * the installed layers + checks whether the
 * capsule's declared requirements are satisfied.
 */
enum class ElysiumRuntimeCapability {
    /** The layer can execute native binaries
     *  (compiled for the device's ABI). */
    EXECUTE_NATIVE,

    /** The layer can execute x86_64 Linux binaries
     *  (via Box64 user-mode translation). */
    EXECUTE_X86_64,

    /** The layer can execute x86 (32-bit) Linux
     *  binaries (via FEX user-mode translation). */
    EXECUTE_X86,

    /** The layer can execute Windows PE binaries
     *  (via Wine). */
    EXECUTE_WINDOWS,

    /** The layer provides Vulkan GPU support
     *  (the umbrella capability). */
    GPU_VULKAN,

    /** The layer provides Mesa Turnip (Vulkan on
     *  Adreno specifically). */
    GPU_VULKAN_TURNIP,

    /** The layer provides DXVK (Direct3D 9-11 over
     *  Vulkan, for Wine). */
    GPU_VULKAN_DXVK,

    /** The layer provides VKD3D-Proton (Direct3D 12
     *  over Vulkan, for Wine). */
    GPU_VULKAN_VKD3D,

    /** The layer provides OpenGL ES GPU support. */
    GPU_OPENGL_ES,
}

/**
 * The manifest of a runtime layer. The manifest
 * is the **typed contract** between the Elysium
 * Linux distribution team (the publisher) and the
 * orchestrator (the consumer). The manifest is:
 *   - `id: ElysiumRuntimeLayerId`
 *   - `version: ElysiumPackageVersion`
 *   - `hostAbi: ElysiumAbi`
 *   - `displayName: String`
 *   - `capabilities: Set<ElysiumRuntimeCapability>`
 *   - `dependencies: List<ElysiumPackageDependency>`
 *     — the Elysium packages the layer requires
 *     (e.g. Mesa Turnip may depend on a particular
 *     kernel-module package).
 *   - `provides: List<String>` — the Elysium
 *     packages the layer provides (e.g. the
 *     `libvulkan_adreno.so` library is provided
 *     by the Mesa Turnip layer).
 *   - `files: List<ElysiumPackageFile>` — the
 *     files the layer installs in the rootfs
 *     (binary path + content hash + permissions).
 *   - `description: String` — the user-facing
 *     description.
 *   - `homepage: String` — the upstream project's
 *     homepage.
 *   - `contentHash: ContentHash` — the SHA-256 of
 *     the layer tarball.
 *   - `signature: Signature` — the signature on
 *     the canonical form of the manifest.
 *
 * The manifest follows the same trust model as
 * [ElysiumPackageManifest]: the canonical form
 * excludes the signature; `verifySignature`
 * rebuilds the canonical form + signs + compares.
 */
data class ElysiumRuntimeLayerManifest(
    val id: ElysiumRuntimeLayerId,
    val version: ElysiumPackageVersion,
    val hostAbi: ElysiumAbi,
    val displayName: String,
    val capabilities: Set<ElysiumRuntimeCapability>,
    val dependencies: List<ElysiumPackageDependency> = emptyList(),
    val provides: List<String> = emptyList(),
    val files: List<ElysiumPackageFile> = emptyList(),
    val description: String,
    val homepage: String,
    val contentHash: ContentHash,
    val signature: Signature,
) {
    init {
        require(displayName.isNotBlank()) {
            "ElysiumRuntimeLayerManifest.displayName must not be blank"
        }
        require(description.isNotBlank()) {
            "ElysiumRuntimeLayerManifest.description must not be blank"
        }
        require(homepage.isNotBlank()) {
            "ElysiumRuntimeLayerManifest.homepage must not be blank"
        }
        // Every `provides` capability is non-blank.
        require(provides.all { it.isNotBlank() }) {
            "ElysiumRuntimeLayerManifest.provides must not contain blank entries"
        }
        // A runtime layer must declare at least
        // one file (an empty file list is a smell).
        require(files.isNotEmpty()) {
            "ElysiumRuntimeLayerManifest.files must not be empty; " +
                "an empty layer is a deployment error"
        }
    }

    /**
     * The canonical form. The form is the
     * deterministic UTF-8 byte sequence used to
     * compute the manifest's signature + to verify
     * the signature at install time.
     *
     * The form EXCLUDES the `signature` (the
     * signature is computed over the form; the
     * form is the input, not the output, of the
     * signature).
     */
    val canonicalForm: String
        get() = buildString {
            append("elysium-runtime-layer:v1")
            append("|id=").append(id.value)
            append("|version=").append(version.canonical)
            append("|hostAbi=").append(ElysiumAbi.canonicalName(hostAbi))
            append("|displayName=").append(displayName)
            append("|capabilities=")
            append(capabilities.sortedBy { it.name }.joinToString(",") { it.name })
            append("|deps=")
            append(
                dependencies.sortedBy { it.packageName }
                    .joinToString(";") { dep -> dep.canonical },
            )
            append("|provides=").append(provides.sorted().joinToString(","))
            append("|files=")
            append(
                files.sortedBy { it.installPath }
                    .joinToString(";") { file -> file.canonical },
            )
            append("|description=").append(description)
            append("|homepage=").append(homepage)
            append("|contentHash=").append(contentHash.value)
        }

    /**
     * Verify the manifest's signature. The
     * function builds the canonical form +
     * compares the manifest's signature to the
     * expected signature. A failed verification
     * is a hard rejection (a tampered manifest is
     * never installed).
     */
    fun verifySignature(expectedSignature: Signature): Result<Unit> {
        val canonical = canonicalForm
        val recomputed = Signature.sign(
            payload = canonical.toByteArray(Charsets.UTF_8),
            key = expectedSignature.value.toByteArray(),
        )
        return if (recomputed.value == signature.value) {
            Result.success(Unit)
        } else {
            Result.failure(
                ElysiumRuntimeLayerVerificationError.SignatureMismatch(
                    layerId = id.value,
                    version = version,
                    expected = expectedSignature.value,
                    actual = signature.value,
                ),
            )
        }
    }

    /**
     * The string form. The string is the
     * canonical form.
     */
    override fun toString(): String = canonicalForm
}

/**
 * The typed error envelope for runtime layer
 * verification. The errors are the typed outcomes
 * the layer loader returns on a failed
 * verification.
 *
 * The error envelope follows the same pattern as
 * [ElysiumPackageVerificationError] (Phase 73 first
 * half) — but scoped to runtime layers.
 */
sealed class ElysiumRuntimeLayerVerificationError(
    message: String,
) : RuntimeException(message) {

    /**
     * The layer manifest's signature does not
     * match the expected signature. The manifest
     * is tampered (or signed by a different
     * publisher).
     */
    data class SignatureMismatch(
        val layerId: String,
        val version: ElysiumPackageVersion,
        val expected: String,
        val actual: String,
    ) : ElysiumRuntimeLayerVerificationError(
        message = "Signature mismatch for layer $layerId@${version.canonical}: " +
            "expected $expected, got $actual",
    )

    /**
     * The layer's content hash does not match the
     * tarball's actual content hash. The layer is
     * corrupted.
     */
    data class ContentHashMismatch(
        val layerId: String,
        val version: ElysiumPackageVersion,
        val expected: String,
        val actual: String,
    ) : ElysiumRuntimeLayerVerificationError(
        message = "Content hash mismatch for layer $layerId@${version.canonical}: " +
            "expected $expected, got $actual",
    )

    /**
     * The layer is not available for the requested
     * host ABI. E.g. the user requested Mesa Turnip
     * for X86_64 — Turnip is Adreno-only.
     */
    data class UnsupportedAbi(
        val layerId: String,
        val version: ElysiumPackageVersion,
        val layerHostAbi: ElysiumAbi,
        val requestedHostAbi: ElysiumAbi,
    ) : ElysiumRuntimeLayerVerificationError(
        message = "Layer $layerId@${version.canonical} " +
            "(${ElysiumAbi.canonicalName(layerHostAbi)}) is not available for " +
            "host ABI ${ElysiumAbi.canonicalName(requestedHostAbi)}",
    )
}
