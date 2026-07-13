package com.elysium.vanguard.core.runtime.distros.gui.rfb

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RfbVncAuthTest {

    @Test
    fun `encodes the TigerVNC compatible response for a fixed challenge`() {
        val response = RfbVncAuth.response(
            challenge = ByteArray(16) { it.toByte() },
            password = "password".toCharArray()
        )

        assertArrayEquals(
            byteArrayOf(
                0xB8.toByte(), 0x66, 0x92.toByte(), 0x41, 0x25, 0xC8.toByte(), 0xEE.toByte(), 0xBB.toByte(),
                0x9D.toByte(), 0xEB.toByte(), 0xC1.toByte(), 0xDB.toByte(), 0x61, 0xC5.toByte(), 0x38, 0xE2.toByte()
            ),
            response
        )
    }

    @Test
    fun `encodes the TigerVNC compatible password file`() {
        assertArrayEquals(
            byteArrayOf(0xDB.toByte(), 0xD8.toByte(), 0x3C, 0xFD.toByte(), 0x72, 0x7A, 0x14, 0x58),
            RfbVncAuth.passwordFile("password".toCharArray())
        )
    }
}
