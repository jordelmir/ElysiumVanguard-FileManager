package com.elysium.vanguard.core.orchestrator

import kotlin.math.min
import kotlin.math.pow

/**
 * Phase 80 (Universal Execution Engine) — the
 * **Recovery Policy**, the typed rules that
 * decide whether to restart a failed process
 * + the backoff strategy between restart
 * attempts.
 *
 * Per the master vision's Universal Execution
 * Engine (section 6), the dispatch flow is:
 *
 *   Runtime Selection
 *     ↓
 *   Sandbox and Mount Policy
 *     ↓
 *   Process Supervisor (Phase 78)
 *     ↓
 *   Telemetry and Recovery (Phase 79 + this
 *   phase)
 *
 * Phase 79 was the **telemetry** step (the
 * process watcher records the lifecycle
 * events). This phase is the **recovery**
 * step: given a [ProcessEvent] + the
 * [RecoveryPolicy] + the current attempt
 * count, the [RecoveryExecutor] decides
 * whether to restart the process.
 *
 * The recovery policy is **pure-domain** (no
 * I/O, no Android dependencies). The test impl
 * is the `InMemoryRecoveryExecutor`. The
 * production impl may be the same
 * (the recovery executor is stateless + pure;
 * the same impl is used in production).
 *
 * The recovery policy is **3 primitives**:
 *
 *   - **`BackoffStrategy`** — the typed
 *     strategy for the delay between restart
 *     attempts (Fixed / Exponential / Linear).
 *   - **`RecoveryPolicy`** — the typed policy
 *     (None / OnFailure / OnCrash /
 *     OnNonZeroExit).
 *   - **`RecoveryDecision`** — the typed
 *     decision (DoNotRestart /
 *     RestartAfter / RestartExhausted).
 */
sealed class RecoveryExecutor {

    /**
     * Decide whether to restart a process
     * given a [RecoveryPolicy] + a
     * [ProcessEvent] + the current attempt
     * count. The attempt count is the
     * number of times the process has
     * been restarted so far (0 for the
     * first attempt, 1 for the first
     * restart, etc.).
     *
     * Returns a [RecoveryDecision] with
     * the typed action.
     */
    abstract fun decide(
        policy: RecoveryPolicy,
        event: ProcessEvent,
        attemptCount: Int,
    ): RecoveryDecision

    /**
     * Compute the delay before the next
     * restart attempt. The delay is a
     * function of the [BackoffStrategy]
     * + the attempt number.
     */
    abstract fun computeDelay(
        backoff: BackoffStrategy,
        attempt: Int,
    ): Long

    /**
     * Check whether the policy is
     * `None` (the convenience predicate
     * for the "no recovery" case).
     */
    fun isNoRecovery(policy: RecoveryPolicy): Boolean =
        policy is RecoveryPolicy.None
}

/**
 * The typed backoff strategy. The
 * strategy determines the delay between
 * restart attempts.
 *
 * The strategy is a sealed class with 3
 * cases:
 *   - **`Fixed`** — the same delay for
 *     every attempt.
 *   - **`Exponential`** — exponentially
 *     increasing delay (with a cap).
 *   - **`Linear`** — linearly increasing
 *     delay.
 */
sealed class BackoffStrategy {

    /**
     * A fixed delay. The same delay is
     * used for every restart attempt.
     */
    data class Fixed(
        val delayMs: Long,
    ) : BackoffStrategy() {
        init {
            require(delayMs >= 0) {
                "BackoffStrategy.Fixed.delayMs must be >= 0, " +
                    "got $delayMs"
            }
        }
    }

    /**
     * An exponentially increasing
     * delay. The delay is
     * `baseMs * multiplier^attempt`,
     * capped at `maxMs`.
     */
    data class Exponential(
        val baseMs: Long,
        val maxMs: Long,
        val multiplier: Double,
    ) : BackoffStrategy() {
        init {
            require(baseMs > 0) {
                "BackoffStrategy.Exponential.baseMs must be > 0, " +
                    "got $baseMs"
            }
            require(maxMs >= baseMs) {
                "BackoffStrategy.Exponential.maxMs ($maxMs) " +
                    "must be >= baseMs ($baseMs)"
            }
            require(multiplier >= 1.0) {
                "BackoffStrategy.Exponential.multiplier must be " +
                    ">= 1.0, got $multiplier"
            }
        }
    }

