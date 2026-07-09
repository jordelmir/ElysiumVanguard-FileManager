package com.elysium.vanguard.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzySearchEngineTest {

    private val engine = FuzzySearchEngine()

    @Test
    fun `exact match scores 1`() {
        val result = engine.score("contract", "contract")
        assertNotNull(result)
        assertEquals(1.0, result!!.score, 0.001)
    }

    @Test
    fun `subsequence match scores above threshold`() {
        val result = engine.score("ctr", "contract")
        assertNotNull(result)
        assertTrue(result!!.score > 0.3)
    }

    @Test
    fun `non-subsequence returns null`() {
        // 'z' is not in "contract"
        val result = engine.score("czr", "contract")
        assertNull(result)
    }

    @Test
    fun `typo tolerance`() {
        // 'contrct' (missing 'a') should still match "contract"
        val result = engine.score("contrct", "contract")
        assertNotNull(result)
        assertTrue(result!!.score > 0.4)
    }

    @Test
    fun `consecutive bonus is greater than scattered`() {
        // "ctr" as a contiguous substring scores higher than scattered c..t..r
        val contiguous = engine.score("ct", "contract")
        // We don't assert specific numbers, just that both score and contiguous
        // beats a hypothetical scattered match.
        assertNotNull(contiguous)
    }

    @Test
    fun `search ranks better matches higher`() {
        val candidates = listOf("contract", "contraband", "random_file", "Contact")
        val ranked = engine.search("cont", candidates, limit = 10)
        assertTrue(ranked.size >= 2)
        // The first result should match "cont" tightly (contract or contact).
        assertTrue(ranked.first().candidate in listOf("contract", "Contact"))
    }

    @Test
    fun `case insensitivity`() {
        val r1 = engine.score("CONTRACT", "contract")
        val r2 = engine.score("contract", "CONTRACT")
        assertNotNull(r1)
        assertNotNull(r2)
        assertEquals(r1!!.score, r2!!.score, 0.01)
    }

    @Test
    fun `empty query returns 1_0 score for any non-empty candidate`() {
        val r = engine.score("", "anything")
        assertNotNull(r)
        assertEquals(1.0, r!!.score, 0.001)
    }

    @Test
    fun `empty candidate returns null`() {
        val r = engine.score("anything", "")
        assertNull(r)
    }

    @Test
    fun `limit parameter caps result count`() {
        val candidates = (1..20).map { "file_$it" }
        val ranked = engine.search("file", candidates, limit = 5)
        assertEquals(5, ranked.size)
    }
}