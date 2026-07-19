package com.elysium.vanguard.foundry.core.dsl.editor

import com.elysium.vanguard.foundry.core.dsl.parser.CompilationDiagnostic

/**
 * Phase 2 / I-2.8 — a **Diagnostic Annotation**.
 *
 * The annotation is the typed value the editor
 * renders. A [CompilationDiagnostic] is the
 * schema-agnostic diagnostic; a
 * [DiagnosticAnnotation] is the
 * source-position-annotated variant the editor
 * consumes.
 *
 * The annotation has:
 *   - The source position (line + column) where
 *     the diagnostic fires.
 *   - The diagnostic's severity (HARD / SOFT /
 *     REGULATORY / SAFETY_CRITICAL / OPTIMIZATION).
 *   - The diagnostic's message (the user-facing
 *     string).
 *   - The diagnostic's code (the rule's stable
 *     identifier).
 *   - The path(s) the diagnostic references
 *     (the JSON paths).
 *
 * The editor renders the annotation as:
 *   - A red squiggle for HARD / SAFETY_CRITICAL /
 *     REGULATORY.
 *   - A yellow squiggle for SOFT.
 *   - A blue squiggle for OPTIMIZATION.
 *   - A hover tooltip with the diagnostic's
 *     message + a "go to definition" link to
 *     the diagnostic's rule documentation.
 */
data class DiagnosticAnnotation(
    val position: SourcePosition,
    val severity: Severity,
    val message: String,
    val code: String,
    val paths: List<String>,
) {
    /**
     * The severity of the annotation. The
     * editor uses the severity to pick the
     * squiggle color.
     */
    enum class Severity {
        /** The diagnostic blocks compilation. Red squiggle. */
        ERROR,

        /** The diagnostic blocks compilation. Red squiggle (same as ERROR for the editor). */
        SAFETY_CRITICAL,

        /** The diagnostic blocks for the affected market. Red squiggle. */
        REGULATORY,

        /** The diagnostic is a warning. Yellow squiggle. */
        WARNING,

        /** The diagnostic is an optimization suggestion. Blue squiggle. */
        OPTIMIZATION,
    }

    init {
        require(paths.isNotEmpty()) {
            "DiagnosticAnnotation.paths must not be empty"
        }
    }
}

/**
 * Convert a [CompilationDiagnostic] to a list of
 * [DiagnosticAnnotation]s. The function uses a
 * [SourceMap] to look up the source position for
 * each path in the diagnostic.
 *
 * The function is:
 *   - **Total** — every diagnostic is converted; a
 *     diagnostic with no path-match in the source
 *     map returns no annotation (the diagnostic is
 *     still in the report, just not in the editor).
 *   - **Deterministic** — same diagnostic + same
 *     source map → same annotations.
 *   - **Order-preserving** — the annotations are
 *     in the order of the diagnostic's paths.
 */
fun CompilationDiagnostic.toAnnotations(
    sourceMap: SourceMap,
): List<DiagnosticAnnotation> {
    val severity = when (severity) {
        CompilationDiagnostic.Severity.HARD -> DiagnosticAnnotation.Severity.ERROR
        CompilationDiagnostic.Severity.SAFETY_CRITICAL -> DiagnosticAnnotation.Severity.SAFETY_CRITICAL
        CompilationDiagnostic.Severity.REGULATORY -> DiagnosticAnnotation.Severity.REGULATORY
        CompilationDiagnostic.Severity.SOFT -> DiagnosticAnnotation.Severity.WARNING
        CompilationDiagnostic.Severity.OPTIMIZATION -> DiagnosticAnnotation.Severity.OPTIMIZATION
    }
    val message = this.message ?: "(no message)"
    val code = this.code
    val annotations = mutableListOf<DiagnosticAnnotation>()
    for (path in paths) {
        val position = sourceMap.positionFor(path) ?: continue
        annotations.add(
            DiagnosticAnnotation(
                position = position,
                severity = severity,
                message = message,
                code = code,
                paths = listOf(path),
            ),
        )
    }
    return annotations
}
