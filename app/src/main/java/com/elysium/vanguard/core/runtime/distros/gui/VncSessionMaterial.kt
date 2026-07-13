package com.elysium.vanguard.core.runtime.distros.gui

import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbPasswordProvider
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbVncAuth
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

/**
 * Private, one-use material shared by a local Xvnc server and Elysium's RFB
 * client. The rootfs is app-private; the file stores only TigerVNC's legacy
 * password-file encoding, never the plaintext. Calling [close] erases the
 * in-memory secret and removes the file.
 */
internal class VncSessionMaterial private constructor(
    private var secret: CharArray?,
    val hostFile: File,
    val guestPath: String
) : Closeable {
    private val lock = Any()

    val passwordProvider = RfbPasswordProvider {
        synchronized(lock) { secret?.copyOf() }
    }

    override fun close() {
        val file = synchronized(lock) {
            secret?.fill('\u0000')
            secret = null
            hostFile
        }
        eraseAndDelete(file)
    }

    companion object {
        // Debian's /var/run is commonly an absolute symlink to /run. Work
        // from the rootfs' real /run directory so host Java I/O never
        // follows that link out of the app sandbox.
        private const val GUEST_RUNTIME_DIR = "/run/elysium-vnc"
        private const val SECRET_LENGTH = 8
        private const val FILE_TOKEN_LENGTH = 18
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

        @Throws(IOException::class)
        fun create(rootfsDir: File, random: SecureRandom = SecureRandom()): VncSessionMaterial {
            require(rootfsDir.isDirectory) { "rootfs must be an existing directory" }
            val runtimeDir = File(rootfsDir, GUEST_RUNTIME_DIR.removePrefix("/"))
            if (!runtimeDir.isDirectory && !runtimeDir.mkdirs()) {
                throw IOException("could not create private VNC runtime directory")
            }
            val secret = randomToken(random, SECRET_LENGTH).toCharArray()
            val name = "session-${randomToken(random, FILE_TOKEN_LENGTH)}.passwd"
            val hostFile = File(runtimeDir, name)
            try {
                val encoded = RfbVncAuth.passwordFile(secret)
                FileOutputStream(hostFile).use { output ->
                    try {
                        output.write(encoded)
                        output.fd.sync()
                    } finally {
                        encoded.fill(0)
                    }
                }
                hostFile.setReadable(true, true)
                hostFile.setWritable(false, false)
                return VncSessionMaterial(
                    secret = secret,
                    hostFile = hostFile,
                    guestPath = "$GUEST_RUNTIME_DIR/$name"
                )
            } catch (error: Exception) {
                secret.fill('\u0000')
                eraseAndDelete(hostFile)
                if (error is IOException) throw error
                throw IOException("could not create VNC session material", error)
            }
        }

        private fun randomToken(random: SecureRandom, length: Int): String = buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

        private fun eraseAndDelete(file: File) {
            try {
                if (file.isFile) {
                    file.setWritable(true, true)
                    FileOutputStream(file, false).use { output ->
                        output.write(ByteArray(file.length().coerceAtMost(MAX_FILE_BYTES.toLong()).toInt()))
                        output.fd.sync()
                    }
                }
            } catch (_: IOException) {
                // Deletion below is still the important lifecycle boundary.
            } finally {
                file.delete()
            }
        }

        private const val MAX_FILE_BYTES = 64
    }
}
