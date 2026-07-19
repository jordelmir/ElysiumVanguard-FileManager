# Phase 10.10 — FileManager StorageMetricsBar regression (fillMaxSize eats the screen)

The `STORAGE CENTRAL` card was eating the entire screen, hiding the
search bar, the breadcrumbs, and the file list. The fix is a one-line
modifier change; the changelog is mostly the post-mortem.

## The bug

The user reported: "en la seccion filemanager, se rompio todo y una
sola cosa ocupa toda la pantalla". Only the storage bar was visible;
everything else (search, breadcrumbs, file list) was pushed off-screen.

## Root cause

`StorageMetricsBar` (in `FileManagerScreen.kt`, line 707) renders its
content inside a `SovereignCard` (the holographic glass component from
`ui/components/SovereignAnimations.kt`). The card's outer modifier was:

```kotlin
SovereignCard(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
    ...
)
```

The card has **no height constraint** — `holographicGlass` is a visual
modifier, not a layout one. The card's height is determined by its
content. The content, in turn, was:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()       // <-- THE BUG
        .background(statusColor.copy(alpha = 0.16f))
        .padding(16.dp)
) {
    Row { Text("STORAGE CENTRAL"); Text("${percentUsed}% USED") }
    Spacer(8.dp)
    LinearProgressIndicator(...)
    Spacer(8.dp)
    Text("...USED OF ...")
}
```

`Modifier.fillMaxSize()` inside a non-scrollable `Column` says "take all
the height the parent will give me". The parent here is the outer
`Column` in `FileManagerScreen`'s main content body, which lays out
children top-to-bottom with `weight(1f)` reserved only for the file
list. The metrics bar was the **first** child of that outer Column
and the inner `fillMaxSize()` happily consumed the entire remaining
vertical space — the search bar, breadcrumbs, and file list got zero.

## The fix

Replace `Modifier.fillMaxSize()` with `Modifier.fillMaxWidth()` on the
inner `Column`. The colored background (`statusColor.copy(alpha = 0.16f)`)
spans the full card width (visual intent preserved), but the column
collapses to its natural content height — about 96dp, which is what
the original design called for.

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()       // <-- fixed: was fillMaxSize()
        .background(statusColor.copy(alpha = 0.16f))
        .padding(16.dp)
)
```

## Why no test

There is no JVM test for `StorageMetricsBar` (the bar is a pure
composable that reads `SectionColorManager` + `TitanColors` for
styling; it has no business logic to assert). The verification is
visual: build the APK, install, open File Manager, see all the
sections (storage bar, search, shortcuts, breadcrumbs, file list)
in the right order at the right size.

## Scope

- `FileManagerScreen.kt:728` — one modifier change.
- 0 lines added, 0 lines removed (just one character: `Size` → `Width`).
- 0 new tests (composable is presentational, not logic).
- Build: `compileDebugKotlin` green; pre-existing deprecation/unused
  warnings untouched.

## Lesson

- `Modifier.fillMaxSize()` inside a `Column` without a `weight()` is
  almost never what you want in a non-scroll container. The intent
  usually is "fill the parent's width" (use `fillMaxWidth()`), or
  "wrap my content and lay it out as wide as possible" (use nothing
  and let the children's `fillMaxWidth` do the work).
- The `SovereignCard` is a presentational shell, not a layout
  container — it imposes no size constraints. Treat it like a `Box`
  with decoration; anything that should respect the card's bounds
  must be enforced at the modifier level by the caller.
