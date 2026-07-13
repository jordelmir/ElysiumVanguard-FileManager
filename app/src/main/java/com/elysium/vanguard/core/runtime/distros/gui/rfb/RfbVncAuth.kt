package com.elysium.vanguard.core.runtime.distros.gui.rfb

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Supplies one ephemeral VNC password. Ownership of the returned array transfers to the caller. */
internal fun interface RfbPasswordProvider {
    fun acquirePassword(): CharArray?
}

/**
 * VNC challenge-response authentication for an app-private, loopback-only
 * RFB connection. This legacy protocol deliberately supports ASCII secrets
 * only, avoiding silent differences between VNC server implementations.
 */
internal object RfbVncAuth {
    private const val CHALLENGE_BYTES = 16
    private const val PASSWORD_BYTES = 8
    private val PASSWORD_FILE_KEY = byteArrayOf(23, 82, 107, 6, 35, 78, 88, 7)

    fun response(challenge: ByteArray, password: CharArray): ByteArray {
        require(challenge.size == CHALLENGE_BYTES) { "VNC challenge must be 16 bytes" }
        val key = passwordBytes(password)
        try {
            return encrypt(challenge, key)
        } finally {
            key.fill(0)
        }
    }

    /** Returns TigerVNC's eight-byte, password-file representation. */
    fun passwordFile(password: CharArray): ByteArray {
        val plain = passwordBytes(password)
        try {
            return encrypt(plain, PASSWORD_FILE_KEY)
        } finally {
            plain.fill(0)
        }
    }

    private fun passwordBytes(password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "VNC password cannot be empty" }
        require(password.all { it.code in 0x20..0x7E }) { "VNC passwords must use printable ASCII" }
        return ByteArray(PASSWORD_BYTES).also { key ->
            password.take(PASSWORD_BYTES).forEachIndexed { index, character ->
                key[index] = character.code.toByte()
            }
        }
    }

    private fun encrypt(input: ByteArray, legacyKey: ByteArray): ByteArray {
        val jcaKey = ByteArray(PASSWORD_BYTES) { index -> reverseBits(legacyKey[index].toInt()).toByte() }
        try {
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(jcaKey, "DES"))
            return cipher.doFinal(input)
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("VNC authentication is unavailable on this device", error)
        } finally {
            jcaKey.fill(0)
        }
    }

    private fun reverseBits(value: Int): Int = Integer.reverse(value and 0xFF) ushr 24
}
