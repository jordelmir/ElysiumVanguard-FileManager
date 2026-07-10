package com.elysium.vanguard.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 2.3 — JSON encoder tests.
 *
 * Covers the value types our transfer protocol emits: maps, lists, strings (with
 * escape sequences), numbers (int / double), booleans, and null.
 */
class JsonTest {

    @Test fun `encodes null`() {
        assertEquals("null", Json.encode(null))
    }

    @Test fun `encodes primitives`() {
        assertEquals("true", Json.encode(true))
        assertEquals("false", Json.encode(false))
        assertEquals("42", Json.encode(42))
        assertEquals("-7", Json.encode(-7))
        assertEquals("3.14", Json.encode(3.14))
    }

    @Test fun `encodes simple string with escapes`() {
        assertEquals("\"hello\"", Json.encode("hello"))
        assertEquals("\"a\\\"b\"", Json.encode("a\"b"))
        assertEquals("\"line1\\nline2\"", Json.encode("line1\nline2"))
        assertEquals("\"tab\\there\"", Json.encode("tab\there"))
    }

    @Test fun `encodes arrays and maps`() {
        assertEquals("[1,2,3]", Json.encode(listOf(1, 2, 3)))
        assertEquals("{\"a\":1,\"b\":2}", Json.encode(mapOf("a" to 1, "b" to 2)))
    }

    @Test fun `encodes nested structure`() {
        val payload = mapOf(
            "name" to "Elysium",
            "version" to 1.0,
            "tags" to listOf("alpha", "beta"),
            "meta" to mapOf("safe" to true)
        )
        val s = Json.encode(payload)
        assertTrue(s.contains("\"name\":\"Elysium\""))
        assertTrue(s.contains("\"version\":1.0"))
        assertTrue(s.contains("\"tags\":[\"alpha\",\"beta\"]"))
        assertTrue(s.contains("\"meta\":{\"safe\":true}"))
    }

    @Test fun `control chars are unicode-escaped`() {
        assertEquals("\"\\u0001\"", Json.encode("\u0001"))
        assertEquals("\"\\u001f\"", Json.encode("\u001f"))
    }

    @Test fun `handles empty containers`() {
        assertEquals("{}", Json.encode(emptyMap<String, Any>()))
        assertEquals("[]", Json.encode(emptyList<Any>()))
    }
}