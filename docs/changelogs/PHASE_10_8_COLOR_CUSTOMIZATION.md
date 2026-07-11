# Phase 10.8 — Live color customization (primary/secondary/tertiary/quaternary + 5 styles)

On-the-fly palette editing. The user can now change the core accent
colors of the system, pick from five visual rendering styles per slot,
save / load named palettes, and see M3 widgets react immediately to
the new color scheme.

## The system, top to bottom

**Data model** — `ColorSlot` carries a base color plus four derived
colors (glow / metallic start / metallic end / diffused) and a
`SlotStyle` tag. The slot's `withBase()` auto-recomputes the
derived colors from a new base, so the picker feels "alive" — pick
a hue, and the slot's halo / metallic stops / diffusion follow.

`ColorPalette` is the unit the user saves and loads: a stable id,
a human name, and five named slots (PRIMARY / SECONDARY / TERTIARY /
QUATERNARY / ACCENT), plus background / surface / onBackground /
onSurface so OLED_BLACK can flip the dark mode flip.

`SlotStyle` is the visual rendering tag: **NEON** (saturated core
+ halo — the original Elysium look), **PHOSPHORESCENT** (green-yellow
CRT afterglow), **METALLIC** (chrome / gold / silver gradient),
**COMBINED** (neon core + metallic edge — the most premium look),
**DIFFUSED** (low-saturation soft wash — the subdued aurora look).

**Persistence** — `PaletteStore` is a SharedPreferences-backed
JSON blob. One key for the current palette, one set for the user's
saved palettes. The serializer uses a small DTO layer (with stable
ARGB longs) so the on-disk format doesn't depend on internal
changes to `ColorSlot`. Malformed blobs fall back to TITAN_DEFAULT
on load — no crash on corrupt prefs.

**Runtime** — `PaletteManager` is a Hilt-injected singleton
wrapping the store. It exposes a `StateFlow<ColorPalette>` that
UI collects; every mutator (`setPalette`, `updateSlotBase`,
`updateSlotStyle`, `updateSlotIntensity`, `applyPreset`, etc.)
updates the StateFlow synchronously and writes to the store in
the background. Changes propagate to every subscriber
immediately.

**Compose** — `ElysiumTheme` replaces the static `TitanTheme` in
`MainActivity`. It builds an M3 `ColorScheme` from the current
palette's primary / secondary / tertiary / background / surface /
onBackground / onSurface, so every M3 widget (Button, TextField,
Switch, etc.) reacts to a palette change on the next frame.

`LocalPalette` is a `staticCompositionLocalOf<ColorPalette>` so
any composable can read the live palette via
`val palette = LocalPalette.current`. The default value is
TITAN_DEFAULT so previews and isolated tests don't NPE.

`SlotRenderers` (object) exposes three Modifier extensions that
turn a `ColorSlot` into a visual: `slotBorder`, `slotBackground`,
`slotGlow`. Each picks the right math for the slot's style —
NEON gets a vertical gradient + saturated glow, METALLIC gets a
linear dark→bright gradient, COMBINED layers both.

## Built-in presets (8)

| Id | Name | Primary style |
| -- | ---- | ------------- |
| `titan_default` | TITAN (Default) | NEON — the original Elysium look |
| `oled_black` | OLED BLACK | NEON @ 0.6 — true black + monochrome |
| `phosphor_green` | PHOSPHOR GREEN | PHOSPHORESCENT — CRT-terminal |
| `cyber_magenta` | CYBER MAGENTA | NEON — hot pink / purple / cyan |
| `gold_metallic` | GOLD METALLIC | METALLIC — gold / bronze / silver |
| `infrared` | INFRARED | COMBINED — deep red / orange / yellow |
| `holographic` | HOLOGRAPHIC | DIFFUSED — cyan / purple / pink |
| `midnight_neon` | MIDNIGHT NEON | NEON @ 0.7 — deep blue / teal / violet |

The customization screen lets the user tap any preset card to
apply it. Unknown ids fall back to TITAN_DEFAULT.

## The customization screen

New dashboard portal tile "COLORS" (neon yellow accent) opens the
`ColorCustomizationScreen`. Three sections, top to bottom:

1. **Presets** — 8 horizontal cards with a four-dot swatch + name.
   The active preset gets a cyan border.
2. **Slot editors** — one `SlotEditorCard` per slot
   (PRIMARY, SECONDARY, TERTIARY, QUATERNARY, ACCENT). Each card has:
   - A color swatch that opens a full HSV color picker dialog
     (2D saturation/value pad + hue slider + live preview swatch
     + hex readout).
   - A row of 5 `SlotStyle` pill buttons.
   - An intensity slider 0..2 with the slot's color as the thumb.
   - A live preview tile rendered through `SlotRenderers.slotGlow`
     so the user sees exactly how the slot will look.
3. **Save / Saved** — text field + save button (slugifies the name
   into the palette id). Below, the list of user-saved palettes
   with apply + delete actions.

A reset-to-default button in the header reverts to TITAN_DEFAULT.

## Live application

`MainActivity` now reads `paletteManager.current` as Compose state
and wraps content in `ElysiumTheme(palette = palette)`. When the
user picks a new color / style / intensity in the customization
screen:

1. The `ColorCustomizationViewModel` calls
   `manager.updateSlot{Base,Style,Intensity}`.
2. The manager updates its `StateFlow.value` synchronously.
3. MainActivity recomposes with the new palette.
4. `ElysiumTheme` rebuilds the M3 `ColorScheme` and `LocalPalette`.
5. Every M3 widget that reads `MaterialTheme.colorScheme` and
   every composable that reads `LocalPalette.current` reflects
   the new color.

The default TITAN palette is color-equivalent to the old static
`TitanTheme`, so existing screens render identically out of the
box — no visual regression on a fresh install. Migrating the
existing 524 `TitanColors.*` references to read from
`LocalPalette.current` is follow-up work; for now, the new theme
coexists with the static color object.

## Numbers

- **Tests:** 775 → **831** (+56)
- **0 failures, 0 errors, 0 warnings introduced** by new code
- **APK debug:** 169 MB, **BUILD SUCCESSFUL**
- 11 new production files, 5 new test files, 1 changelog
- 1 dashboard portal tile + 1 navigation route
- 8 built-in palettes spanning the 5 styles

`./gradlew testDebugUnitTest` → 831 green.
`./gradlew assembleDebug` → BUILD SUCCESSFUL.
