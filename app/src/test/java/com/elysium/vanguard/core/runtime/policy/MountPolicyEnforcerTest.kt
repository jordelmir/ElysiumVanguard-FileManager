package com.elysium.vanguard.core.runtime.policy

import com.elysium.vanguard.core.runtime.bridge.MountEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 50 — tests for the [MountPolicyEnforcer]
 * + [MountPolicy] + [MountPolicyEntry] value
 * types.
 *
 * The enforcer is the runtime's answer to "what
 * host paths is this workspace allowed to
 * bind-mount?". The tests pin:
 *
 *   - [MountPolicy] init-block invariants
 *     (no duplicate prefixes, the LOCKED_DOWN
 *     + OPEN convenience constants).
 *   - [MountPolicyEntry] init-block invariants
 *     (non-blank, absolute, non-trailing-slash).
 *   - [MountPolicyEnforcer.enforce] for
 *     [MountPolicyMode.ALLOWLIST] mode: a mount
 *     is allowed iff its hostPath starts with a
 *     policy entry's prefix.
 *   - Read-only tightening: a policy entry with
 *     readOnly = true forces the mount to be
 *     read-only even if the proposed mount says
 *     writeable.
 *   - [MountPolicyMode.BLOCKLIST] mode: a mount
 *     is allowed iff its hostPath does NOT start
 *     with a policy entry's prefix.
 *   - [MountPolicyMode.OPEN] mode: every mount
 *     is allowed.
 *   - The returned [MountEnforcementResult.Allowed]
 *     vs [MountEnforcementResult.Denied] paths.
 */
class MountPolicyEnforcerTest {

    private val enforcer = MountPolicyEnforcer()

    private fun mount(
        hostPath: String,
        guestPath: String = "/mnt/$hostPath",
        readOnly: Boolean = true
    ) = MountEntry(
        hostPath = hostPath,
        guestPath = guestPath,
        readOnly = readOnly
    )

    // --- MountPolicyEntry invariants ---

