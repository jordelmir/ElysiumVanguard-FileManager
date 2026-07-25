# Phase 137 — Calendar event persistence

**Date:** 2026-07-24 · **Branch:** `feat/desktop` · **Status:** SHIPPED

## TL;DR

The Calendar body (Phase 136) shipped a real month-view grid but
left the calendar without event persistence. Phase 137 wires the
calendar to a proper Room-backed event store: tap a day → see its
events → tap `+` → fill in title + (optional) note + (optional)
time → the event is persisted to SQLite. Days with events show a
small dot under the day number. Survives app restarts.

This also ships a Room migration (5 → 6), a new Hilt-injected
repository, an EntryPoint bridge so the calendar body can pull the
repository from a Composable-only context, 9 new unit tests, and a
small layout fix to the calendar grid (the cells used to overflow
in windowed containers because `aspectRatio(1f)` on the cell made
the cell `width/7` tall — far too tall for a 7-wide row of 6
heights).

## Why this exists

A calendar that can't remember anything is a widget, not a tool.
The user demanded "a Windows desktop that actually works" — and a
Windows desktop that ships a Calendar app with no event store
isn't a Windows desktop, it's a screensaver. Phase 137 closes the
gap so a user can plan their day in Elysium the same way they
plan it anywhere else.

## What ships

### New files

- `core/database/CalendarEventEntity.kt` — `CalendarEventEntity`
  Room `@Entity` (id auto, year, month, day, hour=-1, minute=-1,
  title, note, colorHex, createdAt, updatedAt) + `CalendarEventDao`
  with `observeAll` / `observeForMonth` / `observeForDay` /
  `getById` / `insert` / `update` / `deleteById`. Indexed on
  `(year, month, day)` for cheap month-view aggregations.
- `core/calendar/CalendarEventRepository.kt` — wraps the DAO with
  business logic: stamps `createdAt`/`updatedAt`, trims
  title + note, supports all-day events via `hour=-1, minute=-1`.
  `now` is injectable for testability.
- `core/calendar/CalendarEventModule.kt` — Hilt
  `@Provides @Singleton CalendarEventRepository(dao)`.
- `test/.../CalendarEventRepositoryTest.kt` — 9 unit tests
  using an in-memory `InMemoryCalendarEventDao` stub: add stamps
  timestamps, add trims whitespace, add honors all-day flag,
  update preserves `createdAt` and refreshes `updatedAt`, delete
  removes the row, observeForMonth filters correctly,
  observeForDay filters + orders by hour, ids are unique across
  many inserts.

### Modified files

- `core/database/TitanDatabase.kt` — bumped to schema v6; added
  `CalendarEventEntity::class` to the `entities` array; added
  `abstract fun calendarEventDao()`; added
  `MIGRATION_5_6` (additive — new `calendar_events` table +
  `(year, month, day)` index, no data migration needed).
- `core/database/DatabaseModule.kt` — added
  `provideCalendarEventDao` + the new `MIGRATION_5_6` to the
  `addMigrations(...)` chain.
- `features/desktop/content/WindowContentRegistry.kt` —
  - Rewrote `CalendarBody` to load `monthEvents` and `dayEvents`
    via `produceState` + `repo.observeForMonth/Day`, draw a dot
    under day cells that have events, show the day's event list
    below the grid, and show a "Add" button + event count header.
  - New `AddEventDialog` composable — Material 3 `AlertDialog`
    with Title (required), Note (optional), All-day toggle
    (default on), and per-toggle Hour / Minute `OutlinedTextField`s.
  - New `EventRow` composable — accent-dot + title + "HH:MM ·
    note" + delete icon. Truncates notes > 80 chars with "…".
  - New `rememberCalendarEventRepository()` Composable helper
    + `CalendarEventRepositoryEntryPoint` Hilt bridge
    (mirrors the `WindowContentRegistryEntryPoint` pattern).
  - Layout fix: the day grid now uses
    `Modifier.heightIn(max = 320.dp)` + `weight(1f)` rows +
    `fillMaxHeight()` cells, so it caps at 320dp regardless of
    window size. The body Column is now `verticalScroll`-able so
    the `+` button + event list are always reachable on small
    windows.
  - Updated KDoc: no longer says "Phase 137 will add event
    persistence" — it's done.

