package com.elysium.vanguard.core.runtime.distros.manifest

import org.json.JSONObject

/**
 * Phase 51 — JSON codec for [DistroManifest].
 *
 * The codec is intentionally hand-rolled: a
 * third-party JSON library (Moshi, kotlinx-
 * serialization) would pull a transitive
 * dependency for a single use site. The
 * manifest has six fields; the codec is ~80
 * lines.
 *
 * ## Encoding
 *
 * The codec encodes the manifest's body to a
 * JSON object (id, version, sha256, sizeBytes,
 * signedAtMs). The signature is NOT in the
 * JSON — it is in a sibling file
 * `manifest.json.sig` as raw bytes. The
 * split keeps the manifest human-readable
 * and lets the runtime apply the same
 * `Signature.update(...)` flow the existing
 * [com.elysium.vanguard.core.runtime.distros.layer.ManifestVerifier]
 * uses.
 *
 * ## Decoding
 *
 * The codec takes the JSON bytes + the
 * signature bytes as separate arguments.
 * Both must be supplied; the codec does not
 * read from disk. The caller is responsible
 * for fetching the manifest and signature.
 */
object DistroManifestCodec {

    /**
     * Encode [manifest] (without the signature)
     * to a JSON object string. The output is
     * stable: the same manifest always
     * serialises to the same bytes (modulo
     * field order, which [JSONObject] preserves
     * in insertion order).
     */
    fun encodeBody(manifest: DistroManifest): String {
        val json = JSONObject()
        json.put("id", manifest.id)
        json.put("version", manifest.version)
        json.put("sha256", manifest.sha256)
        json.put("sizeBytes", manifest.sizeBytes)
        json.put("signedAtMs", manifest.signedAtMs)
        return json.toString()
    }

    /**
     * Decode a manifest from its JSON body +
     * its raw signature bytes. Throws
     * [IllegalArgumentException] on a malformed
     * JSON body or a missing required field.
     *
     * The [DistroManifest.bodyBytes] field is
     * populated with the EXACT bytes the
     * caller supplied. The signature is
     * computed over those bytes; the verifier
     * uses those bytes — NOT a re-serialization
     * of the parsed fields.
     */
    fun decode(manifestJson: String, signature: ByteArray): DistroManifest {
        val bodyBytes = manifestJson.toByteArray(Charsets.UTF_8)
        val json = JSONObject(manifestJson)
        val id = json.optString("id").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("manifest is missing 'id'")
        val version = json.optString("version").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("manifest is missing 'version'")
        val sha256 = json.optString("sha256").takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("manifest is missing 'sha256'")
        val sizeBytes = json.optLong("sizeBytes", -1L)
        require(sizeBytes > 0) { "manifest is missing or has non-positive 'sizeBytes'" }
        val signedAtMs = json.optLong("signedAtMs", -1L)
        require(signedAtMs > 0) { "manifest is missing or has non-positive 'signedAtMs'" }
        return DistroManifest(
            id = id,
            version = version,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            signedAtMs = signedAtMs,
            signature = signature,
            bodyBytes = bodyBytes
        )
    }
}
