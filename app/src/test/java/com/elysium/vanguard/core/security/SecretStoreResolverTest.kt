package com.elysium.vanguard.core.security

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * PHASE 111 — the test suite for the
 * [SecretStoreResolver] production impl.
 * The resolver is a thin shell over the
 * [SecretStore] + UTF-8 decoding. The tests
 * verify:
 *  - A present secret is returned as the
 *    decoded UTF-8 string.
 *  - A missing secret returns a typed
 *    failure.
 *  - Every read is audited (the audit log
 *    records the read).
 */
class SecretStoreResolverTest {

    private lateinit var audit: SecurityAudit
    private lateinit var store: SecretStore
    private lateinit var resolver: SecretStoreResolver

    @Before
    fun setUp() {
        audit = SecurityAudit()
        store = SecretStore(audit = audit)
        resolver = SecretStoreResolver(secretStore = store)
    }

    @Test
    fun `resolver returns the UTF-8 value of a present secret`() {
        val result = store.put(
            secretId = "API_KEY",
            secretType = SecretType.CLOUD_API_KEY,
            value = "sk-12345".toByteArray(Charsets.UTF_8),
            accessReason = "test setup",
        )
        assertTrue("put should succeed", result.isSuccess)

        val resolved = resolver.resolve("API_KEY")
        assertTrue("resolve should succeed, got $resolved", resolved.isSuccess)
        assertEquals("sk-12345", resolved.getOrNull())
    }

    @Test
    fun `resolver returns failure for a missing secret`() {
        val result = resolver.resolve("MISSING_KEY")
        assertTrue("resolve should fail, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull("error should be present", error)
        assertTrue(
            "error should be a FoundryError.VehicleDefinitionInvalid, got ${error!!::class.simpleName}",
            error is FoundryError.VehicleDefinitionInvalid
        )
    }

    @Test
    fun `resolver audits every successful read`() {
        store.put(
            secretId = "API_KEY",
            secretType = SecretType.CLOUD_API_KEY,
            value = "value".toByteArray(),
            accessReason = "test setup",
        )
        resolver.resolve("API_KEY")
        val events = audit.all()
        // 1 PUT + 1 GET = 2 events (plus any
        // audit overhead from the store's
        // internal bookkeeping).
        val reads = events.filter { it.eventType == SecurityEventType.SECRET_READ }
        assertEquals("expected 1 SECRET_READ event, got ${reads.size}", 1, reads.size)
        val read = reads.first()
        assertEquals("API_KEY", read.subjectId)
        assertEquals(SecurityEventOutcome.SUCCESS, read.outcome)
    }

    @Test
    fun `resolver audits every failed read`() {
        resolver.resolve("MISSING_KEY")
        val events = audit.all()
        val denied = events.filter {
            it.eventType == SecurityEventType.SECRET_ACCESS_DENIED
        }
        assertEquals(
            "expected 1 SECRET_ACCESS_DENIED event, got ${denied.size}",
            1,
            denied.size
        )
        val event = denied.first()
        assertEquals("MISSING_KEY", event.subjectId)
        assertEquals(SecurityEventOutcome.DENIED, event.outcome)
    }

    @Test
    fun `resolver handles multi-byte UTF-8 correctly`() {
        // Spanish: "secreto" + "ñ" + "🚀"
        // = 8 + 2 + 4 = 14 bytes in UTF-8.
        val original = "secreto-ñ-🚀"
        store.put(
            secretId = "UNICODE_SECRET",
            secretType = SecretType.VAULT_PASSPHRASE,
            value = original.toByteArray(Charsets.UTF_8),
            accessReason = "test setup",
        )
        val resolved = resolver.resolve("UNICODE_SECRET")
        assertTrue(resolved.isSuccess)
        assertEquals(original, resolved.getOrNull())
    }

    @Test
    fun `resolver distinguishes multiple secret types by id`() {
        store.put(
            secretId = "KEY_A",
            secretType = SecretType.CLOUD_API_KEY,
            value = "value-a".toByteArray(),
            accessReason = "setup",
        )
        store.put(
            secretId = "KEY_B",
            secretType = SecretType.MARKET_SIGNING_KEY,
            value = "value-b".toByteArray(),
            accessReason = "setup",
        )
        val a = resolver.resolve("KEY_A")
        val b = resolver.resolve("KEY_B")
        assertEquals("value-a", a.getOrNull())
        assertEquals("value-b", b.getOrNull())
    }
}
