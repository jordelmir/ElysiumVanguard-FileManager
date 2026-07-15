package com.elysium.vanguard.core.runtime.capsule

import com.elysium.vanguard.core.runtime.network.policy.NetworkPolicy

/**
 * Phase 14 — an immutable description of an installable application.
 *
 * Master order §14: every Linux / Windows application the user
 * runs through Elysium Vanguard is described by an
 * [ApplicationCapsule]. The capsule is the contract between the
 * catalog (which ships capsules) and the runtime (which honours
 * them). The data class here is the typed surface of that
 * contract.
 *
 * The capsule groups the master order's "five-W" of an
 * application: what it is, what it requires, what it can touch,
 * how it presents itself, and where it came from. The runtime
 * is responsible for enforcing every one of those decisions.
 *
 * The example from the master order, in our typed form:
 *
 * ```
 * id = "org.elysium.gimp"
 * displayName = "GIMP"
 * runtime = RuntimeRequirement(preferred = "linux-direct-arm64", fallbacks = listOf("linux-vm"))
 * display = DisplayMode.SEAMLESS
 * permissions = CapsulePermissions(
 *   files = FilePermission.USER_SELECTED,
 *   clipboard = ClipboardPermission.TEXT_AND_IMAGES,
 *   network = true
 * )
 * resources = CapsuleResources(memoryRecommendedMb = 2048)
 * ```
 */
data class ApplicationCapsule(
    /**
     * Reverse-DNS identifier, e.g. `org.elysium.gimp`. Stable
     * across versions; updates are addressed by `id` + `version`.
     */
    val id: String,
    val displayName: String,
    val description: String,
    val version: String,
    val runtime: RuntimeRequirement,
    val architecture: Set<CpuArch>,
    val entrypoint: String,
    val environment: Map<String, String> = emptyMap(),
    val permissions: CapsulePermissions,
    val storage: StoragePolicy,
    val network: NetworkPolicy,
    val display: DisplayMode,
    val gpu: GpuProfile,
    val audio: AudioProfile,
    val resources: CapsuleResources,
    val compatibility: CompatibilityState,
    /**
     * Ed25519 signature over the canonical manifest bytes
     * (capsule id, version, sha256s of the tarballs, etc.). The
     * runtime verifies this before honouring the capsule.
     */
    val signature: String,
    val source: PackageSource
) {
    init {
        require(id.isNotBlank()) { "capsule id must not be blank" }
        require(id matches ID_REGEX) {
            "capsule id must be a reverse-DNS identifier (e.g. org.elysium.gimp): $id"
        }
        require(displayName.isNotBlank()) { "capsule display name must not be blank" }
        require(description.isNotBlank()) { "capsule description must not be blank" }
        require(version.isNotBlank()) { "capsule version must not be blank" }
        require(entrypoint.isNotBlank()) { "capsule entrypoint must not be blank" }
        require(architecture.isNotEmpty()) {
            "capsule must declare at least one supported CPU architecture"
        }
        require(signature matches SIGNATURE_REGEX) {
            "capsule signature must be a 128-char lowercase hex string (Ed25519 in two parts)"
        }
        require(environment.keys.all { it.isNotBlank() && '=' !in it && '\u0000' !in it }) {
            "capsule environment keys must be non-blank and free of '=' and NUL"
        }
        require(environment.values.none { '\u0000' in it }) {
            "capsule environment values must not contain NUL"
        }
        require(resources.memoryRecommendedMb > 0) {
            "memoryRecommendedMb must be positive"
        }
    }

    companion object {
        // Reverse-DNS identifier. Must contain at least one dot
        // (so "GIMP" is rejected, "org.elysium.gimp" is accepted).
        // Each segment is alphanumeric with optional hyphens; the
        // first segment must start with a letter (so "7gimp" is
        // rejected) but inner segments can start with a digit
        // (so "org.elysium.app2" or "com.example.v10" are
        // accepted — common in version-stamped catalogs). We do
        // not allow underscores to match the convention used by
        // Android package names.
        private val ID_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9\\-]*(\\.[a-zA-Z0-9][a-zA-Z0-9\\-]*)+$")
        // Ed25519 is 64 bytes; we accept the signature as either
        // raw 64 bytes (128-char hex) or as two concatenated
        // (signature + public key) 64+32 bytes (192-char hex).
        // Most builds use the 192-char form so the capsule ships
        // its own public key (lets a verifier that doesn't have
        // the APK keychain still check it).
        private val SIGNATURE_REGEX = Regex("^[0-9a-f]{128}([0-9a-f]{64})?$")
    }
}

/**
 * The runtime(s) a capsule can run on, in preference order.
 *
 * `preferred` is tried first; the runtime falls back to the
 * entries in `fallbacks` if `preferred` is unavailable (e.g.
 * the device lacks the binary, the ABI is wrong, the user
 * disabled that runtime in settings).
 */
