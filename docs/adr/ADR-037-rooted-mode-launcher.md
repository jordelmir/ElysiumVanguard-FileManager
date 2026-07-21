# ADR-037 — Rooted Mode Launcher (unshare + chroot + cgroups)

**Status**: accepted
**Date**: 2026-07-20
**Phase**: 102

## Context

The vision for Elysium Vanguard is a **universal computing platform**: every
runtime a user might need (Linux distros, Windows VMs, Android apps) is
available from one device, with one consistent security model. The
Phase 9.6.3 "Distro Launchers" hierarchy already had four tags declared
(`JAILED_SHELL`, `NATIVE_PROOT`, `NAMESPACE_UNSHARE`, `DIRECT_EXEC`) but
only three implementations — the fourth (`NAMESPACE_UNSHARE`) was a
placeholder for a future "advanced" rooted mode.

The user's `find -name "*Chroot*" -o -name "*Namespace*"` audit in 2026-07
confirmed the gap: no launcher in the codebase actually performed chroot
or unshare. The proot launcher (Phase 9.6.4) is a syscall-translation
facade — fast and ubiquitous, but it's not a real chroot. Direct-Exec
runs the rootfs's ELF with the Android loader — better than Jailed, but
no isolation.

We need a **real chroot + namespace + cgroup** launcher for users who
have rooted their device and want full Linux behavior. The launcher must
integrate cleanly with the existing resolver chain (so non-rooted users
fall through to proot automatically) and must be **JVM-testable** (we
test the command builder with no Android imports, the launcher with a
fake probe, the probe with a fake in-memory status).

## Decision

We add a **`NamespacedDistroLauncher`** that builds the command
`su -c 'unshare -m -p -n -i -u -C --propagation private --fork [cgexec ...] chroot <rootfs> /usr/bin/env -i ... /bin/sh -lc <script>'`
and we plumb it into the launcher registry **first**, with a self-reported
`isAvailable == false` on non-rooted devices.

The launcher is composed of three small pieces:

| Piece | Type | Why |
|---|---|---|
| `UnshareCommandBuilder` | pure `object` | The `List<String>` it produces is the seam. The resolver, the probe, the UI all depend on the builder's output, not on the launcher class. Pure functions are trivial to test. |
| `NamespaceSpec` | data class | Captures which of the 7 namespace types are active. `init` block rejects the "always true" set to `false` so a future refactor cannot silently weaken isolation. |
| `CgroupSpec` | data class | Captures the cgroup v2 controller limits. `init` block rejects out-of-range values + enforces `high ≤ max` for memory. |
| `RootedModeProbe` | interface + 2 impls | The production impl runs a single `su -c 'id; which unshare; ...'` call (cached for 5s); tests inject a `FakeRootedModeProbe` that holds a `RootStatus` data class. |
| `NamespacedDistroLauncher` | class | Composes the probe + spec + builder. The `isAvailable(rootfs)` check is the gate the resolver reads. |
| `RootedModeViewModel` + `RootedModeScreen` | Hilt + Compose | UI: shows probe status + master toggle + namespace toggle + cgroup preset picker. |

The launcher is wired into the resolution order **before** `NativeProotLauncher`.
When the device is not rooted, `isAvailable` returns false and the
resolver falls through to proot / direct-exec / jailed automatically. No
caller has to know which launcher is active.

## Why not combine with NativeProotLauncher?

The proot launcher is a *single binary* call:
```
proot -r <rootfs> /bin/sh
```
The rooted launcher is a *pipeline*:
```
su → unshare → cgexec → chroot → env → shell
```
Combining them would force the proot launcher to take a nullable
`RootedModeProbe` and a nullable `NamespaceSpec` it never uses, weakening
both contracts. The two launchers have different capabilities, different
ABIs, and different `requiresRoot` flags. A separate class makes the
divergence explicit.

## Why a separate `NamespaceSpec` (not raw booleans)?

Kotlin can't bind `Triple<Boolean, Boolean, ...>` into Hilt, and a god-class
`RootedModeConfig(allTheBooleans)` is hard to evolve. A data class is
Hilt-bindable in aggregate (one `@Provides` for the whole spec from
saved preferences) and the `init` invariants catch invalid combinations
at construction time.

## Why a separate `CgroupSpec`?

Same reason as `NamespaceSpec`. Plus: cgroup v2 controller semantics
(weight range 1..10000, `memory.high ≤ memory.max`) are not expressible
as raw integers — they need invariants.

## Why a narrow `RootedModeProbe` interface (not raw `Runtime.exec`)?

The probe is a side-effecting Android call (it spawns `su`). Putting
that in an interface lets:
- the test suite inject a 5-line fake that records call counts
- a future refactor swap the implementation (e.g. a `MagiskApi` based
  probe that doesn't shell out)
- the production code be tested by integration only, while the launcher
  itself stays pure-JVM

## Why cgroup v2 (not v1)?

Android has been on cgroup v2 since API 28 (Android 9 Pie). v1 is
deprecated on modern kernels and many rooted devices ship a hybrid
hierarchy where v1 is read-only. Writing limits to v2 is the only
future-proof path. The probe detects v1 and `RootStatus.canHonorCgroupSpec`
returns `false` — the launcher refuses to apply a v2 spec to a v1
hierarchy, rather than silently misconfiguring.

## Operational consequences

- A rooted user toggling "Rooted Mode" ON in the settings screen sees
  the next distro launch go through `su → unshare → chroot` instead of
  `proot`. The behavior change is observable in `ps`: the process tree
  shows `su` as the parent of `unshare`.
- The probe caches for 5 seconds; pressing the "re-probe" button on the
  screen invalidates the cache and runs the `su` call again.
- The first launch on a rooted device pays the Magisk daemon startup
  cost (one-time, ~3s). Subsequent launches are ~10ms per the probe
  cache.

## What we explicitly do NOT do

- We do **not** attempt to mount a real overlay (read-only
  `/system` + read-write `/data` for the rootfs). The chroot is the
  rootfs, period. Adding overlayfs is a follow-up phase.
- We do **not** create a new SELinux domain for the chroot. Android's
  SELinux enforcement will deny some operations; users hitting them
  report the issue and we add perms. The chroot is **not** a
  security boundary against a malicious userland binary — it's an
  organizational tool.
- We do **not** auto-detect root at first launch. The user has to
  explicitly toggle Rooted Mode ON. This makes the privilege
  escalation opt-in and reviewable in the security audit log.
