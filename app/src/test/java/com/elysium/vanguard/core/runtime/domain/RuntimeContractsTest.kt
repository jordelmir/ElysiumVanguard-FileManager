package com.elysium.vanguard.core.runtime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeContractsTest {
    @Test
    fun `full startup stop path is accepted`() {
        val machine = SessionStateMachine()
        machine.transition(SessionState.Validating)
        machine.transition(SessionState.Preparing)
        machine.transition(SessionState.Starting)
        machine.transition(SessionState.Running(pid = 42, startedAtMs = 10))
        machine.transition(SessionState.Stopping)
        val report = report()
        machine.transition(SessionState.Stopped(report))

        assertEquals(SessionState.Stopped(report), machine.state())
    }

    @Test
    fun `suspend recovery path returns to running`() {
        val machine = runningMachine()
        machine.transition(SessionState.Suspending)
        machine.transition(SessionState.Suspended)
        machine.transition(SessionState.Recovering)
        machine.transition(SessionState.Running(pid = 42, startedAtMs = 10))

        assertTrue(machine.state() is SessionState.Running)
    }

    @Test(expected = IllegalStateException::class)
    fun `invalid transition fails closed`() {
        SessionStateMachine().transition(SessionState.Starting)
    }

    @Test(expected = IllegalStateException::class)
    fun `terminal state cannot transition`() {
        val machine = runningMachine()
        machine.transition(SessionState.Failed(error()))
        machine.transition(SessionState.Stopping)
    }

    @Test
    fun `capability profile cannot contradict itself`() {
        val profile = CapabilityProfile(
            available = setOf(RuntimeCapability.PTY),
            unavailableReasons = mapOf(RuntimeCapability.DISPLAY to "not installed")
        )

        assertTrue(profile.supports(RuntimeCapability.PTY))
        assertFalse(profile.supports(RuntimeCapability.DISPLAY))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `session argv rejects nul`() {
        SessionSpec(
            runtimeId = RuntimeId("android"),
            argv = listOf("/system/bin/sh", "bad\u0000argument")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `terminal size has hard limits`() {
        TerminalSize(columns = 100_000, rows = 24)
    }

    private fun runningMachine(): SessionStateMachine = SessionStateMachine().apply {
        transition(SessionState.Validating)
        transition(SessionState.Preparing)
        transition(SessionState.Starting)
        transition(SessionState.Running(pid = 42, startedAtMs = 10))
    }

    private fun report() = ExitReport(
        exitCode = 0,
        signal = null,
        startedAtMs = 10,
        finishedAtMs = 20,
        forced = false,
        processGroupClean = true,
        closedFileDescriptors = 1
    )

    private fun error() = RuntimeError(
        code = RuntimeErrorCode.INTERNAL,
        message = "failed",
        recoverable = false
    )
}
