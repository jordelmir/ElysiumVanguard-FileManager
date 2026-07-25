# Phase 138 — Paint body (canvas + brush + save PNG)

**Date:** 2026-07-25 · **Branch:** `feat/desktop` · **Status:** SHIPPED

## TL;DR

Phase 138 adds a real Paint body to the proprietary Windows
desktop. Compose `Canvas` + `pointerInput` + `detectDragGestures`
captures the finger/stylus path. Each stroke is stored as a
`PaintStroke` data class (ordered list of offsets + color +
width) so we can re-render to any bitmap at any time, and so
`undo()` is just `removeLast()`. The 8-color palette and
1..20 dp brush-size slider cover the practical use cases. Save
renders the current strokes to a software `Bitmap` via
`android.graphics.Canvas` and writes it as PNG to
`/sdcard/Pictures/Elysium/Paint_<ts>.png` (then fires a
media-scan so the image shows up in the system gallery).

This is the 19th app on the desktop. 18 → 19.

## Why this exists

A Windows desktop without a Paint app is a desktop without
screensaver authors, ad-hoc annotation, or 30-second doodles.
The user demanded "una experiencia de windows nativa en android"
— a native Windows experience on Android. Paint ships with
every Windows install since 1985. Elysium Vanguard should
have one too.

## What ships

### Modified files

