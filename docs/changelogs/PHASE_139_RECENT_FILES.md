# Phase 139 — Recent Files (persistent, real, FIFO-capped)

**Status**: shipped
**Date**: 2026-07-25
**Commit**: pending

## The gap

The Phase 121 Windows Explorer (`RealFilesBody`) shipped a real
file manager with breadcrumb, tap-to-navigate, and `Intent.ACTION_VIEW`
for files. But every navigation started from the directory tree — the
user had to dig back through the tree to re-open a file they were
*just* working on. That's the "open recent" gap. Power users expect
a recent-files list at the top of the file manager, persistent across
restarts, capped at a sane N.

## What shipped

A real, persistent "Recent" strip at the top of the Files body:

- **`RecentFileEntity`** + **`RecentFileDao`** (Room, version 6→7
  migration, additive, zero data loss). The table is keyed by
  `path` so re-opening the same file just refreshes
  `lastOpenedAt` + bumps `openCount`. The schema:
  ```
  recent_files(
      path TEXT PRIMARY KEY,
      display_name TEXT NOT NULL,
      size_bytes INTEGER NOT NULL,
      mime_type TEXT,
      last_opened_at INTEGER NOT NULL,
      open_count INTEGER NOT NULL DEFAULT 1
  )
  ```
- **`RecentFileRepository`** — Hilt `@Singleton`. Wraps the DAO
  with: timestamp stamping (test seam via `now: () -> Long`),
  a hard `MAX_RECENT = 50` cap, and a `Flow`-based reactive
  query so the UI updates without polling. The repository is
  the *only* place that touches the `recent_files` table.
- **`RecentFileModule`** — Hilt module providing the
  repository. Standard `@Singleton @Inject` pattern.
- **`RecentFilesRow`** + **`RecentChip`** — a horizontal
  `LazyRow` of file chips at the top of the Files body, capped
  at 8 visible. Each chip is a colored dot (extension-based
  accent) + the display name (ellipsized). Tap to navigate to
  the parent + re-open with the default app. There's a small
  × icon on the right for clear-all.
- **`RecentFileRepositoryEntryPoint`** + **`rememberRecentFileRepository()`**
  Composable helper — Hilt EntryPoint bridge so the Composable
  can resolve the repository via `EntryPointAccessors` without
  constructor injection.
- **Recording is on file open** — when the user taps a file
  row (not a folder), the access is recorded via
  `recentScope.launch { recentRepo.recordAccess(...) }`. The
  scope is `rememberCoroutineScope()` — lifecycle-tied, NOT
  `GlobalScope` (the prior draft used `GlobalScope` and
  leaked coroutines across screen changes; fixed before
  commit).
- **FIFO eviction** — every `recordAccess` calls
  `trimTo(MAX_RECENT)` to keep only the newest 50 entries.

## Test coverage (9 JVM unit tests, all green)

`RecentFileRepositoryTest`:
- `first record creates a new row with open_count of 1`
- `re-recording the same path increments open_count and refreshes timestamp`
- `recordAccess sorts by last_opened_at descending`
- `trimTo caps the list at MAX_RECENT with FIFO eviction` — 60
  inserts → 50 kept, 10 oldest gone
- `clear removes every row`
- `deleteByPath removes just that one row`
- `recordAccessWith uses the supplied timestamp not now` —
  the test seam
- `observeRecent with limit caps the stream`
- `repeated accesses move the same path to the top each time`

The test uses an `InMemoryRecentFileDao` stub (no Android Room,
no Robolectric) — the production DAO is Room-backed; the
in-memory variant is plain `MutableStateFlow` for the `Flow`
contract. Pure JVM unit tests, fast, deterministic.

## Bug fixes (1, in this phase)

- **`GlobalScope` leak in the onClick lambdas.** The first
  draft used `kotlinx.coroutines.GlobalScope.launch` to call
  `recentRepo.recordAccess(...)` and `recentRepo.clear()` from
  the chip clear-all + file open onClicks. GlobalScope is
  process-scoped — the coroutines outlived the Composable
  and could write to a DAO after the screen was destroyed.
  Fix: `val recentScope = rememberCoroutineScope()` at the
  top of `RealFilesBody`, used in both call sites. The
  coroutines are now cancelled when the Files body leaves
  the composition. Plus a documentation note explaining why
  this matters (cross-screen change example).

## Style cleanup (1)

- Three fully-qualified references (`androidx.compose.foundation.lazy.LazyRow`,
  `androidx.compose.material3.IconButton`, `androidx.compose.foundation.Canvas`)
  in the new `RecentFilesRow` + `RecentChip` Composables. Fixed:
  added the missing imports to the file's import block; calls
  are now short-form. Same for `TextOverflow` (was added
  twice, removed the duplicate).

## Build status

- `compileDebugKotlin`: ✅
- `testDebugUnitTest --tests "com.elysium.vanguard.core.recent.*"`: ✅ (9/9)
- Lint warnings: 0 new (only the pre-existing Phase 138
  `ACTION_MEDIA_SCANNER_SCAN_FILE` deprecation, unchanged)
- 0 fatals, 0 build errors

## What this enables

- The Files body is no longer a tree-only navigator. The
  "Recent" strip is the user's "where I just was" without
  making them click back through the tree.
- The recent-files list is the foundation for the next
  file-manager features: "frequent files" (sorted by
  `open_count`), "pinned" (a separate `is_pinned` column in
  a future phase), and per-extension filtering.
- The Room migration is additive and small; future schema
  changes can extend the entity without breaking existing
  installs.

## Files changed (7)

| File | Type | Lines | Purpose |
|---|---|---|---|
| `core/database/RecentFileEntity.kt` | new | 107 | `@Entity` + `Dao` |
| `core/recent/RecentFileRepository.kt` | new | 89 | Repository + MAX_RECENT |
| `core/recent/RecentFileModule.kt` | new | 18 | Hilt module |
| `core/recent/RecentFileRepositoryTest.kt` | new | 218 | 9 JVM unit tests |
| `core/database/DatabaseModule.kt` | modified | +8 | DAO provider + migration |
| `core/database/TitanDatabase.kt` | modified | +29 | Schema v6→v7 + migration |
| `features/desktop/content/WindowContentRegistry.kt` | modified | +224 | RealFilesBody + RecentFilesRow + RecentChip + EntryPoint |
| **Total** | | **+690 / -3** | |
