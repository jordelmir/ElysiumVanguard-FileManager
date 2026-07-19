# Phase 74 (third half) — Elysium Linux Meta-Package

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 74 / Market integration — install path
> **Predecessor:** Phase 74 first half (listing) + second half
> (Capsule) + integration test
> **Vertical:** Elysium Linux + Package Manager

---

## TL;DR

The Elysium Linux meta-package is the **install path** for
the distro. A user installs the distro with:

```
pm install com.elysium.linux.distro@1.0.0
```

The meta-package is a typed `ElysiumPackageManifest` for
`com.elysium.linux.distro` that bundles the 5 runtime layer
packages + the package manager itself as dependencies. The
package manager:

1. Fetches the manifest from the repository.
2. Verifies the manifest's signature.
3. Resolves the transitive dependencies.
4. Installs the deps + the meta-package in dependency order.
5. Records the installed set.

The meta-package is the **bridge** between the distro's
Market contract (Phase 74 first half —
`ElysiumLinuxDistroListing`) and the package manager (Phase
73 second half — `ElysiumPackageManager`).

---

## What shipped

### `ElysiumLinuxDistroPackage` (object)

The typed factory for the `com.elysium.linux.distro`
meta-package. The object has:

- **`NAME`** — `"com.elysium.linux.distro"` (the
  reverse-DNS package name).
- **`VERSION`** — `"1.0.0"` (matches the rootfs version).
- **`DESCRIPTION`** — the user-facing description.
- **`DEPENDENCIES`** — the 5 runtime layer packages + the
  package manager (6 dependencies total).
- **`PROVIDES`** — the canonical capabilities the distro
  provides (`elysium-linux`, `elysium-linux-distro`, the 5
  runtime layer capabilities).
- **`FILES`** — the distro-level config + metadata:
  - `/etc/elysium/elysium-linux.conf`
  - `/etc/elysium/package-sources.list`
  - `/usr/share/elysium-linux/README`
- **`CONTENT_HASH`** — placeholder.
- **`DEFAULT_SIGNING_KEY`** — the test signing key
  (`"elysium-linux-publisher-key"`).
- **`manifest(signingKey)`** — factory that returns the
  signed `ElysiumPackageManifest`.

### The dependencies

The meta-package's dependencies are the **5 runtime layer
packages** + the **package manager itself**:

| Package | Constraint | ABI |
| --- | --- | --- |
| `com.elysium.runtime.native` | `>= 1.0.0` | ANY |
| `com.elysium.runtime.mesa-turnip` | `>= 24.1.0` | ARM64 |
| `com.elysium.runtime.box64` | `>= 0.3.2` | ANY |
| `com.elysium.runtime.fex` | `>= 2404.0.0` | ANY |
| `com.elysium.runtime.wine` | `>= 9.0.0` | ANY |
| `com.elysium.pkgmgr` | `>= 1.0.0` | ANY |

The dependency list is in **install order** (a topological
order: deps are listed before the meta-package that depends
on them). The `MesaTurnip` dep is per-ABI = ARM64 (Turnip
is Adreno-specific).

### The `manifest()` factory

```kotlin
fun manifest(signingKey: String = DEFAULT_SIGNING_KEY): ElysiumPackageManifest {
    val v = ElysiumPackageVersion.parse(VERSION).getOrThrow()
    val unsigned = ElysiumPackageManifest(
        name = NAME,
        version = v,
        abi = ElysiumAbi.ARM64,
        description = DESCRIPTION,
        dependencies = DEPENDENCIES,
        provides = PROVIDES,
        files = FILES,
        scripts = ElysiumPackageScripts.NONE,
        contentHash = CONTENT_HASH,
        signature = Signature("placeholder"),
    )
    return unsigned.copy(
        signature = Signature.sign(
            payload = unsigned.canonicalForm.toByteArray(Charsets.UTF_8),
            key = signingKey.toByteArray(),
        ),
    )
}
```

The factory is **pure-domain** (no I/O, no Android
dependencies). The publisher calls `manifest(signingKey)`
to get the signed manifest, then publishes it to the
repository.

### Cross-consistency with the runtime layer defaults

The meta-package's dependencies are **cross-consistent**
with the runtime layer defaults (Phase 73 third half
I-73.3.1). For every default layer:

- The default's id (e.g. `mesa-turnip`) is mapped to the
  package name (`com.elysium.runtime.mesa-turnip`).
- The default's version (e.g. `24.1.0`) is the dependency's
  version constraint (`>= 24.1.0`).

The integration test (`every runtime layer in the
dependencies has a matching default manifest`) verifies
this cross-consistency.

---

## Design decisions

### Why a meta-package, not a single rootfs tarball?

A meta-package is the **typed install path**; a rootfs
tarball is the **content bundle**. The two are distinct:

- The meta-package declares **what to install** (the
  dependencies on the runtime layer packages + the
  package manager).
- The rootfs tarball is the **content** (the actual binaries
  + libraries + config files).