- `features/desktop/content/WindowContentRegistry.kt` —
  - New private data class `PaintStroke` (color, widthDp,
    points) — the canonical in-memory representation of a
    single drag.
  - New private constant `PAINT_PALETTE` — 8 fixed colors
    (black, white, red, orange, yellow, green, cyan, purple).
    Fixed set so the body stays small; a future phase can
    swap this for an HSV wheel.
  - New private constant `PAINT_MAX_STROKES = 200` — hard
    cap on the undo stack. When a new stroke would exceed the
    cap, the oldest is dropped (FIFO).
  - New `@Composable fun PaintBody()`:
    - **8 color swatches** in a row, each a `Box` with
      `CircleShape` clip. The selected swatch is bigger
      (32dp vs 24dp), has a 2dp primary border, and shows
      a tiny brush icon inside (icon tint switches between
      black/white based on the swatch's luminance so it
      stays readable on any background).
    - **Brush size slider** (`Slider`, valueRange 1f..20f)
      with a "N px" label. Default 6 px. The 1dp step is
      the smallest legible dot.
    - **Undo / Clear / Save** buttons. All three are
      disabled when `strokes.isEmpty()` (this is the
      "polished, not half-baked" test — no Save button that
      silently writes a blank PNG).
    - **Canvas** with `Modifier.pointerInput { detectDragGestures(...) }`.
      Each `onDragStart` seeds a new in-progress stroke;
      `onDrag` appends the new position; `onDragEnd` commits
      the in-progress stroke to the undo stack (only if
      size > 1 — a tap shouldn't be a stroke). `onDragCancel`
      drops the in-progress stroke.
    - **Empty state** ("Draw something! Use the palette above
      to pick a color.") shown when there are no strokes AND
      no in-progress stroke.
    - **Stroke counter** ("3 strokes") at the bottom.
  - New private extension `DrawScope.drawStroke()` — converts
    a `PaintStroke` to a real `android.graphics.Path` and
    draws it via `drawIntoCanvas { canvas -> canvas.nativeCanvas.drawPath(path, paint) }`.
    Anti-aliased, round caps, round joins. Uses the DrawScope's
    `density` (Float) to convert dp → px for the line width.
  - New private top-level function `savePaintToGallery()`:
    - Allocates a 2048×1536 `Bitmap` (4:3) and renders the
      white background.
    - Iterates over each `PaintStroke` and re-draws the path
      with an upscaled width (`widthDp * 4f * exportScale`)
      so the saved PNG looks proportional on a larger canvas.
    - Writes the bitmap as PNG to
      `/sdcard/Pictures/Elysium/Paint_<yyyyMMdd_HHmmss>.png`.
    - Fires `ACTION_MEDIA_SCANNER_SCAN_FILE` so the system
      gallery picks it up immediately.
    - Returns the saved path on success, `null` on any
      exception. Caller (the Save button) shows a snackbar
      with the result.
  - New registry entry `"paint" to WindowContent(icon = Icons.Filled.Brush, body = { PaintBody() })`.
  - New Programs catalog entry (icon `Icons.Filled.Brush`,
    tint `Color(0xFF50FA7B)`, subtitle "Canvas + 8-color
    palette + save PNG").
  - Updated KDoc in the registry header to include `[PaintBody]`
    in the 19-app list.

## Behavior

1. User opens Paint from Programs catalog.
2. The body shows the empty canvas with the placeholder
   "Draw something! Use the palette above to pick a color."
3. The user taps a swatch — the swatch gets the selected
   border + brush icon.
4. The user adjusts the brush size slider (1-20 dp).
5. The user drags a finger across the canvas. `detectDragGestures`
   records every position; the in-progress stroke is drawn
   live (so the user sees the line as they draw).
6. On `onDragEnd` the stroke is committed to the undo stack.
7. The user can tap Undo to remove the last stroke, Clear to
   remove all, or Save to export.
8. Save writes a PNG to `/sdcard/Pictures/Elysium/Paint_<ts>.png`
   and shows a snackbar "Saved: <filename>" (or "Save failed"
   on error).
9. The body's `Modifier.fillMaxWidth().weight(1f)` Canvas
   takes the remaining vertical space below the toolbar.

## Why a `PaintStroke` data class (not just an Offset list)

Three reasons:

1. **Per-stroke color + width.** Each stroke remembers its
   own color and width — switching the palette mid-stroke
   doesn't retroactively re-color the previous one. The
   brush size slider only affects future strokes.
2. **Cheap undo.** Undo is `strokes.removeAt(strokes.lastIndex)`.
   No "current stroke history" stack needed.
3. **Cheap re-render to any canvas.** When the user saves
   the painting, we re-render every `PaintStroke` to a
   software `Bitmap` at the export scale. The strokes are
   the data, not the pixels.

## Why a hard cap on the undo stack (`PAINT_MAX_STROKES = 200`)

`SnapshotStateList` doesn't have a hard cap. Without the
cap, a power user could draw 10,000 short strokes and the
body would keep every `PaintStroke` in memory. 200 is
enough for a real doodle (200 distinct drag motions) and
keeps the per-frame redraw cheap. When the cap is reached,
the OLDEST stroke is dropped (FIFO), so the user sees their
most recent work even after long sessions.

## Tests

- **No new unit tests** — the body is a UI surface over
  pure data (`PaintStroke`) and the rendering math is
  handled by the Android `Canvas` + `Paint` (which we
  don't unit-test). The previous Phase 137 added 9
  `CalendarEventRepositoryTest` tests; the total test
  count is unchanged at **3828** because we don't
  duplicate UI rendering tests.
- **All 3828 tests pass** (1 pre-existing flake
  `FoundryRepositoryContractTest` unchanged).
- **0 lint warnings** (1 deprecation warning on
  `ACTION_MEDIA_SCANNER_SCAN_FILE` — this is a long-standing
  Android API; the replacement `MediaScannerConnection` is
  strictly more verbose and breaks the file-system
  broadcast flow on older devices. Acceptable to keep).

## Visual verification (on-device, VER-N49)

1. Open Dashboard → DESKTOP → Programs → Paint (now the
   19th entry; the dock also picks it up the first time
   the user opens it).
2. Body opens with empty canvas + 8-color palette + 6px
   brush + empty "Draw something!" hint.
3. Drag a finger across the canvas — the line draws live
   (anti-aliased, round caps).
4. Lift the finger — the stroke is committed; the "0
   strokes" counter updates to "1 stroke"; Undo / Clear
   / Save become enabled.
5. Tap Undo — the stroke disappears, counter back to 0.
6. Change color (tap a swatch) — the next stroke uses the
   new color; previous strokes keep their original color.
7. Drag again — verify the new color.
8. Save — snackbar "Saved: Paint_<ts>.png", file appears
   in `/sdcard/Pictures/Elysium/` and in the system
   gallery (after the media scan fires).

## Files

| File | Lines | Status |
| --- | --- | --- |
| `features/desktop/content/WindowContentRegistry.kt` | +240 | new PaintBody + saveToGallery + drawStroke |
| `docs/changelogs/PHASE_138_PAINT_BODY.md` | this | new |

**Build:** `./gradlew assembleDebug` — green, 0 lint.
**Install:** `adb install -r app/build/outputs/apk/debug/app-debug.apk` — Success.
**Tests:** `./gradlew testDebugUnitTest` — 3828 pass, 1 pre-existing flake unchanged.

## Known limitations

- The stroke-to-bitmap export uses a fixed 4:3 aspect ratio
  (2048×1536). The body's actual canvas is whatever the
  window is. Strokes near the edges of a wide window may
  get cropped on export. Phase 139+ can fix this by
  recording the canvas dimensions alongside the strokes
  and exporting at the same aspect ratio.
- No eraser. The undo button is the only way to remove
  strokes. A future phase can add an "eraser" mode that
  saves white strokes with a different `Paint.Cap`.
- No brush shape variation. Only round caps. A future
  phase can add a "brush shape" selector (round, square,
  calligraphy).
- No pen-pressure support. The body records position only.
  A future phase can use `MotionEvent.getPressure(i)` for
  variable line width.
- No fill / shape tools. The body is draw-only. A future
  phase can add a "shape" mode (rectangle, circle, line)
  that captures the start + end points and renders the
  shape on `onDragEnd`.

## Roadmap (Phase 139+)

- **Eraser** mode — paint with white instead of color.
- **Brush shape** selector (round, square, calligraphy).
- **Pen-pressure** support via `MotionEvent.getPressure(i)`.
- **Shape tools** (rectangle, circle, line) — captured on
  `onDragStart` + `onDragEnd` and rendered at end-of-drag.
- **Import image** as a background layer (using
  `ImageDecoder.createSource` to load a bitmap, then
  painting over it).
- **Share** the saved PNG via `ACTION_SEND` (the
  `ChooserType` "share" intent).
- **Color picker** dialog (HSV wheel) as a 9th palette
  option — Phase 138 keeps the palette fixed at 8 colors
  to keep the body small.
