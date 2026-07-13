package com.elysium.vanguard.features.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionIdentityTest {

    @Test
    fun `new session ids are nonblank namespaced and unique`() {
        val ids = (1..128).map { MediaSessionIdentity.newId() }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("elysium-audio-") })
        assertTrue(ids.none(String::isBlank))
    }
}
