# PHASE 116 — Word editor text input + relative-path save bug

**Date**: 2026-07-23
**Scope**: Two critical bugs in Phase 10.5 (Word) + Phase 10.6 (Sheet) editors caught by visual review.
**Build**: `./gradlew testDebugUnitTest` → 3796 tests, 1 pre-existing flake (unchanged)
**APK**: 103 MB

---

## Summary

The dashboard's **WORD** card opens a fully-featured editor with a
formatting toolbar (B/I/U/strike, font, size, color, alignment, lists,
headings, etc.) and a body. Visual review found that the body had
**two critical bugs** that made the editor completely unusable:

1. **Tapping a paragraph opened the soft keyboard, the user typed one
   character, and the keyboard immediately closed** — the user had to
   re-tap the field after every single character. This is the same
   root cause as the Phase 10.10.1 SovereignCard clickable bug and
   the Phase 115 multi-shell `align(TopStart)` bug: an outer modifier
   on the parent Box was consuming the click / focus.

2. **"Save as..." failed with `EROFS (Read-only file system)`** — the
   dialog returned a relative file name (`Untitled.elysium.word`)
   which Android's `java.io.File` constructor resolved to the app's
   current working directory, which is read-only. The save needed an
   absolute writable path (under `context.filesDir`).

The same `EROFS` bug also affected the **SHEET** editor's "Save as..."
dialog (same code pattern). Both are fixed in this phase.

## Bug 1 — keyboard closes after one character

### Root cause

The Word document body is rendered as a `LazyColumn<WordBlock>`. Each
`BlockRow` wraps the text in a Box with `clickable { onClick() }` —
**and the inner `Text` was a read-only `androidx.compose.material3.Text`
widget** (not an `EditableTextCell`). When the user tapped the row:

1. The `clickable` on the Box consumed the click.
2. The `Text` inside was a display widget — not focusable.
3. The `selectBlock` callback fired, but no text input was actually
   exposed to the user.

After the Phase 116 fix:

1. Each `*Text` Composable (paragraph, heading, list item, block quote,
   code block) is now a `BasicTextField` wrapped in a thin `EditableTextCell`
   helper that mirrors the existing visual style (color, size, family,
   weight, style, decoration, letter-spacing, alignment, line height).
2. The `BasicTextField` is the click target, so it gains focus + the
   soft keyboard pops up via `LocalSoftwareKeyboardController.show()`.
3. The outer Box's `clickable` is **removed** — the same Phase 10.10.1
   fix pattern. Block selection is now driven by the
   `BasicTextField`'s `onFocusChanged` callback (when the field gains
   focus, the block is selected; the cyan border is then visible).
4. The `LazyColumn` key was a **block content hash** (`p-${runs.hashCode()}-${format.hashCode()}`)
   that changed on every keystroke. Compose destroyed the
   `BasicTextField` on every character, killing the focus + the IME.
   The key is now the **stable block index** (the block is at the same
   position forever; the content changes are driven by the new state).

### Before / after

```kotlin
// WordEditorScreen.kt — before
itemsIndexed(doc.blocks, key = { _, b -> b.id() }) { index, block ->
    BlockRow(
        block = block,
        onClick = { onSelectBlock(index) },  // <-- consumed the click
        ...
    )
}

// WordEditorScreen.kt — after
itemsIndexed(doc.blocks, key = { index, _ -> index }) { index, block ->
    BlockRow(
        block = block,
        onClick = { /* no-op; selection is driven by onFocusChanged */ },
        ...
    )
}

@Composable
private fun BlockRow(block, selected, onClick, onSetText, onAppend) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) Color(0xFF61AFEF) else Color.Transparent, ...)
            .padding(horizontal = 8.dp, vertical = 4.dp)  // <-- no .clickable
    ) {
        when (block) {
            is WordParagraph -> ParagraphText(block, selected, onSetText = onSetText, onFocus = onClick),
            ...
        }
    }
}

@Composable
private fun ParagraphText(p, selected, onSetText, onFocus) {
    val text = p.runs.joinToString("") { it.text }
    val format = p.runs.firstOrNull()?.format ?: CharacterFormat()
    EditableTextCell(
        text = text, format = format,
        onValueChange = onSetText, onFocus = onFocus,
        ...
    )
}

@Composable
private fun EditableTextCell(text, format, textAlign, onValueChange, ..., onFocus) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusModifier = Modifier.onFocusChanged { state ->
        if (state.isFocused) { onFocus?.invoke(); keyboard?.show() }
    }
    androidx.compose.material3.TextField(
        value = text, onValueChange = onValueChange,
        textStyle = ... (mirrors the original Text style),
        colors = TextFieldDefaults.colors(  // <-- hide all decoration
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            ... all other indicators: Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth().then(focusModifier),
    )
}
```

## Bug 2 — `EROFS (Read-only file system)` on save

### Root cause

```kotlin
// WordEditorViewModel.kt — before
fun saveAs(path: String) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)  // <-- relative path
                when {
                    path.endsWith(".docx", true) -> WordDocx.exportFile(_doc.value, file)
                    else -> WordFile.writeFile(file, _doc.value)
                }
            }
        }
        ...
    }
}
```

