package com.elysium.vanguard.core.runtime.capsule

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature

/**
 * Phase 68 — the **Capsule**, the universal package
 * format for the Elysium Vanguard Universal Platform.
 *
 * Per sección 11 of the master vision ("Marketplace
 * universal"): the catalog includes distros, Linux
 * apps, Wine profiles, containers, toolchains, IDEs,
 * servers, project templates, game configs, plugins,
 * automations, and AI agents. A creator publishes a
 * **Capsule** — a typed manifest that declares:
 *   - **What** the package is (id, name, version).
 *   - **Where** it runs (runtime + architecture).
 *   - **How** it starts (entrypoint + args).
 *   - **What hardware** it needs (GPU config).
 *   - **What access** it needs (permissions: network +
 *     storage allowlist).
 *   - **Trust** (content hash + signature for
 *     content-addressed storage).
 *
 * The Capsule is the **bridge** between the
 * `MarketListing` (Phase 59, "what's in the catalog")
 * and the `WorkspaceDefinition` (Phase 66, "how the
 * user runs it locally"). A `MarketListing` may wrap
 * a `Capsule` (the listing is the distribution contract;
 * the capsule is the runtime contract).
 *
 * The Capsule is **typed** (per `.ai/AGENTS.md` 24.1):
 * every field has a type. A free-form string is never
 * the value of a permission, a path, or an enum.
 *
 * The Capsule is **versioned** (`apiVersion`): a
 * breaking change is a new version + a migration
 * tool. A silent lossy migration is a contract
 * violation.
 */
data class Capsule(
    val apiVersion: CapsuleApiVersion,
    val id: CapsuleId,
    val name: String,
    val version: String,
    val description: String,
    val runtime: Runtime,
    val architecture: Architecture,
    val distribution: Distribution,
    val entrypoint: EntryPoint,
    val gpu: GpuConfig,
    val permissions: Permissions,
    val signature: Signature,
    val contentHash: ContentHash,
) {
    init {
        require(name.isNotBlank()) { "Capsule.name must not be blank" }
        require(version.isNotBlank()) { "Capsule.version must not be blank" }
        require(version.matches(VERSION_PATTERN_REGEX)) {
            "Capsule.version must match $VERSION_PATTERN, got: $version"
        }
        require(entrypoint.executable.isNotBlank()) {
            "EntryPoint.executable must not be blank"
        }
        require(entrypoint.executable.startsWith("/")) {
            "EntryPoint.executable must be absolute, got: ${entrypoint.executable}"
        }
        require(signature.value.isNotBlank()) {
            "Capsule.signature must not be blank"
        }
        require(contentHash.value.isNotBlank()) {
            "Capsule.contentHash must not be blank"
        }
    }

    companion object {
        const val VERSION_PATTERN: String = "^[0-9]+\\.[0-9]+\\.[0-9]+(-[A-Za-z0-9.]+)?$"
        private val VERSION_PATTERN_REGEX = Regex(VERSION_PATTERN)
    }
}

/**
 * The Capsule API version. A breaking change is a new
 * version + a migration tool.
 */
data class CapsuleApiVersion(val value: String) {
    init {
        require(value.matches(API_VERSION_PATTERN_REGEX)) {
            "CapsuleApiVersion must match $API_VERSION_PATTERN, got: $value"
        }
    }
    companion object {
        const val API_VERSION_PATTERN: String = "^elysium\\.capsule/v\\d+(\\.\\d+)?$"

        // The regex is declared first because `V1` uses
        // it in its initializer. Declaring `V1` before
        // the regex would crash with
        // `ExceptionInInitializerError` (the regex would
        // be null when `V1` is constructed). Same
        // pattern as the Phase F2 / Phase 66 / Phase 67
        // `ApiVersion` types.
        private val API_VERSION_PATTERN_REGEX = Regex(API_VERSION_PATTERN)

        val V1: CapsuleApiVersion = CapsuleApiVersion("elysium.capsule/v1")
    }
}

/**
 * A typed capsule id. The id follows the Java package
 * naming convention (e.g. `com.elysium.blender.arm64`)
 * so a creator's namespace is respected.
 */
data class CapsuleId(val value: String) {
    init {
        require(value.isNotBlank()) { "CapsuleId.value must not be blank" }
        require(value.matches(ID_PATTERN_REGEX)) {
            "CapsuleId.value must match $ID_PATTERN, got: $value"
        }
    }
    companion object {
        const val ID_PATTERN: String = "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"
        private val ID_PATTERN_REGEX = Regex(ID_PATTERN)
    }
}

/**
 * The runtime kind — what runtime the capsule
 * targets. The Capsule is a manifest, not a binding
 * to a specific backend; the orchestrator (Phase 67)
 * dispatches by the runtime.
 */
enum class Runtime {
    /** A Linux binary (proot / chroot / native). */
    LINUX,

    /** A Windows binary (Wine / QEMU VM). */
    WINDOWS,

    /** A macOS binary (future; not yet shipped). */
    MACOS,

    /** A web application (runs in the embedded browser). */
    WEB,
}

/**
 * The CPU architecture. The Capsule declares the
 * arch it was built for; the orchestrator dispatches
 * to the matching binary translation layer (Box64 /
 * FEX for x86_64 on ARM64, etc.).
 */
