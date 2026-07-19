package com.elysium.vanguard.foundry.core.council

import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import java.math.BigDecimal
import java.util.UUID

/**
 * Phase F4 first half (G5, I-4.1) — the **AI
 * Proposal**, the typed envelope an agent writes
 * to the AI council.
 *
 * Per `.ai/AGENTS.md` section 21 + skill 05: every
 * proposal is **typed** (not free-form text) +
 * **authored** (a known agent with a known role)
 * + **evidenced** (a list of supporting evidence)
 * + **scoped** (targets a specific revision) +
 * **time-stamped** (immutable timestamp).
 *
 * The proposal is **immutable** (a data class; no
 * setters). A new proposal is a new value. The
 * proposal's lifecycle (a counter-proposal, an
 * amendment) is a new `AIProposal` value, not a
 * mutation of the existing one.
 *
 * The proposal is the **single unit of work** the
 * council processes. The council aggregates
 * proposals + computes a consensus + escalates
 * to a human when consensus is not reached.
 */
data class AIProposal(
    /**
     * The proposal's unique id. The id is a
     * UUID (per the Foundry id convention).
     */
    val proposalId: AIProposalId,

    /**
     * The agent that wrote the proposal. The
     * author carries the role the agent is
     * playing (per [AIAuthor]).
     */
    val author: AIAuthor,

    /**
     * The revision the proposal targets. Every
     * proposal targets exactly one revision; a
     * proposal that wants to address multiple
     * revisions is multiple proposals.
     */
    val targetRevisionId: VehicleRevisionId,

    /**
     * The kind of action the proposal
     * recommends. The kind is a typed value
     * (per the proposal kinds below); a
     * free-form string is never the kind.
     */
    val kind: AIProposalKind,

    /**
     * The proposal's rationale. The rationale
     * is the human-readable reasoning the
     * agent provides for the proposal. The
     * rationale is mandatory (every proposal
     * has a rationale; an unexplained proposal
     * is a smell).
     */
    val rationale: String,

    /**
     * The proposal's evidence. The evidence
     * is the list of supporting evidence the
     * agent cites. The evidence is mandatory
     * (every proposal has at least one piece
     * of evidence; an evidence-less proposal
     * is a smell).
     */
    val evidence: List<AIProposalEvidence>,

    /**
     * The proposal's confidence. The confidence
     * is a BigDecimal in [0, 1] (0 = no
     * confidence, 1 = full confidence). The
     * confidence is the agent's self-assessed
     * confidence in the proposal; the council
     * uses the confidence to weight the
     * proposal in the consensus calculation.
     *
     * BigDecimal is used per ADR-0001 ("Money
     * is BigDecimal, never Double/Float").
     * Confidence is not money, but the same
     * principle applies: precision matters
     * for audit + reproducibility.
     */
    val confidence: BigDecimal,

    /**
     * The proposal's timestamp. The timestamp
     * is the millis since epoch the proposal
     * was created. The timestamp is
     * **immutable** (a proposal's timestamp
     * never changes).
     */
    val timestampMs: Long,
) {
    init {
        require(rationale.isNotBlank()) {
            "AIProposal.rationale must not be blank"
        }
        require(evidence.isNotEmpty()) {
            "AIProposal.evidence must not be empty; an evidence-less " +
                "proposal is a smell (the agent has no basis for the " +
                "recommendation)"
        }
        require(confidence >= BigDecimal.ZERO && confidence <= BigDecimal.ONE) {
            "AIProposal.confidence must be in [0, 1], got $confidence"
        }
        require(timestampMs > 0) {
            "AIProposal.timestampMs must be > 0, got $timestampMs"
        }
    }
}

/**
 * The typed id of an AI proposal. The id is a
 * UUID (per the Foundry id convention).
 */
@JvmInline
value class AIProposalId(val value: UUID) {
    companion object {
        fun random(): AIProposalId = AIProposalId(UUID.randomUUID())
        fun from(raw: String): Result<AIProposalId> = try {
            Result.success(AIProposalId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("AIProposalId", raw, e))
        }
    }
}

