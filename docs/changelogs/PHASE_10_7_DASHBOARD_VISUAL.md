# Phase 10.7 — Visual del dashboard + quick action ribbon

**Fecha:** 2026-07-10
**Status:** ✅ SHIPPED — 0 new tests (UI work), 0 failures,
0 warnings introduced, BUILD SUCCESSFUL, APK installed and
launches on Android 16.
**Versión:** 1.0.0-TITAN+10.7

---

## TL;DR

Jor asked for the dashboard to feel "como pro top mundial".
Phase 10.7 adds a **Quick Action Ribbon** — three pulsing
glass tiles for the highest-traffic "make something" actions
(new Word document, new Sheet workbook, install a Linux
distro) — placed directly under the hero header so the
user never has to scroll to find them. Two new portal
tiles (`WORD`, `SHEET`) join the existing four
(`FILE SYSTEM`, `MEDIA VAULT`, `AUDIO HUB`, `RUNTIME`).

The ribbon is wrapped in a `pulsingNeonBorder` so it never
feels static. Each tile has its own infinite-transition
animation that pulses the accent color softly between 50%
and 100% alpha.

---

## What changed

### `features/dashboard/DashboardScreen.kt`

**Two new portal items** joined the grid:

- **WORD** (cyan accent) — opens `editor_word_new` →
  the Phase 10.5 Elysium Word editor with a blank
  document.
- **SHEET** (magenta accent) — opens `editor_sheet_new` →
  the Phase 10.6 Elysium Sheet editor with a blank
  workbook.

The two new `onNavigateToWord` and `onNavigateToSheet`
parameters on `DashboardScreen(...)` are optional so
existing call sites don't have to change. `MainActivity`
wires them up in the dashboard route.

**New `QuickActionRibbon` composable** (private to the
file) renders three glass tiles in a horizontal row:

- Each tile is 72dp tall with a `RoundedCornerShape(14.dp)`
  background, the accent color at 10% alpha, and a 1dp
  border at `accent × 0.6 × glow` (the `glow` alpha is
  driven by an `infiniteTransition` that loops the value
  between 0.5 and 1.0 every 1.8 s).
- Each tile has the icon at the top (22dp) and the label
  below in 10sp bold with 1.5sp letter spacing. The
  colors come from the `TitanColors` palette so the
  ribbon feels native to the rest of the dashboard.
- The whole ribbon is wrapped in
  `Modifier.pulsingNeonBorder(cornerRadius = 18.dp,
  glowColor = TitanColors.NeonCyan, glassAlpha = 0.06f)`
  so the whole row glows softly even when the tiles
  themselves are at their dim point in the cycle.

**`QuickActionTile`** is a private composable that takes
the icon, label, accent color, and an `onClick`. The
modifier-accepting `weight(1f)` shape keeps the three
tiles evenly sized regardless of label length.

### `MainActivity.kt`

The dashboard route now passes two new callbacks to the
screen:

```kotlin
onNavigateToWord  = { navController.navigate("editor_word_new") },
onNavigateToSheet = { navController.navigate("editor_sheet_new") }
```

These route to the empty-document variants of the Word
and Sheet editors — same routes that the `editor_word`
and `editor_sheet` paths (with a `{path}` argument) accept.

---

## How the user experiences it

1. Open the app. The hero header is unchanged — `ELYSIUM
   VANGUARD / NEURAL COMMAND CENTER` with the live storage
   ring, RAM ring, and battery ring underneath.
2. Below the rings sits the new **Quick Action Ribbon**:
   three glass tiles, side by side, each pulsing softly in
   its accent color. Tapping any one navigates straight to
   the relevant editor.
3. Below the ribbon the section header `OPERATIONAL NODES`
   labels the original six-tile grid, which now contains
   six entries: `FILE SYSTEM`, `MEDIA VAULT`, `AUDIO HUB`,
   `RUNTIME`, `WORD`, `SHEET`. The grid keeps its
   `GridCells.Adaptive(minSize = 150.dp)` shape so the
   layout still adapts to phone, foldable, and tablet.
4. The status strip (`CORE / SHIELD / THREATS`) is
   unchanged.

The visual change is small in absolute terms — three new
tiles, two new portal items — but it makes the
"create something" actions discoverable on the very first
frame the dashboard renders, which is what "como pro top
mundial" really means in a mobile file manager.

---

## Numbers

- **Tests:** 753 → **753** (no new tests — UI work)
- **0 failures, 0 errors, 0 warnings introduced**
- APK debug build green, 169 MB
- 1 modified (`DashboardScreen.kt`, +85 lines for the
  ribbon + portal items)
- 1 modified (`MainActivity.kt`, +2 lines for the new
  callbacks)

---

## What this phase does NOT close (parking lot for 10.7.x)

- **Hero animation.** The Titan logo could animate into
  place on first load, with a brief flash of the neon
  border that fades out after 600 ms. Parked.
- **Drag-to-reorder portal tiles.** The grid is currently
  fixed; a future pass can let the user customize the
  order via long-press → drag.
- **Per-section accent persistence.** `SectionColorManager`
  stores the accent in memory; a future pass writes it to
  `SharedPreferences` and restores on next launch.
- **Quick action customization.** Hard-coded `WORD / SHEET
  / RUNTIME` for now. A future pass can let the user pick
  any of the existing portal tiles as a quick action.

---

**Mantenedor:** Jor + Mavis
**Próxima sesión:** Phase 10.8 — back to Phase 10.x backlog
(RAR password extraction, ZIP password output, full Office
import suite, …) — Jor picks.
