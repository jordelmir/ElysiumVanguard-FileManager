# v1.0.0-TITAN — Phases 10.4–10.8

RUNTIME fix + Elysium Word + Elysium Sheet + Dashboard visual refresh
+ **live color customization (Phase 10.8)**. This is the
"office + real Linux PTY + customizable identity" drop.

## Phase 10.4 — RUNTIME Direct-Exec launcher (real PTY, no probe one-shot)

**The actual bug:** the previous `JailedDistroLauncher` ran a one-shot
probe and died, so every distro reported "no PTY". The
`NativeProotLauncher` was wired but `libproot.so` wasn't bundled.

**Fix:** new `DirectExecDistroLauncher` finds the real shell of the
rootfs and exec's it with `PATH` / `LD_LIBRARY_PATH` pointing at the
rootfs. Debian / Ubuntu / Alpine / Arch now give a real interactive
PTY — `apt update && apt install htop` works end-to-end.

## Phase 10.5 — Elysium Word (full Word clone)

- Character formatting (font, size, color, bold/italic/underline/
  strikethrough, super/sub, small-caps, all-caps).
- Paragraph formatting (alignment, line spacing, space before/after,
  indent, tab stops).
- Blocks (paragraph, headings H1–H6, lists with nesting, page breaks,
  horizontal rules, block quotes, code blocks).
- `.elysium.word` (JSON) and `.docx` (OOXML) — opens clean in Word,
  LibreOffice, Google Docs.
- 8 presets, two-row toolbar, live format panel.

## Phase 10.6 — Elysium Sheet (full Excel clone)

- Real formula engine: lexer + recursive-descent parser + pure JVM
  evaluator. **32 functions** (SUM, AVERAGE, MIN, MAX, COUNT, COUNTA,
  IF, AND, OR, NOT, VLOOKUP, ROUND, ABS, LEN, LEFT, RIGHT, MID, TRIM,
  UPPER, LOWER, PROPER, CONCATENATE, NOW, TODAY, PI, SQRT, POWER,
  MOD, IFERROR, ISBLANK, ISNUMBER, ISTEXT).
- Errors (`#DIV/0!`, `#VALUE!`, etc.) propagate through arithmetic.
- Cell formatting (font, color, fill, borders, 9 number formats,
  alignment, indent).
- Multi-sheet with tabs, frozen panes.
- `.elysium.sheet` (JSON) and `.xlsx` (OOXML).

## Phase 10.7 — Dashboard visual

- Quick Action Ribbon (WORD / SHEET / RUNTIME tiles above the grid).
- WORD (cyan) and SHEET (magenta) portal tiles.
- Tiles pulse their accent color via `infiniteTransition`; whole
  ribbon wrapped in `pulsingNeonBorder`.

## Phase 10.8 — Live color customization (NEW)

On-the-fly palette editing. The user changes the core accent colors
of the system, picks from 5 visual styles per slot, saves / loads
named palettes, and sees M3 widgets react immediately.

- **5 slots:** PRIMARY, SECONDARY, TERTIARY, QUATERNARY, ACCENT.
- **5 styles:** NEON, PHOSPHORESCENT, METALLIC, COMBINED, DIFFUSED.
- **8 built-in presets:** TITAN (Default), OLED BLACK, PHOSPHOR
  GREEN, CYBER MAGENTA, GOLD METALLIC, INFRARED, HOLOGRAPHIC,
  MIDNIGHT NEON.
- **Color picker:** 2D saturation/value pad + hue slider + live
  preview + hex readout. Pure-JVM HSV math.
- **Persistence:** SharedPreferences-backed JSON. The current
  palette and the user's saved palettes survive app restart.
- **Live application:** `ElysiumTheme` reads from `PaletteManager`'s
  `StateFlow`. M3 widgets (Button, TextField, Switch, …) reflect
  the new color scheme on the next frame after a slot edit.

Open the customization screen via the new dashboard **COLORS** tile
(neon yellow).

## Numbers

- **Tests:** 696 → **831** (+135 net across all 10.x phases)
- **0 failures, 0 errors, 0 new warnings**
- **APK debug:** 169 MB, **BUILD SUCCESSFUL**
- 11 new production files, 5 new test files, 5 changelogs
  (10.4 through 10.8)
- 1 real RUNTIME bug diagnosed and fixed (10.4)
- 1 dashboard portal tile (10.8) + 1 navigation route

`./gradlew testDebugUnitTest` → 831 green.
`./gradlew assembleDebug` → BUILD SUCCESSFUL.

## Asset

- `app-debug.apk` — debug build, 169 MB. Install with
  `adb install -r app-debug.apk` (you may need to uninstall any
  earlier debug build first because of the signing key).