/**
 * The kind of action the proposal recommends.
 * The kind is a typed value (not a free-form
 * string); a `when` on the kind is **exhaustive**
 * (adding a 6th kind is a compile error in every
 * consumer that hasn't been updated).
 *
 * The kinds are derived from the AI authority
 * boundary (per `.ai/AGENTS.md` section 21.3):
 *   - AI may **interpret, propose, draft, explain**.
 *   - AI may **request evidence** when the
 *     proposal is based on incomplete information.
 *   - AI may **escalate** when the proposal
 *     exceeds the AI's authority (safety-critical,
 *     regulatory, mechanical compatibility, etc.).
 *   - AI may **approve / reject** in narrow cases
 *     (the council's consensus can be applied
 *     directly when no human review is required).
 */
enum class AIProposalKind(val displayLabel: String) {
    /**
     * The agent approves the target revision.
     * The approval is the agent's typed
     * agreement; the council aggregates
     * approvals into a consensus.
     */
    APPROVE("Approve"),

    /**
     * The agent rejects the target revision.
     * The rejection is the agent's typed
     * disagreement; the council aggregates
     * rejections into a consensus.
     */
    REJECT("Reject"),

    /**
     * The agent requests more evidence. The
     * proposal is **conditional**: the agent
     * does not approve or reject; the agent
     * asks for additional information before
     * deciding.
     */
    REQUEST_EVIDENCE("Request Evidence"),

    /**
     * The agent escalates to human review. The
     * escalation is the agent's request for a
     * human to make the final decision. The
     * escalation is the **default** when the
     * proposal exceeds the AI's authority.
     */
    ESCALATE("Escalate to Human"),
}

/**
 * The typed evidence the proposal cites. The
 * evidence is the **machine-readable** support
 * for the proposal; a `when` on the evidence is
 * **exhaustive** (adding a 4th kind is a compile
 * error in every consumer that hasn't been
 * updated).
 *
 * The evidence is the platform's response to the
 * AI authority boundary's "without evidence"
 * clause (per `.ai/AGENTS.md` section 21.3: AI may
 * NOT create verified technical facts without
 * evidence). Every proposal must cite at least
 * one piece of evidence.
 */
sealed class AIProposalEvidence {

    /**
     * A reference to an external source (a
     * standard, a regulation, a research paper,
     * an internal document). The reference is
     * the **machine-readable** link to the
     * source; the consumer follows the link to
     * verify the evidence.
     */
    data class Reference(
        val reference: String,
        val source: String,
    ) : AIProposalEvidence() {
        init {
            require(reference.isNotBlank()) {
                "AIProposalEvidence.Reference.reference must not be blank"
            }
            require(source.isNotBlank()) {
                "AIProposalEvidence.Reference.source must not be blank"
            }
        }
    }

    /**
     * A calculation result. The agent
     * performed a calculation (e.g. "torque =
     * power * 7121 / rpm") and the result is
     * the calculated value. The expression is
     * the **machine-readable** formula; the
     * result is the typed value.
     */
    data class Calculation(
        val expression: String,
        val result: String,
    ) : AIProposalEvidence() {
        init {
            require(expression.isNotBlank()) {
                "AIProposalEvidence.Calculation.expression must not be blank"
            }
            require(result.isNotBlank()) {
                "AIProposalEvidence.Calculation.result must not be blank"
            }
        }
    }

    /**
     * A comparison to a known reference. The
     * agent compared the target to a known
     * reference (e.g. "this spec matches the
     * 2024 reference design"); the comparison
     * is the **machine-readable** assertion.
     */
    data class Comparison(
        val comparesTo: String,
        val comparison: String,
    ) : AIProposalEvidence() {
        init {
            require(comparesTo.isNotBlank()) {
                "AIProposalEvidence.Comparison.comparesTo must not be blank"
            }
            require(comparison.isNotBlank()) {
                "AIProposalEvidence.Comparison.comparison must not be blank"
            }
        }
    }
}
