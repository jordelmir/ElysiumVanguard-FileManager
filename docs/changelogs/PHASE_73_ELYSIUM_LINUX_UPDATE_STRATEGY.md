# Phase 73 (third half, I-73.3.4) — Elysium Linux Update Strategy

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 73 / Linux distro foundation — update strategy
> **Predecessor:** I-73.3.3 (ABI Capability Matrix)
> **Vertical:** Elysium Linux (`com.elysium.vanguard.core.linux.*`)

---

## TL;DR

Elysium Linux has a **typed update strategy**. The distro
supports two strategies:

1. **A/B updates** — two rootfs slots (`slot_a` + `slot_b`).
   An update writes to the inactive slot; the device reboots
   into the new slot; the old slot is preserved for rollback.
2. **Versioned images** — every rootfs is a content-addressed
   `rootfs-v1.2.3.tar.zst` file. The device holds the last N
   versions; a rollback re-extracts the image from the cache.

The strategy is a **sealed class** (two cases, not a string id).
The orchestrator + the update manager use `when` on the
strategy; adding a third strategy is a compile error in every
consumer that hasn't been updated.

This is the **fourth of five sub-tasks** in the Phase 73 third
half. The remaining sub-task:

- **I-73.3.5** — `ElysiumCvePolicy` (vulnerability policy +
  response SLA).

---

## What shipped

### `ElysiumUpdateStrategy` (sealed class, 2 cases)

```kotlin
sealed class ElysiumUpdateStrategy {
    data class ABUpdate(
        val currentSlot: ElysiumRootfsSlot,
        val inactiveSlot: ElysiumRootfsSlot,
        val rollbackOnFailure: Boolean = true,
    ) : ElysiumUpdateStrategy()

    data class VersionedImage(
        val currentVersion: ElysiumRootfsVersion,
        val availableVersions: List<ElysiumRootfsVersion>,
        val maxRetained: Int = DEFAULT_MAX_RETAINED,
    ) : ElysiumUpdateStrategy() {
        companion object {
            const val DEFAULT_MAX_RETAINED: Int = 3
        }
    }
}
```

### `ElysiumRootfsSlot` (enum)

The slot is one of the two physical partitions used in A/B
updates.

```kotlin
enum class ElysiumRootfsSlot(val symbol: String) {
    A("a"),
    B("b");
    val other: ElysiumRootfsSlot  // A <-> B
}
```

The `other` property is the slot NOT this one — a convenience
for "the next slot" in A/B updates.

### `ElysiumRootfsVersion` (data class)

A typed semver for the rootfs. The version is **distinct from
the package version** — a rootfs is a *content bundle* (a
tarball containing many packages); a package is a *software
unit* (an .so, a binary, a library).

```kotlin
data class ElysiumRootfsVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ElysiumRootfsVersion> {
    val canonical: String           // "1.2.3"
    val imageFileName: String       // "rootfs-v1.2.3.tar.zst"
}
```

The `imageFileName` is the canonical Elysium Linux image name
(`<version>.tar.zst` — a tarball compressed with zstd).

### `ElysiumUpdatePlan`

The orchestrator's recipe for executing an update.

```kotlin
data class ElysiumUpdatePlan(
    val strategy: ElysiumUpdateStrategy,
    val targetVersion: ElysiumRootfsVersion,
    val estimatedBytes: Long,
    val requiresReboot: Boolean,
    val rollbackPlan: ElysiumRollbackPlan,
)
```

### `ElysiumRollbackPlan`

The orchestrator's recipe for undoing an update. **Every
update must have a rollback path** (the `canRollback = false`
case is the explicit opt-out).

```kotlin
data class ElysiumRollbackPlan(
    val canRollback: Boolean,
    val targetVersion: ElysiumRootfsVersion?,
    val estimatedBytes: Long,
    val estimatedDurationMs: Long,
) {
    companion object {
        val NO_ROLLBACK: ElysiumRollbackPlan
    }
}
```

The init block enforces: if `canRollback` is `true`, then
`targetVersion` must be non-null. A rollback plan without a
target version is rejected at construction.

---

## Design decisions

### Why a sealed class with 2 cases, not a single strategy class with a flag?

A sealed class is **type-safe**. The compiler knows the
strategy is one of exactly 2 kinds. A `when` on the strategy
is **exhaustive** — adding a 3rd strategy is a compile error
in every consumer that hasn't been updated.

A single class with a `kind: StrategyKind` flag is **stringly
typed** — the flag is a string, the `when` on the flag is not
exhaustive, and a typo is a silent default.

The 2 cases reflect the **2 distinct update strategies** the
distro supports. They have different invariants (ABUpdate
requires different slots; VersionedImage requires
currentVersion in availableVersions) — sealed classes capture
this.

### Why a typed rootfs version, distinct from the package version?

