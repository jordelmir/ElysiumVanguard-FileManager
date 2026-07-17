# Phase 35 — `FileWorkspaceStore` (production persistence for workspaces)

Date: 2026-07-17
Status: **Shipped** — `assembleDebug` green, 1344 tests, 0 failures, 2 skipped.

## What landed

The workspace layer (Phase 24) had a `WorkspaceStore`
interface + an `InMemoryWorkspaceStore` test impl, but
no production impl. Phase 35 adds
`FileWorkspaceStore` — a Gson-backed, file-per-workspace
store under a base directory.

Every `Workspace` is a single JSON file:
```
<baseDir>/<id>.json
```

The store is the runtime's persistence seam. The
`WorkspaceManager` composes a `WorkspaceStore`; tests
inject the in-memory variant, production injects
`FileWorkspaceStore` pointing at
`context.filesDir/workspaces`.

### Files

**Production (1 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/workspaces/FileWorkspaceStore.kt`
  — implements the `WorkspaceStore` interface. Backed by
  Gson + a private DTO layer (same pattern as
  `PaletteSerializer`). Each `save()` writes to
  `<id>.json.tmp` first, then renames over `<id>.json` —
  atomic on POSIX filesystems (Android's `ext4` /
  `f2fs` / scoped storage all support it).

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/workspaces/FileWorkspaceStoreTest.kt`
  — 19 tests pin:
  - Round-trip: save → load returns an equal
    `Workspace` (every field, every session by kind,
    state preserved, session order preserved).
  - `load` returns null for an unknown id.
  - `list` returns every saved workspace; ignores
    unrelated files (`README.md`, `*.json.tmp`) in
    the baseDir.
  - `delete` removes the file; returns true on the
    first call, false on the second; cleans up a
    stale `.json.tmp` from a crashed write.
  - `baseDir` is created lazily on construction.
  - A corrupt JSON file is treated as missing on
    `load` and skipped on `list` (cold start must
    not crash).
  - `save` does not leave a `.json.tmp` on success;
    overwrites an existing workspace.
  - On-disk format is human-readable JSON (id,
    state, kind discriminator all visible to
    `cat`).
  - Thread safety under 4 × 25 writers + 4 × 50
    readers + 4 × 10 mutators on disjoint id
    spaces; every writer's id is present at the
    end.

## Design notes

### Why a DTO layer, not Gson directly on the production class

Two reasons, both observable in the rest of the runtime:

1. **`init` blocks throw on partially-formed data.**
   `Workspace` rejects a blank id, a blank name, an
   empty session list when `state == Closed`, and
   duplicate session ids. If Gson hydrated the
   production class directly, a half-written file
   would crash the cold start. The DTO has no
   `init` checks; the mapper applies them in one
   place after the full JSON has been parsed.
2. **Schema stability.** If `Workspace` ever gains a
   field, the DTO can default it (the `WorkspaceDto`
   adds fields with defaults, the production class
   is the source of truth at runtime). If `Workspace`
   loses a field, the DTO can drop the read in
   `toDomain()` without touching the production
   class.

### Why atomic rename, not overwrite-in-place

A process crash mid-write of a 200-line JSON file
would leave a half-written workspace on disk. On
next launch, `load("ws-1")` would return null (the
parse fails), and the manager would re-create the
in-memory entry on the next save. That is a real
failure mode — the user loses every change since
the last successful save. The rename-over-existing
trick is one syscall; either the old file is there
or the new one is, never half-and-half.

### Why one file per workspace, not one big index file

The single-index-file pattern requires a read of
the whole index to list workspaces, and a write of
the whole index to save one. With a per-workspace
file, list is `listFiles` (one syscall) + parallel
parse, and save is one write + one rename. The
file count is small (the user has a handful of
workspaces, not thousands) so the directory size
is not a concern.

### Why thread safety is `synchronized` on a single lock

The store is the runtime's single point of mutation
for the workspace layer. The manager already holds a
per-workspace lock for cross-workspace state
transitions; adding a second lock layer would invite
deadlock. The store-level lock is held only across
the disk I/O, which is the only operation that needs
serialisation. Read-mostly paths (`load`, `list`) are
serialised too — the lock is cheap on Linux and the
manager's per-workspace lock is the actual concurrency
bottleneck in practice.

## What the store does

| Concern | Where it lives |
|---|---|
| The DTO | `WorkspaceDto` + `SessionDto` (private data classes) |
| The mapper | `Workspace.toDto()` + `WorkspaceDto.toDomain()` |
| The serializer | `Gson` (one shared instance) |
| The atomic write | `tmp` → `renameTo(target)` |
| The baseDir lifecycle | `mkdirs()` on construction; `save` / `delete` never need to create |
| The lock | `synchronized(lock)` on every public method |
| The cold-start safety | `JsonSyntaxException` and `IllegalStateException` are caught → `load` returns null / `list` skips the file |

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `FileWorkspaceStoreTest` | 19 (new) | 0 |
| **Project total** | **1344** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 35 is **a Hilt module
that wires the runtime's collaborators end-to-end
(`RuntimeModule`)**:
- `WorkspaceStore` → `FileWorkspaceStore`
  (`Context.filesDir/workspaces`)
- `WindowsVmBackend` → production QEMU backend
- `RuntimeEventBus` → production bus +
  `BusToLogAdapter` to the existing
  `RuntimeEventLog`
- `SessionRunner` → `SessionRunnerRegistry` (the
  Phase 32 dispatcher)
- `MainScreenViewModel` and `WorkspacesViewModel`
  → `@HiltViewModel` + `@Inject constructor`

Until Phase 36, the two new ViewModels cannot be
instantiated in production (no DI graph), so the
Compose UI is blocked. Phase 36 is the small,
foundational step that unblocks every later
runtime-UI phase.
