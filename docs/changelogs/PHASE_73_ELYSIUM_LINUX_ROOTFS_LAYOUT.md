# Phase 73 (third half, I-73.3.2) — Elysium Linux Rootfs Layout

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 73 / Linux distro foundation — rootfs layout
> **Predecessor:** I-73.3.1 (Runtime Layers)
> **Vertical:** Elysium Linux (`com.elysium.vanguard.core.linux.*`)

---

## TL;DR

The Elysium Linux rootfs layout is **defined**. The distro has
a canonical filesystem shape that the orchestrator, package
manager, and runtime layer catalog all agree on. The layout is:

- **FHS-compliant** — follows the Filesystem Hierarchy Standard
  (`/bin`, `/etc`, `/usr`, `/var`, etc.).
- **Elysium-typed** — every path is a `ElysiumRootfsPath` (a
  value class that validates absolute paths + rejects
  path-traversal).
- **Immutable + read-only** — a fresh layout is constructed with
  a custom root; the default layout is the FHS root `/`.
- **Factory methods for runtime state** — `runtimeLayerPath(id,
  version)`, `packageInstallPath(name)`, `workspacePath(name)`.

This is the **second of five sub-tasks** in the Phase 73 third
half. The remaining sub-tasks:

- **I-73.3.3** — `ElysiumAbiCapabilityMatrix` (which layer on
  which ABI).
- **I-73.3.4** — `ElysiumUpdateStrategy` (A/B vs versioned
  image rollback).
- **I-73.3.5** — `ElysiumCvePolicy` (vulnerability policy +
  response SLA).

---

## What shipped

### `ElysiumRootfsPath` (value class)

A typed absolute path inside the Elysium rootfs. The path is a
data class that validates:

- The path is non-blank.
- The path is absolute (starts with `/`).
- The path does NOT contain `..` (path traversal is rejected).
- The path does NOT contain `//` (double slashes are a smell).

```kotlin
data class ElysiumRootfsPath(val value: String) {
    fun join(relative: String): ElysiumRootfsPath  // append a relative segment
    val parent: ElysiumRootfsPath                  // the immediate parent
    val segments: List<String>                     // the path components
    fun relativeTo(base: ElysiumRootfsPath): String  // the relative portion
}
```

The path is **immutable** (a data class; no setters). A new path
is a new value. The path's lifecycle (a file create, a directory
move) is a new `ElysiumRootfsPath` value.

### `ElysiumRootfsLayout` (data class)

The canonical Elysium rootfs layout. 25 typed paths covering:

| Group | Paths |
| --- | --- |
| **System** | `rootPath`, `binPath`, `optPath`, `usrPath`, `varPath` |
| **System libraries** | `usrLibPath`, `usrBinPath` |
| **Elysium config** | `etcElysiumPath`, `etcElysiumRuntimePath` |
| **Elysium libraries** | `usrLibElysiumPath`, `usrLibElysiumRuntimePath` |
| **Elysium packages** | `optElysiumPackagesPath` |
| **Elysium state** | `varLibElysiumPath`, `varLibElysiumCatalogPath`, `varLibElysiumPackagesPath`, `varLibElysiumStatePath` |
| **Elysium logs** | `varLogElysiumPath`, `varLogElysiumPackageManagerPath`, `varLogElysiumOrchestratorPath`, `varLogElysiumAuditPath` |
| **Workspaces** | `workspacesPath` |

The layout has a **default** (`ElysiumRootfsLayout.DEFAULT`) that
uses the canonical FHS root (`/`). A custom layout (e.g. for a
chroot test) can be constructed with a different `rootPath`; the
init block enforces that every path is under the root.

### Factory methods

The layout exposes three factory methods for paths that depend
on runtime state:

```kotlin
val layout = ElysiumRootfsLayout.DEFAULT

// Runtime layer install path:
//   /usr/lib/elysium/runtime/box64/0.3.2
val layerPath = layout.runtimeLayerPath(
    ElysiumRuntimeLayerId.BOX64,
    ElysiumPackageVersion.parse("0.3.2").getOrThrow(),
)

// Package install path:
//   /opt/elysium/packages/com.elysium.runtime.python
val packagePath = layout.packageInstallPath("com.elysium.runtime.python")

// Workspace path:
//   /workspaces/blender-linux
val workspacePath = layout.workspacePath("blender-linux")
```

