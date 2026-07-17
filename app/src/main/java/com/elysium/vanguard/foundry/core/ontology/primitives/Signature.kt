package com.elysium.vanguard.foundry.core.ontology.primitives

/**
 * An asymmetric signature wrapper. In Phase 1 the signature is a
 * deterministic HMAC-SHA-256 over the payload bytes using a per-revision
 * key derived from the `ContentHash`; in production the platform uses
 * post-quantum-ready asymmetric signatures (Ed25519 → ML-DSA-65 in
 * Phase 7 per `.ai/AGENTS.md` section 14 + skill 12).
 *
 * The signature is the only proof of authorship for a `ProvenanceRecord`
 * and a `VehicleRevision`. A signature that fails verification at load
 * time is a `R-T-7` typed error and the platform fails closed.
 */
@JvmInline
value class Signature(val value: String) {

    init {
        require(value.isNotEmpty()) { "Signature must not be empty" }
    }

    companion object {
        /**
         * Phase 1 signing: deterministic HMAC-SHA-256. The key is derived
         * from the content hash; this is NOT a production-grade signature
         * (it's symmetric, not asymmetric) but it is deterministic + it
         * binds the signature to the payload. Production signatures are
         * an ADR-pending Phase 7 deliverable.
         */
        fun sign(payload: ByteArray, key: ByteArray): Signature {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
            val digest = mac.doFinal(payload)
            return Signature(digest.toHexString())
        }

        fun sign(text: String, key: ByteArray): Signature = sign(text.toByteArray(Charsets.UTF_8), key)

        private fun ByteArray.toHexString(): String {
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_CHARS[v ushr 4])
                sb.append(HEX_CHARS[v and 0x0F])
            }
            return sb.toString()
        }

        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
