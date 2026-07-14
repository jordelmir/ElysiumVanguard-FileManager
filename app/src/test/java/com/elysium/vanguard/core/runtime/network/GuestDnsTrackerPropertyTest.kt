package com.elysium.vanguard.core.runtime.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Phase 11.2 — Property-based tests for the DNS refresh pipeline.
 *
 * The master order §33.2 mandates property tests for the VT parser,
 * archive extractor, path handling and "protocol decoders" (which
 * includes our observer flow). The `distinctUntilChanged().drop(1)`
 * pipeline in [GuestDnsSessionTracker] is exactly the kind of code
 * where a single bad ordering of operators silently drops real
 * changes or fires duplicates — Phase 11.1 already caught one such
 * bug in unit tests. This file generates 200 random sequences per
 * property to make the invariant hold against a wider input space
 * than any hand-picked scenario.
 *
 * The properties we pin:
 *
 *   P1. After `start()`, the counter is exactly 1 (the initial sync).
 *   P2. After N changes drawn from a *known-distinct* set, the counter
 *       is 1 + N.
 *   P3. After a sequence where some consecutive pairs are duplicates,
 *       the counter is 1 + (number of distinct runs in the sequence).
 *       `distinctUntilChanged` is a consecutive-duplicate filter, not
 *       a global distinct set; we pin the actual semantics, not a
 *       weaker proxy.
 *   P4. After `stop()` then N more changes, the counter is unchanged.
 *   P5. After a re-`start()`, the counter is 1 + distinct-runs + 1 +
 *       subsequent distinct runs.
 *   P6. The pipeline is commutative over permutations of the input
 *       sequence when the sequence is a *run-length encoding* of
 *       itself — i.e. two sequences that produce the same run pattern
 *       fire the same number of refreshes.
 *   P7. The pipeline never fires more than `1 + N` refreshes for any
 *       sequence of length N, even adversarial duplicates.
 *
 * We do not pull in `kotest-property` to keep the dependency surface
 * small. A seeded `Random` makes failures reproducible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuestDnsTrackerPropertyTest {

    /** Property: start runs exactly one initial sync, regardless of the
     *  observer's seed state. */
    @Test
    fun `P1 - start runs exactly one initial sync`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repeat(50) { iteration ->
            val seed = 1000L + iteration
            val observer = InMemoryGuestDnsObserver(
                snapshot = { randomConfig(Random(seed)) }
            )
            val registry = ActiveRootfsRegistry()
            val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
            val rootfs = Files.createTempDirectory("elysium-prop-p1-$iteration").toFile()
            try {
                val counter = AtomicInteger(0)
                registry.register(rootfs) { counter.incrementAndGet() }

                tracker.start()
                advanceUntilIdle()
                assertEquals(
                    "P1 iteration $iteration: start must trigger exactly one refresh",
                    1,
                    counter.get()
                )
            } finally {
                rootfs.deleteRecursively()
                tracker.stop()
            }
        }
    }

    /** Property: known-distinct changes trigger refreshes (no false
     *  negatives from the filter). */
    @Test
    fun `P2 - all-distinct sequence triggers one refresh per change`() =
        runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repeat(200) { iteration ->
            val rng = Random(2000L + iteration)
            val config = ArrayConfig()
            config.current = randomConfig(rng)
            val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
            val registry = ActiveRootfsRegistry()
            val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
            val rootfs = Files.createTempDirectory("elysium-prop-p2-$iteration").toFile()
            try {
                val counter = AtomicInteger(0)
                registry.register(rootfs) { counter.incrementAndGet() }

                tracker.start()
                advanceUntilIdle()
                val baseline = counter.get() // 1

                // 20 changes, all distinct from each other and from
                // the seed (the random space is wide enough).
                val changes = generateDistinct(rng, count = 20, seed = config.current)
                for (change in changes) {
                    config.current = change
                    observer.signalChange()
                }
                advanceUntilIdle()

                assertEquals(
                    "P2 iteration $iteration: 20 distinct changes after 1 initial sync",
                    baseline + 20,
                    counter.get()
                )
            } finally {
                rootfs.deleteRecursively()
                tracker.stop()
            }
        }
    }

    /** Property: consecutive duplicates are filtered; non-consecutive
     *  duplicates pass. The number of refreshes equals 1 + the number
     *  of distinct runs in the [V_init, c1, c2, ..., cN] sequence. */
    @Test
    fun `P3 - consecutive duplicates are filtered (run-length semantics)`() =
        runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repeat(200) { iteration ->
            val rng = Random(3000L + iteration)
            val config = ArrayConfig()
            config.current = randomConfig(rng)
            val seed = config.current
            val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
            val registry = ActiveRootfsRegistry()
            val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
            val rootfs = Files.createTempDirectory("elysium-prop-p3-$iteration").toFile()
            try {
                val counter = AtomicInteger(0)
                registry.register(rootfs) { counter.incrementAndGet() }

                tracker.start()
                advanceUntilIdle()
                val baseline = counter.get() // 1

                // Build a sequence with random "runs": each value
                // repeats 1..4 times before the next value takes over.
                val changes = buildSequenceWithRuns(rng, totalLength = 30, seed = seed)
                val expectedRuns = countRuns(seed, changes)

                for (change in changes) {
                    config.current = change
                    observer.signalChange()
                }
                advanceUntilIdle()

                assertEquals(
                    "P3 iteration $iteration: ${changes.size} changes, " +
                        "$expectedRuns runs, baseline=$baseline",
                    baseline + expectedRuns,
                    counter.get()
                )
            } finally {
                rootfs.deleteRecursively()
                tracker.stop()
            }
        }
    }

    /** Property: stop blocks subsequent changes until the next start. */
    @Test
    fun `P4 - stop prevents further refreshes`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repeat(50) { iteration ->
            val rng = Random(4000L + iteration)
            val config = ArrayConfig()
            config.current = randomConfig(rng)
            val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
            val registry = ActiveRootfsRegistry()
            val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
            val rootfs = Files.createTempDirectory("elysium-prop-p4-$iteration").toFile()
            try {
                val counter = AtomicInteger(0)
                registry.register(rootfs) { counter.incrementAndGet() }

                tracker.start()
                advanceUntilIdle()
                tracker.stop()

                for (pass in 0 until 10) {
                    config.current = randomConfig(rng)
                    observer.signalChange()
                }
                advanceUntilIdle()

                assertEquals(
                    "P4 iteration $iteration: stop must prevent further refreshes",
                    1,
                    counter.get()
                )
            } finally {
                rootfs.deleteRecursively()
                tracker.stop()
            }
        }
    }

    /** Property: re-start re-applies the initial sync and resumes
     *  tracking from the new seed. */
    @Test
    fun `P5 - restart re-syncs and resumes`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repeat(50) { iteration ->
            val rng = Random(5000L + iteration)
            val config = ArrayConfig()
            config.current = randomConfig(rng)
            val seed = config.current
            val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
            val registry = ActiveRootfsRegistry()
            val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
            val rootfs = Files.createTempDirectory("elysium-prop-p5-$iteration").toFile()
            try {
                val counter = AtomicInteger(0)
                registry.register(rootfs) { counter.incrementAndGet() }

                tracker.start()
                advanceUntilIdle()

                val firstChanges = buildSequenceWithRuns(rng, totalLength = 10, seed = seed)
                val firstRuns = countRuns(seed, firstChanges)
                for (c in firstChanges) {
                    config.current = c
                    observer.signalChange()
                }
                advanceUntilIdle()
                val midCount = counter.get()
                assertEquals(
                    "P5 first phase iteration $iteration",
                    1 + firstRuns,
                    midCount
                )

                tracker.stop()
                for (pass in 0 until 5) {
                    config.current = randomConfig(rng)
                    observer.signalChange()
                }
                advanceUntilIdle()
                assertEquals(
                    "P5 stopped phase iteration $iteration",
                    midCount,
                    counter.get()
                )

                tracker.start()
                advanceUntilIdle()
                // Re-start pays the initial sync fee.
                val postRestartBase = midCount + 1
                assertEquals(
                    "P5 restart initial sync iteration $iteration",
                    postRestartBase,
                    counter.get()
                )

                val secondChanges = buildSequenceWithRuns(
                    rng,
                    totalLength = 10,
                    seed = config.current
                )
                val secondRuns = countRuns(config.current, secondChanges)
                for (c in secondChanges) {
                    config.current = c
                    observer.signalChange()
                }
                advanceUntilIdle()
                assertEquals(
                    "P5 post-restart phase iteration $iteration",
                    postRestartBase + secondRuns,
                    counter.get()
                )
            } finally {
                rootfs.deleteRecursively()
                tracker.stop()
            }
        }
    }

    /** Property: two sequences that produce the same run pattern
     *  (i.e. one is a permutation that preserves the run structure)
     *  fire the same number of refreshes. The pipeline depends on
     *  the *runs*, not on the values themselves. */
    @Test
    fun `P6 - pipeline is order-independent over run pattern`() =
        runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repeat(50) { iteration ->
            val rng = Random(6000L + iteration)
            val seed = randomConfig(rng)
            val runPatternA = buildRunPattern(rng, totalLength = 25)
            val runPatternB = runPatternA.reversed() // reverses the run order
            val counterA = runSequence(dispatcher, seed, runPatternA)
            val counterB = runSequence(dispatcher, seed, runPatternB)
            // Both have the same number of runs.
            val runsA = countRuns(seed, runPatternA)
            val runsB = countRuns(seed, runPatternB)
            assertEquals(
                "P6 iteration $iteration: runs match ($runsA vs $runsB)",
                runsA,
                runsB
            )
            assertEquals(
                "P6 iteration $iteration: counters must match",
                counterA,
                counterB
            )
        }
    }

    /** Property: the pipeline never fires more than `1 + N` refreshes
     *  for a sequence of length N. Adversarial duplicates cannot push
     *  us higher. */
    @Test
    fun `P7 - upper bound on refreshes is 1 + sequence length`() =
        runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repeat(50) { iteration ->
            val rng = Random(7000L + iteration)
            val config = ArrayConfig()
            config.current = randomConfig(rng)
            val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
            val registry = ActiveRootfsRegistry()
            val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
            val rootfs = Files.createTempDirectory("elysium-prop-p7-$iteration").toFile()
            try {
                val counter = AtomicInteger(0)
                registry.register(rootfs) { counter.incrementAndGet() }

                tracker.start()
                advanceUntilIdle()

                // 100 changes, all the *same* value. The pipeline
                // must collapse them to 1 refresh (the first), and
                // the initial sync still counts.
                val v = randomConfig(rng)
                repeat(100) {
                    config.current = v
                    observer.signalChange()
                }
                advanceUntilIdle()

                // Worst case: every value is distinct → 1 + 100 = 101.
                // In this test, all are the same → 1 + 1 = 2.
                assertTrue(
                    "P7 iteration $iteration: ${counter.get()} > 1 + 100",
                    counter.get() <= 1 + 100
                )
                assertEquals(
                    "P7 iteration $iteration: all duplicates must collapse to one refresh",
                    2, // initial sync + the first distinct
                    counter.get()
                )
            } finally {
                rootfs.deleteRecursively()
                tracker.stop()
            }
        }
    }

    // --- helpers ---

    private suspend fun kotlinx.coroutines.test.TestScope.runSequence(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        seed: GuestDnsConfig,
        changes: List<GuestDnsConfig>
    ): Int {
        val config = ArrayConfig()
        config.current = seed
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
        val rootfs = Files.createTempDirectory("elysium-prop-p6-seq").toFile()
        val counter = AtomicInteger(0)
        try {
            registry.register(rootfs) { counter.incrementAndGet() }
            tracker.start()
            advanceUntilIdle()
            for (c in changes) {
                config.current = c
                observer.signalChange()
            }
            advanceUntilIdle()
            return counter.get()
        } finally {
            rootfs.deleteRecursively()
            tracker.stop()
        }
    }

    /** Build a sequence of distinct configs, each different from the
     *  previous one and from the seed. */
    private fun generateDistinct(
        rng: Random,
        count: Int,
        seed: GuestDnsConfig
    ): List<GuestDnsConfig> {
        val out = mutableListOf<GuestDnsConfig>()
        var last = seed
        repeat(count) {
            var next: GuestDnsConfig
            do {
                next = randomConfig(rng)
            } while (next == last)
            out += next
            last = next
        }
        return out
    }

    /** Build a sequence with random "runs": each generated value
     *  repeats 1..4 times before the next one takes over. Total
     *  length is `totalLength`. */
    private fun buildSequenceWithRuns(
        rng: Random,
        totalLength: Int,
        seed: GuestDnsConfig
    ): List<GuestDnsConfig> {
        val out = mutableListOf<GuestDnsConfig>()
        var last = seed
        while (out.size < totalLength) {
            val runLength = 1 + rng.nextInt(4)
            val runValue = randomConfig(rng)
            repeat(runLength.coerceAtMost(totalLength - out.size)) {
                out += if (rng.nextBoolean()) runValue else runValue
                last = runValue
            }
        }
        return out
    }

    /** Same as [buildSequenceWithRuns] but returns the raw pattern
     *  (each element is a config; consecutive duplicates are
     *  intentional). */
    private fun buildRunPattern(
        rng: Random,
        totalLength: Int
    ): List<GuestDnsConfig> = buildSequenceWithRuns(rng, totalLength, randomConfig(rng))

    /** Count the number of "runs" in `changes` when chained after
     *  `seed`. A run is a maximal run of identical values. The number
     *  of runs equals the number of times the value differs from
     *  its predecessor, which is exactly what `distinctUntilChanged`
     *  emits. */
    private fun countRuns(seed: GuestDnsConfig, changes: List<GuestDnsConfig>): Int {
        var count = 0
        var last = seed
        for (c in changes) {
            if (c != last) {
                count++
                last = c
            }
        }
        return count
    }

    private fun randomConfig(rng: Random): GuestDnsConfig {
        val nameservers = (1..(1 + rng.nextInt(3))).map { "192.0.2.${rng.nextInt(256)}" }
        val search = if (rng.nextBoolean()) {
            listOf("domain${rng.nextInt(100)}.example")
        } else emptyList()
        return GuestDnsConfig(nameservers = nameservers, searchDomains = search)
    }

    private class ArrayConfig {
        var current: GuestDnsConfig = GuestDnsConfig.EMPTY
    }
}