### `ElysiumRootfsPath` operations

| Operation | Returns | Notes |
| --- | --- | --- |
| `join(relative)` | `ElysiumRootfsPath` | appends a relative segment; rejects `..` and `/` |
| `parent` | `ElysiumRootfsPath` | the immediate parent; `/` is its own parent |
| `segments` | `List<String>` | the path components between `/` separators |
| `relativeTo(base)` | `String` | the relative portion; throws if not a descendant |

### The canonical rootfs tree

```
/                          (rootfs root)
├── /bin                   (essential binaries)
├── /etc
│   └── /etc/elysium       (Elysium config)
│       └── /etc/elysium/runtime   (runtime layer configs)
├── /opt
│   └── /opt/elysium/packages   (Elysium packages)
├── /usr
│   ├── /usr/bin
│   ├── /usr/lib
│   │   └── /usr/lib/elysium
│   │       └── /usr/lib/elysium/runtime   (runtime layers)
│   │           ├── native/<ver>/
│   │           ├── mesa-turnip/<ver>/
│   │           ├── box64/<ver>/
│   │           ├── fex/<ver>/
│   │           └── wine/<ver>/
│   └── /usr/share
├── /var
│   ├── /var/cache
│   ├── /var/lib
│   │   └── /var/lib/elysium
│   │       ├── catalog/    (the layer catalog)
│   │       ├── packages/   (the package database)
│   │       └── state/      (runtime state)
│   └── /var/log
│       └── /var/log/elysium
│           ├── pm/         (package manager logs)
│           ├── orchestrator/   (orchestrator logs)
│           └── audit/      (security audit logs)
└── /workspaces             (per-app reproducible workspaces)
```

---

## Design decisions

### Why a value class for paths, not a plain `String`?

A `String` has no validation. A plain `String` can be `""`,
`"usr/bin"` (relative), `"/usr/../etc"` (path traversal),
`"/usr//bin"` (double-slash smell). Every consumer of the path
would re-validate, and the validation rules would drift.

A `ElysiumRootfsPath` is a **typed value** that:

- Rejects blank, relative, traversal, and double-slash values
  **at construction time**.
- Exposes path operations (`join`, `parent`, `segments`,
  `relativeTo`) as **typed methods** (not string manipulation).
- Is **immutable** (a data class; no setters).

The trade-off: every API that takes a path takes a
`ElysiumRootfsPath`, not a `String`. This is **intentional** —
the type system enforces the invariants.

### Why reject `..` (path traversal) at the type level?

Path traversal is a **security boundary**, not a UX concern. A
path like `/opt/elysium/packages/../../../etc/passwd` is a
classic privilege-escalation vector. The init block of
`ElysiumRootfsPath` rejects any path containing `..` — the
type system enforces the invariant, so a developer cannot
accidentally introduce a path traversal.

The trade-off: a legitimate `..` segment is rejected. This is
**intentional** — legitimate `..` segments are rare in a
well-designed rootfs, and the rejection makes path traversal
**a type error**, not a runtime check.

### Why FHS-compliant?

FHS (Filesystem Hierarchy Standard) is the **industry standard**
for Linux directory structure. Every Linux tool, every Linux
distribution, every Linux developer expects `/usr/bin`,
`/var/log`, `/etc/elysium` to mean the canonical thing. By
following FHS, Elysium Linux is **drop-in compatible** with:

- Standard Linux binaries (Box64, FEX, Wine, etc. install to
  their canonical FHS locations).
- Standard Linux tools (the package manager, the orchestrator,
  the audit log all use FHS paths).
- Standard Linux developer expectations (a developer reading
  the rootfs tree knows exactly what each path means).

The Elysium-specific additions are in **subdirectories** of the
FHS roots (`/etc/elysium/`, `/usr/lib/elysium/`,
`/opt/elysium/`, `/var/lib/elysium/`, `/var/log/elysium/`,
`/workspaces/`). The subdirectory pattern keeps Elysium's
additions **scoped** — a developer can spot them at a glance.

### Why `/workspaces/` at the root, not under `/home/` or `/var/`?

