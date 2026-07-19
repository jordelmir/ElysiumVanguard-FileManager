# Phase 75 — Elysium Linux Default Repository

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 75 / Elysium Linux vision alignment
> **Predecessor:** Phase 74 third half (Elysium Linux meta-package)
> **Vertical:** Elysium Linux (`com.elysium.vanguard.core.linux.*`)

---

## TL;DR

The Elysium Linux distro is **installable out of the
box**. The new `ElysiumLinuxDefaultRepository` is a
pre-populated `ElysiumRepository` containing the
Elysium Linux meta-package + the 5 runtime layer
packages + the package manager. The user can install
the distro with:

```
pm install com.elysium.linux.distro
```

with no additional configuration — every declared
dependency is in the default repository.

The default repository is the **canonical source** of
the Elysium Linux packages. A future Phase 73+ increment
can add a `HttpElysiumRepository` (a remote repository
that fetches packages from the Elysium Linux distribution
server) + a `MultiElysiumRepository` (aggregates multiple
repositories).

---

## What shipped

### `ElysiumLinuxDefaultRepository` (object)

The pre-populated `ElysiumRepository` for the Elysium
Linux distro. The `build()` factory returns a repository
containing 7 packages:

1. **`com.elysium.linux.distro@1.0.0`** — the Elysium
   Linux meta-package (per Phase 74 third half).
2. **`com.elysium.runtime.native@1.0.0`** — the
   native ARM64 runtime layer.
3. **`com.elysium.runtime.mesa-turnip@24.1.0`** — the
   Mesa Turnip Vulkan driver.
4. **`com.elysium.runtime.box64@0.3.2`** — the Box64
   x86_64 translator.
5. **`com.elysium.runtime.fex@2404.0.0`** — the FEX x86
   translator.
6. **`com.elysium.runtime.wine@9.0.0`** — the Wine
   Windows API re-implementation.
7. **`com.elysium.pkgmgr@1.0.0`** — the Elysium package
   manager (`elysium-pm`).

### `PackageNames` (object)

The **typed references** to the 7 packages in the
default repository. The constants are the canonical
package names; a consumer (the package manager, the
UI) uses the constants to look up the manifest:

```kotlin
object PackageNames {
    const val DISTRO: String = "com.elysium.linux.distro"
    const val NATIVE: String = "com.elysium.runtime.native"
    const val MESA_TURNIP: String = "com.elysium.runtime.mesa-turnip"
    const val BOX64: String = "com.elysium.runtime.box64"
    const val FEX: String = "com.elysium.runtime.fex"
    const val WINE: String = "com.elysium.runtime.wine"
    const val PACKAGE_MANAGER: String = "com.elysium.pkgmgr"
}
```

### `PackageVersions` (object)

The **typed references** to the 7 package versions. The
constants are the canonical `ElysiumPackageVersion`
values; a consumer uses the constants to construct an
install request:

```kotlin
object PackageVersions {
    val DISTRO: ElysiumPackageVersion = ElysiumPackageVersion(1, 0, 0)
    val NATIVE: ElysiumPackageVersion = ElysiumPackageVersion(1, 0, 0)
    val MESA_TURNIP: ElysiumPackageVersion = ElysiumPackageVersion(24, 1, 0)
    val BOX64: ElysiumPackageVersion = ElysiumPackageVersion(0, 3, 2)
    val FEX: ElysiumPackageVersion = ElysiumPackageVersion(2404, 0, 0)
    val WINE: ElysiumPackageVersion = ElysiumPackageVersion(9, 0, 0)
    val PACKAGE_MANAGER: ElysiumPackageVersion = ElysiumPackageVersion(1, 0, 0)
}
```

### `DEFAULT_SIGNING_KEY` (const)

The default signing key for the Elysium Linux packages.
The real production key is published with the Elysium
Linux distribution team's certificate. Tests use
the default key; production uses a publisher-specific
key.

### The build flow

```kotlin
fun build(signingKey: String = DEFAULT_SIGNING_KEY): ElysiumRepository {
    val repo = InMemoryElysiumRepository()
    addManifest(repo, ElysiumLinuxDistroPackage.manifest(signingKey), signingKey)
    for (layer in ElysiumRuntimeLayerDefaults.ALL) {
        addManifest(repo, buildRuntimeLayerManifest(layer, signingKey), signingKey)
    }
    addManifest(repo, packageManagerManifest(signingKey), signingKey)
    return repo
}
```

The build is **pure-domain** (no I/O, no Android
dependencies). The build is **type-safe** (every manifest
is constructed via a typed factory; every add is
signature-verified at add time).

### The runtime layer manifest builder

