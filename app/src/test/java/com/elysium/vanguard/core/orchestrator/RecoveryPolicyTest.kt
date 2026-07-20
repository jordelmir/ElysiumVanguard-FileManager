package com.elysium.vanguard.core.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 80 (Universal Execution Engine) — the
 * JVM tests for [RecoveryPolicy].
 *
 * The tests cover:
 *   - BackoffStrategy invariants (Fixed
 *     delayMs < 0, Exponential baseMs <= 0,
 *     Exponential maxMs < baseMs, Exponential
 *     multiplier < 1.0, Linear baseMs < 0,
 *     Linear incrementMs < 0).
 *   - RecoveryPolicy invariants
 *     (OnFailure maxAttempts <= 0,
 *     OnNonZeroExit maxAttempts <= 0,
 *     OnAnyExit maxAttempts <= 0).
 *   - RecoveryDecision invariants
 *     (DoNotRestart blank reason,
 *     RestartAfter nextAttempt <= 0,
 *     RestartAfter delayMs < 0,
 *     RestartExhausted attempts <= 0,
 *     RestartExhausted blank lastReason).
 *   - InMemoryRecoveryExecutor (None
 *     DoNotRestart, OnFailure / OnNonZeroExit /
 *     OnAnyExit for each event type, max
 *     attempts, Fixed / Exponential / Linear
 *     backoff, isNoRecovery).
 *   - Realistic scenarios (process crashes N
 *     times, exponential backoff sequence,
 *     OnAnyExit on exit code 0).
 */
class RecoveryPolicyTest {

    // ============================================================
    // BackoffStrategy invariants
    // ============================================================

