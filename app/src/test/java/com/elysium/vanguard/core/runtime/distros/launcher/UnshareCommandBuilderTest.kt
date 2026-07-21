package com.elysium.vanguard.core.runtime.distros.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 102 — JVM tests for the pure [UnshareCommandBuilder].
 *
 * No Android imports; these run under plain JUnit. The builder
 * is a `List<String>` constructor so the test surface is just
 * "given X, the argv contains Y".
 */
class UnshareCommandBuilderTest {

    @Test
    fun `build composes su -c unshare --mount --pid chroot env sh -lc script`() {
        val rootfs = File("/data/elysium/ubuntu/rootfs")
        val cmd = UnshareCommandBuilder.build(
            rootfsDir = rootfs,
            script = "echo hi",
            namespaces = NamespaceSpec.FULL_SANDBOX,
            cgroups = CgroupSpec.NONE,
        )
        // 1st layer: su -c <inner>
        assertEquals("su", cmd[0])
        assertEquals("-c", cmd[1])
        val inner = cmd[2]
        // 2nd layer: unshare with all 7 namespace flags + fork
        assertTrue("unshare missing: $inner", inner.startsWith("unshare "))
        assertTrue("--mount missing: $inner", inner.contains("--mount"))
        assertTrue("--pid missing: $inner", inner.contains("--pid"))
        assertTrue("--network missing: $inner", inner.contains("--network"))
        assertTrue("--ipc missing: $inner", inner.contains("--ipc"))
        assertTrue("--uts missing: $inner", inner.contains("--uts"))
        assertTrue("--cgroup missing: $inner", inner.contains("--cgroup"))
        assertTrue("--fork missing: $inner", inner.contains("--fork"))
        assertTrue("--propagation private missing: $inner", inner.contains("--propagation private"))
        // 3rd layer: chroot <rootfs>
        assertTrue("chroot missing: $inner", inner.contains(" chroot "))
        assertTrue("rootfs path missing: $inner", inner.contains("/data/elysium/ubuntu/rootfs"))
        // 4th layer: env -i ... /bin/sh -lc 'echo hi'
        assertTrue("env -i missing: $inner", inner.contains("/usr/bin/env -i"))
        assertTrue("HOME=/root missing: $inner", inner.contains("HOME=/root"))
        assertTrue("PATH missing: $inner", inner.contains("PATH="))
        assertTrue("/bin/sh -lc missing: $inner", inner.contains("/bin/sh -lc"))
        assertTrue("script missing: $inner", inner.contains("echo hi"))
    }

    @Test
    fun `build with empty script drops -lc and uses -l for interactive login`() {
        val cmd = UnshareCommandBuilder.build(
            rootfsDir = File("/data/elysium/ubuntu/rootfs"),
            script = "",
            namespaces = NamespaceSpec.FULL_SANDBOX,
            cgroups = CgroupSpec.NONE,
        )
        val inner = cmd[2]
        assertTrue("expected -l (login shell), got: $inner", inner.contains("/bin/sh -l"))
        assertTrue("did NOT expect -lc, got: $inner", !inner.contains("-lc"))
    }

    @Test
    fun `build injects cgexec when CgroupSpec has any controller`() {
        val cmd = UnshareCommandBuilder.build(
            rootfsDir = File("/data/elysium/ubuntu/rootfs"),
            script = "echo hi",
            namespaces = NamespaceSpec.FULL_SANDBOX,
            cgroups = CgroupSpec(
                cpuWeight = 500,
                memoryMaxBytes = 2_000_000_000L,
                pidsMax = 256,
            ),
            sliceName = "elysium-fedora.slice",
        )
        val inner = cmd[2]
        // cgexec must appear AFTER unshare flags and BEFORE chroot
        val unshareIdx = inner.indexOf("unshare")
        val cgexecIdx = inner.indexOf("cgexec")
        val chrootIdx = inner.indexOf("chroot")
        assertTrue("cgexec missing: $inner", cgexecIdx > 0)
        assertTrue("cgexec must follow unshare: $inner", cgexecIdx > unshareIdx)
        assertTrue("cgexec must precede chroot: $inner", cgexecIdx < chrootIdx)
        // The controller list should be alphabetical + comma-separated
        assertTrue("expected 'cpu,memory,pids' in $inner",
            inner.contains("-g cpu,memory,pids:elysium-fedora.slice"))
    }

    @Test
    fun `build with empty CgroupSpec omits cgexec entirely`() {
        val cmd = UnshareCommandBuilder.build(
            rootfsDir = File("/data/elysium/ubuntu/rootfs"),
            script = "echo hi",
            namespaces = NamespaceSpec.FULL_SANDBOX,
            cgroups = CgroupSpec.NONE,
        )
        val inner = cmd[2]
        assertTrue("cgexec must be absent when CgroupSpec is empty: $inner",
            !inner.contains("cgexec"))
    }

