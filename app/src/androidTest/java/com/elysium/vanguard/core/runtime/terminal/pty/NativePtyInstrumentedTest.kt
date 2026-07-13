package com.elysium.vanguard.core.runtime.terminal.pty

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class NativePtyInstrumentedTest {
    @Test fun read_after_close_is_an_io_condition_not_a_process_crash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pty = NativePty.spawn(
            command = listOf("/system/bin/sh", "-c", "sleep 5"),
            environment = mapOf("TERM" to "xterm-256color", "PATH" to System.getenv("PATH").orEmpty()),
            workingDirectory = context.cacheDir,
            columns = 80,
            rows = 24
        )
        pty.close()

        try {
            pty.read(ByteArray(64), timeoutMs = 1)
            fail("read after close must throw IOException")
        } catch (_: IOException) {
            // Expected: callers can handle teardown on their normal I/O path.
        }
    }

    @Test fun shell_receives_a_real_controlling_terminal_and_window_size() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pty = NativePty.spawn(
            command = listOf("/system/bin/sh", "-c", "printf 'pty-ok\\n'; stty size"),
            environment = mapOf("TERM" to "xterm-256color", "PATH" to System.getenv("PATH").orEmpty()),
            workingDirectory = context.cacheDir,
            columns = 101,
            rows = 37
        )
        try {
            val output = StringBuilder()
            val chunk = ByteArray(4_096)
            for (attempt in 0 until 40) {
                when (val count = pty.read(chunk, timeoutMs = 250)) {
                    -1 -> break
                    0 -> Unit
                    else -> output.append(String(chunk, 0, count, Charsets.UTF_8))
                }
                if ("pty-ok" in output && "37 101" in output) break
            }
            var exitCode: Int? = null
            for (attempt in 0 until 30) {
                val result = pty.waitForExit(timeoutMs = 100)
                if (result != null) {
                    exitCode = result
                    break
                }
            }
            assertEquals("shell output: $output", 0, exitCode)
            assertTrue("PTY marker missing: $output", "pty-ok" in output)
            assertTrue("stty did not receive PTY geometry: $output", "37 101" in output)
        } finally {
            pty.close()
        }
    }
}
