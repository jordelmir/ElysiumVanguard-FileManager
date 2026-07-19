package com.elysium.vanguard.foundry.core.council

import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.math.BigDecimal
import java.util.UUID

/**
 * Phase F4 first half (G5, I-4.1) — the **AI
 * Council Decision**, the aggregated result of
 * the council's deliberation.
 *
 * Per `.ai/AGENTS.md` section 21 + skill 05: the
 * council's decision is a **typed value** (a
 * sealed class with multiple cases) that captures
 * the **outcome** of the deliberation:
 *
 *   - **UnanimousApprove** / **UnanimousReject**:
 *     every voting agent agreed; the consensus
 *     can be applied directly.
 *   - **MajorityApprove** / **MajorityReject**:
 *     the majority agreed; the dissenters are
 *     recorded for the audit log; the consensus
 *     can be applied directly.
 *   - **Split**: the council is split (no clear
 *     majority); the decision requires human
 *     review.
 *   - **Escalated**: the council unanimously
 *     escalated; the decision requires human
 *     review (typically because the proposal
 *     exceeds the AI's authority).
 *   - **Insufficient**: the council has fewer
 *     than 2 voting agents; the decision
 *     requires human review.
 *
 * The decision is **immutable** (a sealed class
 * with `val` fields; no setters). A new decision
 * is a new value. The decision's lifecycle (a
 * human review, a follow-up deliberation) is a
 * new `AICouncilDecision` value, not a mutation
 * of the existing one.
 */
sealed class AICouncilDecision {

    /** The revision this decision is about. */
    abstract val revisionId: VehicleRevisionId

    /** The individual votes that produced the
     *  decision. The list is sorted by
     *  `timestampMs` (ascending; the earliest
     *  vote is first). */
    abstract val votes: List<AIVote>

    /** The average confidence across all
     *  voting agents. The average is a
     *  BigDecimal in [0, 1]. */
    abstract val averageConfidence: BigDecimal

    /**
     * The council unanimously approves the
     * target revision. Every voting agent
     * voted `APPROVE`.
     */
    data class UnanimousApprove(
        override val revisionId: VehicleRevisionId,
        override val votes: List<AIVote>,
        override val averageConfidence: BigDecimal,
    ) : AICouncilDecision()

    /**
     * The council unanimously rejects the
     * target revision. Every voting agent
     * voted `REJECT`.
     */
    data class UnanimousReject(
        override val revisionId: VehicleRevisionId,
        override val votes: List<AIVote>,
        override val averageConfidence: BigDecimal,
    ) : AICouncilDecision()

    /**
     * The council majority-approves the
     * target revision. The majority voted
     * `APPROVE`; the minority voted
     * differently. The dissenters are
     * recorded for the audit log.
     */
    data class MajorityApprove(
        override val revisionId: VehicleRevisionId,
        override val votes: List<AIVote>,
        override val averageConfidence: BigDecimal,
        val dissentingVotes: List<AIVote>,
    ) : AICouncilDecision()

    /**
     * The council majority-rejects the
     * target revision. The majority voted
     * `REJECT`; the minority voted
     * differently.
     */
    data class MajorityReject(
        override val revisionId: VehicleRevisionId,
        override val votes: List<AIVote>,
        override val averageConfidence: BigDecimal,
        val dissentingVotes: List<AIVote>,
    ) : AICouncilDecision()

    /**
     * The council is split: the votes are
     * tied or near-tied (no clear majority).
     * The decision requires human review.
     */
    data class Split(
        override val revisionId: VehicleRevisionId,
        override val votes: List<AIVote>,
        override val averageConfidence: BigDecimal,
        val tieBreakReason: String,
    ) : AICouncilDecision() {
        init {
            require(tieBreakReason.isNotBlank()) {
                "AICouncilDecision.Split.tieBreakReason must not be blank"
            }
        }
    }

    /**
     * The council unanimously escalated to
     * human review. Every voting agent
     * voted `ESCALATE`. The decision requires
     * human review (typically because the
     * proposal exceeds the AI's authority).
     */
    data class Escalated(
        override val revisionId: VehicleRevisionId,
        override val votes: List<AIVote>,
        override val averageConfidence: BigDecimal,
        val escalationReason: String,
    ) : AICouncilDecision() {
        init {
            require(escalationReason.isNotBlank()) {
                "AICouncilDecision.Escalated.escalationReason must not be blank"
            }
        }
    }

    /**
     * The council has insufficient votes
     * (fewer than 2 voting agents). The
     * decision requires human review.
     */
    data class Insufficient(
        override val revisionId: VehicleRevisionId,
        override val votes: List<AIVote>,
        val minimumRequired: Int,
    ) : AICouncilDecision() {
        override val averageConfidence: BigDecimal
            get() = BigDecimal.ZERO

        init {
            require(minimumRequired >= 2) {
                "AICouncilDecision.Insufficient.minimumRequired must be >= 2"
            }
        }
    }
}

/**
 * An individual vote from an agent. The vote is
 * the **agent's typed decision** on a specific
 * revision.
 *
 * The vote is **immutable** (a data class; no
 * setters). A new vote is a new value. The
 * agent's lifecycle (a re-vote, a retraction)
 * is a new `AIVote` value, not a mutation of
 * the existing one.
 */
