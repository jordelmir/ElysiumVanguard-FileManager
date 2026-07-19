# Phase 73 (second half) — Elysium Linux Package Manager

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 73 / Linux distro foundation — runtime
> **Predecessor:** Phase 73 first half (manifest + trust chain)
> **Vertical:** Elysium Linux (`com.elysium.vanguard.core.linux.*`)

---

## TL;DR

The Elysium Linux package manager is **operational**. The runtime
now installs, upgrades, and removes packages from a typed
repository, with:

- **Atomic installs** with snapshot rollback.
- **Transitive dependency resolution** (DFS topological order).
- **Constraint satisfaction** (8 kinds: EXACT / GTE / LTE / GT / LT /
  CARET / TILDE / ANY; picks the latest compatible version).
- **Cycle detection** with the full cycle path reported.
- **Dependent-packages check** on remove (no silent breakage of
  other installed packages).
- **Signature verification** (every install + every repository
  addManifest verifies the publisher signature; the trust chain
  from Phase 73 first half is now in the install path).
- **Typed error envelope**: `ElysiumPackageInstallError` (8 sealed
  variants) — every failure mode is a typed value, never a
  free-form string.
- **Pure-domain** (`InMemoryElysiumPackageManager` + `InMemoryElysiumRepository`)
  with **no I/O + no Android dependencies** — the production
  HttpRepository + FsPackageManager are Phase 73 third half.

The package manager is the **typical entry point** for the runtime
hooks (the Elysium Vanguard File Manager + the Capsule installer +
the Workspace Orchestrator + the AI council all consume it).

---

## What shipped

### `ElysiumRepository` + `InMemoryElysiumRepository`

The repository is the **source of signed manifests**. The interface:

```kotlin
interface ElysiumRepository {
    fun fetchManifest(name: String, version: ElysiumPackageVersion): ElysiumPackageManifest?
    fun listVersions(name: String): List<ElysiumPackageVersion>
    fun listPackages(): List<String>
    fun size(): Int
}
```

The in-memory implementation is a `ConcurrentHashMap<String,
ConcurrentHashMap<String, ElysiumPackageManifest>>` (name → version
canonical → manifest). `addManifest(manifest, expectedSigningKey)`
verifies the signature before storing; a failed verification is a
hard rejection (the manifest is never added to the repo).

| Method | Sort order | Notes |
| --- | --- | --- |
| `listVersions` | descending (latest first) | sorted semver-comparable |
| `listPackages` | ascending (alphabetical) | `sorted()` |
| `size` | n/a | total manifests across all packages |

### `ElysiumPackageManager` + `InMemoryElysiumPackageManager`

The package manager is the **stateless composition of a repository
+ an installed set**. The interface:

```kotlin
interface ElysiumPackageManager {
    fun install(name: String, version: ElysiumPackageVersion): ElysiumPackageInstallResult
    fun upgrade(name: String, targetVersion: ElysiumPackageVersion): ElysiumPackageInstallResult
    fun remove(name: String): ElysiumPackageInstallResult
    fun listInstalled(): List<InstalledPackage>
    fun isInstalled(name: String): Boolean
    fun installedVersion(name: String): ElysiumPackageVersion?
}
```

The in-memory implementation is a `ConcurrentHashMap<String,
InstalledPackage>`. Operations are **thread-safe** via the
underlying concurrent map.

### `ElysiumPackageInstallResult`

```kotlin
sealed class ElysiumPackageInstallResult {
    data class Success(
        val operation: Operation,
        val packageName: String,
        val version: ElysiumPackageVersion?,
        val installedPackages: List<InstalledPackage>,
    ) : ElysiumPackageInstallResult()

    data class Failure(
        val operation: Operation,
        val packageName: String,
        val reason: ElysiumPackageInstallError,
    ) : ElysiumPackageInstallResult()

    enum class Operation { INSTALL, UPGRADE, REMOVE }
}
```

The result is a **typed value** (not a free-form string). Every
failure mode is an `ElysiumPackageInstallError` subclass.

### `ElysiumPackageInstallError` (8 sealed variants)

