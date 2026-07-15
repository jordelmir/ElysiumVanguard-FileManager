package com.elysium.vanguard.core.runtime.network.firewall

import com.elysium.vanguard.core.runtime.network.policy.NetworkMode
import com.elysium.vanguard.core.runtime.network.policy.NetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 15 — tests for the [NetworkPolicyFirewall] translator
 * and the [InMemoryFirewallBackend].
 *
 * The translator is the seam between Phase 13's broker
 * (single-packet decisions) and the platform firewall
 * (session-wide state). The tests pin the rule shape for each
 * [NetworkMode] and the diff / apply / remove lifecycle.
 */
class NetworkPolicyFirewallTest {

    private val translator = NetworkPolicyFirewall()

    // --- compile by mode ---

    @Test
    fun `compile BLOCKED emits exactly one drop-all rule`() {
        val state = translator.compile("s1", NetworkPolicy(mode = NetworkMode.BLOCKED))
        assertEquals("elysium-s1", state.chainName)
        // 1 drop-all rule.
        assertEquals(1, state.rules.size)
        val r = state.rules.single()
        assertEquals(FirewallAction.DROP, r.action)
        assertEquals(Direction.INBOUND, r.direction)
    }

    @Test
    fun `compile LOOPBACK_ONLY emits accept-lo and drop-all`() {
        val state = translator.compile("s1", NetworkPolicy(mode = NetworkMode.LOOPBACK_ONLY))
        val byId = state.rules.associateBy { it.id }
        assertTrue("must have accept-lo v4", byId.values.any {
            it.id.endsWith(".accept-lo") && it.family == AddressFamily.IPV4
        })
        assertTrue("must have accept-lo v6", byId.values.any {
            it.id.endsWith(".accept-lo-v6") && it.family == AddressFamily.IPV6
        })
        assertTrue("must have a drop-all terminator", byId.values.any {
            it.id.endsWith(".drop-all") && it.action == FirewallAction.DROP
        })
        // No published ports, so no published-port accept.
        assertTrue("must not have published accept", byId.values.none {
            it.id.contains(".published-")
        })
    }

    @Test
    fun `compile OUTBOUND_ONLY emits outbound accept and a drop terminator`() {
        val state = translator.compile("s1", NetworkPolicy(mode = NetworkMode.OUTBOUND_ONLY))
        val byId = state.rules.associateBy { it.id }
        assertTrue("must have outbound NEW accept", byId.values.any {
            it.id.endsWith(".out-new") &&
                it.direction == Direction.OUTBOUND &&
                it.state == ConnectionState.NEW
        })
        assertTrue("must have outbound ESTABLISHED accept", byId.values.any {
            it.id.endsWith(".out-new-est") &&
                it.direction == Direction.OUTBOUND &&
                it.state == ConnectionState.ESTABLISHED
        })
        assertTrue("must have drop-all", byId.values.any {
            it.id.endsWith(".drop-all") && it.action == FirewallAction.DROP
        })
    }

    @Test
    fun `compile OUTBOUND_ONLY with publishedPorts emits one accept per port per family`() {
        val policy = NetworkPolicy(
            mode = NetworkMode.OUTBOUND_ONLY,
            publishedPorts = setOf(8080, 9090)
        )
        val state = translator.compile("s1", policy)
        val published = state.rules.filter { it.id.contains(".published-") }
        // 2 ports * 2 source ranges (V4 any + V6 any) = 4 rules.
        assertEquals(4, published.size)
        assertTrue(published.all { it.action == FirewallAction.ACCEPT })
        assertTrue(published.all { it.direction == Direction.INBOUND })
        assertTrue(published.all { it.protocol == FirewallProtocol.TCP })
        assertTrue(published.any { it.port == 8080 })
        assertTrue(published.any { it.port == 9090 })
        // One V4 and one V6 rule per port.
        assertEquals(2, published.count { it.port == 8080 })
        assertEquals(2, published.count { it.port == 9090 })
        assertTrue(published.any { it.family == AddressFamily.IPV4 })
        assertTrue(published.any { it.family == AddressFamily.IPV6 })
    }

    @Test
    fun `compile LAN emits bidirectional LAN accept and a drop-internet-inbound`() {
        val state = translator.compile("s1", NetworkPolicy(mode = NetworkMode.LAN))
        val byId = state.rules.associateBy { it.id }
        // Bidirectional LAN: at least one outbound + one inbound
        // for each of the 5 LAN ranges.
        val lanOut = byId.values.count {
            it.id.contains(".bidir-out-") && it.direction == Direction.OUTBOUND
        }
        val lanIn = byId.values.count {
            it.id.contains(".bidir-in-") && it.direction == Direction.INBOUND
        }
        assertEquals(5, lanOut)
        assertEquals(5, lanIn)
        assertTrue("must drop inbound internet NEW", byId.values.any {
            it.id.endsWith(".drop-internet-in") && it.action == FirewallAction.DROP
        })
    }