    @Test
    fun `BackoffStrategy Fixed rejects negative delayMs`() {
        try {
            BackoffStrategy.Fixed(delayMs = -1L)
            fail("expected IllegalArgumentException for negative delayMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("delayMs"))
        }
    }

    @Test
    fun `BackoffStrategy Exponential rejects non-positive baseMs`() {
        try {
            BackoffStrategy.Exponential(
                baseMs = 0L,
                maxMs = 1000L,
                multiplier = 2.0,
            )
            fail("expected IllegalArgumentException for baseMs <= 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("baseMs"))
        }
    }

    @Test
    fun `BackoffStrategy Exponential rejects maxMs less than baseMs`() {
        try {
            BackoffStrategy.Exponential(
                baseMs = 1000L,
                maxMs = 500L,
                multiplier = 2.0,
            )
            fail("expected IllegalArgumentException for maxMs < baseMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxMs"))
        }
    }

    @Test
    fun `BackoffStrategy Exponential rejects multiplier less than 1_0`() {
        try {
            BackoffStrategy.Exponential(
                baseMs = 1000L,
                maxMs = 10000L,
                multiplier = 0.5,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "multiplier < 1.0",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("multiplier"))
        }
    }

    @Test
    fun `BackoffStrategy Linear rejects negative baseMs`() {
        try {
            BackoffStrategy.Linear(
                baseMs = -1L,
                incrementMs = 100L,
            )
            fail("expected IllegalArgumentException for negative baseMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("baseMs"))
        }
    }

    @Test
    fun `BackoffStrategy Linear rejects negative incrementMs`() {
        try {
            BackoffStrategy.Linear(
                baseMs = 100L,
                incrementMs = -1L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "negative incrementMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("incrementMs"))
        }
    }

    // ============================================================
    // RecoveryPolicy invariants
    // ============================================================

    @Test
    fun `RecoveryPolicy OnFailure rejects non-positive maxAttempts`() {
        try {
            RecoveryPolicy.OnFailure(
                maxAttempts = 0,
                backoff = BackoffStrategy.Fixed(100L),
            )
            fail(
                "expected IllegalArgumentException for " +
                    "maxAttempts <= 0",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxAttempts"))
        }
    }

    @Test
    fun `RecoveryPolicy OnNonZeroExit rejects non-positive maxAttempts`() {
        try {
            RecoveryPolicy.OnNonZeroExit(
                maxAttempts = 0,
                backoff = BackoffStrategy.Fixed(100L),
            )
            fail(
                "expected IllegalArgumentException for " +
                    "maxAttempts <= 0",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxAttempts"))
        }
    }

    @Test
    fun `RecoveryPolicy OnAnyExit rejects non-positive maxAttempts`() {
        try {
            RecoveryPolicy.OnAnyExit(
                maxAttempts = 0,
                backoff = BackoffStrategy.Fixed(100L),
            )
            fail(
                "expected IllegalArgumentException for " +
                    "maxAttempts <= 0",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxAttempts"))
        }
    }

    // ============================================================
    // RecoveryDecision invariants
    // ============================================================

    @Test
    fun `RecoveryDecision DoNotRestart rejects blank reason`() {
        try {
            RecoveryDecision.DoNotRestart(
                handleId = ProcessId.random(),
                reason = "",
            )
            fail("expected IllegalArgumentException for blank reason")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    @Test
    fun `RecoveryDecision RestartAfter rejects non-positive nextAttempt`() {
        try {
            RecoveryDecision.RestartAfter(
                handleId = ProcessId.random(),
                nextAttempt = 0,
                delayMs = 100L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "nextAttempt <= 0",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("nextAttempt"))
        }
    }

    @Test
    fun `RecoveryDecision RestartAfter rejects negative delayMs`() {
        try {
            RecoveryDecision.RestartAfter(
                handleId = ProcessId.random(),
                nextAttempt = 1,
                delayMs = -1L,
            )
            fail("expected IllegalArgumentException for delayMs < 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("delayMs"))
        }
    }

    @Test
    fun `RecoveryDecision RestartExhausted rejects non-positive attempts`() {
        try {
            RecoveryDecision.RestartExhausted(
                handleId = ProcessId.random(),
                attempts = 0,
                lastReason = "crashed",
            )
            fail(
                "expected IllegalArgumentException for " +
                    "attempts <= 0",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("attempts"))
        }
    }

    @Test
    fun `RecoveryDecision RestartExhausted rejects blank lastReason`() {
        try {
            RecoveryDecision.RestartExhausted(
                handleId = ProcessId.random(),
                attempts = 3,
                lastReason = "",
            )
            fail("expected IllegalArgumentException for blank lastReason")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("lastReason"))
        }
    }

    // ============================================================
    // InMemoryRecoveryExecutor — None policy
    // ============================================================

    @Test
    fun `None policy returns DoNotRestart for any event`() {
        val executor = InMemoryRecoveryExecutor()
        val started = buildStartedEvent()
        val failed = buildFailedEvent()
        val exited = buildExitedEvent()
        assertTrue(executor.decide(RecoveryPolicy.None, started, 0)
            is RecoveryDecision.DoNotRestart)
        assertTrue(executor.decide(RecoveryPolicy.None, failed, 0)
            is RecoveryDecision.DoNotRestart)
        assertTrue(executor.decide(RecoveryPolicy.None, exited, 0)
            is RecoveryDecision.DoNotRestart)
    }

    @Test
    fun `isNoRecovery returns true for None`() {
        val executor = InMemoryRecoveryExecutor()
        assertTrue(executor.isNoRecovery(RecoveryPolicy.None))
    }

    @Test
    fun `isNoRecovery returns false for OnFailure`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnFailure(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        assertTrue(!executor.isNoRecovery(policy))
    }

    // ============================================================
    // InMemoryRecoveryExecutor — OnFailure
    // ============================================================

    @Test
    fun `OnFailure returns DoNotRestart for a Started event`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnFailure(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        val decision = executor.decide(policy, buildStartedEvent(), 0)
        assertTrue(decision is RecoveryDecision.DoNotRestart)
    }

    @Test
    fun `OnFailure returns DoNotRestart for an Exited event`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnFailure(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        val decision = executor.decide(policy, buildExitedEvent(), 0)
        assertTrue(decision is RecoveryDecision.DoNotRestart)
    }

    @Test
    fun `OnFailure returns RestartAfter for a Failed event when attemptCount is less than maxAttempts`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnFailure(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        val decision = executor.decide(policy, buildFailedEvent(), 0)
        assertTrue(decision is RecoveryDecision.RestartAfter)
        val restart = decision as RecoveryDecision.RestartAfter
        assertEquals(1, restart.nextAttempt)
        assertEquals(100L, restart.delayMs)
    }

    @Test
    fun `OnFailure returns RestartExhausted for a Failed event when attemptCount equals maxAttempts`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnFailure(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        val decision = executor.decide(policy, buildFailedEvent(), 3)
        assertTrue(decision is RecoveryDecision.RestartExhausted)
        val exhausted = decision as RecoveryDecision.RestartExhausted
        assertEquals(3, exhausted.attempts)
    }

    // ============================================================
    // InMemoryRecoveryExecutor — OnNonZeroExit
    // ============================================================

    @Test
    fun `OnNonZeroExit returns DoNotRestart for exit code 0`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnNonZeroExit(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        val decision = executor.decide(
            policy,
            buildExitedEvent(exitCode = 0),
            0,
        )
        assertTrue(decision is RecoveryDecision.DoNotRestart)
    }

    @Test
    fun `OnNonZeroExit returns RestartAfter for non-zero exit code when attemptCount is less than maxAttempts`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnNonZeroExit(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        val decision = executor.decide(
            policy,
            buildExitedEvent(exitCode = 1),
            0,
        )
        assertTrue(decision is RecoveryDecision.RestartAfter)
    }

    @Test
    fun `OnNonZeroExit returns RestartExhausted when attemptCount equals maxAttempts`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnNonZeroExit(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        val decision = executor.decide(
            policy,
            buildExitedEvent(exitCode = 1),
            3,
        )
        assertTrue(decision is RecoveryDecision.RestartExhausted)
    }

    // ============================================================
    // InMemoryRecoveryExecutor — OnAnyExit
    // ============================================================

    @Test
    fun `OnAnyExit returns RestartAfter for exit code 0`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnAnyExit(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        val decision = executor.decide(
            policy,
            buildExitedEvent(exitCode = 0),
            0,
        )
        assertTrue(decision is RecoveryDecision.RestartAfter)
    }

    @Test
    fun `OnAnyExit returns RestartExhausted when attemptCount equals maxAttempts`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnAnyExit(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        val decision = executor.decide(
            policy,
            buildExitedEvent(exitCode = 0),
            3,
        )
        assertTrue(decision is RecoveryDecision.RestartExhausted)
    }

    @Test
    fun `OnAnyExit returns DoNotRestart for a Started event`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnAnyExit(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(100L),
        )
        val decision = executor.decide(policy, buildStartedEvent(), 0)
        assertTrue(decision is RecoveryDecision.DoNotRestart)
    }

    // ============================================================
    // InMemoryRecoveryExecutor — computeDelay
    // ============================================================

    @Test
    fun `Fixed backoff returns the same delay for any attempt`() {
        val executor = InMemoryRecoveryExecutor()
        val backoff = BackoffStrategy.Fixed(delayMs = 500L)
        assertEquals(500L, executor.computeDelay(backoff, 0))
        assertEquals(500L, executor.computeDelay(backoff, 1))
        assertEquals(500L, executor.computeDelay(backoff, 5))
        assertEquals(500L, executor.computeDelay(backoff, 100))
    }

    @Test
    fun `Exponential backoff returns base for attempt 0`() {
        val executor = InMemoryRecoveryExecutor()
        val backoff = BackoffStrategy.Exponential(
            baseMs = 100L,
            maxMs = 10_000L,
            multiplier = 2.0,
        )
        assertEquals(100L, executor.computeDelay(backoff, 0))
    }

    @Test
    fun `Exponential backoff doubles the delay for each attempt`() {
        val executor = InMemoryRecoveryExecutor()
        val backoff = BackoffStrategy.Exponential(
            baseMs = 100L,
            maxMs = 100_000L,
            multiplier = 2.0,
        )
        assertEquals(100L, executor.computeDelay(backoff, 0))
        assertEquals(200L, executor.computeDelay(backoff, 1))
        assertEquals(400L, executor.computeDelay(backoff, 2))
        assertEquals(800L, executor.computeDelay(backoff, 3))
        assertEquals(1_600L, executor.computeDelay(backoff, 4))
    }

    @Test
    fun `Exponential backoff caps the delay at maxMs`() {
        val executor = InMemoryRecoveryExecutor()
        val backoff = BackoffStrategy.Exponential(
            baseMs = 100L,
            maxMs = 1_000L,
            multiplier = 2.0,
        )
        assertEquals(100L, executor.computeDelay(backoff, 0))
        assertEquals(200L, executor.computeDelay(backoff, 1))
        assertEquals(400L, executor.computeDelay(backoff, 2))
        assertEquals(800L, executor.computeDelay(backoff, 3))
        assertEquals(1_000L, executor.computeDelay(backoff, 4)) // capped
        assertEquals(1_000L, executor.computeDelay(backoff, 5)) // capped
    }

    @Test
    fun `Linear backoff increases the delay linearly`() {
        val executor = InMemoryRecoveryExecutor()
        val backoff = BackoffStrategy.Linear(
            baseMs = 100L,
            incrementMs = 200L,
        )
        assertEquals(100L, executor.computeDelay(backoff, 0))
        assertEquals(300L, executor.computeDelay(backoff, 1))
        assertEquals(500L, executor.computeDelay(backoff, 2))
        assertEquals(900L, executor.computeDelay(backoff, 4))
    }

    // ============================================================
    // Realistic scenarios
    // ============================================================

    @Test
    fun `realistic scenario process crashes 3 times, recovery restarts 3 times, gives up on the 4th crash`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnFailure(
            maxAttempts = 3,
            backoff = BackoffStrategy.Fixed(delayMs = 1_000L),
        )

        // Crash 1: attemptCount = 0, restart
        // (attempt 1).
        val d1 = executor.decide(policy, buildFailedEvent(), 0)
        assertTrue(d1 is RecoveryDecision.RestartAfter)
        assertEquals(1, (d1 as RecoveryDecision.RestartAfter).nextAttempt)

        // Crash 2: attemptCount = 1, restart
        // (attempt 2).
        val d2 = executor.decide(policy, buildFailedEvent(), 1)
        assertTrue(d2 is RecoveryDecision.RestartAfter)
        assertEquals(2, (d2 as RecoveryDecision.RestartAfter).nextAttempt)

        // Crash 3: attemptCount = 2, restart
        // (attempt 3).
        val d3 = executor.decide(policy, buildFailedEvent(), 2)
        assertTrue(d3 is RecoveryDecision.RestartAfter)
        assertEquals(3, (d3 as RecoveryDecision.RestartAfter).nextAttempt)

        // Crash 4: attemptCount = 3, give up.
        val d4 = executor.decide(policy, buildFailedEvent(), 3)
        assertTrue(d4 is RecoveryDecision.RestartExhausted)
        assertEquals(
            3,
            (d4 as RecoveryDecision.RestartExhausted).attempts,
        )
    }

    @Test
    fun `realistic scenario exponential backoff produces the right delay sequence`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnFailure(
            maxAttempts = 5,
            backoff = BackoffStrategy.Exponential(
                baseMs = 1_000L,
                maxMs = 60_000L,
                multiplier = 2.0,
            ),
        )

        // Crash 1: attemptCount = 0, restart
        // with delay 1s (base * 2^1 = 2000? or
        // base * 2^0 = 1000?). We use
        // base * 2^nextAttempt: 2^1 = 2, so
        // 2000.
        val d1 = executor.decide(policy, buildFailedEvent(), 0)
        assertTrue(d1 is RecoveryDecision.RestartAfter)
        assertEquals(2_000L, (d1 as RecoveryDecision.RestartAfter).delayMs)

        // Crash 2: attemptCount = 1, restart
        // with delay 4s.
        val d2 = executor.decide(policy, buildFailedEvent(), 1)
        assertEquals(4_000L, (d2 as RecoveryDecision.RestartAfter).delayMs)

        // Crash 3: attemptCount = 2, restart
        // with delay 8s.
        val d3 = executor.decide(policy, buildFailedEvent(), 2)
        assertEquals(8_000L, (d3 as RecoveryDecision.RestartAfter).delayMs)
    }

    @Test
    fun `realistic scenario OnAnyExit restarts on exit code 0 and recovers`() {
        val executor = InMemoryRecoveryExecutor()
        val policy = RecoveryPolicy.OnAnyExit(
            maxAttempts = 2,
            backoff = BackoffStrategy.Fixed(delayMs = 500L),
        )

        // First exit: attemptCount = 0,
        // restart (attempt 1).
        val d1 = executor.decide(
            policy,
            buildExitedEvent(exitCode = 0),
            0,
        )
        assertTrue(d1 is RecoveryDecision.RestartAfter)
        assertEquals(1, (d1 as RecoveryDecision.RestartAfter).nextAttempt)

        // Second exit: attemptCount = 1,
        // restart (attempt 2).
        val d2 = executor.decide(
            policy,
            buildExitedEvent(exitCode = 0),
            1,
        )
        assertTrue(d2 is RecoveryDecision.RestartAfter)
        assertEquals(2, (d2 as RecoveryDecision.RestartAfter).nextAttempt)

        // Third exit: attemptCount = 2, give
        // up.
        val d3 = executor.decide(
            policy,
            buildExitedEvent(exitCode = 0),
            2,
        )
        assertTrue(d3 is RecoveryDecision.RestartExhausted)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildStartedEvent(): ProcessEvent.Started =
        ProcessEvent.Started(
            handleId = ProcessId.random(),
            pid = 12345,
            timestampMs = 1_700_000_000_000L,
        )

    private fun buildExitedEvent(
        exitCode: Int = 0,
    ): ProcessEvent.Exited = ProcessEvent.Exited(
        handleId = ProcessId.random(),
        exitCode = exitCode,
        durationMs = 5_000L,
        timestampMs = 1_700_000_005_000L,
    )

    private fun buildFailedEvent(): ProcessEvent.Failed =
        ProcessEvent.Failed(
            handleId = ProcessId.random(),
            failureReason = "segmentation fault",
            durationMs = 1_000L,
            timestampMs = 1_700_000_001_000L,
        )
}