The user installs the meta-package; the package manager
resolves the dependencies + downloads the rootfs + the
runtime layer tarballs + the package manager. The
meta-package is the **top-level contract**; the tarballs
are the **content**.

### Why include the package manager as a dependency?

The package manager is the **meta-package's own meta**: the
meta-package installs the distro + the package manager.
The `elysium-pm` binary is the entry point of the
distro's Capsule (per Phase 74 second half); without the
package manager, the distro can't install additional
packages.

The dependency is **circular** in a sense (the
package manager is the thing that installs the package
manager), but the `elysium-pm` binary is shipped as a
**standalone package** that the user can install
independently. The meta-package's dependency is on the
**standalone package**, not on the meta-package itself.

### Why use the GTE constraint for the dependencies?

GTE (`>=`) is the **loosest** constraint that preserves
correctness: a user who has Mesa Turnip `24.2.0` installed
+ tries to install the meta-package doesn't need to
downgrade to `24.1.0` (the GTE constraint accepts both).

The constraint is **deliberately not EXACT**: the user
might have a newer version installed (a Phase 7+ update);
the meta-package should accept the newer version as long
as it satisfies the constraint.

### Why the FEX version is `2404.0.0` (CalVer)?

FEX uses CalVer (`YYMM`), not semver. The package manager's
`ElysiumPackageVersion` requires 3 parts (`MAJOR.MINOR.PATCH`),
so FEX's `2404` is encoded as `2404.0.0` (year.month.0; the
patch level is unused for FEX's CalVer). This is the same
encoding the runtime layer default uses (per Phase 73 third
half I-73.3.1, where this same CalVer-vs-semver mismatch
was discovered and fixed).

### Why is the meta-package's `abi = ARM64`?

The meta-package is the **install path for the Elysium
Linux distro on Android ARM64**. The `abi` field is the
target ABI (the ABI the meta-package is built for). The
distro is currently ARM64-only; future Phase 73+ increments
may add `X86_64` (for Chromebooks / emulators).

---

## Tests

19 new tests in `ElysiumLinuxDistroPackageTest`. The tests
cover:

- **Package identity** (2 tests): name, version.
- **Dependencies** (7 tests): each of the 6 dependencies is
  present + count is 6.
- **Provides** (2 tests): elysium-linux marker + every
  runtime layer capability.
- **Files** (4 tests): elysium-linux.conf, package-sources.list,
  README, every path is absolute.
- **manifest() factory** (3 tests): returns a valid signed
  manifest, verifies with default key, rejects wrong key.
- **Cross-consistency** (1 test): every runtime layer
  dependency matches a Phase 73 third half I-73.3.1 default.

**Total linux tests:** 241 (32 manifest + 30 package manager
+ 41 runtime layers + 35 rootfs layout + 23 capability
matrix + 29 update strategy + 32 CVE policy + 19 distro
package).
**Total project tests:** 2928 (was 2909, +19 new).

---

## Phase 74 — fully closed

With the meta-package shipped, **Phase 74 is fully closed**:

- **Phase 74 first half**: `ElysiumLinuxDistroListing` (the
  distribution contract).
- **Phase 74 second half**: `ElysiumLinuxCapsule` (the
  runtime contract).
- **Phase 74 integration**: `ElysiumLinuxMarketIntegrationTest`
  (the cross-component consistency proof).
- **Phase 74 third half** (this): `ElysiumLinuxDistroPackage`
  (the install path).

The Elysium Linux distro is now **discoverable + runnable
+ publishable + installable + consistent + installable via
the package manager**. The Phase 74 work is **complete**.

The next Phase 73+ increment is the **real binary** (the
actual rootfs tarball, the Mesa/Turnip/Box64/FEX/Wine
binaries). The typed foundation is fully specified; the
real binaries are the next concrete step.

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumLinuxDistroPackage.kt` | new | meta-package object + manifest factory |
| `app/src/test/java/com/elysium/vanguard/core/linux/ElysiumLinuxDistroPackageTest.kt` | new | 19 JVM tests |

---

## The role in the bigger picture

The Elysium Linux meta-package is the **typed install
path** for the distro. The user has three ways to install
the distro:

1. **Via the Market** — the user finds the listing in the
   Market, clicks "Install", the `MarketInstaller` (Phase
   60) downloads + verifies + installs.
2. **Via the Capsule** — the user finds the Capsule in the
   catalog, the orchestrator runs the Capsule's entrypoint
   (`/usr/bin/elysium-pm init`), the package manager
   installs the distro.
3. **Via the package manager directly** — the user runs
   `pm install com.elysium.linux.distro@1.0.0` (the
   standard CLI).

All three paths converge on the **same meta-package**:
`com.elysium.linux.distro`. The meta-package is the
**canonical install contract**; the three paths are three
**entry points** to the same install.

The meta-package is the **typed contract** that the
package manager, the Market, and the orchestrator all
agree on. A user who installs the distro through any
path gets the same result.