    @Test
    fun `user namespace flag only appears when NamespaceSpec user is true`() {
        val off = UnshareCommandBuilder.build(
            rootfsDir = File("/r"),
            script = "true",
            namespaces = NamespaceSpec.FULL_SANDBOX,
            cgroups = CgroupSpec.NONE,
        )
        val on = UnshareCommandBuilder.build(
            rootfsDir = File("/r"),
            script = "true",
            namespaces = NamespaceSpec.FULL_SANDBOX.copy(user = true),
            cgroups = CgroupSpec.NONE,
        )
        assertTrue("user flag must be off by default: ${off[2]}",
            !off[2].contains("--user"))
        assertTrue("user flag must be on when requested: ${on[2]}",
            on[2].contains("--user"))
    }

    @Test
    fun `custom binaries are honored`() {
        val cmd = UnshareCommandBuilder.build(
            rootfsDir = File("/r"),
            script = "true",
            namespaces = NamespaceSpec.FULL_SANDBOX,
            cgroups = CgroupSpec.NONE,
            suBinary = "/system/xbin/su",
            unshareBinary = "/apex/com.android.runtime/bin/unshare",
            chrootBinary = "/system/bin/chroot",
            cgexecBinary = "/system/bin/cgexec",
        )
        // Layer 1: the su wrapper
        assertEquals("/system/xbin/su", cmd[0])
        assertEquals("-c", cmd[1])
        // Layer 2: the inner (what `su -c` will execute). The
        // inner starts with the unshare binary, NOT the su
        // binary — su is the wrapper, unshare is the work.
        val inner = cmd[2]
        assertTrue("inner must start with custom unshare path: $inner",
            inner.startsWith("/apex/com.android.runtime/bin/unshare "))
        assertTrue("custom chroot path: $inner", inner.contains("/system/bin/chroot /r"))
    }

    @Test
    fun `buildProbeCommand wraps args in sh -lc and quotes each arg`() {
        val cmd = UnshareCommandBuilder.build(
            rootfsDir = File("/r"),
            script = "cat /etc/os-release; uname -r",
            namespaces = NamespaceSpec.FULL_SANDBOX,
            cgroups = CgroupSpec.NONE,
        )
        val inner = cmd[2]
        // The whole multi-arg script is a single shell-quoted token
        // because we pass it via the `script` parameter, not
        // as individual args.
        assertTrue("expected /bin/sh -lc '<script>' in $inner",
            inner.contains("/bin/sh -lc 'cat /etc/os-release; uname -r'"))
    }

    @Test
    fun `shellQuote leaves safe strings unquoted and wraps unsafe ones in single quotes`() {
        // Safe strings: alnum + a few punctuation
        assertEquals("hello", UnshareCommandBuilder.shellQuote("hello"))
        assertEquals("/bin/sh", UnshareCommandBuilder.shellQuote("/bin/sh"))
        assertEquals("PATH=/usr/bin", UnshareCommandBuilder.shellQuote("PATH=/usr/bin"))
        assertEquals("a-b_c.d", UnshareCommandBuilder.shellQuote("a-b_c.d"))
        // Empty string MUST be passed as ''
        assertEquals("''", UnshareCommandBuilder.shellQuote(""))
        // Unsafe strings: spaces, quotes, $, etc.
        assertEquals("'hello world'", UnshareCommandBuilder.shellQuote("hello world"))
        // POSIX single-quote escape: close ', insert \', open '
        // (4 chars between the surrounding single quotes: \, ', ', ').
        // For "it's" the result is 'it'\''s' (9 chars: ', i, t, ', \, ', ', s, ').
        assertEquals("'it'\\''s'", UnshareCommandBuilder.shellQuote("it's"))
        assertEquals("'a;b'", UnshareCommandBuilder.shellQuote("a;b"))
    }

    @Test
    fun `rootfsDir with blank path throws`() {
        try {
            UnshareCommandBuilder.build(
                rootfsDir = File(""),
                script = "true",
                namespaces = NamespaceSpec.FULL_SANDBOX,
                cgroups = CgroupSpec.NONE,
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    @Test
    fun `MISSING_SENTINEL is the exact string the resolver pattern-matches on`() {
        // Regression test: a refactor that renames the sentinel
        // would silently break LauncherResolver. Pin the value.
        assertEquals("unshare-missing", UnshareCommandBuilder.MISSING_SENTINEL)
    }
}
