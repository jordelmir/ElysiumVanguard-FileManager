package com.elysium.vanguard.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityZeroTrustTest {

    // --- DeviceIntegrity ---

    @Test
    fun `device integrity is trusted when all checks pass`() {
        val integrity = DeviceIntegrity(
            isRooted = false,
            isDebuggerAttached = false,
            isSignatureValid = true,
            appPackageName = "com.elysium.vanguard",
            appSignatureDigest = "abc",
        )
        assertTrue(integrity.isTrusted)
        assertTrue(integrity.failures.isEmpty())
    }

    @Test
    fun `device integrity is untrusted when rooted`() {
        val integrity = DeviceIntegrity(
            isRooted = true,
            isDebuggerAttached = false,
            isSignatureValid = true,
            appPackageName = "com.elysium.vanguard",
            appSignatureDigest = "abc",
        )
        assertFalse(integrity.isTrusted)
        assertTrue(integrity.failures.contains(IntegrityFailure.ROOTED))
    }

    @Test
    fun `device integrity is untrusted when debugger attached`() {
        val integrity = DeviceIntegrity(
            isRooted = false,
            isDebuggerAttached = true,
            isSignatureValid = true,
            appPackageName = "com.elysium.vanguard",
            appSignatureDigest = "abc",
        )
        assertFalse(integrity.isTrusted)
        assertTrue(integrity.failures.contains(IntegrityFailure.DEBUGGER_ATTACHED))
    }

    @Test
    fun `device integrity is untrusted when signature is invalid`() {
        val integrity = DeviceIntegrity(
            isRooted = false,
            isDebuggerAttached = false,
            isSignatureValid = false,
            appPackageName = "com.elysium.vanguard",
            appSignatureDigest = "tampered",
        )
        assertFalse(integrity.isTrusted)
        assertTrue(integrity.failures.contains(IntegrityFailure.SIGNATURE_INVALID))
    }

    @Test
    fun `device integrity reports all failures when multiple fail`() {
        val integrity = DeviceIntegrity(
            isRooted = true,
            isDebuggerAttached = true,
            isSignatureValid = false,
            appPackageName = "com.elysium.vanguard",
            appSignatureDigest = null,
        )
        assertFalse(integrity.isTrusted)
        assertEquals(3, integrity.failures.size)
    }

    // --- SecurityAudit ---

    @Test
    fun `security audit records an event`() {
        val audit = SecurityAudit()
        assertEquals(0, audit.count())
        val event = SecurityAuditEvent(
            eventType = SecurityEventType.INTEGRITY_CHECK,
            subjectId = "device-1",
            outcome = SecurityEventOutcome.SUCCESS,
            details = SecurityEventDetails.IntegrityCheckDetails(
                isTrusted = true,
                failures = emptyList(),
                appSignatureDigest = "abc",
            ),
            at = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1L),
        )
        audit.record(event)
        assertEquals(1, audit.count())
        assertEquals(event, audit.all().first())
    }

    @Test
    fun `security audit filters by subject id`() {
        val audit = SecurityAudit()
        audit.record(
            SecurityAuditEvent(
                eventType = SecurityEventType.SECRET_READ,
                subjectId = "secret-1",
                outcome = SecurityEventOutcome.SUCCESS,
                details = SecurityEventDetails.SecretAccessDetails(
                    secretId = "secret-1",
                    secretType = "MARKET_SIGNING_KEY",
                    accessReason = "publish listing",
                ),
                at = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1L),
            ),
        )
        audit.record(
            SecurityAuditEvent(
                eventType = SecurityEventType.SECRET_READ,
                subjectId = "secret-2",
                outcome = SecurityEventOutcome.SUCCESS,
                details = SecurityEventDetails.SecretAccessDetails(
                    secretId = "secret-2",
                    secretType = "CLOUD_API_KEY",
                    accessReason = "fetch cloud listing",
                ),
                at = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1L),
            ),
        )
        assertEquals(2, audit.count())
        assertEquals(1, audit.forSubject("secret-1").size)
        assertEquals(1, audit.forSubject("secret-2").size)
    }

    @Test
    fun `security audit event rejects blank subject id`() {
        try {
            SecurityAuditEvent(
                eventType = SecurityEventType.SECRET_READ,
                subjectId = "",
                outcome = SecurityEventOutcome.SUCCESS,
                details = SecurityEventDetails.SecretAccessDetails(
                    secretId = "x",
                    secretType = "x",
                    accessReason = "x",
                ),
                at = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1L),
            )
            assert(false) { "expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("subjectId must not be blank"))
        }
    }

    // --- SecretStore ---

    @Test
    fun `secret store put then get returns the same value`() {
        val audit = SecurityAudit()
        val store = SecretStore(audit = audit)
        val result = store.put(
            secretId = "market-signing-key",
            secretType = SecretType.MARKET_SIGNING_KEY,
            value = "secret-bytes".toByteArray(),
            accessReason = "initial setup",
        )
        assertTrue(result.isSuccess)
        val getResult = store.get("market-signing-key", accessReason = "publish listing")
        assertTrue(getResult.isSuccess)
        assertEquals("secret-bytes", String(getResult.getOrThrow()))
    }

    @Test
    fun `secret store put then get records two audit events`() {
        val audit = SecurityAudit()
        val store = SecretStore(audit = audit)
        store.put(
            secretId = "x",
            secretType = SecretType.CLOUD_API_KEY,
            value = "v".toByteArray(),
            accessReason = "setup",
        )
        store.get("x", accessReason = "use")
        val events = audit.all()
        assertEquals(2, events.size)
        assertEquals(SecurityEventType.SECRET_WRITE, events[0].eventType)
        assertEquals(SecurityEventType.SECRET_READ, events[1].eventType)
    }

    @Test
    fun `secret store get on missing secret returns typed failure and DENIED audit`() {
        val audit = SecurityAudit()
        val store = SecretStore(audit = audit)
        val result = store.get("does-not-exist", accessReason = "use")
        assertTrue(result.isFailure)
        val events = audit.all()
        assertEquals(1, events.size)
        assertEquals(SecurityEventType.SECRET_ACCESS_DENIED, events.first().eventType)
        assertEquals(SecurityEventOutcome.DENIED, events.first().outcome)
    }

    @Test
    fun `secret store rejects blank id`() {
        val audit = SecurityAudit()
        val store = SecretStore(audit = audit)
        val result = store.put(
            secretId = "",
            secretType = SecretType.MARKET_SIGNING_KEY,
            value = "v".toByteArray(),
            accessReason = "setup",
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `secret store rejects empty value`() {
        val audit = SecurityAudit()
        val store = SecretStore(audit = audit)
        val result = store.put(
            secretId = "x",
            secretType = SecretType.MARKET_SIGNING_KEY,
            value = ByteArray(0),
            accessReason = "setup",
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `secret store delete removes the secret`() {
        val audit = SecurityAudit()
        val store = SecretStore(audit = audit)
        store.put("x", SecretType.MARKET_SIGNING_KEY, "v".toByteArray(), "setup")
        assertEquals(1, store.count())
        store.delete("x")
        assertEquals(0, store.count())
    }

    @Test
    fun `secret store delete on missing secret returns typed failure`() {
        val audit = SecurityAudit()
        val store = SecretStore(audit = audit)
        val result = store.delete("does-not-exist")
        assertTrue(result.isFailure)
    }

    @Test
    fun `secret store multiple secrets are isolated`() {
        val audit = SecurityAudit()
        val store = SecretStore(audit = audit)
        store.put("a", SecretType.MARKET_SIGNING_KEY, "va".toByteArray(), "setup")
        store.put("b", SecretType.CLOUD_API_KEY, "vb".toByteArray(), "setup")
        assertEquals(2, store.count())
        assertEquals("va", String(store.get("a", "use").getOrThrow()))
        assertEquals("vb", String(store.get("b", "use").getOrThrow()))
    }

    @Test
    fun `secret type has 4 values`() {
        assertEquals(4, SecretType.values().size)
    }

    @Test
    fun `secret event type has 6 values`() {
        // Phase 100 added KILL_SWITCH_TRIGGERED.
        assertEquals(6, SecurityEventType.values().size)
    }

    @Test
    fun `security event outcome has 3 values`() {
        assertEquals(3, SecurityEventOutcome.values().size)
    }

    @Test
    fun `integrity failure has 3 values`() {
        assertEquals(3, IntegrityFailure.values().size)
    }

    @Test
    fun `secret equality is value based on byte array`() {
        val a = Secret(
            id = "x",
            type = SecretType.MARKET_SIGNING_KEY,
            value = "hello".toByteArray(),
            createdAt = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1L),
            lastAccessedAt = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1L),
        )
        val b = Secret(
            id = "x",
            type = SecretType.MARKET_SIGNING_KEY,
            value = "hello".toByteArray(),
            createdAt = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1L),
            lastAccessedAt = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1L),
        )
        assertEquals(a, b)
    }

    @Test
    fun `secret value hash matches SHA-256 of the value`() {
        val value = "hello".toByteArray()
        val secret = Secret(
            id = "x",
            type = SecretType.MARKET_SIGNING_KEY,
            value = value,
            createdAt = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1L),
            lastAccessedAt = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1L),
        )
        val expected = com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash.of(value)
        assertEquals(expected, secret.valueHash)
    }
}
