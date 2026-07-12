package com.elysium.vanguard.core.runtime.terminal.pty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NativePtyContractTest {
    @Test fun `native wait sentinels cannot collide`() {
        assertNotEquals(NativePtyBridge.STATUS_RUNNING, NativePtyBridge.STATUS_CLOSED)
        assertNotEquals(NativePtyBridge.STATUS_CLOSED, NativePtyBridge.STATUS_UNKNOWN)
    }

    @Test fun `sentinels reserve only impossible exit values`() {
        assertEquals(Int.MIN_VALUE, NativePtyBridge.STATUS_RUNNING)
        assertEquals(Int.MIN_VALUE + 2, NativePtyBridge.STATUS_UNKNOWN)
    }
}