A rootfs is a **content bundle** — it contains many packages.
The rootfs version tracks the **bundle as a whole**: a new
minor release (1.2.3 → 1.3.0) may include new packages, new
runtime layers, or a new package manager version. A new patch
release (1.2.3 → 1.2.4) is a security fix or a bug fix.

The rootfs version is **decoupled** from the package versions
inside it. A rootfs can include an older package version (a
package was rolled back); a rootfs can include a newer package
version (a package was patched); the rootfs version only
changes when the **bundle** changes.

### Why a slot enum, not just a string?

The slot is a **typed value**. The init block of `ABUpdate`
rejects `currentSlot == inactiveSlot` (a slot cannot update
itself). A `String` would not enforce this; an enum does.

The `other` property is a **convenience**: the next update
slot is always the other slot. A `String` would require the
caller to write the conditional; an enum encapsulates it.

### Why a `requiresReboot: Boolean` on the plan?

A/B updates **require a reboot** to switch slots. Versioned
images do NOT (the orchestrator can re-mount the new rootfs
in-place). The `requiresReboot` flag tells the orchestrator
whether to schedule a reboot notification to the user.

The flag is **not** a property of the strategy (a versioned
image COULD be implemented with a reboot, e.g. for kernel
updates). The flag is a property of the **plan** (the specific
execution of the strategy).

### Why a `NO_ROLLBACK` constant on `ElysiumRollbackPlan`?

The constant is a **typed sentinel**. The consumer reads
`ElysiumRollbackPlan.NO_ROLLBACK` instead of constructing
`ElysiumRollbackPlan(canRollback = false, ...)` ad hoc. The
constant guarantees the consumer gets the canonical "no
rollback" plan (canRollback = false, targetVersion = null,
estimatedBytes = 0, estimatedDurationMs = 0).

The constant is **immutable** (a `val` in a companion object).
A consumer cannot accidentally mutate the sentinel.

---

## Tests

29 new tests in `ElysiumUpdateStrategyTest`. The tests cover:

- **ElysiumRootfsSlot** (5 tests): symbol strings, `other`
  lookup, toString.
- **ElysiumRootfsVersion** (7 tests): canonical form, reject
  negative major / minor / patch, imageFileName, semver
  comparison.
- **ABUpdate** (3 tests): different slots accepted, same slot
  rejected, rollbackOnFailure flag.
- **VersionedImage** (5 tests): valid config accepted, empty
  availableVersions rejected, currentVersion not in list
  rejected, maxRetained < 1 rejected, DEFAULT_MAX_RETAINED
  constant.
- **ElysiumUpdatePlan** (2 tests): valid config accepted,
  negative estimatedBytes rejected.
- **ElysiumRollbackPlan** (5 tests): canRollback with target
  accepted, canRollback without target rejected, canRollback
  false accepted, NO_ROLLBACK constant, negative duration
  rejected.
- **Data class equality** (2 tests): two ABUpdate with same
  fields are equal, ABUpdate and VersionedImage are not equal.

**Total linux tests:** 190 (32 manifest + 30 package manager
+ 41 runtime layers + 35 rootfs layout + 23 capability matrix
+ 29 update strategy).
**Total project tests:** 2825 (was 2796, +29 new).

---

## What's next — Phase 73 third half, sub-task I-73.3.5

`ElysiumCvePolicy` — the vulnerability policy and disclosure
SLA. Elysium Linux has a typed answer to "how does the distro
handle CVEs?". The policy has:

- **Severity levels** (CRITICAL / HIGH / MEDIUM / LOW) with
  CVSS score ranges.
- **Response SLA** (the maximum time from a CVE disclosure to
  a patched Elysium Linux release, per severity).
- **Disclosure timeline** (the time from a CVE patch to a
  public CVE record, per severity).
- **Affected package tracking** (the typed list of packages
  affected by a CVE; a CVE is associated with one or more
  packages + a fixed-in version).

The policy is the **distro's commitment to security**. A user
who installs Elysium Linux has a typed answer to "when will my
device be patched?".

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumUpdateStrategy.kt` | new | strategy sealed class + slot enum + version data class + plan + rollback plan |
| `app/src/test/java/com/elysium/vanguard/core/linux/ElysiumUpdateStrategyTest.kt` | new | 29 JVM tests |

---

## The role in the bigger picture

The update strategy is the **runtime lifecycle**. Elysium Linux
is a long-lived distro (a phone is supported for years); the
update strategy is the policy that keeps the distro patched
over time.

- A/B updates are **fast + safe** — the device never has a
  half-installed rootfs; the new rootfs is fully written
  before the device reboots.
- Versioned images are **simple + cheap** — no slot management;
  the device holds the last N images; a rollback is
  `pm rollback rootfs-v1.2.2`.

The choice between A/B and versioned is **per-device-class**.
A modern flagship phone with 256 GB of storage uses A/B (dual
storage is cheap); an entry-level phone with 32 GB of storage
uses versioned images (dual storage is too expensive).

The strategy is the **typed answer** to "how does this device
update itself?".
