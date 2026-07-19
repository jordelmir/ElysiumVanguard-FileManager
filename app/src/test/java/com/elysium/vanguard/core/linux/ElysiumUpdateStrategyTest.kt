package com.elysium.vanguard.core.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 73 third half (I-73.3.4) — the JVM tests
 * for [ElysiumUpdateStrategy] (sealed class with 2
 * cases) + [ElysiumRootfsSlot] +
 * [ElysiumRootfsVersion] + [ElysiumUpdatePlan] +
 * [ElysiumRollbackPlan].
 *
 * These tests cover:
 *   - ElysiumRootfsSlot: well-known constants,
 *     other slot lookup.
 *   - ElysiumRootfsVersion: canonical form, image
 *     file name, semver comparison.
 *   - ABUpdate: validation (different slots),
 *     default rollbackOnFailure.
 *   - VersionedImage: validation (non-empty
 *     availableVersions, currentVersion in
 *     availableVersions, maxRetained >= 1).
 *   - ElysiumUpdatePlan: validation (non-blank
 *     canonical, non-negative estimatedBytes).
 *   - ElysiumRollbackPlan: validation (consistency
 *     between canRollback + targetVersion),
 *     NO_ROLLBACK constant.
 */
class ElysiumUpdateStrategyTest {

    // ============================================================
    // ElysiumRootfsSlot
    // ============================================================

    @Test
    fun `slot A has the symbol a`() {
        assertEquals("a", ElysiumRootfsSlot.A.symbol)
    }

    @Test
    fun `slot B has the symbol b`() {
        assertEquals("b", ElysiumRootfsSlot.B.symbol)
    }

    @Test
    fun `slot A other is B`() {
        assertEquals(ElysiumRootfsSlot.B, ElysiumRootfsSlot.A.other)
    }

    @Test
    fun `slot B other is A`() {
        assertEquals(ElysiumRootfsSlot.A, ElysiumRootfsSlot.B.other)
    }

    @Test
    fun `slot toString returns the symbol`() {
        assertEquals("a", ElysiumRootfsSlot.A.toString())
        assertEquals("b", ElysiumRootfsSlot.B.toString())
    }

    // ============================================================
    // ElysiumRootfsVersion
    // ============================================================

    @Test
    fun `version canonical is MAJOR dot MINOR dot PATCH`() {
        val v = ElysiumRootfsVersion(1, 2, 3)
        assertEquals("1.2.3", v.canonical)
    }