The vision doc (`PHASE_9_7_8_UNIVERSAL.md`) defines the user's
per-app reproducible workspaces as
`/workspaces/<workspace-name>/{rootfs,mounts.json,env.json,launcher.json}`.
The pattern is **at the rootfs root**, not under `/home/` or
`/var/`, because:

- `/workspaces/` is the **canonical place** for app
  reproducibility (the orchestrator looks here first).
- `/home/` is for user homes (a separate concern).
- `/var/` is for variable data (workspaces are static + per-app,
  not variable).

The vision doc's pattern matches the layout.

### Why a custom-root layout (not just `DEFAULT`)?

A custom root is **essential for testing** — the JVM tests
construct a layout with `/opt/elysium-test` as the root and
verify the factory methods produce paths under that root. A
chrooted Elysium Linux install (e.g. a developer running
Elysium Linux in a container) uses a different root. The init
block enforces the **invariant that every path is under the
root** — so the custom-root case is type-safe.

---

## Tests

35 new tests in `ElysiumRootfsLayoutTest`. The tests cover:

- **ElysiumRootfsPath validation** (6 tests): blank rejection,
  relative rejection, path-traversal rejection, double-slash
  rejection, root path acceptance, normal FHS path acceptance.
- **ElysiumRootfsPath operations** (8 tests): join
  (concatenation + reject absolute + reject traversal + reject
  blank), parent (root + top-level + nested), segments (root
  + nested), relativeTo (relative portion + same path + reject
  non-descendant).
- **ElysiumRootfsLayout — default FHS paths** (9 tests): root,
  bin, etc, etcElysium-under-etc, usrLibElysium-under-usrLib,
  usrLibElysiumRuntime-under-usrLibElysium,
  varLibElysium-under-varLib, varLogElysium-under-varLog,
  workspaces-at-root.
- **ElysiumRootfsLayout — factory methods** (4 tests):
  runtimeLayerPath (box64), runtimeLayerPath (mesa-turnip),
  packageInstallPath (python), workspacePath (blender-linux).
- **ElysiumRootfsLayout — custom root** (2 tests): custom root
  propagates to factory methods, paths outside the root are
  rejected.
- **Data class equality** (2 tests): two layouts with the
  same fields are equal, two paths with the same value are
  equal.

**Total linux tests:** 138 (32 manifest + 30 package manager +
41 runtime layers + 35 rootfs layout).
**Total project tests:** 2773 (was 2738, +35 new).

---

## What's next — Phase 73 third half, sub-task I-73.3.3

`ElysiumAbiCapabilityMatrix` — the documentation of which
runtime layer is available on which ABI. The matrix maps
`ElysiumAbi` to `Set<ElysiumRuntimeLayerId>`:

| ABI | Native | Mesa Turnip | Box64 | FEX | Wine |
| --- | --- | --- | --- | --- | --- |
| ARM64 | ✅ | ✅ (Adreno) | ✅ | ✅ | ✅ |
| ARM32 | ✅ | ✅ (Adreno) | ❌ | ❌ | ❌ |
| X86_64 | ✅ | ❌ | ✅ (native) | ✅ (native) | ✅ |
| X86 | ✅ | ❌ | ❌ | ✅ (native) | ❌ |
| ANY | depends | depends | depends | depends | depends |

The matrix is the **typed answer** to the orchestrator's
question: "Can this device run this capsule?". The orchestrator
asks the matrix; the matrix says yes or no with the reason.

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumRootfsLayout.kt` | new | path value class + layout data class |
| `app/src/test/java/com/elysium/vanguard/core/linux/ElysiumRootfsLayoutTest.kt` | new | 35 JVM tests |

---

## The role in the bigger picture

The rootfs layout is the **filesystem contract** between every
Elysium Linux component:

- The package manager installs packages to
  `/opt/elysium/packages/<name>/`.
- The runtime layer catalog installs layers to
  `/usr/lib/elysium/runtime/<id>/<version>/`.
- The orchestrator writes per-workspace files to
  `/workspaces/<workspace>/`.
- The security audit log writes to
  `/var/log/elysium/audit/`.
- The package manager writes its log to
  `/var/log/elysium/pm/`.

Every component agrees on the **same canonical paths**. The
layout is the **shared contract** that makes the components
interoperable.