    /**
     * A linearly increasing delay. The
     * delay is `baseMs + incrementMs *
     * attempt`.
     */
    data class Linear(
        val baseMs: Long,
        val incrementMs: Long,
    ) : BackoffStrategy() {
        init {
            require(baseMs >= 0) {
                "BackoffStrategy.Linear.baseMs must be >= 0, " +
                    "got $baseMs"
            }
            require(incrementMs >= 0) {
                "BackoffStrategy.Linear.incrementMs must be >= 0, " +
                    "got $incrementMs"
            }
        }
    }
}

/**
 * The typed recovery policy. The policy
 * determines **when** to restart a process
 * (a Failed event? an Exited event with
 * non-zero exit code? an Exited event with
 * any exit code? never?).
 *
 * The policy is a sealed class with 4
 * cases:
 *   - **`None`** — never restart.
 *   - **`OnFailure`** — restart on Failed
 *     events only.
 *   - **`OnNonZeroExit`** — restart on
 *     Exited events with non-zero exit
 *     code.
 *   - **`OnAnyExit`** — restart on any
 *     Exited event (regardless of exit
 *     code).
 */
sealed class RecoveryPolicy {

    /**
     * Never restart. The process is
     * not recoverable; the user must
     * intervene manually.
     */
    data object None : RecoveryPolicy()

    /**
     * Restart on Failed events only.
     * The process is restarted when
     * the ProcessWatcher emits a
     * `ProcessEvent.Failed` (the
     * process crashed OR failed to
     * launch).
     */
    data class OnFailure(
        val maxAttempts: Int,
        val backoff: BackoffStrategy,
    ) : RecoveryPolicy() {
        init {
            require(maxAttempts > 0) {
                "RecoveryPolicy.OnFailure.maxAttempts must be " +
                    "> 0, got $maxAttempts"
            }
        }
    }

    /**
     * Restart on Exited events with
     * non-zero exit code. The
     * process is restarted when the
     * ProcessWatcher emits a
     * `ProcessEvent.Exited` with
     * `exitCode != 0`.
     */
    data class OnNonZeroExit(
        val maxAttempts: Int,
        val backoff: BackoffStrategy,
    ) : RecoveryPolicy() {
        init {
            require(maxAttempts > 0) {
                "RecoveryPolicy.OnNonZeroExit.maxAttempts " +
                    "must be > 0, got $maxAttempts"
            }
        }
    }

    /**
     * Restart on any Exited event
     * (regardless of exit code). The
     * process is restarted when the
     * ProcessWatcher emits a
     * `ProcessEvent.Exited` (the
     * process exited, normally OR
     * abnormally).
     */
    data class OnAnyExit(
        val maxAttempts: Int,
        val backoff: BackoffStrategy,
    ) : RecoveryPolicy() {
        init {
            require(maxAttempts > 0) {
                "RecoveryPolicy.OnAnyExit.maxAttempts must be " +
                    "> 0, got $maxAttempts"
            }
        }
    }
}

/**
 * The typed recovery decision. The
 * decision is the executor's output
 * given a [RecoveryPolicy] + a
 * [ProcessEvent] + the current attempt
 * count.
 *
 * The decision is a sealed class with 3
 * cases:
 *   - **`DoNotRestart`** — the process
 *     is not restarted.
 *   - **`RestartAfter`** — the process
 *     is restarted after a delay.
 *   - **`RestartExhausted`** — the
 *     process has reached the max
 *     attempts; it is not restarted.
 */
sealed class RecoveryDecision {

    /**
     * The handle id the decision is
     * for. The id is the join key the
     * consumer uses to act on the
     * decision.
     */
    abstract val handleId: ProcessId

    /**
     * The process is not restarted.
     * The reason is a human-readable
     * string.
     */
    data class DoNotRestart(
        override val handleId: ProcessId,
        val reason: String,
    ) : RecoveryDecision() {
        init {
            require(reason.isNotBlank()) {
                "RecoveryDecision.DoNotRestart.reason must " +
                    "not be blank"
            }
        }
    }

    /**
     * The process is restarted after
     * a delay. The delay is a
     * function of the backoff
     * strategy + the current attempt
     * number.
     */
    data class RestartAfter(
        override val handleId: ProcessId,
        val nextAttempt: Int,
        val delayMs: Long,
    ) : RecoveryDecision() {
        init {
            require(nextAttempt > 0) {
                "RecoveryDecision.RestartAfter.nextAttempt " +
                    "must be > 0, got $nextAttempt"
            }
            require(delayMs >= 0) {
                "RecoveryDecision.RestartAfter.delayMs " +
                    "must be >= 0, got $delayMs"
            }
        }
    }