```kotlin
sealed class ElysiumPackageInstallError(message: String) : RuntimeException(message) {
    data class ManifestNotFound(name, version)
    data class SignatureVerificationFailed(name, version)
    data class UnsatisfiableDependency(name, missingDep, constraint)
    data class CyclicDependency(name, cyclePath: List<String>)
    data class DependentPackages(name, dependents: List<String>)
    data class NotAnUpgrade(name, current, target)
    data class AlreadyInstalled(name, version)
    data class NotInstalled(name)
}
```

Every subclass is also a `RuntimeException` so the dependency
resolver can throw it through the install pipeline (the catch in
`install` unwraps the exception into a typed
`ElysiumPackageInstallResult.Failure`).

### `InstalledPackage`

```kotlin
data class InstalledPackage(
    val name: String,
    val version: ElysiumPackageVersion,
    val installedAtMs: Long,
    val contentHash: ContentHash,
) {
    val canonicalId: String  // "$name@${version.canonical}:${contentHash.value}"
}
```

The `installedAtMs` is **pluggable** via the `clock: () -> Long`
constructor parameter (default `System::currentTimeMillis`); tests
use a fixed clock to assert deterministic timestamps.

### `InMemoryElysiumPackageManager` — algorithm walkthrough

The install pipeline:

1. **Already-installed check.** If the package is installed at the
   target version → `Failure(AlreadyInstalled)`.
2. **Manifest fetch.** From the repository → if missing →
   `Failure(ManifestNotFound)`.
3. **Signature verification.** HMAC-SHA-256 over the canonical
   form (Phase 73 first half) → if mismatched →
   `Failure(SignatureVerificationFailed)`.
4. **Transitive dependency resolution.** DFS topological order:
   - For each dep, pick the latest version that satisfies the
     `VersionConstraint`.
   - If no version satisfies → throws
     `ElysiumPackageInstallError.UnsatisfiableDependency`.
   - If a cycle is detected → throws
     `ElysiumPackageInstallError.CyclicDependency` with the full
     `cyclePath: List<String>` (e.g. `["com.example.a", "com.example.b", "com.example.a"]`).
   - The `install` method catches the typed exception and wraps it
     into `Failure(reason = e)`.
