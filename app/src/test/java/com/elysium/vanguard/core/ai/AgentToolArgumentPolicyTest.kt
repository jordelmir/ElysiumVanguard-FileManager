package com.elysium.vanguard.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentToolArgumentPolicyTest {
    @Test
    fun `package scripts quote a valid package atomically`() {
        assertEquals(
            "apk add --no-cache -- 'neovim'",
            AgentToolArgumentPolicy.installScript("apk", "neovim")
        )
    }

    @Test
    fun `package scripts reject shell metacharacters`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolArgumentPolicy.installScript("apt", "curl;rm-rf")
        }
    }

    @Test
    fun `unified patch requires a real diff header`() {
        assertEquals(
            "--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new\n",
            AgentToolArgumentPolicy.validateUnifiedPatch("--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new\n")
        )
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolArgumentPolicy.validateUnifiedPatch("echo unsafe")
        }
    }
}