    /**
     * The process has reached the
     * max attempts; it is not
     * restarted. The last reason is
     * the failure reason that
     * triggered the final attempt.
     */
    data class RestartExhausted(
        override val handleId: ProcessId,
        val attempts: Int,
        val lastReason: String,
    ) : RecoveryDecision() {
        init {
            require(attempts > 0) {
                "RecoveryDecision.RestartExhausted.attempts " +
                    "must be > 0, got $attempts"
            }
            require(lastReason.isNotBlank()) {
                "RecoveryDecision.RestartExhausted.lastReason " +
                    "must not be blank"
            }
        }
    }
}

/**
 * The in-memory [RecoveryExecutor] for
 * testing + production. The executor
 * is the stateless composition of:
 *   - The decision rules (per
 *     [RecoveryPolicy]).
 *   - The backoff functions (per
 *     [BackoffStrategy]).
 *
 * The executor is **thread-safe** (no
 * mutable fields).
 */
class InMemoryRecoveryExecutor : RecoveryExecutor() {

    override fun decide(
        policy: RecoveryPolicy,
        event: ProcessEvent,
        attemptCount: Int,
    ): RecoveryDecision = when (policy) {
        is RecoveryPolicy.None -> RecoveryDecision.DoNotRestart(
            handleId = event.handleId,
            reason = "policy is None",
        )
        is RecoveryPolicy.OnFailure -> when (event) {
            is ProcessEvent.Failed -> {
                if (attemptCount >= policy.maxAttempts) {
                    RecoveryDecision.RestartExhausted(
                        handleId = event.handleId,
                        attempts = attemptCount,
                        lastReason = event.failureReason,
                    )
                } else {
                    val nextAttempt = attemptCount + 1
                    val delayMs = computeDelay(
                        policy.backoff,
                        nextAttempt,
                    )
                    RecoveryDecision.RestartAfter(
                        handleId = event.handleId,
                        nextAttempt = nextAttempt,
                        delayMs = delayMs,
                    )
                }
            }
            else -> RecoveryDecision.DoNotRestart(
                handleId = event.handleId,
                reason = "policy is OnFailure; event is " +
                    "${event::class.simpleName}",
            )
        }
        is RecoveryPolicy.OnNonZeroExit -> when (event) {
            is ProcessEvent.Exited -> {
                if (event.exitCode == 0) {
                    RecoveryDecision.DoNotRestart(
                        handleId = event.handleId,
                        reason = "policy is OnNonZeroExit; " +
                            "exitCode is 0",
                    )
                } else if (attemptCount >= policy.maxAttempts) {
                    RecoveryDecision.RestartExhausted(
                        handleId = event.handleId,
                        attempts = attemptCount,
                        lastReason = "exit code " + event.exitCode,
                    )
                } else {
                    val nextAttempt = attemptCount + 1
                    val delayMs = computeDelay(
                        policy.backoff,
                        nextAttempt,
                    )
                    RecoveryDecision.RestartAfter(
                        handleId = event.handleId,
                        nextAttempt = nextAttempt,
                        delayMs = delayMs,
                    )
                }
            }
            else -> RecoveryDecision.DoNotRestart(
                handleId = event.handleId,
                reason = "policy is OnNonZeroExit; event is " +
                    "${event::class.simpleName}",
            )
        }
        is RecoveryPolicy.OnAnyExit -> when (event) {
            is ProcessEvent.Exited -> {
                if (attemptCount >= policy.maxAttempts) {
                    RecoveryDecision.RestartExhausted(
                        handleId = event.handleId,
                        attempts = attemptCount,
                        lastReason = "exit code " + event.exitCode,
                    )
                } else {
                    val nextAttempt = attemptCount + 1
                    val delayMs = computeDelay(
                        policy.backoff,
                        nextAttempt,
                    )
                    RecoveryDecision.RestartAfter(
                        handleId = event.handleId,
                        nextAttempt = nextAttempt,
                        delayMs = delayMs,
                    )
                }
            }
            else -> RecoveryDecision.DoNotRestart(
                handleId = event.handleId,
                reason = "policy is OnAnyExit; event is " +
                    "${event::class.simpleName}",
            )
        }
    }

    override fun computeDelay(
        backoff: BackoffStrategy,
        attempt: Int,
    ): Long = when (backoff) {
        is BackoffStrategy.Fixed -> backoff.delayMs
        is BackoffStrategy.Exponential -> {
            require(attempt >= 0) {
                "computeDelay: attempt must be >= 0, got $attempt"
            }
            val multiplierPow = backoff.multiplier.pow(attempt.toDouble())
            val rawMs = backoff.baseMs.toDouble() * multiplierPow
            min(rawMs.toLong(), backoff.maxMs)
        }
        is BackoffStrategy.Linear ->
            backoff.baseMs + (backoff.incrementMs * attempt)
    }
}