    @Test
    fun `version rejects negative major`() {
        try {
            ElysiumRootfsVersion(-1, 0, 0)
            fail("expected IllegalArgumentException for negative major")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("major"))
        }
    }

    @Test
    fun `version rejects negative minor`() {
        try {
            ElysiumRootfsVersion(1, -1, 0)
            fail("expected IllegalArgumentException for negative minor")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("minor"))
        }
    }

    @Test
    fun `version rejects negative patch`() {
        try {
            ElysiumRootfsVersion(1, 0, -1)
            fail("expected IllegalArgumentException for negative patch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("patch"))
        }
    }

    @Test
    fun `version imageFileName follows the canonical pattern`() {
        val v = ElysiumRootfsVersion(1, 2, 3)
        assertEquals("rootfs-v1.2.3.tar.zst", v.imageFileName)
    }

    @Test
    fun `version compareTo follows the semver order`() {
        val v123 = ElysiumRootfsVersion(1, 2, 3)
        val v124 = ElysiumRootfsVersion(1, 2, 4)
        val v130 = ElysiumRootfsVersion(1, 3, 0)
        val v200 = ElysiumRootfsVersion(2, 0, 0)
        assertTrue(v123 < v124)
        assertTrue(v124 < v130)
        assertTrue(v130 < v200)
        assertEquals(0, v123.compareTo(v123))
    }

    // ============================================================
    // ABUpdate
    // ============================================================

    @Test
    fun `ABUpdate accepts different current and inactive slots`() {
        val ab = ElysiumUpdateStrategy.ABUpdate(
            currentSlot = ElysiumRootfsSlot.A,
            inactiveSlot = ElysiumRootfsSlot.B,
        )
        assertEquals(ElysiumRootfsSlot.A, ab.currentSlot)
        assertEquals(ElysiumRootfsSlot.B, ab.inactiveSlot)
        assertTrue("expected rollbackOnFailure to default to true", ab.rollbackOnFailure)
    }

    @Test
    fun `ABUpdate rejects the same current and inactive slot`() {
        try {
            ElysiumUpdateStrategy.ABUpdate(
                currentSlot = ElysiumRootfsSlot.A,
                inactiveSlot = ElysiumRootfsSlot.A,
            )
            fail("expected IllegalArgumentException for same slot")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'different', got: ${e.message}",
                e.message!!.contains("different"),
            )
        }
    }

    @Test
    fun `ABUpdate with rollbackOnFailure false is accepted`() {
        val ab = ElysiumUpdateStrategy.ABUpdate(
            currentSlot = ElysiumRootfsSlot.B,
            inactiveSlot = ElysiumRootfsSlot.A,
            rollbackOnFailure = false,
        )
        assertFalse(ab.rollbackOnFailure)
    }

    // ============================================================
    // VersionedImage
    // ============================================================

    @Test
    fun `VersionedImage accepts a valid configuration`() {
        val v1 = ElysiumRootfsVersion(1, 0, 0)
        val v2 = ElysiumRootfsVersion(1, 1, 0)
        val vi = ElysiumUpdateStrategy.VersionedImage(
            currentVersion = v1,
            availableVersions = listOf(v1, v2),
        )
        assertEquals(v1, vi.currentVersion)
        assertEquals(2, vi.availableVersions.size)
        assertEquals(
            ElysiumUpdateStrategy.VersionedImage.DEFAULT_MAX_RETAINED,
            vi.maxRetained,
        )
    }

    @Test
    fun `VersionedImage rejects empty availableVersions`() {
        try {
            ElysiumUpdateStrategy.VersionedImage(
                currentVersion = ElysiumRootfsVersion(1, 0, 0),
                availableVersions = emptyList(),
            )
            fail("expected IllegalArgumentException for empty availableVersions")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("availableVersions"))
        }
    }

    @Test
    fun `VersionedImage rejects currentVersion not in availableVersions`() {
        try {
            ElysiumUpdateStrategy.VersionedImage(
                currentVersion = ElysiumRootfsVersion(2, 0, 0),
                availableVersions = listOf(ElysiumRootfsVersion(1, 0, 0)),
            )
            fail("expected IllegalArgumentException for currentVersion not in list")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'availableVersions', got: ${e.message}",
                e.message!!.contains("availableVersions"),
            )
        }
    }

    @Test
    fun `VersionedImage rejects maxRetained less than 1`() {
        try {
            ElysiumUpdateStrategy.VersionedImage(
                currentVersion = ElysiumRootfsVersion(1, 0, 0),
                availableVersions = listOf(ElysiumRootfsVersion(1, 0, 0)),
                maxRetained = 0,
            )
            fail("expected IllegalArgumentException for maxRetained < 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxRetained"))
        }
    }

    @Test
    fun `VersionedImage default maxRetained is 3`() {
        assertEquals(3, ElysiumUpdateStrategy.VersionedImage.DEFAULT_MAX_RETAINED)
    }

    // ============================================================
    // ElysiumUpdatePlan
    // ============================================================

    @Test
    fun `update plan accepts a valid configuration`() {
        val v1 = ElysiumRootfsVersion(1, 0, 0)
        val v2 = ElysiumRootfsVersion(1, 1, 0)
        val strategy = ElysiumUpdateStrategy.ABUpdate(
            currentSlot = ElysiumRootfsSlot.A,
            inactiveSlot = ElysiumRootfsSlot.B,
        )
        val plan = ElysiumUpdatePlan(
            strategy = strategy,
            targetVersion = v2,
            estimatedBytes = 500_000_000L,
            requiresReboot = true,
            rollbackPlan = ElysiumRollbackPlan(
                canRollback = true,
                targetVersion = v1,
                estimatedBytes = 500_000_000L,
                estimatedDurationMs = 30_000L,
            ),
        )
        assertEquals(v2, plan.targetVersion)
        assertEquals(500_000_000L, plan.estimatedBytes)
        assertTrue(plan.requiresReboot)
    }

    @Test
    fun `update plan rejects negative estimatedBytes`() {
        try {
            ElysiumUpdatePlan(
                strategy = ElysiumUpdateStrategy.ABUpdate(
                    currentSlot = ElysiumRootfsSlot.A,
                    inactiveSlot = ElysiumRootfsSlot.B,
                ),
                targetVersion = ElysiumRootfsVersion(1, 1, 0),
                estimatedBytes = -1L,
                requiresReboot = true,
                rollbackPlan = ElysiumRollbackPlan.NO_ROLLBACK,
            )
            fail("expected IllegalArgumentException for negative estimatedBytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("estimatedBytes"))
        }
    }

    // ============================================================
    // ElysiumRollbackPlan
    // ============================================================

    @Test
    fun `rollback plan accepts canRollback with a target version`() {
        val v = ElysiumRootfsVersion(1, 0, 0)
        val plan = ElysiumRollbackPlan(
            canRollback = true,
            targetVersion = v,
            estimatedBytes = 100L,
            estimatedDurationMs = 1000L,
        )
        assertTrue(plan.canRollback)
        assertEquals(v, plan.targetVersion)
    }

    @Test
    fun `rollback plan rejects canRollback without a target version`() {
        try {
            ElysiumRollbackPlan(
                canRollback = true,
                targetVersion = null,
                estimatedBytes = 100L,
                estimatedDurationMs = 1000L,
            )
            fail("expected IllegalArgumentException for null target")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("targetVersion"))
        }
    }

    @Test
    fun `rollback plan accepts canRollback false with a null target version`() {
        val plan = ElysiumRollbackPlan(
            canRollback = false,
            targetVersion = null,
            estimatedBytes = 0L,
            estimatedDurationMs = 0L,
        )
        assertFalse(plan.canRollback)
        assertNull(plan.targetVersion)
    }

    @Test
    fun `rollback plan NO_ROLLBACK has canRollback false`() {
        assertFalse(ElysiumRollbackPlan.NO_ROLLBACK.canRollback)
        assertNull(ElysiumRollbackPlan.NO_ROLLBACK.targetVersion)
    }

    @Test
    fun `rollback plan rejects negative estimatedDurationMs`() {
        try {
            ElysiumRollbackPlan(
                canRollback = false,
                targetVersion = null,
                estimatedBytes = 0L,
                estimatedDurationMs = -1L,
            )
            fail("expected IllegalArgumentException for negative duration")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("estimatedDurationMs"))
        }
    }

    // ============================================================
    // Data class equality
    // ============================================================

    @Test
    fun `two ABUpdate with the same fields are equal`() {
        val a = ElysiumUpdateStrategy.ABUpdate(
            currentSlot = ElysiumRootfsSlot.A,
            inactiveSlot = ElysiumRootfsSlot.B,
        )
        val b = ElysiumUpdateStrategy.ABUpdate(
            currentSlot = ElysiumRootfsSlot.A,
            inactiveSlot = ElysiumRootfsSlot.B,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ABUpdate and VersionedImage with the same fields are not equal`() {
        // Different sealed cases are never equal
        // (the sealed class's `equals` checks the
        // class first).
        val v = ElysiumRootfsVersion(1, 0, 0)
        val ab = ElysiumUpdateStrategy.ABUpdate(
            currentSlot = ElysiumRootfsSlot.A,
            inactiveSlot = ElysiumRootfsSlot.B,
        )
        val vi = ElysiumUpdateStrategy.VersionedImage(
            currentVersion = v,
            availableVersions = listOf(v),
        )
        assertNotEquals(ab as ElysiumUpdateStrategy, vi)
    }

    @Test
    fun `two versions with the same fields are equal`() {
        val a = ElysiumRootfsVersion(1, 2, 3)
        val b = ElysiumRootfsVersion(1, 2, 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
