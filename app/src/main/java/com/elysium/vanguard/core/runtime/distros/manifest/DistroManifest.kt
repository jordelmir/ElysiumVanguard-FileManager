package com.elysium.vanguard.core.runtime.distros.manifest

/**
 * Phase 51 — the signed distro manifest.
 *
 * A [DistroManifest] is the contract between
 * the build-pipeline side (which has the
 * Elysium Vanguard Linux offline signing key)
 * and the device (which has the public key
 * shipped in the APK). The manifest declares
 * the rootfs's SHA-256 + size; the
 * [signature] is the Ed25519 signature over
 * the canonical JSON bytes.
 *
 * The manifest is intentionally minimal — it
 * carries only the fields a runtime needs to
 * install a rootfs without trusting the
 * catalog. A future "manifest v2" can add
 * channels, expiry timestamps, or a list of
 * sibling mirrors; Phase 51 ships the
 * minimum.
 *
 * ## Wire format
 *
 * The manifest is a JSON object:
 *
 * ```json
 * {
 *   "id": "debian-12",
 *   "version": "12.4",
 *   "sha256": "9b8e1f...c0d",
 *   "sizeBytes": 268435456,
 *   "signedAtMs": 1721325600000
 * }
 * ```
 *
 * The signature is in a sibling file
 * `manifest.json.sig` (64 raw bytes, NOT
 * base64). The split keeps the JSON
 * human-readable; the signature is the raw
 * Ed25519 output the runtime feeds to
 * [com.elysium.vanguard.core.runtime.distros.layer.ManifestVerifier].
 */
data class DistroManifest(
    val id: String,
    val version: String,
    val sha256: String,
    val sizeBytes: Long,
    val signedAtMs: Long,
    val signature: ByteArray,
    /**
     * The exact JSON body bytes the signature
     * is computed over. The verifier uses
     * these bytes — NOT a re-serialization of
     * the parsed fields — to avoid JSON
     * canonicalization drift. Two manifests
     * with the same parsed fields but
     * different whitespace / key order /
     * number formatting have different
     * [bodyBytes] and therefore different
     * signatures.
     */
    val bodyBytes: ByteArray
) {
    init {
        require(id.isNotBlank()) { "manifest id must not be blank" }
        require(version.isNotBlank()) { "manifest version must not be blank" }
        require(sha256.isNotBlank()) { "manifest sha256 must not be blank" }
        require(sha256.length == 64) {
            "manifest sha256 must be 64 lowercase hex chars; got ${sha256.length}"
        }
        require(sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "manifest sha256 must be lowercase hex; got '$sha256'"
        }
        require(sizeBytes > 0) { "manifest sizeBytes must be positive" }
        require(signedAtMs > 0) { "manifest signedAtMs must be positive" }
        require(signature.size == 64) {
            "Ed25519 signature must be 64 bytes; got ${signature.size}"
        }
        require(bodyBytes.isNotEmpty()) { "manifest bodyBytes must not be empty" }
    }

    /**
     * Override equals to use content equality
     * on the signature byte array (the default
     * `data class` equals uses `Any?.equals`
     * which is reference equality for
     * `ByteArray`).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DistroManifest) return false
        return id == other.id &&
            version == other.version &&
            sha256 == other.sha256 &&
            sizeBytes == other.sizeBytes &&
            signedAtMs == other.signedAtMs &&
            signature.contentEquals(other.signature) &&
            bodyBytes.contentEquals(other.bodyBytes)
    }

    /**
     * Override hashCode to use the signature's
     * content hash (the default `data class`
     * hashCode uses `Any?.hashCode` which is
     * identity-based for `ByteArray`).
     */
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + sha256.hashCode()
        result = 31 * result + sizeBytes.hashCode()
        result = 31 * result + signedAtMs.hashCode()
        result = 31 * result + signature.contentHashCode()
        result = 31 * result + bodyBytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "DistroManifest(id=$id, version=$version, " +
            "sha256=$sha256, sizeBytes=$sizeBytes, " +
            "signedAtMs=$signedAtMs, signatureBytes=${signature.size}, " +
            "bodyBytes=${bodyBytes.size})"
}
