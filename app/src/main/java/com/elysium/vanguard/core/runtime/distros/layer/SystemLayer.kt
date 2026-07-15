package com.elysium.vanguard.core.runtime.distros.layer

/**
 * Phase 12.2 — an immutable system-layer artifact.
 *
 * Master order §11.3 / §11.5: the runtime is layered. The
 * [BaseLayer] is the upstream rootfs, immutable. The
 * [SystemLayer] is the Elysium Vanguard overlay that we ship
 * signed: a tarball with a SHA-256 hash, a version, a name, and
 * a list of files it owns. Multiple [SystemLayer]s apply
 * in order, each overwriting any earlier file at the same path.
 *
 * Real signatures (Ed25519, RSA, etc.) live one layer up; this
 * version pins the manifest with SHA-256 only. Adding signature
 * verification is Phase 12.4 — see ADR-004.
 */
data class SystemLayer(
    /** Stable identifier, e.g. "elysium-cli" or "elysium-bridges". */
    val id: String,
    /** Display name, e.g. "Elysium CLI". */
    val displayName: String,
    /**
     * Monotonically increasing version string. Two layers with
     * the same [id] but different versions: the higher version
     * wins on apply.
     */
    val version: String,
    /** Absolute path of the tarball on disk. */
    val tarball: java.io.File,
    /**
     * Expected SHA-256 of the tarball as a lowercase hex string.
     * Verified before extraction; a mismatch aborts the apply.
     */
    val sha256: String,
    /**
     * Optional human-readable note. The UI surfaces this in the
     * "what's new" view after an update.
     */
    val notes: String = ""
) {
    init {
        require(id.isNotBlank()) { "layer id must not be blank" }
        require(version.isNotBlank()) { "layer version must not be blank" }
        require(tarball.isFile) { "layer tarball not found: $tarball" }
        require(sha256.matches(SHA256_REGEX)) {
            "sha256 must be a 64-character lowercase hex string: $sha256"
        }
    }

    /** Update cadence; surfaces in the catalog and the rollback UI. */
    val channel: UpdateChannel = UpdateChannel.STABLE

    private companion object {
        val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    }
}

enum class UpdateChannel(val id: String) {
    STABLE("stable"),
    BETA("beta"),
    NIGHTLY("nightly");

    companion object {
        fun fromId(id: String): UpdateChannel =
            entries.firstOrNull { it.id == id } ?: STABLE
    }
}
