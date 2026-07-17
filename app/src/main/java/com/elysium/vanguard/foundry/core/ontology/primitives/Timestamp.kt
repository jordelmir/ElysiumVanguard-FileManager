package com.elysium.vanguard.foundry.core.ontology.primitives

/**
 * A monotonic timestamp value class. In Phase 1 the timestamp is a
 * wall-clock epoch milliseconds; in later phases the platform will swap
 * to the existing Elysium Vanguard Hybrid Logical Clock (HLC) via the
 * `TimestampSource` interface.
 *
 * Why a value class over `Long`: a raw `Long` is never a domain value
 * (per `.ai/AGENTS.md` 24.1 — "A `Map<String, Any>` is never the value").
 * The wrapper enforces the unit + the invariants + the equality
 * semantics.
 *
 * Monotonicity is enforced by the factory: a `Timestamp.now(clock)`
 * call may never return a value less than the previously issued
 * timestamp. In multi-threaded contexts the caller MUST serialize the
 * `now()` calls or supply a thread-safe source.
 */
@JvmInline
value class Timestamp(val epochMs: Long) {

    init {
        require(epochMs >= 0) { "Timestamp epochMs must be non-negative, got $epochMs" }
    }

    companion object {
        /**
         * A monotonic timestamp source. Phase 1 implementation uses
         * `System.currentTimeMillis()` and a `synchronized` lock for
         * monotonicity; the production implementation will be the EV
         * HLC.
         */
        fun interface TimestampSource {
            fun now(): Timestamp
        }

        /**
         * A thread-safe monotonic source backed by `System.currentTimeMillis()`.
         * For production use, swap in the HLC source from
         * `core/runtime/sync/`.
         */
        fun monotonicWallClock(): TimestampSource = TimestampSource {
            synchronized(LOCK) {
                val now = System.currentTimeMillis()
                val next = if (now <= lastIssued) lastIssued + 1 else now
                lastIssued = next
                Timestamp(next)
            }
        }

        @Volatile
        private var lastIssued: Long = 0L
        private val LOCK = Any()
    }
}