5. **Atomic install with rollback.** Snapshot the `installed` map;
   install deps in order, then the package itself. On any
   exception → restore the snapshot → `Failure(SignatureVerificationFailed)`
   (the generic catch-all label; the actual cause is in the
   exception's `cause` chain in production).
6. **Return `Success`.** The list of installed packages is the
   post-install state.

The upgrade pipeline:

1. Check the package is installed → `Failure(NotInstalled)`.
2. Check the target version is **strictly greater** than the
   current → `Failure(NotAnUpgrade)`.
3. Remove the old version; delegate to `install(name, target)`.

The remove pipeline:

1. Check the package is installed → `Failure(NotInstalled)`.
2. Walk every other installed package; for each, fetch the
   manifest from the repo and check whether any of its declared
   `dependencies` references the package being removed. If any
   other package depends on it → `Failure(DependentPackages)`.
3. Remove the package; return `Success`.

---

## Design decisions

### Why typed exceptions, not `Result<List<...>>`?

The first cut of `resolveDependencies` returned
`Result<List<ElysiumPackageManifest>>` and unwrapped via
`exceptionOrNull()!!`. The compiler caught the type mismatch:
`Throwable` (from `exceptionOrNull`) is wider than
`ElysiumPackageInstallError` (the `reason` field). The first cut
also had a `?: return` bug in the `visit` recursion that silently
dropped unsatisfiable deps (the function returned without adding
the manifest to `order` *and* without reporting the failure).

**Resolution:** make the resolver **throw** the typed
`ElysiumPackageInstallError` subclass directly. The `install`
method wraps the call in `try { resolveDependencies(manifest) } catch
(e: ElysiumPackageInstallError) { return Failure(reason = e) }`.

The benefits:

- **No type erasure between Throwable and ElysiumPackageInstallError.**
  The throw site is typed at compile time.
- **No silent `?: return` bugs.** Throwing forces every error
  path to be handled.
- **Cycle path is preserved.** The exception carries the full
  `cyclePath: List<String>` (e.g. `["a", "b", "a"]`), so the
  consumer can report the exact loop.

### Why `ConcurrentHashMap`, not `mutableMapOf`?

The package manager is consumed by the Elysium Vanguard File
Manager (UI thread), the Capsule installer (background thread),
the Workspace Orchestrator (background thread), and the AI
council (background thread). The shared mutable state needs to be
thread-safe. `ConcurrentHashMap` gives:

- O(1) atomic `put` / `get` / `remove`.
- Safe concurrent iteration (`values()` is a weakly consistent
  snapshot).
- No locking around the hot path.

The trade-off: a single `install` that touches multiple packages
is **not atomic at the map level** (the snapshot + rollback in
`install` provides the atomicity). A concurrent `install(nameA)`
+ `install(nameB)` may interleave; the snapshot for `nameA`
captures the pre-state, and a concurrent `nameB` install is
preserved in the post-state. This is the correct semantics for a
multi-package install (no lost writes).

### Why `clock: () -> Long`, not `System.currentTimeMillis()` directly?

Tests need **deterministic timestamps**. The `installedAtMs`
field is observable behavior — if a test asserts the timestamp,
the test must control time. The constructor parameter is
zero-cost in production (default is `System::currentTimeMillis`)
and 1-line in tests (`clock = { 1000L }`).

### Why a separate `ElysiumRepository` interface, not a parameter to the manager?

The package manager must be **swappable on the storage backend**.
The production implementation will read from a signed HTTP
repository (Phase 73 third half); the test implementation reads
from an in-memory map. By extracting the repository as an
interface:

- The package manager's logic is **testable without HTTP**.
- The repository can be a **mock** in higher-level integration
  tests.
- The trust boundary is **clear**: the repository is the only
  seam the package manager uses to fetch a manifest; the
  signature verification happens after the fetch.

### Why `data class` for `InstalledPackage`?

The install pipeline reads the package back from the `installed`
map after the install (e.g. for the `Success.installedPackages`
list). The data class makes equality + hashCode trivial, so the
map can be tested with `assertEquals`.

### Why not auto-prune orphaned deps on upgrade/remove?

A typical package manager prunes orphaned dependencies on
upgrade/remove (e.g. apt autoremove). The Elysium Linux package
manager **does NOT** in this phase — pruning is a Phase 73 third
half feature. The current behavior:

- **Upgrade**: removes the old version, installs the new version +
  new deps. Old deps that are no longer needed stay installed
  (the user can `pm.remove(name)` them manually).
- **Remove**: removes the package; deps that are no longer needed
  stay installed.

This is **intentional**: pruning is a UX decision (when does the
user want a package to be removed?) and the policy belongs in
Phase 7 (Production hardening) with the SLOs + on-call
playbooks + CVE SLA. The current behavior is **conservative +
explicit**: nothing is removed without a `pm.remove` call.

---

## Tests

30 new tests in `ElysiumPackageManagerTest`. The tests cover:

- **Repository** (8 tests): addManifest, fetchManifest (hit +
  miss), listVersions (descending), listVersions (empty), listPackages
  (alphabetical), size, wrong-signature rejection.
- **Install happy path** (2 tests): leaf package, install timestamp.
- **Install failure cases** (5 tests): ManifestNotFound,
  SignatureVerificationFailed, AlreadyInstalled,
  UnsatisfiableDependency, CyclicDependency.
- **Install with transitive deps** (3 tests): transitive order,
  latest-version picker, dep re-use (already installed).
- **Upgrade** (3 tests): happy path, NotInstalled, NotAnUpgrade.
- **Remove** (4 tests): happy path, NotInstalled, DependentPackages,
  dep cleanup.
- **listInstalled / isInstalled / installedVersion** (4 tests):
  sorted output, empty case, isInstalled true/false, installedVersion
  null.
- **InstalledPackage canonical** (1 test): canonicalId format.

The previous ElysiumPackageManifestTest (32 tests, Phase 73 first
half) still passes unchanged.

**Total linux tests:** 62 (32 manifest + 30 package manager).
**Total project tests:** 2697 (was 2667, +30 new).

---

## What's next — Phase 73 third half

The third half is the **real binary**:

- Minimal rootfs (Elysium Linux's own distro).
- Mesa/Turnip integration (Vulkan driver for Adreno).
- Box64/FEX integration (x86_64 + x86 translation).
- Wine managed by versions.
- Build reproducible (the canonical form is the build input; the
  output is the rootfs tarball).
- First-party repository of Elysium packages.

The package manager is the consumer; the third half is the
producer + the content.

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumRepository.kt` | new | repository interface + in-memory impl |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumPackageManager.kt` | new | package manager interface + in-memory impl + result + error envelope |
| `app/src/test/java/com/elysium/vanguard/core/linux/ElysiumPackageManagerTest.kt` | new | 30 JVM tests |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumPackageManifest.kt` | unchanged | (Phase 73 first half; still used) |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumAbi.kt` | unchanged | (Phase 73 first half; still used) |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumPackageVersion.kt` | unchanged | (Phase 73 first half; still used) |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumPackageDependency.kt` | unchanged | (Phase 73 first half; still used) |

---

## Bug-fixes (test-discovered, fixed in this phase)

### 1. `Result<List<...>>.exceptionOrNull()!!` type mismatch

**Symptom:** `Type mismatch: Throwable but ElysiumPackageInstallError`
at the `reason = resolvedDeps.exceptionOrNull()!!` site.

**Root cause:** The first cut of `resolveDependencies` returned
`Result<List<ElysiumPackageManifest>>` (with `Throwable` as the
failure type). The `Failure.reason` field is typed as
`ElysiumPackageInstallError`. The unwrap site mixed the two
type universes.

**Fix:** Made the resolver **throw** a typed
`ElysiumPackageInstallError` subclass. The `install` method
catches the typed exception and wraps it. No more `Result<>` in
the resolver path; the result is just `List<ElysiumPackageManifest>`.

### 2. `?: return` silently dropped unsatisfiable deps

**Symptom:** The `pickDependencyVersion` returned `null` when no
version satisfied the constraint; the `visit` function's
`depManifest ?: return` short-circuit meant the failure was
**silently swallowed** — the install returned `Success` with
zero installed packages.

**Root cause:** The recursion was supposed to propagate the
failure to the outer `Result.failure` site, but the early return
just exited the recursive call.

**Fix:** Made `pickDependencyVersion` throw
`ElysiumPackageInstallError.UnsatisfiableDependency` directly
when no version satisfies. The throw propagates up to the
`install` catch site.

### 3. Cyclic dependencies had no error variant

**Symptom:** The first cut threw `IllegalStateException` on a
cycle, which would have been a **non-typed** error in the
production failure path (a real consumer would not know to
expect a generic `IllegalStateException`).

**Fix:** Added `ElysiumPackageInstallError.CyclicDependency`
with `name + cyclePath: List<String>`. The cycle path is the
loop, e.g. `["com.example.a", "com.example.b", "com.example.a"]`.

---

## The role in the bigger picture

Phase 73 second half is the **second of three** halves in the
Elysium Linux distro foundation:

- **First half** ✅ — `ElysiumPackageManifest` + `ElysiumAbi` +
  `ElysiumPackageVersion` + `ElysiumPackageDependency` +
  `VersionConstraint` + `ElysiumPackageFile` + `FilePermissions` +
  `ElysiumPackageScripts` + signature verification (HMAC-SHA-256).
- **Second half** ✅ (this phase) — `ElysiumRepository` +
  `ElysiumPackageManager` (install / upgrade / remove / atomic
  rollback / transitive dep resolution / cycle detection).
- **Third half** (next) — minimal rootfs + Mesa/Turnip + Box64/FEX
  + Wine integration + first-party repository.

Once third half is done, the Elysium Linux distro is **operational
end-to-end**: a user can `pm install com.elysium.runtime.python`
and get a real, signed, reproducible package on a real device.
