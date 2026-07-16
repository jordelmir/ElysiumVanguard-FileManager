# Phase 17 — DistroManager ↔ Provisioning Pipeline Wiring

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1106 tests, 0 failures, 2 skipped.

## What landed

The four provisioning steps (overlay, profile, layer, sign) are
now wired through the runtime's single public install surface.
`DistroManager.installBlocking(id, profile)` extracts the rootfs
via the installer, then hands the result to the
`DistroProvisioningPipeline` to write the Elysium os-release
overlay, plan the profile, and sign a manifest next to the
rootfs.

### Files

**Production (1 changed):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/distros/DistroManager.kt`
  - Two new constructor params: `provisioningPipeline:
    DistroProvisioningPipeline?` and a
    `distroResolver: (String) -> Distro?` hook (defaults to
    `DistroCatalog.find`). The resolver is what lets the unit
    test register a synthetic distro without touching the
    immutable production catalog.
  - `installBlocking(id, profile = ElysiumProfile.DEFAULT)`
    now takes a profile. When a pipeline is configured, the
    installer's overlay is suppressed (the pipeline owns
    that step) and the pipeline runs after extraction.
  - Companion `defaultOverlay()` returns a TITAN-versioned
    overlay for the legacy no-pipeline path.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/distros/DistroManagerProvisioningTest.kt`
  — 4 end-to-end tests:
    1. `installBlocking` extracts the rootfs, applies the
       overlay, and signs the manifest. The signature
       verifies.
    2. `installBlocking` fails when the rootfs is unhealthy
       (no /etc/os-release, no shell) and the manager wraps
       the failure in a typed `Result`.
    3. The legacy no-pipeline path still applies the
       fallback overlay via the installer and produces no
       manifest.
    4. Unknown distro id returns a typed failure without
       calling the downloader.

### Why this matters

Before Phase 17 the four provisioning pieces worked in
isolation but the wiring was an unwritten contract — the
`RuntimeViewModel` called `manager.installBlocking(id)` and
the manager called `installer.install(distro, baseDir)`, but
nothing called the pipeline. The pipeline was an orphan that
you could test in a vacuum but never reach from the UI.

Phase 17 closes the gap. Every install that has a pipeline
configured now produces a signed manifest the device can
re-verify on every boot. The `installBlocking` method is the
single public surface; the pipeline is an internal seam. A
caller that does not want the pipeline (e.g. a unit test
that exercises just the installer's extraction) can construct
a `DistroManager` without one and the manager falls back to
the legacy overlay.

### The re-verify guarantee now reaches the UI

The pipeline's re-verify step (Phase 16) is now the contract
the UI inherits. If the manifest is ever produced with a
broken signer, the manager's `installBlocking` returns a
`Result.failure` — the UI never sees a "successful" install
that the device would later reject. The cost is one
verification per install; the value is a hard guarantee.

### How the install path looks now

```
RuntimeViewModel.installBlocking(id, profile)
  -> DistroManager.installBlocking(id, profile)
    -> DistroInstaller.install(distro, baseDir)        [extract + validate]
    -> DistroProvisioningPipeline.provision(            [overlay + plan + sign + re-verify]
         rootfs, profile, family, layerTarball?, manifestDir)
    <- signed DistroProvisioningResult
  <- Result.success(rootfs) OR Result.failure(IOException)
```

The pipeline is optional; without it, the manager's
fallback overlay (TITAN version) is passed to the installer
and the path stops at the extract + overlay step. With it,
the install is end-to-end-signed.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `DistroManagerProvisioningTest` | 4 | 0 |
| **Project total** | **1106** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

**Phase 18** — hardware broker (master order §18). The
runtime's host hardware (USB, Bluetooth, NFC) needs a
typed access policy just like the network does. The pattern
is the same as `NetworkBroker` + `NetworkPolicy`: pure-JVM
decision engine, typed decisions, per-session policy. The
seam the production hardware backend plugs into is a
`HardwareBroker` interface (analogous to
`FirewallRuleBackend`).

Then **§19 WinLayer + §20 Windows VM** (master order). The
Windows layer is the WSL/equivalent that lets Elysium run
Windows guests under a QEMU/KVM-backed VM.
