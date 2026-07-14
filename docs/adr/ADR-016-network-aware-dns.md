# ADR-016: Network-aware guest DNS refresh

- Status: Accepted
- Date: 2026-07-13
- Owners: Elysium Vanguard runtime
- Phase: 11.0 + 11.1
- Governing order: Universal Computing Fabric §10.1, §33.5 (E2E #11–12)

## Context

The PRoot guest inherits whatever the host `/etc/resolv.conf` was at the moment
the proot command was built. When the device moves from Wi-Fi to mobile data,
activates a VPN, changes private DNS, or loses and recovers the network, the
guest keeps the old nameservers and `getaddrinfo` fails until the user restarts
the session.

The previous implementation (`AndroidGuestDnsConfigProvider`) was a one-shot
snapshot read from `ConnectivityManager.getLinkProperties(activeNetwork)`. There
was no `NetworkCallback`, no flow, no observer. The device could move networks
all it wanted — the guest would not notice.

The master order §10.1 explicitly requires the resolver to refresh on:

- session start;
- Wi-Fi → data and data → Wi-Fi;
- VPN up / VPN down;
- private DNS change;
- network loss + recovery.

The same order's §33.5 E2E test #11–12 walks the user through changing the
network and expecting `apt update` to keep working.

## Decision

Introduce a three-layer architecture with explicit, narrow contracts:

```
┌────────────────────────────────────────────────────────────────────┐
│ AndroidGuestDnsObserver   (Android-only)                          │
│  - ConnectivityManager.NetworkCallback                              │
│  - onAvailable / onLost / onCapabilitiesChanged /                 │
│    onLinkPropertiesChanged                                         │
│  - emits GuestDnsConfig to a MutableSharedFlow                     │
└────────────────────────────────────────────────────────────────────┘
                ↓ observe(): Flow<GuestDnsConfig>
┌────────────────────────────────────────────────────────────────────┐
│ GuestDnsSessionTracker   (process-wide singleton)                  │
│  - distinctUntilChanged() → drop(1) → collect { refreshAll() }     │
│  - start(): one-shot initial registry.refreshAll()                 │
│  - Dispatchers.Default                                             │
└────────────────────────────────────────────────────────────────────┘
                ↓ refreshAll()
┌────────────────────────────────────────────────────────────────────┐
│ ActiveRootfsRegistry   (thread-safe Map<File, () -> Unit>)         │
│  - register / unregister (per-launcher closures)                   │
│  - refreshAll: atomic snapshot, per-rootfs try/catch               │
└────────────────────────────────────────────────────────────────────┘
                ↓ invokes the registered closure
┌────────────────────────────────────────────────────────────────────┐
│ NativeProotLauncher.refreshDnsForRootfs(rootfs)                    │
│  - re-reads the observer's current() snapshot                      │
│  - writeResolvConfAtomically                                       │
│  - PRoot's bind mount reflects the new file without restarting      │
└────────────────────────────────────────────────────────────────────┘
```

### Why three layers

A single class that owns the network callback, the per-session bookkeeping
and the file rewriting would couple three concerns that change for three
different reasons:

- `AndroidGuestDnsObserver` changes when Android changes its network API
  (it has already done so between API 21 and API 31).
- `GuestDnsSessionTracker` changes when we change how we react to changes
  (today: a single `refreshAll`; tomorrow: per-session policy filtering).
- `ActiveRootfsRegistry` changes when the launchers or session lifecycle
  change (today: proot only; tomorrow: VM + remote backends).

Each layer is independently testable on the JVM. The InMemory
`GuestDnsObserver` is the seam that lets the tracker and registry be
exercised without a device.

### Why `distinctUntilChanged().drop(1)`, not the other way around

`MutableSharedFlow(replay=1)` gives a new subscriber the last published
value. We need to:

- drop the replay (the launcher's `buildShellCommand` already wrote the
  same content);
- ignore duplicate emissions (Android re-publishes on capabilities
  changes that may not move the resolver).

If we put `drop(1)` first, the first emission post-drop is the baseline
for `distinctUntilChanged`, and a duplicate of the replay value is no
longer distinguishable from a real change. With the order reversed,
`distinctUntilChanged` establishes the replay as the baseline, then
`drop(1)` removes it; subsequent duplicates compare equal to the
baseline and are filtered.

A unit test (now in `GuestDnsSessionTrackerTest.duplicate consecutive
configs do not double-refresh`) pins this ordering.

### Why the registry, not a callback interface

The launcher owns the per-rootfs state (its `runtimeTmpDir`, the key
under which the file is written, the `guestDnsConfigProvider` it was
built with). Asking the launcher to implement a `DnsRefreshable`
interface couples the launcher to the DNS subsystem. A registry that
holds `() -> Unit` closures inverts the dependency: the launcher
exposes `refreshDnsForRootfs`, the registry stores `{ launcher::refresh }`,
neither imports the other.

## Consequences

Positive:

- A network flip on Android propagates to every active guest's
  `resolv.conf` within one observer-emission cycle (~tens of ms).
- `apt update` after a Wi-Fi → data walk no longer fails with
  "Temporary failure resolving deb.debian.org".
- The observer is the only Android-touching class in the chain.
  All the rest is pure JVM and unit-testable.
- The registry's failure isolation means a corrupted guest cannot
  block DNS refreshes for the others.

Negative:

- The initial sync at `start()` does an extra file rewrite on the
  happy path (the launcher's first write already produced the same
  content). Cost: one atomic rename per active rootfs at process
  boot. Tolerable.
- A network change in the milliseconds between the launcher's
  initial write and the tracker's `start()` would be lost. The
  explicit initial sync via `registry.refreshAll()` closes that
  window. If that window ever grows to seconds, we will need
  finer-grained hand-off.

## Alternatives considered

- **Single `GuestDnsCoordinator` class with the network callback,
  per-session bookkeeping and file writing in one place.** Rejected
  because the three concerns change independently and one class
  would be a 500-line god-object that we have to test with Robolectric.
- **`Flow<GuestDnsConfig>` exposed directly to each session, no
  tracker.** Rejected because every session would need its own
  collector with the same `distinctUntilChanged().drop(1)`
  pipeline, and the launcher's refresh closure would have to
  survive the session. Centralizing in a tracker deduplicates that.
- **`WorkManager` job on every network change.** Rejected because
  the latency (WorkManager has a 10-second minimum) is too long for
  a user typing into a terminal; the refresh needs to land before
  the next `getaddrinfo`.

## Rollback

- Disable `tracker.start()` from `Application.onCreate` (Phase 11.3).
  The observer stays in memory, the registry stays empty, no refreshes
  fire, and the platform regresses to the pre-Phase-11 behaviour
  (one-shot snapshot at session start).
- Remove `ActiveRootfsRegistry` and `GuestDnsSessionTracker` from
  `DistroModule`. The `NativeProotLauncher.refreshDnsForRootfs` method
  stays as dead code we can either keep (for a future direct caller)
  or remove in a follow-up commit.

## Risks

- The Android `NetworkCallback` API is not stable across all OEM
  skins. Vendor-specific cellular dual-SIM devices may emit
  `onCapabilitiesChanged` on a sub-thread that posts a `connectivity`
  intent without an actual change. The `distinctUntilChanged` filter
  mitigates this: the publisher compares by value, not by event, so
  a no-op re-publish is a no-op refresh.
- The PRoot bind mount does not survive `kill -HUP` on the launcher.
  We do not use `SIGHUP` in any production code path today, but if
  we add it (e.g. for a future `restart` command), the DNS file
  would also need to be re-bound. Document in Phase 12 when we add
  the `restart` capability.

## Status

- Implemented in Phase 11.0 (`GuestDnsObserver`,
  `InMemoryGuestDnsObserver`, `AndroidGuestDnsObserver`,
  `NativeProotLauncher.refreshDnsForRootfs`).
- Wired through `ActiveRootfsRegistry` + `GuestDnsSessionTracker`
  in Phase 11.1.
- App-level hook (`Application.onCreate`) is Phase 11.3.
- Distro-level registration of rootfses is Phase 11.4.
- Property test for the flow pipeline is Phase 11.2 (this delivery).
- Instrumented test of `AndroidGuestDnsObserver` with real
  `ConnectivityManager` is Phase 11.2 (this delivery).
