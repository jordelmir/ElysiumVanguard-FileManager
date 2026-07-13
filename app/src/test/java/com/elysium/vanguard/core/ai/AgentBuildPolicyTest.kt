package com.elysium.vanguard.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentBuildPolicyTest {
    @Test
    fun `android build has only fixed gradle variants`() {
        assertEquals("./gradlew :app:assembleDebug", AgentBuildPolicy.script("android", "debug"))
        assertEquals("./gradlew :app:assembleRelease", AgentBuildPolicy.script("android", "release"))
    }

    @Test
    fun `unknown build target is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentBuildPolicy.script("shell", "anything")
        }
    }
}