enum class Architecture {
    /** 64-bit ARM (the dominant Android arch). */
    ARM64,

    /** 32-bit ARM (legacy). */
    ARM32,

    /** 64-bit x86 (desktops, QEMU on ARM64). */
    X86_64,

    /** 32-bit x86 (legacy). */
    X86,

    /** Architecture-agnostic (e.g. interpreted, JVM). */
    ANY,
}

/**
 * The distribution reference. A Capsule that targets
 * a specific distro declares the distro by id (e.g.
 * `elysium-linux-1`). A Capsule that is distro-agnostic
 * declares `ANY` (the orchestrator picks the user's
 * preferred distro).
 */
data class Distribution(val id: String) {
    init {
        require(id.isNotBlank()) { "Distribution.id must not be blank" }
    }
    companion object {
        /** The user's preferred distro is used
         *  (the orchestrator decides). */
        val ANY: Distribution = Distribution("any")

        /** The Elysium Vanguard Linux distro (the
         *  proprietary one — section 10 of the vision). */
        val ELYSIUM_LINUX_1: Distribution = Distribution("elysium-linux-1")
    }
}

/**
 * The entry point — the binary that the capsule
 * launches. The entrypoint is **absolute** (the
 * runtime knows the rootfs).
 */
data class EntryPoint(
    val executable: String,
    val args: List<String> = emptyList(),
    val workingDirectory: String = "/",
) {
    init {
        require(executable.isNotBlank()) { "EntryPoint.executable must not be blank" }
        require(executable.startsWith("/")) {
            "EntryPoint.executable must be absolute, got: $executable"
        }
        require(workingDirectory.isNotBlank()) { "EntryPoint.workingDirectory must not be blank" }
        require(workingDirectory.startsWith("/")) {
            "EntryPoint.workingDirectory must be absolute, got: $workingDirectory"
        }
    }
}

/**
 * The GPU config — what GPU API + driver the capsule
 * needs. The runtime checks the device's actual
 * capabilities before launching.
 */
data class GpuConfig(
    val api: GpuApi,
    val driver: GpuDriver,
) {
    init {
        // The GPU requirement is mandatory (per vision
        // section 6 — the orchestrator must check the
        // device's GPU before launching).
    }
}

/**
 * The GPU API. The Capsule declares what it needs;
 * the runtime matches against the device's actual
 * capabilities (Phase 62 — Mesa Turnip).
 */
enum class GpuApi {
    /** No GPU required (a CLI app). */
    NONE,

    /** OpenGL ES (the Android default). */
    OPENGL_ES,

    /** Vulkan (modern, what Mesa Turnip implements). */
    VULKAN,

    /** OpenCL (compute). */
    OPENCL,
}

/**
 * The GPU driver. The Capsule declares what driver it
 * needs; the runtime matches against the device's
 * actual driver.
 */
enum class GpuDriver {
    /** No driver required. */
    NONE,

    /** Mesa Turnip (Adreno — Phase 62). */
    TURNIP,

    /** Mesa Freedreno (legacy Adreno). */
    FREEDRENO,

    /** Mesa Panfrost (Mali). */
    PANFROST,

    /** Mesa Lima (legacy Mali). */
    LIMA,

    /** Mesa software rasterizer. */
    SOFTPIPE,

    /** DXVK (Wine + Direct3D 9-11). */
    DXVK,

    /** VKD3D-Proton (Wine + Direct3D 12). */
    VKD3D_PROTON,
}

/**
 * The permissions — what the capsule can access.
 *
 * Per sección 9 of the vision ("Seguridad Zero Trust"):
 * the platform assumes any executable can be hostile.
 * The Capsule declares the **minimum** access it
 * needs. The runtime enforces the permissions; any
 * unauthorized access is denied.
 *
 * `network`: whether the capsule can access the
 * network. The vision's example declares
 * `network: false` for a GPU-only capsule.
 *
 * `storage`: the list of storage paths the capsule
 * can access. The value `["user-selected"]` means
 * the user picks the paths at install time (the
 * runtime shows a file picker). An empty list means
 * the capsule has no storage access.
 */
data class Permissions(
    val network: Boolean,
    val storage: List<StorageScope>,
) {
    init {
        // A capsule with `storage = []` AND `network = false`
        // is a pure sandbox (no I/O). The vision's
        // GPU-only example has `storage = ["user-selected"]`,
        // so a non-empty storage is the normal case. A
        // pure-sandbox capsule is rejected as a deployment
        // error (a capsule that wants zero access should
        // declare it explicitly via a different mechanism,
        // not by declaring empty permissions).
        require(!(storage.isEmpty() && !network)) {
            "Permissions: a capsule with no storage AND no " +
                "network is a pure sandbox; the runtime " +
                "should reject it as a deployment error"
        }
    }
}

/**
 * A storage scope — what the capsule can access on
 * the device's storage.
 */
enum class StorageScope {
    /** The user picks the paths at install time. */
    USER_SELECTED,

    /** The capsule's own data dir (the standard
     *  Android scoped storage for the app). */
    APP_PRIVATE,

    /** The MediaStore (shared media — photos, music,
     *  videos). */
    MEDIA_STORE,

    /** SMB / SFTP / WebDAV (network storage). */
    NETWORK,
}