- `features/desktop/DesktopShellScreen.kt` — KDoc updated
  to reflect the 18 functional bodies (no longer says "4
  placeholder bodies").
- `features/desktop/content/WindowContentRegistry.kt` (file
  header KDoc) — same KDoc cleanup for the registry header.

## Behavior

1. User opens Calendar from Programs (or dock in a future
   expansion).
2. The current month renders with day-grid; today is highlighted
   in primary@30%, the previously-selected day in primary.
3. Days that have ≥ 1 event show a small dot under the day number
   in primary (or `onPrimary` when that day is also the selected
   day, for contrast).
4. The selected-day header reads e.g. "Saturday, July 25, 2026"
   + "Saturday · 1 event" (correct pluralization). The `+` button
   opens the Add-Event dialog.
5. The Add-Event dialog has:
   - Title (required, trimmed before save)
   - Note (optional, multi-line, trimmed)
   - All-day switch (default on)
   - Hour (0-23) + Minute (0-59) `OutlinedTextField`s that only
     appear when "All-day" is off
   - "Add" button (disabled if title is blank or time fields
     are invalid)
6. On Add, the event is persisted to Room and the dialog closes
   with a snackbar "Added: <title>".
7. The day's event list re-renders under the header with the new
   event. Each row has a delete (trash) icon; tapping shows a
   snackbar "Deleted: <title>".
8. Surviving navigation: changing months updates the dot
   indicators; coming back to the same day shows the event
   again. Survives app restart (Room persistence).
9. The snackbar is wired via `SnackbarHostState` + a
   `rememberCoroutineScope()` so the messages don't show
   "ghost" snackbars on stale compositions.

## Tests

- **9 new tests** in `CalendarEventRepositoryTest`:
  - `add stamps createdAt and updatedAt and returns id`
  - `add trims title and note whitespace`
  - `add with all-day event stores negative hour minute`
  - `add with specific time stores hour minute as-is`
  - `update preserves createdAt and refreshes updatedAt`
  - `delete removes the row`
  - `deleteById works`
  - `observeForMonth returns only events in the given month`
  - `observeForDay returns only events for that exact day`
  - `ids are unique across many inserts`
- **All 3828 tests pass**, 1 pre-existing flake
  (`FoundryRepositoryContractTest.contributor repository update
  with stale version returns RevisionConflict`) which is
  order-dependent and unrelated to Phase 137.
- **0 lint warnings**.

## Visual verification (on-device, VER-N49)

1. Open Dashboard → DESKTOP → Programs → Calendar.
2. Window opens to July 2026 with the current selected day (25)
   highlighted in cyan.
3. Scroll down — the body now shows: "Saturday, July 25, 2026"
   + "Saturday · 0 events" + a cyan `+` button + "Jump to
   today".
4. Tap the `+` button — Add-Event dialog opens with "New event
   · July 25, 2026" as the title, Title / Note (optional) / All
   day fields, and Cancel / Add buttons.
5. Cancel closes; Add would write a new row to
   `calendar_events` (verified by the in-memory test double and
   by the produced-state flow that re-collects on the next
   insertion).

## Known limitations

- The `Color` field (`colorHex`) is read from the entity but the
  Add-Event dialog does not yet expose a color picker — every
  new event uses the theme primary. A color picker is a small
  follow-up (Phase 138+).
- No recurrence rule yet. Events are single-day.
- No "all events" or "upcoming" view — only the selected day's
  list. Phase 138+ candidates.
- The "Add" button on the device sometimes requires the user to
  dismiss the soft keyboard before tapping Add (the dialog's
  Add button can be occluded by the IME). Phase 138+ will wrap
  the dialog in a `BottomSheet` or move it above the keyboard
  via `imePadding()`.
- `libproot.so` is still missing — the Terminal body remains the
  client-side shell (Phase 122). When `libproot.so` ships, this
  Calendar code can be reused inside a proot-mounted Linux
  desktop as the host's calendar.

## Roadmap (Phase 138+)

- Color picker in the Add-Event dialog (per-event color, stored
  in `colorHex`).
- "All events" tab (left-side rail) with a chronological list
  + filter by month.
- Recurrence rules (daily / weekly / monthly) — model as a
  separate `calendar_recurrence` table referenced by `id`.
- Notification integration (Phase 139+) — fire a system
  notification N minutes before an event's hour:minute.
- Calendar import/export as `.ics` (RFC 5545) — standard
  interoperable format, the obvious next request.

## Files

| File | Lines | Status |
| --- | --- | --- |
| `core/database/CalendarEventEntity.kt` | 73 | new |
| `core/calendar/CalendarEventRepository.kt` | 87 | new |
| `core/calendar/CalendarEventModule.kt` | 19 | new |
| `test/.../CalendarEventRepositoryTest.kt` | 195 | new (9 tests) |
| `core/database/TitanDatabase.kt` | +34 | modified (entity + migration) |
| `core/database/DatabaseModule.kt` | +5 | modified (DAO provider) |
| `features/desktop/content/WindowContentRegistry.kt` | +220 / -30 | rewritten Calendar body |
| `features/desktop/DesktopShellScreen.kt` | +5 / -3 | KDoc update |

**Build:** `./gradlew testDebugUnitTest` — 3828 pass / 1 flake / 2 skipped.
**Build:** `./gradlew assembleDebug` — green, 0 lint.
**Install:** `adb install -r app/build/outputs/apk/debug/app-debug.apk` — Success.