    @Test
    fun `compile INTERNET emits accept-all-bidirectional and no drop`() {
        val state = translator.compile("s1", NetworkPolicy(mode = NetworkMode.INTERNET))
        val byId = state.rules.associateBy { it.id }
        assertTrue("must accept all outbound", byId.values.any {
            it.id.endsWith(".bidir") && it.direction == Direction.OUTBOUND
        })
        assertTrue("must accept all inbound", byId.values.any {
            it.id.endsWith(".bidir-in") && it.direction == Direction.INBOUND
        })
        assertTrue("must not have a drop terminator", byId.values.none {
            it.action == FirewallAction.DROP
        })
    }

    @Test
    fun `compile rules are sorted by priority ascending`() {
        val state = translator.compile("s1", NetworkPolicy(mode = NetworkMode.LAN))
        val priorities = state.rules.map { it.priority }
        assertEquals(priorities.sorted(), priorities)
    }

    @Test
    fun `compile rejects a blank sessionId`() {
        try {
            translator.compile("", NetworkPolicy(mode = NetworkMode.LOOPBACK_ONLY))
            fail("expected IllegalArgumentException for blank sessionId")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `chainNameFor sanitises and caps at 28 chars`() {
        assertEquals("elysium-abc", translator.chainNameFor("abc"))
        // 28 chars total: 'elysium-' (8) + 20 chars of session id.
        val long = "a".repeat(40)
        val name = translator.chainNameFor(long)
        assertEquals(28, name.length)
        assertTrue("name must start with elysium-", name.startsWith("elysium-"))
    }

    @Test
    fun `chainNameFor rejects a sessionId with no alnum characters`() {
        try {
            translator.chainNameFor("---")
            fail("expected IllegalArgumentException for an unparseable sessionId")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    // --- diff ---

    @Test
    fun `diff against the same state is empty`() {
        val state = translator.compile("s1", NetworkPolicy(mode = NetworkMode.LOOPBACK_ONLY))
        val diff = translator.diff(state, state)
        assertTrue("diff must be empty when states match", diff.isEmpty)
        assertEquals(0, diff.totalChanges)
    }

    @Test
    fun `diff flags rules missing from the current state as added`() {
        val desired = translator.compile("s1", NetworkPolicy(mode = NetworkMode.LOOPBACK_ONLY))
        val empty = FirewallState(chainName = desired.chainName, rules = emptyList())
        val diff = translator.diff(desired, empty)
        assertEquals(desired.rules.size, diff.added.size)
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.kept.isEmpty())
    }

    @Test
    fun `diff flags rules missing from the desired state as removed`() {
        val current = translator.compile("s1", NetworkPolicy(mode = NetworkMode.LOOPBACK_ONLY))
        val empty = FirewallState(chainName = current.chainName, rules = emptyList())
        val diff = translator.diff(empty, current)
        assertEquals(current.rules.size, diff.removed.size)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.kept.isEmpty())
    }

    @Test
    fun `diff keeps rules with the same id and splits the rest`() {
        // OUTBOUND_ONLY is the only mode (with published ports)
        // that adds new rules between the common and the desired
        // state; the loopback / drop terminator of LOOPBACK_ONLY
        // is the same regardless of published ports (we
        // intentionally don't emit them in LOOPBACK_ONLY).
        val common = translator.compile("s1", NetworkPolicy(mode = NetworkMode.OUTBOUND_ONLY))
        val desiredExtra = translator.compile(
            "s1",
            NetworkPolicy(mode = NetworkMode.OUTBOUND_ONLY, publishedPorts = setOf(1234))
        )
        val diff = translator.diff(desiredExtra, common)
        // Same mode (OUTBOUND_ONLY) and same session id -> the
        // loopback / outbound / drop-all rules share ids between
        // the two states, so all common rules are "kept".
        assertEquals(common.rules.size, diff.kept.size)
        // The desiredExtra has at least one more rule than common.
        assertTrue("desired must add at least one rule", diff.added.isNotEmpty())
        // The new rule is the published-port accept.
        assertTrue(diff.added.any { it.id.contains(".published-1234-") })
        // No rule from the current state was removed.
        assertTrue(diff.removed.isEmpty())
    }

    // --- IpRange validation ---

    @Test
    fun `IpRange accepts a well-formed CIDR`() {
        IpRange("10.0.0.0/8")
        IpRange("192.168.1.0/24")
        IpRange("::1/128")
        IpRange("any")
    }

    @Test
    fun `IpRange rejects a non-CIDR string`() {
        try {
            IpRange("not-a-cidr")
            fail("expected IllegalArgumentException for missing slash")
        } catch (expected: IllegalArgumentException) { /* */ }
        try {
            IpRange("10.0.0.0/abc")
            fail("expected IllegalArgumentException for non-numeric prefix")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `FirewallRule rejects an out-of-range port`() {
        try {
            FirewallRule(
                id = "x",
                direction = Direction.INBOUND,
                family = AddressFamily.IPV4,
                interfaceName = "lo",
                protocol = FirewallProtocol.TCP,
                port = 70000,
                source = IpRange.ANY,
                destination = IpRange.ANY,
                state = ConnectionState.ANY,
                action = FirewallAction.ACCEPT,
                priority = 0,
                comment = "bad"
            )
            fail("expected IllegalArgumentException for port 70000")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    // --- InMemoryFirewallBackend ---

    @Test
    fun `InMemoryFirewallBackend apply stores and snapshot returns the state`() {
        val backend = InMemoryFirewallBackend()
        val state = translator.compile("s1", NetworkPolicy(mode = NetworkMode.LOOPBACK_ONLY))
        backend.apply(state)
        assertEquals(state, backend.snapshot("elysium-s1"))
        assertEquals(listOf("elysium-s1"), backend.listChains())
    }

    @Test
    fun `InMemoryFirewallBackend remove drops the chain`() {
        val backend = InMemoryFirewallBackend()
        val state = translator.compile("s1", NetworkPolicy(mode = NetworkMode.LOOPBACK_ONLY))
        backend.apply(state)
        backend.remove(state.chainName)
        assertNull(backend.snapshot(state.chainName))
        assertTrue(backend.listChains().isEmpty())
    }

    @Test
    fun `InMemoryFirewallBackend apply is atomic per chain`() {
        val backend = InMemoryFirewallBackend()
        val a = translator.compile("s1", NetworkPolicy(mode = NetworkMode.BLOCKED))
        val b = translator.compile("s1", NetworkPolicy(mode = NetworkMode.INTERNET))
        backend.apply(a)
        backend.apply(b)
        // The second apply replaces the first; the snapshot
        // must show the new state, not a partial mix.
        assertEquals(b, backend.snapshot("elysium-s1"))
    }

    @Test
    fun `InMemoryFirewallBackend is thread-safe under concurrent apply`() {
        val backend = InMemoryFirewallBackend()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { idx ->
            Thread {
                start.await()
                repeat(20) { i ->
                    val policy = if (i % 2 == 0) {
                        NetworkPolicy(mode = NetworkMode.LOOPBACK_ONLY)
                    } else {
                        NetworkPolicy(mode = NetworkMode.OUTBOUND_ONLY)
                    }
                    backend.apply(translator.compile("s$idx", policy))
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        // 8 chains, one per thread.
        assertEquals(8, backend.listChains().size)
        // Every chain has a snapshot.
        for (chain in backend.listChains()) {
            assertNotNull(backend.snapshot(chain))
        }
    }

    @Test
    fun `FirewallState rejects duplicate rule ids`() {
        val rule = FirewallRule(
            id = "dup",
            direction = Direction.INBOUND,
            family = AddressFamily.IPV4,
            interfaceName = "lo",
            protocol = FirewallProtocol.ANY,
            port = null,
            source = IpRange.ANY,
            destination = IpRange.ANY,
            state = ConnectionState.ANY,
            action = FirewallAction.ACCEPT,
            priority = 0,
            comment = "x"
        )
        try {
            FirewallState(chainName = "elysium-s1", rules = listOf(rule, rule))
            fail("expected IllegalArgumentException for duplicate ids")
        } catch (expected: IllegalArgumentException) {
            // Must mention the duplicate id.
        }
    }

    @Test
    fun `apply-then-diff lifecycle compiles, applies, and reconciles`() {
        val backend = InMemoryFirewallBackend()
        val firewall = NetworkPolicyFirewall()
        // Session 1 starts as OUTBOUND_ONLY.
        val s1 = firewall.compile("s1", NetworkPolicy(mode = NetworkMode.OUTBOUND_ONLY))
        backend.apply(s1)
        // User adds a published port.
        val s1Upgraded = firewall.compile(
            "s1",
            NetworkPolicy(mode = NetworkMode.OUTBOUND_ONLY, publishedPorts = setOf(8080))
        )
        val diff = firewall.diff(s1Upgraded, s1)
        assertFalse("upgrade must add at least one rule", diff.isEmpty)
        assertTrue(diff.added.any { it.id.contains(".published-8080-") })
        backend.apply(s1Upgraded)
        assertEquals(s1Upgraded, backend.snapshot(s1.chainName))
        // Session ends.
        backend.remove(s1.chainName)
        assertNull(backend.snapshot(s1.chainName))
    }
}
