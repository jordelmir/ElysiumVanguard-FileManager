package com.elysium.vanguard.core.runtime.distros.gui

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedDiagnosticLogTest {

    @Test
    fun `keeps only the newest printable diagnostic text`() {
        val log = BoundedDiagnosticLog(maxChars = 12)

        log.append("first\u001b[31m")
        log.append(" second\nlatest")

        assertEquals("econd\nlatest", log.snapshot())
    }
}