    @Test
    fun `MountPolicyEntry rejects a blank hostPathPrefix`() {
        try {
            MountPolicyEntry(hostPathPrefix = "")
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `MountPolicyEntry rejects a non-absolute hostPathPrefix`() {
        try {
            MountPolicyEntry(hostPathPrefix = "sdcard/photos")
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `MountPolicyEntry exposes a normalised prefix with a trailing slash stripped`() {
        val entry = MountPolicyEntry(hostPathPrefix = "/sdcard/")
        // The raw field is preserved (the user
        // gave "/sdcard/"), but the normalised
        // prefix strips the trailing slash.
        assertEquals("/sdcard/", entry.hostPathPrefix)
        assertEquals("/sdcard", entry.normalisedPrefix)
    }

    @Test
    fun `MountPolicyEntry rejects a hostPathPrefix of just root`() {
        try {
            MountPolicyEntry(hostPathPrefix = "/")
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    // --- MountPolicy invariants ---

    @Test
    fun `MountPolicy rejects duplicate hostPathPrefix entries`() {
        try {
            MountPolicy(
                entries = listOf(
                    MountPolicyEntry(hostPathPrefix = "/sdcard"),
                    MountPolicyEntry(hostPathPrefix = "/sdcard")
                )
            )
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `MountPolicy LOCKED_DOWN has ALLOWLIST mode and empty entries`() {
        assertEquals(MountPolicyMode.ALLOWLIST, MountPolicy.LOCKED_DOWN.mode)
        assertTrue(MountPolicy.LOCKED_DOWN.entries.isEmpty())
        assertTrue(MountPolicy.LOCKED_DOWN.defaultReadOnly)
    }

    @Test
    fun `MountPolicy OPEN has OPEN mode and no default read-only`() {
        assertEquals(MountPolicyMode.OPEN, MountPolicy.OPEN.mode)
        assertFalse(MountPolicy.OPEN.defaultReadOnly)
    }

    // --- Enforcer: ALLOWLIST mode ---

    @Test
    fun `ALLOWLIST allows a mount whose hostPath matches an entry's prefix`() {
        val policy = MountPolicy(
            mode = MountPolicyMode.ALLOWLIST,
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/photos", readOnly = false)
            )
        )
        val result = enforcer.enforce(
            policy,
            listOf(mount("/sdcard/photos", readOnly = false))
        )
        assertTrue(result is MountEnforcementResult.Allowed)
        val allowed = (result as MountEnforcementResult.Allowed).filteredMounts
        assertEquals(1, allowed.size)
        assertEquals("/sdcard/photos", allowed[0].hostPath)
    }

    @Test
    fun `ALLOWLIST allows a sub-path of an entry's prefix`() {
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard", readOnly = true)
            )
        )
        val result = enforcer.enforce(
            policy,
            listOf(mount("/sdcard/photos/2024/jan.jpg"))
        )
        assertTrue(result is MountEnforcementResult.Allowed)
    }

    @Test
    fun `ALLOWLIST denies a mount outside the allowlist`() {
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/photos")
            )
        )
        val result = enforcer.enforce(
            policy,
            listOf(mount("/sdcard/private"))
        )
        assertTrue(result is MountEnforcementResult.Denied)
        val denied = result as MountEnforcementResult.Denied
        assertEquals(1, denied.violations.size)
        assertEquals("/sdcard/private", denied.violations[0].hostPath)
    }

    @Test
    fun `ALLOWLIST denies a mount whose prefix is a sibling, not a child`() {
        // "/sdcard/photos" must NOT match
        // "/sdcard/photos-private" — the match is
        // path-prefix, not string-prefix.
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/photos")
            )
        )
        val result = enforcer.enforce(
            policy,
            listOf(mount("/sdcard/photos-private"))
        )
        assertTrue(result is MountEnforcementResult.Denied)
    }

    @Test
    fun `ALLOWLIST tightens a writeable mount to read-only when the policy entry says so`() {
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(
                    hostPathPrefix = "/sdcard/photos",
                    readOnly = true
                )
            )
        )
        val result = enforcer.enforce(
            policy,
            listOf(mount("/sdcard/photos", readOnly = false))
        )
        assertTrue(result is MountEnforcementResult.Allowed)
        val allowed = (result as MountEnforcementResult.Allowed).filteredMounts
        assertTrue(
            "policy entry should have tightened the mount to read-only",
            allowed[0].readOnly
        )
    }

    @Test
    fun `ALLOWLIST does not loosen a read-only mount when the policy entry is writeable`() {
        // The policy can only TIGHTEN, not loosen.
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(
                    hostPathPrefix = "/sdcard/photos",
                    readOnly = false
                )
            )
        )
        val result = enforcer.enforce(
            policy,
            listOf(mount("/sdcard/photos", readOnly = true))
        )
        assertTrue(result is MountEnforcementResult.Allowed)
        val allowed = (result as MountEnforcementResult.Allowed).filteredMounts
        assertTrue("mount stays read-only", allowed[0].readOnly)
    }

    @Test
    fun `ALLOWLIST with empty entries denies every mount`() {
        val policy = MountPolicy.LOCKED_DOWN
        val result = enforcer.enforce(
            policy,
            listOf(mount("/sdcard/photos"), mount("/sdcard/videos"))
        )
        assertTrue(result is MountEnforcementResult.Denied)
        val denied = result as MountEnforcementResult.Denied
        assertEquals(2, denied.violations.size)
    }

    // --- Enforcer: BLOCKLIST mode ---

    @Test
    fun `BLOCKLIST allows a mount outside the blocklist`() {
        val policy = MountPolicy(
            mode = MountPolicyMode.BLOCKLIST,
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/private")
            )
        )
        val result = enforcer.enforce(
            policy,
            listOf(mount("/sdcard/photos"))
        )
        assertTrue(result is MountEnforcementResult.Allowed)
    }

    @Test
    fun `BLOCKLIST denies a mount that matches a blocklist entry`() {
        val policy = MountPolicy(
            mode = MountPolicyMode.BLOCKLIST,
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/private")
            )
        )
        val result = enforcer.enforce(
            policy,
            listOf(mount("/sdcard/private/keys.txt"))
        )
        assertTrue(result is MountEnforcementResult.Denied)
    }

    // --- Enforcer: OPEN mode ---

    @Test
    fun `OPEN allows every mount regardless of the policy entries`() {
        val policy = MountPolicy.OPEN
        val result = enforcer.enforce(
            policy,
            listOf(
                mount("/sdcard/private", readOnly = false),
                mount("/data/data/com.elysium.vanguard")
            )
        )
        assertTrue(result is MountEnforcementResult.Allowed)
        val allowed = (result as MountEnforcementResult.Allowed).filteredMounts
        assertEquals(2, allowed.size)
    }

    // --- Mixed result ---

    @Test
    fun `mixed result returns both allowed subset and violations`() {
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/photos")
            )
        )
        val result = enforcer.enforce(
            policy,
            listOf(
                mount("/sdcard/photos/jan.jpg"),
                mount("/sdcard/private"),
                mount("/sdcard/videos")
            )
        )
        assertTrue(result is MountEnforcementResult.Denied)
        val denied = result as MountEnforcementResult.Denied
        assertEquals(1, denied.allowedMounts.size)
        assertEquals("/sdcard/photos/jan.jpg", denied.allowedMounts[0].hostPath)
        assertEquals(2, denied.violations.size)
        assertEquals("/sdcard/private", denied.violations[0].hostPath)
        assertEquals("/sdcard/videos", denied.violations[1].hostPath)
    }
}