```kotlin
private fun buildRuntimeLayerManifest(
    layer: ElysiumRuntimeLayerManifest,
    signingKey: String,
): ElysiumPackageManifest {
    val unsigned = ElysiumPackageManifest(
        name = "com.elysium.runtime.${layer.id.value}",
        version = layer.version,
        abi = layer.hostAbi,
        description = layer.description,
        dependencies = layer.dependencies,
        provides = layer.provides,
        files = layer.files,
        scripts = ElysiumPackageScripts.NONE,
        contentHash = layer.contentHash,
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

The builder takes an `ElysiumRuntimeLayerManifest`
(per Phase 73 third half I-73.3.1) and produces a
package manifest. The build is deterministic + signed.

---

## Design decisions

### Why a default repository, not a remote one?

The user needs the Elysium Linux distro **installable
out of the box** — `pm install com.elysium.linux.distro`
should "just work" without configuring a remote
repository URL.

A default in-memory repository is the **minimum viable
install path**: the user can install the distro +
the runtime layers + the package manager with no
external configuration. A future Phase 73+ increment
can add a remote HTTP repository for **updates** (the
default repository ships the initial install; the
remote repository ships updates).

### Why pre-populate with 7 packages, not 1 (the meta-package)?

The user can `pm install com.elysium.linux.distro` and
the package manager will resolve the dependencies
transitively. **However**, the transitive resolution
requires the dependencies to be in the repository —
without the runtime layer packages in the repository,
the install fails with `ManifestNotFound`.

The default repository is the **complete source** of
the Elysium Linux packages: the meta-package + the 5
runtime layers + the package manager. Every declared
dependency is present; every install is successful.

### Why typed `PackageNames` + `PackageVersions` constants?

A consumer (the UI, the CLI) can use the constants
directly:

```kotlin
val manifest = repo.fetchManifest(
    ElysiumLinuxDefaultRepository.PackageNames.DISTRO,
    ElysiumLinuxDefaultRepository.PackageVersions.DISTRO,
)
```

vs the stringly-typed equivalent:

```kotlin
val manifest = repo.fetchManifest(
    "com.elysium.linux.distro",
    ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
)
```

The typed constants are **safer** (a typo is a compile
error) + **more discoverable** (the IDE autocomplete
shows every available package). The stringly-typed
version is a refactor trap (the strings could drift
from the canonical names).

### Why is the `build()` factory a separate object (not a class)?

The default repository is a **single canonical source**
— there is only one Elysium Linux distro, and the
default repository is the canonical install path. The
`object` keyword captures the "single canonical instance"
semantics; the `build()` factory returns a fresh
`ElysiumRepository` for the consumer to use.

A class would imply multiple instances (e.g. a user-
specific default repository). The default repository
is the same for every user; a single object is the
right abstraction.

### Why is the default repository pure-domain (no I/O)?

The default repository is a **test fixture** +
**production initial state**. A future Phase 7+
increment can add a `MultiElysiumRepository` that
combines the default repository with a remote
repository:

```kotlin
class MultiElysiumRepository(
    private val primary: ElysiumRepository,
    private val secondary: ElysiumRepository,
) : ElysiumRepository {
    override fun fetchManifest(name, version) =
        primary.fetchManifest(name, version)
            ?: secondary.fetchManifest(name, version)
    // ...
}
```

The default repository is the **primary** (the local
cache); the remote is the **secondary** (the network).
The pure-domain default repository + a future pure-
domain remote repository = a clean architecture.

---

## Tests

14 new tests in `ElysiumLinuxDefaultRepositoryTest`. The
tests cover:

- **Repository build + size** (2 tests): 7 packages, 7
  unique names.
- **Repository contents** (7 tests): each of the 7
  packages (meta + 5 layers + pkgmgr) is present.
- **Signature verification** (1 test): every manifest
  verifies with the default signing key.
- **Meta-package dependencies are present** (2 tests):
  every dependency in the meta-package is in the
  repository; meta-package has exactly 6 dependencies.
- **Constants** (2 tests): `PackageNames.DISTRO` matches
  the meta-package name; `PackageVersions.DISTRO`
  parses the meta-package version.

**Total linux tests:** 255 (was 241; +14 new).
**Total project tests:** 3027 (was 3013, +14 new).

---

## What's next — Phase 75 second half (Elysium Linux install)

`ElysiumLinuxInstaller` — the high-level installer that
takes an `ElysiumRepository` + an `ElysiumPackageManager` +
a package name + a version + performs the install:

1. Fetches the manifest from the repository.
2. Verifies the manifest's signature.
3. Resolves the transitive dependencies.
4. Installs the package + the deps in dependency order.
5. Returns the install result.

The installer is the **runtime equivalent** of the
existing `LocalMarketInstaller` (Phase 60) — but for
the Elysium Linux packages, not the Market packages.
The installer is a thin wrapper around the existing
`ElysiumPackageManager.install` (Phase 73 second half).

The installer is **pure-domain** (no I/O, no Android
dependencies). The test implementation is an
`InMemoryElysiumLinuxInstaller`; the production
implementation is an `AndroidElysiumLinuxInstaller`
(a future Phase 7+ increment).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumLinuxDefaultRepository.kt` | new | default repository factory + constants |
| `app/src/test/java/com/elysium/vanguard/core/linux/ElysiumLinuxDefaultRepositoryTest.kt` | new | 14 JVM tests |

---

## The role in the bigger picture

The default repository is the **install path** for the
Elysium Linux distro. The path is:

1. **Discovery** (Phase 74 first half) — the user finds
   the distro via the Market catalog.
2. **Listing** (Phase 74 first half) — the
   `ElysiumLinuxDistroListing` is the catalog row.
3. **Capsule** (Phase 74 second half) — the
   `ElysiumLinuxCapsule` is the runtime contract.
4. **Meta-package** (Phase 74 third half) — the
   `ElysiumLinuxDistroPackage` is the install path
   via the package manager.
5. **Default repository** (this phase) — the
   `ElysiumLinuxDefaultRepository` is the canonical
   source of the Elysium Linux packages.

The user can install the distro with `pm install
com.elysium.linux.distro` and the package manager
resolves the dependencies transitively. The default
repository is **complete**: every declared dependency
is in the repository, every install is successful.

The default repository is the **bridge** between the
typed foundation (Phase 73) and the real binaries
(future Phase 73+). The bridge is **content**: the
default repository ships the initial install; a
future remote repository ships the updates.
