package com.elysium.vanguard.foundry.core.ontology.primitives

import java.security.MessageDigest

/**
 * Content-addressed hash. A `@JvmInline value class` over a lowercase hex
 * string of the SHA-256 digest.
 *
 * Per `docs/foundry/domain-ownership.md` section 1 (the `ContentHash`
 * primitive) + `.ai/skills/03-vehicle-domain-ontology/SKILL.md`:
 *   - The platform's content-addressed store is keyed on this hash.
 *   - The hash is verified at load time; a mismatch is a hard rejection.
 *   - Two distinct artifacts with the same hash (a 2^128 collision) is
 *     a `R-DI-7` accepted risk in `docs/foundry/risk-register.md`.
 */
@JvmInline
value class ContentHash(val value: String) {

    init {
        require(value.length == 64) {
            "ContentHash must be a 64-character SHA-256 hex digest, got ${value.length} chars"
        }
        require(value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "ContentHash must be lowercase hex, got: $value"
        }
    }

    companion object {
        /**
         * Compute the SHA-256 digest of the given bytes and return it as a
         * `ContentHash`. Stable across JVMs, OSes, and Kotlin versions.
         */
        fun of(bytes: ByteArray): ContentHash {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return ContentHash(digest.toHexString())
        }

        /**
         * Compute the SHA-256 digest of the given UTF-8 string. Convenience
         * overload for the common case of canonical text inputs.
         */
        fun of(text: String): ContentHash = of(text.toByteArray(Charsets.UTF_8))

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
