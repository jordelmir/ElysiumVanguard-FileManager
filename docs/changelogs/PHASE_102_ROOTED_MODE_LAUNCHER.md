# Phase 102 — Rooted Mode Launcher (unshare + chroot + cgroups)

**Vision gap closed**: #2 (Linux modo rooted — chroot, namespaces, cgroups)
**Status**: shipped
**Date**: 2026-07-20

## The gap

`LauncherKind.NAMESPACE_UNSHARE` was declared in Phase 9.6.3 but **no
implementation existed**. `find -name "*Chroot*" -o -name "*Namespace*"`
returned only the network/mount policies — no real chroot, no unshare,
no cgroup enforcement. Rooted users had no way to get a "real Linux"
experience on their device; the proot launcher is a syscall-translation
facade, not a chroot.

## What shipped

A **complete unshare + chroot + cgroup launcher** that slots in at the
top of the launcher resolution chain, plus a Hilt-injected probe + a
Compose settings screen. The launcher is JVM-testable end-to-end.

### Production code (5 new files + 1 modified)

| File | Purpose |
|---|---|
| `core/runtime/distros/launcher/UnshareCommandBuilder.kt` | Pure builder for the `su → unshare → cgexec → chroot → env → sh` command. No Android imports, no I/O. |
| `core/runtime/distros/launcher/NamespaceSpec.kt` | Typed wrapper for the 7 namespace flags. `init` rejects the "always true" set to `false` so a refactor cannot silently weaken isolation. |
| `core/runtime/distros/launcher/CgroupSpec.kt` | Typed wrapper for cgroup v2 controller limits (cpu/memory/io/pids). `init` rejects out-of-range values + enforces `memory.high ≤ memory.max`. |
| `core/runtime/distros/launcher/RootedModeProbe.kt` | Narrow interface + `RootStatus` data class + `RootProvider` enum. The gate the launcher reads. |
| `core/runtime/distros/launcher/AndroidRootedModeProbe.kt` | Production impl. Single `su -c '...'` call, 5s cache, 3s shell timeout. Detects Magisk / KernelSU / APatch via PackageManager. |
| `core/runtime/distros/launcher/NamespacedDistroLauncher.kt` | The launcher itself. `buildShellCommand` returns `["unshare-missing"]` on non-rooted devices — the resolver falls through to proot automatically. |
| `core/runtime/distros/DistroModule.kt` (modified) | New Hilt providers for `NamespacedDistroLauncher` + `RootedModeProbe`. The launcher registry now lists the namespaced launcher first. |
| `core/runtime/distros/launcher/LauncherResolution.kt` (modified) | Resolution order: rooted → proot → direct-exec → jailed. The namespaced launcher's self-reported `isAvailable == false` makes the fallback automatic. |
| `features/rooted/RootedModeViewModel.kt` | `@HiltViewModel` that owns the persisted prefs + reactive status. |
| `features/rooted/SharedPreferencesRootedModePrefs.kt` | Production impl of `RootedModePrefs` (small file: `elysium_rooted_mode`). |
| `features/rooted/RootedModeScreen.kt` | Compose screen: status card + master toggle + namespace toggle + cgroup preset picker. |
| `features/rooted/RootedModeModule.kt` | `@Binds` for the prefs interface → impl. |
| `MainActivity.kt` (modified) | New `rooted_mode` nav route. |

### Tests (6 new files, **+55 tests**)

| File | Tests |
|---|---|
| `UnshareCommandBuilderTest.kt` | 10 tests — `su -c unshare --mount --pid chroot env sh -lc`, empty script → `-l`, cgexec injection, empty cgexec, user-ns flag toggle, custom binaries, shellQuote edge cases, sentinel regression, blank rootfsDir |
| `CgroupSpecTest.kt` | 10 tests — controller list canonical order, controller activation, range enforcement, memory invariant, BACKGROUND preset, NONE preset |
| `NamespaceSpecTest.kt` | 3 tests — FULL_SANDBOX defaults, hard-true flags reject `false`, user toggle free |
| `NamespacedDistroLauncherTest.kt` | 11 tests — kind + capabilities, MISSING_SENTINEL on each failure mode, real command on fully-rooted, user-ns auto-drop when kernel blocks, env variables empty, cgroup v1 + empty spec still launches |
| `RootStatusTest.kt` | 13 tests — `canLaunchRooted` truth table, `canHonorCgroupSpec` truth table, RootProvider sentinel |
| `RootedModeViewModelTest.kt` | 5 tests — init loads prefs, toggle persists, user-ns persists, cgroup persists, refreshStatus probes |