data class RuntimeRequirement(
    val preferred: RuntimeId,
    val fallbacks: List<RuntimeId> = emptyList()
) {
    init {
        require(fallbacks.none { it == preferred }) {
            "fallbacks must not include the preferred runtime"
        }
    }
}

@JvmInline
value class RuntimeId(val value: String) {
    init { require(value.isNotBlank()) { "runtime id must not be blank" } }
    override fun toString(): String = value
}

enum class CpuArch {
    ARM64,
    X86_64,
    X86
}

/**
 * Fine-grained permissions the capsule requests. The runtime
 * enforces these via the broker (network), the file bridge
 * (storage), and the clipboard broker (clipboard).
 */
data class CapsulePermissions(
    val files: FilePermission,
    val clipboard: ClipboardPermission,
    /**
     * Whether the capsule is allowed to open outbound network
     * connections. Even when true, the [ApplicationCapsule.network]
     * policy still gates per-target access — this is just the
     * top-level switch.
     */
    val network: Boolean
) {
    init {
        // Cross-permission sanity: a capsule that asks for FULL
        // clipboard but only NONE files is suspicious; the
        // [com.elysium.vanguard.core.runtime.security.CapsuleAuditor]
        // will surface a warning. We don't reject, because a
        // fully-headless text-only app is a legitimate case.
    }
}

enum class FilePermission { NONE, USER_SELECTED, FULL }
enum class ClipboardPermission { DISABLED, TEXT, TEXT_AND_IMAGES, FULL }

/**
 * Where the capsule's data lives.
 *
 *   - PRIVATE: capsule-private dir under `<rootfs>/home/<user>/.local/share/<id>`.
 *   - SHARED: a workspace-mounted dir shared with the host (path
 *     is the mount target inside the rootfs).
 *   - BOUND: a bind-mount from a specific host path (path is the
 *     host source).
 */
data class StoragePolicy(
    val mode: Mode,
    val path: String? = null
) {
    enum class Mode { PRIVATE, SHARED, BOUND }

    init {
        if (mode == Mode.SHARED || mode == Mode.BOUND) {
            require(!path.isNullOrBlank()) {
                "SHARED and BOUND storage modes require a non-blank path"
            }
        }
    }
}

enum class DisplayMode { SEAMLESS, WINDOW, FULLSCREEN }
enum class GpuProfile {
    /** llvmpipe / software rasterizer. Always available. */
    SOFTWARE,

    /** Vulkan / Zink over the system's Vulkan driver. */
    VULKAN,

    /** Native OpenGL via the host's GL driver. */
    OPENGL,

    /**
     * Per-SOC optimized paths (Turnip, Adreno-specific hacks).
     * May crash on devices outside the tested set; the runtime
     * surfaces this in the compatibility state and never enables
     * it without explicit user opt-in.
     */
    EXPERIMENTAL
}

enum class AudioProfile {
    /** No audio. Capsules that try to open /dev/snd fail. */
    DISABLED,

    /** Output only. Microphone is denied. */
    OUTPUT_ONLY,

    /** Bidirectional audio with the audio broker in the loop. */
    FULL
}

/**
 * Resource hints the runtime uses for placement, scheduling,
 * and the "this app needs X MB" UI hint. These are advisory;
 * the runtime may place the capsule on a runtime that has
 * more memory available (e.g. linux-vm) when the direct
 * runtime is short.
 */
data class CapsuleResources(
    val memoryRecommendedMb: Int,
    val diskFootprintMb: Int = 0
) {
    init {
        require(memoryRecommendedMb > 0) {
            "memoryRecommendedMb must be positive"
        }
        require(diskFootprintMb >= 0) {
            "diskFootprintMb must be non-negative"
        }
    }
}

/**
 * The runtime's known compatibility with this capsule on the
 * current device. Updated dynamically by the compatibility
 * registry; a fresh install defaults to `COMPATIBLE` and the
 * runtime may downgrade after the first run.
 */
enum class CompatibilityState {
    /** Smoke-tested on this device family. */
    VERIFIED,

    /** Known to work, smoke test pending. */
    COMPATIBLE,

    /** Works with caveats (e.g. some features disabled). */
    PARTIAL,

    /** Untested on this device. Use at your own risk. */
    EXPERIMENTAL,

    /** Confirmed broken on this device. */
    UNSUPPORTED,

    /** Requires a specific GPU driver version we don't have. */
    BLOCKED_BY_DRIVER,

    /** Only runs under a VM. The runtime should not pick direct. */
    REQUIRES_VM
}

enum class PackageSource {
    /** The official Elysium Vanguard repository. Signed by the team. */
    OFFICIAL_REPO,

    /** A community-maintained repo the user opted into. */
    COMMUNITY,

    /** User-supplied (sideloaded). The runtime cannot guarantee safety. */
    USER_SUPPLIED
}