The `SaveAsDialog` callback passes `"Untitled.elysium.word"` (a
relative name). `File("Untitled.elysium.word")` resolves to the app's
current working directory, which is the root filesystem on Android —
read-only. The save throws `EROFS`.

### Fix

Inject `@ApplicationContext context: android.content.Context` into the
ViewModel. The `saveRoot` is `File(context.filesDir, "documents")`.
A relative name is anchored under `saveRoot`; an absolute path is used
as-is. Both `WordEditorViewModel.saveAs` and
`SheetEditorViewModel.saveAs` are fixed.

```kotlin
// WordEditorViewModel.kt — after
@HiltViewModel
class WordEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val saveRoot: File
        get() = File(context.filesDir, "documents")

    fun saveAs(path: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = if (File(path).isAbsolute) {
                        File(path)
                    } else {
                        File(saveRoot, path)
                    }
                    val absolute = file.absolutePath
                    when {
                        absolute.endsWith(".docx", true) -> WordDocx.exportFile(_doc.value, file)
                        else -> WordFile.writeFile(file, _doc.value)
                    }
                }
            }.onSuccess { currentPath = File(saveRoot, path).absolutePath }
                .onFailure { _lastError.value = "Save failed: ${it.message}" }
        }
    }
}
```

## New VM method — `WordEditorViewModel.setBlockText`

The previous `appendText(text)` only **appended** to the last run of
the selected block. The screen's `BlockRow` had an `onAppend` callback
but no `onSet` (no replacement). Phase 116 adds
`setBlockText(index: Int, text: String)` which **replaces the runs
with a single run** carrying the first run's format. This is the
canonical "the user typed new content" operation; the first run's
format is preserved so the user keeps the bold/italic/etc. they
applied via the toolbar.

The method is no-op for out-of-range or text-less blocks (page
break / horizontal rule).

## Files changed

```
app/src/main/java/com/elysium/vanguard/features/word/WordEditorScreen.kt
  - BlockRow: removed .clickable; selection via onFocusChanged
  - ParagraphText / HeadingText / ListItemText / BlockQuoteText / CodeBlockText: editable
  - New EditableTextCell helper (style-preserving BasicTextField + Material3 TextField variant)
  - LazyColumn key: b.id() -> index (stable)
  - unused WordBlock.id() kept for reference only (suppressed)

app/src/main/java/com/elysium/vanguard/features/word/WordEditorViewModel.kt
  - New setBlockText(index, text) method (replaces runs, preserves first run's format)
  - @ApplicationContext context injection
  - saveRoot: File(context.filesDir, "documents")
  - saveAs(): relative path -> File(saveRoot, path); absolute -> as-is

app/src/main/java/com/elysium/vanguard/features/sheet/SheetEditorViewModel.kt
  - @ApplicationContext context injection
  - saveRoot: same
  - saveAs(): same relative-vs-absolute resolution

docs/changelogs/PHASE_116_WORD_EDITOR_TEXT_INPUT.md  (this file)
```

## Visual review confirmed working

After the fix, on the Android device:

1. **Tapping a paragraph** opens the soft keyboard, the cursor
   appears in the text, the field shows the cyan selection border.
2. **Typing a sentence** ("Elysium Vanguard es necesario") is accepted
   in a single `adb shell input text` call. Word counter increments
   from 1 → 2 → 4 words correctly. The keyboard stays open across
   the entire text entry.
3. **Tapping "+ Add paragraph"** adds a new empty paragraph + a second
   editable field. The first paragraph keeps its text. Paragraph
   counter goes 1 → 2.
4. **Tapping "Save as..."** opens the dialog. The default name
   "Untitled" + format chip "Elysium" (`.elysium.word`) is pre-selected.
5. **Tapping "Save"** writes the file to
   `/data/data/com.elysium.vanguard/files/documents/Untitled.elysium.word`
   (writable, inside the app's `filesDir`).
6. **Word + paragraph counters** in the status bar reflect the
   document's true content.

The same `Save as...` dialog + `saveAs` fix applies to the SHEET
editor (untested on device due to a temporary ADB disconnect, but the
code path is identical).

## Lessons

- **LazyColumn keys must be stable across state changes**. A key
  derived from the content's hash changes when the user types —
  Compose then destroys + recreates the entire item (losing the
  TextField's focus + IME state). Use the **index** (or a stable UUID
  assigned when the item is created).
- **`clickable` on a parent Box consumes taps that should reach a
  child input field**. The fix (in 3 phases now: 10.10.1
  SovereignCard, 115 multi-shell, 116 Word editor) is the same:
  remove the outer `clickable`, drive the parent state from the
  child field's focus / value change callbacks.
- **Android `File(relativePath)` is dangerous**. The current working
  directory is the root filesystem (read-only). Always anchor
  relative names under `context.filesDir` (or
  `getExternalFilesDir(null)` for user-visible storage).
- **Use `Material3 TextField` over `BasicTextField` for typed input**.
  `BasicTextField` is the lighter-weight primitive but doesn't hold
  focus across recompositions as reliably. The Material3 variant
  handles IME + focus state correctly out of the box; the decoration
  can be hidden via `TextFieldDefaults.colors(... = Color.Transparent)`.