### Docs

| File | Purpose |
|---|---|
| `docs/adr/ADR-037-rooted-mode-launcher.md` | Why this is a separate launcher, why NamespaceSpec/CgroupSpec are typed wrappers, why cgroup v2 not v1, what we explicitly do NOT do |
| `docs/changelogs/PHASE_102_ROOTED_MODE_LAUNCHER.md` | This changelog |

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  LauncherResolver.resolve(rootfs)                        │
│    ↓                                                     │
│  1. NamespacedDistroLauncher (rooted + unshare + cgroup) │
│     ↑ isAvailable(rootfs) — false on non-rooted devices │
│    ↓ (falls through)                                     │
│  2. NativeProotLauncher (proot binary present)           │
│    ↓                                                     │
│  3. DirectExecDistroLauncher (rootfs's shell + loader)   │
│    ↓                                                     │
│  4. JailedDistroLauncher (Android /system/bin/sh)        │
└─────────────────────────────────────────────────────────┘
```

The command built by `NamespacedDistroLauncher` (with all flags on,
cgroup spec empty):

```
su -c 'unshare --mount --pid --network --ipc --uts --cgroup \
        --propagation private --fork \
        chroot /data/elysium/ubuntu/rootfs \
        /usr/bin/env -i \
          HOME=/root USER=root LOGNAME=root \
          SHELL=/bin/sh TERM=xterm-256color \
          LANG=C.UTF-8 TMPDIR=/tmp \
          PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        /bin/sh -lc <script>'
```

With a cgroup spec (`cpuWeight=100, memoryMax=2GiB, pidsMax=256`):

```
su -c 'unshare --mount --pid --network --ipc --uts --cgroup \
        --propagation private --fork \
        cgexec -g cpu,memory,io,pids:elysium.slice \
        chroot /data/elysium/ubuntu/rootfs \
        /usr/bin/env -i ... /bin/sh -lc <script>'
```

## Test counts

- Before: 3521 tests, 0 failures
- After: **3576 tests**, 0 new failures
- Pre-existing flake: 1 (`FoundryServiceRepositoryIntegrationTest` —
  pre-existing at `f08dad5`, not introduced by Phase 102; verified by
  `git stash` + retest)

## Build

- `compileDebugKotlin`: green
- `assembleDebug`: green (98MB APK)
- `testDebugUnitTest --tests "com.elysium.vanguard.core.runtime.distros.launcher.*"`: 100% green

## What this enables

- **True chroot**: the rootfs is its own filesystem, not a proot translation
- **PID isolation**: process inside the chroot sees itself as PID 1
- **Network namespace**: a clean network stack (the NetworkBroker can plumb veth)
- **Cgroup v2 limits**: per-distro CPU weight, memory ceiling, IO weight, pids cap
- **User namespace** (opt-in): nested user-ns isolation when the kernel allows it
- **Audit visibility**: every launch is now visible to the device's process tree as a `su` + `unshare` chain — security review tools see it

## What's still missing (next phases)

- **Box64 / FEX / DXVK** (Phase 103+): graphics trans stack for x86_64 Windows games
- **Distro overlay** (Phase 103+): read-only `/system` + read-write `/data` overlayfs on top of the chroot
- **Rooted-mode audit log entry** (Phase 103+): when a rooted launch happens, record it in `SecurityAudit` (currently the regular process-launch audit fires, but a typed "ROOTED_LAUNCH" event would help forensics)
- **Auto-detection of root at first launch** (Phase 103+): opt-in "Try Rooted Mode" hint on the Distro picker when the probe reports the device is rooted
