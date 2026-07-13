package com.elysium.vanguard.core.runtime.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class NetworkBrokerImplTest {

    private val clockRef = AtomicLong(1_700_000_000_000L)
    private var iptablesAvailable = false
    private val iptablesCalls = mutableListOf<List<String>>()

    private fun newBroker(): NetworkBrokerImpl {
        iptablesCalls.clear()
        return NetworkBrokerImpl(
            iptablesAvailable = { iptablesAvailable },
            iptablesApply = { rules ->
                iptablesCalls += rules
                IptablesApplyResult(success = true, stdout = "ok", stderr = "")
            },
            clock = { clockRef.incrementAndGet() }
        )
    }

    @After
    fun tearDown() {
        iptablesAvailable = false
    }

    @Test
    fun `applyPolicy LOOPBACK returns Active with ENV_ONLY when no iptables`() = block {
        val broker = newBroker()
        val result = broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.LOOPBACK, allowDns = false))
        assertTrue(result.isSuccess)
        val app = broker.applicationFor("s1")
        assertNotNull(app)
        assertEquals(PolicyLevel.LOOPBACK, app!!.level)
        assertEquals(EnforcementLayer.ENV_ONLY, app.enforcementLayer)
        assertEquals(NetworkBrokerState.Active, broker.state.value)
    }

    @Test
    fun `applyPolicy LOOPBACK reports IPTABLES when binary is present`() = block {
        iptablesAvailable = true
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.LOOPBACK))
        val app = broker.applicationFor("s1")!!
        assertEquals(EnforcementLayer.IPTABLES, app.enforcementLayer)
    }

    @Test
    fun `applyPolicy with blank sessionId fails`() = block {
        val broker = newBroker()
        val result = broker.applyPolicy("", NetworkPolicy(level = PolicyLevel.INTERNET))
        assertTrue(result.isFailure)
    }

    @Test
    fun `revokePolicy clears session state and returns to Idle when last`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.INTERNET))
        broker.revokePolicy("s1")
        assertNull(broker.applicationFor("s1"))
        assertEquals(NetworkBrokerState.Idle, broker.state.value)
    }

    @Test
    fun `revokePolicy keeps Active when other sessions remain`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.INTERNET))
        broker.applyPolicy("s2", NetworkPolicy(level = PolicyLevel.LOOPBACK))
        broker.revokePolicy("s1")
        assertEquals(NetworkBrokerState.Active, broker.state.value)
        assertNotNull(broker.applicationFor("s2"))
    }

    @Test
    fun `registerPort on loopback is allowed under any policy`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.BLOCKED))
        val r = broker.registerPort(
            "s1",
            PortBinding(guestPort = 8080, hostPort = 8080, interface_ = "127.0.0.1")
        )
        assertTrue(r.isSuccess)
        assertTrue(r.getOrNull()!!.success)
        assertEquals(listOf(8080), broker.getBindings("s1").map { it.hostPort })
    }

    @Test
    fun `registerPort on non-loopback is rejected under BLOCKED policy`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.BLOCKED))
        val r = broker.registerPort(
            "s1",
            PortBinding(guestPort = 8080, hostPort = 8080, interface_ = "0.0.0.0")
        )
        assertTrue(r.isSuccess) // Result.success wrapper, but the inner result reports failure
        assertFalse(r.getOrNull()!!.success)
        assertTrue(r.getOrNull()!!.error!!.contains("BLOCKED"))
    }

    @Test
    fun `registerPort on non-loopback is accepted under INTERNET policy`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.INTERNET))
        val r = broker.registerPort(
            "s1",
            PortBinding(guestPort = 80, hostPort = 8080, interface_ = "0.0.0.0")
        )
        assertTrue(r.getOrNull()!!.success)
        val binding = broker.getBindings("s1").single()
        assertEquals(8080, binding.hostPort)
    }

    @Test
    fun `registerPort without applied policy fails`() = block {
        val broker = newBroker()
        val r = broker.registerPort("s1", PortBinding(guestPort = 8080))
        assertTrue(r.isFailure)
    }

    @Test
    fun `unregisterPort removes the binding and audits it`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.INTERNET))
        broker.registerPort("s1", PortBinding(guestPort = 8080, hostPort = 8080))
        val removed = broker.unregisterPort("s1", 8080)
        assertTrue(removed.isSuccess)
        assertTrue(broker.getBindings("s1").isEmpty())
        assertTrue(broker.auditLog("s1").any { it.operation == "PORT_UNBIND" })
    }

    @Test
    fun `unregisterPort unknown returns failure`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.INTERNET))
        val r = broker.unregisterPort("s1", 9999)
        assertTrue(r.isFailure)
    }

    @Test
    fun `isAllowed BLOCKED denies every operation except unix socket and port bind`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.BLOCKED))
        for (op in NetworkOperation.values()) {
            val expected = op == NetworkOperation.UNIX_SOCKET || op == NetworkOperation.PORT_BIND
            assertEquals("op=$op", expected, broker.isAllowed("s1", op))
        }
    }

    @Test
    fun `isAllowed LOOPBACK allows only loopback plus DNS when enabled`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.LOOPBACK, allowDns = true))
        assertTrue(broker.isAllowed("s1", NetworkOperation.UNIX_SOCKET))
        assertTrue(broker.isAllowed("s1", NetworkOperation.DNS_RESOLVE))
        assertTrue(broker.isAllowed("s1", NetworkOperation.TCP_OUTBOUND))
        assertFalse(broker.isAllowed("s1", NetworkOperation.TCP_INBOUND))
    }

    @Test
    fun `isAllowed OUTBOUND allows outgoing only`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.OUTBOUND))
        assertTrue(broker.isAllowed("s1", NetworkOperation.TCP_OUTBOUND))
        assertFalse(broker.isAllowed("s1", NetworkOperation.TCP_INBOUND))
        assertTrue(broker.isAllowed("s1", NetworkOperation.DNS_RESOLVE))
    }

    @Test
    fun `isAllowed INTERNET with DNS allows every operation`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.INTERNET, allowDns = true))
        for (op in NetworkOperation.values()) {
            assertTrue("op=$op", broker.isAllowed("s1", op))
        }
    }

    @Test
    fun `isAllowed INTERNET with DNS off denies DNS_RESOLVE only`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.INTERNET, allowDns = false))
        for (op in NetworkOperation.values()) {
            val expected = op != NetworkOperation.DNS_RESOLVE
            assertEquals("op=$op", expected, broker.isAllowed("s1", op))
        }
    }

    @Test
    fun `isAllowed CUSTOM with empty allow lists blocks outbound`() = block {
        val broker = newBroker()
        broker.applyPolicy(
            "s1",
            NetworkPolicy(level = PolicyLevel.CUSTOM, allowedPorts = emptySet(), allowedHosts = emptySet())
        )
        assertFalse(broker.isAllowed("s1", NetworkOperation.TCP_OUTBOUND))
    }

    @Test
    fun `isAllowed without policy defaults to true`() = block {
        val broker = newBroker()
        assertTrue(broker.isAllowed("missing", NetworkOperation.TCP_OUTBOUND))
    }

    @Test
    fun `generateRules BLOCKED emits a chain that drops all non-loopback`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.BLOCKED))
        val rules = broker.generateRules("s1")
        assertTrue(rules.any { it.contains("ELYSIUM_S1") && it.contains("-o lo -j ACCEPT") })
        assertTrue(rules.any { it.contains("ELYSIUM_S1") && it.endsWith("-j DROP") })
    }

    @Test
    fun `generateRules LOOPBACK includes DNS drop when DNS off`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.LOOPBACK, allowDns = false))
        val rules = broker.generateRules("s1")
        assertTrue(rules.any { it.contains("--dport 53") && it.endsWith("-j DROP") })
    }

    @Test
    fun `generateRules CUSTOM emits per-host and per-port lines`() = block {
        val broker = newBroker()
        broker.applyPolicy(
            "s1",
            NetworkPolicy(
                level = PolicyLevel.CUSTOM,
                allowedHosts = setOf("github.com", "crates.io"),
                allowedPorts = setOf(443, 80),
                blockedHosts = setOf("ads.example"),
                blockedPorts = setOf(25)
            )
        )
        val rules = broker.generateRules("s1")
        assertTrue(rules.any { it.contains("-d github.com -j ACCEPT") })
        assertTrue(rules.any { it.contains("-d crates.io -j ACCEPT") })
        assertTrue(rules.any { it.contains("-p tcp --dport 443 -j ACCEPT") })
        assertTrue(rules.any { it.contains("-p udp --dport 443 -j ACCEPT") })
        assertTrue(rules.any { it.contains("-d ads.example -j DROP") })
        assertTrue(rules.any { it.contains("-p tcp --dport 25 -j DROP") })
    }

    @Test
    fun `generateRules returns comment when no policy is applied`() = block {
        val broker = newBroker()
        val rules = broker.generateRules("missing")
        assertTrue(rules.single().startsWith("# no policy"))
    }

    @Test
    fun `environmentVariables LOOPBACK points HTTP_PROXY at unreachable loopback port`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.LOOPBACK))
        val env = broker.environmentVariables("s1")
        assertEquals("LOOPBACK", env["ELYSIUM_NET_POLICY"])
        assertEquals("http://127.0.0.1:9", env["HTTP_PROXY"])
        assertEquals("http://127.0.0.1:9", env["HTTPS_PROXY"])
        assertEquals("http://127.0.0.1:9", env["http_proxy"])
        assertEquals("http://127.0.0.1:9", env["https_proxy"])
        assertEquals("127.0.0.1,localhost", env["no_proxy"])
    }

    @Test
    fun `environmentVariables BLOCKED sets NO_PROXY to wildcard`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.BLOCKED))
        val env = broker.environmentVariables("s1")
        assertEquals("*", env["NO_PROXY"])
        assertEquals("*", env["no_proxy"])
        assertEquals("", env["HTTP_PROXY"])
    }

    @Test
    fun `environmentVariables CUSTOM emits allow-list as comma-separated env`() = block {
        val broker = newBroker()
        broker.applyPolicy(
            "s1",
            NetworkPolicy(
                level = PolicyLevel.CUSTOM,
                allowedHosts = setOf("github.com", "crates.io"),
                blockedHosts = setOf("ads.example")
            )
        )
        val env = broker.environmentVariables("s1")
        assertEquals("github.com,crates.io", env["ELYSIUM_NET_ALLOWED_HOSTS"])
        assertEquals("ads.example", env["NO_PROXY"])
    }

    @Test
    fun `environmentVariables INTERNET has no proxy hints`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.INTERNET))
        val env = broker.environmentVariables("s1")
        assertEquals("INTERNET", env["ELYSIUM_NET_POLICY"])
        assertNull(env["HTTP_PROXY"])
    }

    @Test
    fun `applyGeneratedRules reports iptables unavailability and degrades to env-only`() = block {
        iptablesAvailable = false
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.LOOPBACK))
        val result = broker.applyGeneratedRules("s1")
        assertFalse(result.success)
        assertTrue(result.error!!.contains("iptables binary not present"))
        val app = broker.applicationFor("s1")!!
        assertEquals(EnforcementLayer.ENV_ONLY, app.enforcementLayer)
        assertFalse(app.iptablesApplied)
    }

    @Test
    fun `applyGeneratedRules forwards rules to the applier when iptables is present`() = block {
        iptablesAvailable = true
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.LOOPBACK))
        val result = broker.applyGeneratedRules("s1")
        assertTrue(result.success)
        assertEquals(1, iptablesCalls.size)
        val app = broker.applicationFor("s1")!!
        assertEquals(EnforcementLayer.IPTABLES, app.enforcementLayer)
        assertTrue(app.iptablesApplied)
    }

    @Test
    fun `applyGeneratedRules without an applied policy returns failure`() = block {
        val broker = newBroker()
        val result = broker.applyGeneratedRules("missing")
        assertFalse(result.success)
        assertTrue(result.error!!.contains("no policy applied"))
    }

    @Test
    fun `audit log records apply revoke bind and unbind events`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.INTERNET))
        broker.registerPort("s1", PortBinding(guestPort = 8080, hostPort = 8080))
        broker.unregisterPort("s1", 8080)
        broker.revokePolicy("s1")
        val log = broker.auditLog("s1")
        assertTrue(log.any { it.operation == "APPLY" })
        assertTrue(log.any { it.operation == "PORT_BIND" })
        assertTrue(log.any { it.operation == "PORT_UNBIND" })
        assertTrue(log.any { it.operation == "REVOKE" })
    }

    @Test
    fun `close clears every session and returns to Idle`() = block {
        val broker = newBroker()
        broker.applyPolicy("s1", NetworkPolicy(level = PolicyLevel.INTERNET))
        broker.applyPolicy("s2", NetworkPolicy(level = PolicyLevel.LOOPBACK))
        broker.registerPort("s1", PortBinding(guestPort = 8080))
        broker.close()
        assertTrue(broker.getBindings("s1").isEmpty())
        assertNull(broker.applicationFor("s2"))
        assertEquals(NetworkBrokerState.Idle, broker.state.value)
    }

    @Test
    fun `probeIptablesBinary returns false when no candidate path exists`() {
        // The probe looks at /system/bin/iptables etc. On a JVM build
        // host those paths are not executable, so the probe returns
        // false. This test pins the production posture.
        assertFalse(NetworkBrokerImpl.probeIptablesBinary())
    }

    private fun block(block: suspend () -> Unit) = runBlocking { block() }
}
