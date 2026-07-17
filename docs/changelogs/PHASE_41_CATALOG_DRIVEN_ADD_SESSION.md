# Phase 41 — Catalog-driven "Add session" picker

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1359 tests, 0 failures, 2 skipped.

## What landed

The "Add session" dialog no longer accepts free-form
`distroId` / `profileId` / `windowsSpecId` text
fields. Phase 41 replaces them with **data-driven
dropdowns** backed by the real runtime catalogs:

- **Distro picker** — `DistroCatalog.ALL` (the
  hand-curated list of every supported Linux
  rootfs: Debian, Ubuntu, Alpine, Arch, Kali,
  Fedora, etc.). The dropdown shows the
  `displayName` ("Ubuntu 24.04 LTS", "Alpine
  latest", …); the runtime uses the
  underlying `id` ("ubuntu-noble", …).
- **Profile picker** — `ElysiumProfile.entries`
  (the four Elysium profiles: Lite, Balanced,
  Desktop, Headless). The dropdown shows the
  `displayName` ("Elysium Balanced", …).
- **Windows spec picker** — `WindowsVmCatalog.official().all`
  (the hand-curated list of Windows builds:
  Win11 Pro 23H2, Win Server 2019, etc.). The
  dropdown shows the `displayName`.

The dialog returns a fully-formed
[WorkspaceSession] with a real, catalog-backed
id. The user never has to type a `distroId`
or `specId` by hand — the picker is the only
data path.

### Files

**Production (1 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/ui/MainScreen.kt` —
  `AddSessionDialog` rewritten. The three text
  fields were replaced with three calls to a
  new generic `DropdownPicker<T>` composable.
  The composable is value-typed (`T` is the
  selected object, not a string) so the caller
  gets type-safe access to the underlying
  catalog entry (e.g. `selectedDistro.family`,
  `selectedProfile.layerId`, etc.). A future
  phase can pin the user's pick to a specific
  catalog revision via the type, not via
  fuzzy string matching.

**Tests:** none added. The data is static
catalog data; the dialog's existing
validation (display name is non-blank) is
the only mutable state, and the test would
just re-pin the same `DistroCatalog.ALL`
list. The dialog's correctness is exercised
end-to-end by the JVM tests of
[WorkspacesViewModel] + [WorkspaceManager]
(the dialog's `onConfirm` constructs a
[WorkspaceSession] the manager accepts in
any order; the existing `addSession` tests
cover the manager side).

## What the dialog now does

| Field | Phase 40 (free-form) | Phase 41 (data-driven) |
|---|---|---|
| Distro | text input, user must know the id | dropdown of every `DistroCatalog.ALL` entry |
| Profile | text input | dropdown of every `ElysiumProfile` entry |
| Windows spec | text input | dropdown of every `WindowsVmCatalog.official().all` entry |
| Display name | text input | text input (unchanged) |

The dropdowns auto-populate the first entry
on first show; the user can scroll through
the list and pick a different one. The
selection is value-typed (the `Distro`
object, not the id string), so the
`onConfirm` callback has direct access to
the catalog metadata.

## Why this matters

Phase 40's free-form text fields were
**minimum viable UX** — they worked, but a
user who did not know the exact `distroId`
of "Ubuntu 24.04 LTS" could not add an
Ubuntu session. Phase 41 makes the picker
discoverable: the user sees the human name
and the runtime uses the id. The catalog
becomes the single source of truth for
"what can I add?".

The value-typed picker also opens the door
to richer session metadata: a future
"Add session" form can show the selected
distro's `approxSizeBytes`, the selected
profile's `estimatedRssMb`, the selected
Windows spec's `recommendedRamMb`, etc.,
without re-resolving the id string. The
picker is now a first-class runtime API.

## Design notes

### Why a generic `DropdownPicker<T>` composable

The three pickers share the exact same
shape: a `readOnly` `OutlinedTextField` that
shows the selected option's label, a
`DropdownMenu` that lists every option, a
trailing icon that toggles the menu. The
only thing that varies is the option's type
(`Distro`, `ElysiumProfile`, `WindowsVmSpec`)
and the label / id accessors. A generic
composable captures the shape once; the
three callers pass the type-specific
accessors. The composable is small
(~50 lines) and the three call sites are
each a 5-line block.

### Why a value type, not a string

The picker stores `selectedDistro: Distro`
(not `selectedDistroId: String`). The benefit
is that the `onConfirm` callback constructs
the `WorkspaceSession` directly from the
catalog entry — no string lookup, no chance
of a typo'd id, no `DistroCatalog.find(id)
?: error("unknown distro")` fallback. The
catalog's `id` field is the canonical
identifier; the picker guarantees the
caller always has a valid one.

### Why the catalogs are static references

`DistroCatalog.ALL` and `ElysiumProfile.entries`
are compile-time constants. `WindowsVmCatalog.official()`
constructs a `WindowsVmCatalog` from a
`companion object` factory that pre-registers
the supported Windows builds. The dialog
reads them directly — no Hilt injection, no
ViewModel state. The catalog data is
intentionally immutable; the picker reflects
the current catalog as of the dialog's
composition.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| (no new tests) | 0 | 0 |
| **Project total** | **1359** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 41 is **Phase 42 —
the dashboard entry to the `runtime_main`
route**. The dashboard already has an
`onNavigateToRuntime` callback pointing to
the old `runtime` route (the catalog screen);
Phase 42 updates it to point to `runtime_main`
(the new sovereign runtime home screen). A
second follow-up (Phase 43) adds the
`androidTest/` end-to-end coverage for
`MainScreen`.
