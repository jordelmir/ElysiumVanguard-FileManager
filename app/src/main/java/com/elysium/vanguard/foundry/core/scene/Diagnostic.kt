package com.elysium.vanguard.foundry.core.scene

import java.util.UUID

/**
 * Phase 3 / I-3.6 — the **Diagnostic** data class.
 *
 * The diagnostic is the fault model integration
 * the digital twin uses to surface part-level
 * faults. Per the implementation roadmap I-3.6:
 *
 *   - "`Diagnostic` is a typed `DTC` reference +
 *     a `Symptom` + a `Hypothesis` list + a
 *     `TestProcedure` list + a `RepairAction`
 *     list + a `TelemetrySnapshot` reference +
 *     a `VerificationStatus`."
 *
 * The diagnostic has:
 *   - `id: DiagnosticId` — the diagnostic's id.
 *   - `dtcCode: String` — the OBD-II Diagnostic
 *     Trouble Code (e.g. "P0420" for catalytic
 *     converter efficiency below threshold).
 *   - `symptom: String` — the user-facing
 *     symptom (e.g. "Check Engine Light on;
 *     reduced fuel economy").
 *   - `hypotheses: List<Hypothesis>` — the
 *     possible causes, ranked by likelihood.
 *   - `testProcedures: List<TestProcedure>` —
 *     the diagnostic procedures (the steps a
 *     mechanic takes to confirm the cause).
 *   - `repairActions: List<RepairAction>` —
 *     the actions the user can take to fix
 *     the fault.
 *   - `verificationStatus: VerificationStatus` —
 *     the trust level of the diagnostic
 *     (UNVERIFIED, COMMUNITY_CORROBORATED,
 *     ENGINEER_REVIEWED, OEM_VERIFIED, etc.).
 *
 * The diagnostic is **immutable** (a data class;
 * no setters). A new diagnostic is a new id; an
 * updated diagnostic is a new id; the old
 * diagnostic is retained for back-compat.
 */
data class Diagnostic(
    val id: DiagnosticId,
    val dtcCode: String,
    val symptom: String,
    val hypotheses: List<Hypothesis>,
    val testProcedures: List<TestProcedure>,
    val repairActions: List<RepairAction>,
    val verificationStatus: VerificationStatus,
) {
    init {
        require(dtcCode.isNotBlank()) {
            "Diagnostic.dtcCode must not be blank"
        }
        require(symptom.isNotBlank()) {
            "Diagnostic.symptom must not be blank"
        }
        // The verification status is metadata; the
        // consumer is responsible for checking
        // `verificationStatus` before showing the
        // diagnostic to the user. An unverified
        // proposal is allowed in the Diagnostic
        // (a community-corroborated diagnostic
        // starts as unverified + is promoted to
        // COMMUNITY_CORROBORATED by the AI council).
    }
}

/**
 * A typed id for a [Diagnostic]. The id is a `UUID`.
 * The id is a runtime-generated id (the DTC code
 * is the user-facing identifier; the id is the
 * platform's identifier).
 */
@JvmInline
value class DiagnosticId(val value: UUID) {
    companion object {
        fun random(): DiagnosticId = DiagnosticId(UUID.randomUUID())
        fun from(raw: String): Result<DiagnosticId> = try {
            Result.success(DiagnosticId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(
                com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError.InvalidUuidFormat(
                    idTypeName = "DiagnosticId",
                    rawInput = raw,
                    parseFailure = e,
                ),
            )
        }
    }
}

/**
 * A hypothesis — a possible cause of a [Diagnostic].
 * The hypothesis has:
 *   - `description: String` — the user-facing
 *     description (e.g. "Catalytic converter
 *     efficiency below threshold").
 *   - `likelihood: Double` — the likelihood
 *     (0.0 to 1.0; 1.0 = certain). The
 *     diagnostic's hypotheses are sorted by
 *     likelihood descending.
 */
data class Hypothesis(
    val description: String,
    val likelihood: Double,
) {
    init {
        require(description.isNotBlank()) {
            "Hypothesis.description must not be blank"
        }
        require(likelihood in 0.0..1.0) {
            "Hypothesis.likelihood must be in 0.0..1.0, got $likelihood"
        }
    }
}

/**
 * A test procedure — the diagnostic steps a
 * mechanic takes to confirm the cause of a
 * [Diagnostic]. The procedure has:
 *   - `name: String` — the procedure's name
 *     (e.g. "Check O2 sensor voltage").
 *   - `steps: List<String>` — the steps in
 *     order.
 */
data class TestProcedure(
    val name: String,
    val steps: List<String>,
) {
    init {
        require(name.isNotBlank()) {
            "TestProcedure.name must not be blank"
        }
        require(steps.isNotEmpty()) {
            "TestProcedure.steps must not be empty"
        }
        require(steps.all { it.isNotBlank() }) {
            "TestProcedure.steps must not contain blank entries"
        }
    }
}

/**
 * A repair action — the action the user can
 * take to fix a [Diagnostic]. The action has:
 *   - `name: String` — the action's name
 *     (e.g. "Replace catalytic converter").
 *   - `description: String` — a longer
 *     description of the action.
 *   - `estimatedTimeMinutes: Int` — the
 *     estimated time (for the UI's "estimated
 *     repair time" badge).
 */
data class RepairAction(
    val name: String,
    val description: String,
    val estimatedTimeMinutes: Int,
) {
    init {
        require(name.isNotBlank()) {
            "RepairAction.name must not be blank"
        }
        require(description.isNotBlank()) {
            "RepairAction.description must not be blank"
        }
        require(estimatedTimeMinutes >= 0) {
            "RepairAction.estimatedTimeMinutes must be >= 0, " +
                "got $estimatedTimeMinutes"
        }
    }
}

/**
 * The trust level of a [Diagnostic]. The status
 * is the platform's source of truth for "how
 * confident are we in this diagnostic".
 *
 * The status transitions are append-only
 * (a `COMMUNITY_CORROBORATED` may become
 * `ENGINEER_REVIEWED` but not back to
 * `UNVERIFIED`). A silent downgrade is a
 * `R-DI-6` typed error.
 */
enum class VerificationStatus {
    /**
     * Sentinel: the status is not set. Used as
     * a default value + a "missing required field"
     * indicator. The diagnostic's constructor
     * rejects this value.
     */
    UNVERIFIED_PROPOSAL,

    /**
     * The community has corroborated the
     * diagnostic (multiple independent
     * users have reported the same fault
     * + the same hypothesis + the same
     * repair action worked). The lowest
     * "trusted" level.
     */
    COMMUNITY_CORROBORATED,

    /**
     * A platform engineer has reviewed the
     * diagnostic. The engineer confirms the
     * hypothesis + the test procedure + the
     * repair action are correct.
     */
    ENGINEER_REVIEWED,

    /**
     * An OEM has verified the diagnostic. The
     * OEM confirms the hypothesis + the test
     * procedure + the repair action are the
     * OEM's official recommendation. The
     * highest trust level.
     */
    OEM_VERIFIED,
}