data class AIVote(
    /**
     * The vote's unique id. The id is a UUID
     * (per the Foundry id convention).
     */
    val voteId: AIVoteId,

    /**
     * The agent that cast the vote. The
     * author carries the role the agent is
     * playing (per [AIAuthor]).
     */
    val author: AIAuthor,

    /**
     * The revision the vote is about. Every
     * vote is about exactly one revision; a
     * vote that wants to address multiple
     * revisions is multiple votes.
     */
    val revisionId: VehicleRevisionId,

    /**
     * The decision the agent cast. The
     * decision is a typed value (per
     * [AIVoteDecision]); a free-form string
     * is never the decision.
     */
    val decision: AIVoteDecision,

    /**
     * The agent's rationale for the vote. The
     * rationale is the human-readable
     * reasoning the agent provides for the
     * vote. The rationale is mandatory.
     */
    val rationale: String,

    /**
     * The agent's confidence in the vote. The
     * confidence is a BigDecimal in [0, 1].
     */
    val confidence: BigDecimal,

    /**
     * The vote's timestamp. The timestamp is
     * the millis since epoch the vote was
     * cast. The timestamp is **immutable**.
     */
    val timestampMs: Long,
) {
    init {
        require(rationale.isNotBlank()) {
            "AIVote.rationale must not be blank"
        }
        require(confidence >= BigDecimal.ZERO && confidence <= BigDecimal.ONE) {
            "AIVote.confidence must be in [0, 1], got $confidence"
        }
        require(timestampMs > 0) {
            "AIVote.timestampMs must be > 0, got $timestampMs"
        }
    }
}

/**
 * The typed id of an AI vote. The id is a UUID
 * (per the Foundry id convention).
 */
@JvmInline
value class AIVoteId(val value: UUID) {
    companion object {
        fun random(): AIVoteId = AIVoteId(UUID.randomUUID())
        fun from(raw: String): Result<AIVoteId> = try {
            Result.success(AIVoteId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("AIVoteId", raw, e))
        }
    }
}

/**
 * The decision the agent cast on the revision.
 * The decision is a typed value; a `when` on the
 * decision is **exhaustive**.
 *
 * The decisions are derived from the AI authority
 * boundary (per `.ai/AGENTS.md` section 21.3):
 *   - `APPROVE` — the agent agrees.
 *   - `REJECT` — the agent disagrees.
 *   - `ABSTAIN` — the agent declines to vote
 *     (the agent does not have sufficient
 *     expertise on the revision).
 *   - `ESCALATE` — the agent defers to human
 *     review.
 */
enum class AIVoteDecision(val displayLabel: String) {
    /** The agent approves the target revision. */
    APPROVE("Approve"),

    /** The agent rejects the target revision. */
    REJECT("Reject"),

    /** The agent declines to vote (insufficient
     *  expertise). */
    ABSTAIN("Abstain"),

    /** The agent escalates to human review. */
    ESCALATE("Escalate"),
}

/**
 * The **Human Review** — the typed human's
 * final decision on a council decision.
 *
 * Per `.ai/AGENTS.md` section 21.3: AI may NOT
 * directly approve safety-critical, certify
 * regulatory, declare mechanical compatibility,
 * finalize financial settlements, determine
 * legal ownership, modify signed releases, or
 * create verified technical facts without
 * evidence. The human review is the **final
 * authority** on these decisions.
 *
 * The human review is **immutable** (a data
 * class; no setters). A new review is a new
 * value. The review's lifecycle (a follow-up
 * review, a retraction) is a new `HumanReview`
 * value, not a mutation of the existing one.
 *
 * The human review is **signed** (the human
 * signs the review with their signature; the
 * signature binds the human to the decision
 * for audit + legal).
 */
data class HumanReview(
    /**
     * The review's unique id. The id is a
     * UUID (per the Foundry id convention).
     */
    val reviewId: HumanReviewId,

    /**
     * The human reviewer. The reviewer is
     * a [UserId] (per the Foundry id
     * convention; the reviewer is a
     * platform user, not a model agent).
     */
    val reviewerId: UserId,

    /**
     * The council decision the human is
     * reviewing. The human reviews the
     * council's decision + makes the
     * final call.
     */
    val councilDecision: AICouncilDecision,

    /**
     * The human's final decision. The
     * decision is a typed value (per
     * [HumanReviewDecision]); a free-form
     * string is never the decision.
     */
    val decision: HumanReviewDecision,

    /**
     * The human's rationale for the
     * decision. The rationale is the
     * human-readable reasoning. The
     * rationale is mandatory.
     */
    val rationale: String,

    /**
     * The review's timestamp. The
     * timestamp is the millis since
     * epoch the review was made. The
     * timestamp is **immutable**.
     */
    val timestampMs: Long,

    /**
     * The human's signature on the
     * review. The signature binds the
     * human to the decision for audit
     * + legal. The signature is verified
     * at load time.
     */
    val signature: Signature,
) {
    init {
        require(rationale.isNotBlank()) {
            "HumanReview.rationale must not be blank"
        }
        require(timestampMs > 0) {
            "HumanReview.timestampMs must be > 0, got $timestampMs"
        }
        require(signature.value.isNotBlank()) {
            "HumanReview.signature must not be blank"
        }
    }
}

/**
 * The typed id of a human review. The id is a
 * UUID (per the Foundry id convention).
 */
@JvmInline
value class HumanReviewId(val value: UUID) {
    companion object {
        fun random(): HumanReviewId = HumanReviewId(UUID.randomUUID())
        fun from(raw: String): Result<HumanReviewId> = try {
            Result.success(HumanReviewId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("HumanReviewId", raw, e))
        }
    }
}

/**
 * The human's final decision on a council
 * decision. The decision is a typed value;
 * a `when` on the decision is **exhaustive**.
 */
enum class HumanReviewDecision(val displayLabel: String) {
    /** The human approves the council's
     *  decision. The decision is final. */
    APPROVE("Approve"),

    /** The human rejects the council's
     *  decision. The decision is final. */
    REJECT("Reject"),

    /** The human defers the decision. The
     *  decision is NOT final; the council
     *  re-deliberates with additional
     *  context. */
    DEFER("Defer"),
}
