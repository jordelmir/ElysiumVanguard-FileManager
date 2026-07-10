package com.elysium.vanguard.features.crdteditor

/**
 * PHASE 9.20 — Pure-Kotlin helpers extracted from the
 * `CrdtDocumentEditorScreen` composable so they can be unit
 * tested without spinning up Compose or an emulator.
 *
 * What lives here:
 *   - [fileName] — strip the directory prefix off a path.
 *   - [EditorResult.label] — pretty-print the human-readable
 *     summary of a one-off editor result.
 *   - [BodyEditorDiff] — convert a `BasicTextField.onValueChange`
 *     (prev → next) into the dispatch sequence it implies.
 *
 * These are all deterministic, pure, JVM-testable. The actual
 * rendering is done in the composable; these helpers exist so
 * the screen's *behavior* (rather than its *appearance*) has
 * regression coverage.
 *
 * Phase 9.20 — first build; intentionally minimal.
 */
object CrdtEditorScreenHelpers {

    /**
     * Strip the directory prefix off [path] and return the
     * basename. Returns `(no file)` for a blank input.
     *
     * Public + package-private callers in this package share it
     * so the production screen and the tests agree on the
     * contract.
     */
    fun fileName(path: String): String {
        if (path.isBlank()) return "(no file)"
        val idx = path.lastIndexOf('/')
        // On Windows we'd also want a backslash fallback, but
        // Android paths are posix and we don't accept paths
        // authored in the screen's nav arg.
        return if (idx < 0) path else path.substring(idx + 1)
    }
}

/**
 * Pretty-print the [EditorResult] for the status row. Returns a
 * one-word descriptor suitable for the small monospace caption
 * under the body.
 */
fun EditorResult.label(): String = when (this) {
    EditorResult.Saved -> "saved"
    is EditorResult.Synced -> "synced ${opsAbsorbed}op"
    EditorResult.SyncNoPeer -> "no peer"
}

/**
 * Diff between the previous and next state of the body's
 * `BasicTextField`. We model body editing as append-and-backspace
 * (no mid-string cursor manipulation) so every change either:
 *
 *   - is a single-character append at the end (one [Intent.AppendChar]),
 *   - is a single trailing backspace (one [Intent.Backspace]),
 *   - or is a mid-string edit we deliberately ignore (so the op
 *     log stays linear — see comment on `BodyEditor`).
 *
 * Phase 9.20 — first build; intentionally minimal.
 */
object BodyEditorDiff {

    /**
     * The per-call decision the screen takes for an
     * onValueChange event.
     */
    sealed interface Decision {
        data class Chars(val appended: String) : Decision
        data object Backspace : Decision
        /** Mid-string edit; safely ignored. */
        data object Ignore : Decision
    }

    /**
     * Compute the [Decision] for a transition from [prev] to
     * [next]. The screen runs the resulting decision through
     * `dispatch` and updates its local buffer accordingly.
     *
     * Rules:
     *   - If [next] is a strict suffix of [prev] with the same
     *     prefix, it's an append → emit the delta chars.
     *   - If [next] equals [prev] minus its last char (or any
     *     equivalent trailing-backspace scenario), emit one
     *     Backspace.
     *   - Otherwise, mid-string edit → ignore so the op log
     *     stays linear.
     */
    fun compute(prev: String, next: String): Decision {
        if (next == prev) return Decision.Ignore
        // Strict append case: next starts with prev and grew.
        if (next.length > prev.length && next.startsWith(prev)) {
            val delta = next.substring(prev.length)
            return Decision.Chars(delta)
        }
        // Trailing-backspace case: prev starts with next and
        // grew. We require *single-step* backspace so the op
        // log remains a 1:1 mirror of edits; rapid deletes are
        // handled one-tick-at-a-time by the Android keystroke
        // model anyway.
        if (prev.length > next.length && prev.startsWith(next)) {
            if (prev.length - next.length == 1) return Decision.Backspace
            // Multi-char backspaces (e.g. word delete) collapse
            // to a single backspace at a time; the rest get
            // re-emitted on subsequent ticks.
            return Decision.Backspace
        }
        return Decision.Ignore
    }
}
