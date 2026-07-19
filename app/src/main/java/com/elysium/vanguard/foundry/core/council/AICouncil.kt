package com.elysium.vanguard.foundry.core.council

import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase F4 second half (G5, I-4.2) — the **AI
 * Council** orchestrator.
 *
 * The orchestrator is the **runtime** that
 * aggregates votes + computes consensus +
 * escalates to human review. The orchestrator
 * is the **stateless** composition of:
 *   - A set of votes keyed by `revisionId`.
 *   - A set of human reviews keyed by
 *     `reviewId`.
 *
 * The orchestrator is **pure-domain** (no I/O,
 * no Android dependencies). The test
 * implementation is an in-memory
 * [InMemoryAICouncil]; the production
 * implementation is a distributed
 * `RemoteAICouncil` (a future Phase 7+ increment).
 *
 * The orchestrator follows the AI authority
 * boundary (per `.ai/AGENTS.md` section 21.3):
 *   - The AI may submit proposals + votes.
 *   - The orchestrator aggregates votes into
 *     a typed [AICouncilDecision].
 *   - The human may review a decision (final
 *     authority on safety-critical, regulatory,
 *     mechanical compatibility, financial
 *     settlements, legal ownership, etc.).
 *
 * The orchestrator is **thread-safe** (the
 * underlying maps are `ConcurrentHashMap`).
 */
interface AICouncil {

    /**
     * Submit a vote to the council. The vote is
     * stored under `vote.revisionId`. A vote
     * can be submitted multiple times by the
     * same author (each submission is a new
     * `AIVote`; the council does NOT replace
     * the prior vote).
     */
    fun submit(vote: AIVote): Result<Unit>

    /**
     * Compute the council's [AICouncilDecision]
     * for a specific revision. The decision is
     * computed from the votes currently stored
     * for the revision.
     *
     * Returns the typed decision:
     *   - [AICouncilDecision.UnanimousApprove] —
     *     every voting agent approved.
     *   - [AICouncilDecision.UnanimousReject] —
     *     every voting agent rejected.
     *   - [AICouncilDecision.MajorityApprove] —
     *     the majority approved; the dissenters
     *     are recorded.
     *   - [AICouncilDecision.MajorityReject] —
     *     the majority rejected; the dissenters
     *     are recorded.
     *   - [AICouncilDecision.Split] — no clear
     *     majority.
     *   - [AICouncilDecision.Escalated] — every
     *     voting agent escalated.
     *   - [AICouncilDecision.Insufficient] —
     *     fewer than 2 voting agents.
     */
    fun decide(revisionId: VehicleRevisionId): Result<AICouncilDecision>

    /**
     * Record a [HumanReview] in the council. The
     * review is the human's final decision on
     * a council decision.
     *
     * Returns `Result.success(Unit)` on success;
     * `Result.failure(...)` if the review's
     * `councilDecision.revisionId` does not
     * match a known revision.
     */
    fun applyReview(review: HumanReview): Result<Unit>

    /**
     * Get the human review for a specific
     * revision. Returns `null` if no review
     * has been recorded.
     */
    fun reviewFor(revisionId: VehicleRevisionId): HumanReview?

    /**
     * Get all votes for a specific revision. The
     * list is sorted by `timestampMs` (ascending;
     * the earliest vote is first).
     */
    fun votesFor(revisionId: VehicleRevisionId): List<AIVote>
}

/**
 * An in-memory [AICouncil] for testing. The
 * orchestrator is the stateless composition of:
 *   - A `Map<VehicleRevisionId, List<AIVote>>`
 *     keyed by revision.
 *   - A `Map<HumanReviewId, HumanReview>` keyed
 *     by review id (the review is indexed by
 *     `reviewId` for fast lookup; the
 *     `reviewFor` method uses
 *     `councilDecision.revisionId` for
 *     revision lookup).
 *
 * The orchestrator is **thread-safe** (the
 * underlying maps are `ConcurrentHashMap`).
 */
class InMemoryAICouncil : AICouncil {

    private val votesByRevision:
        java.util.concurrent.ConcurrentHashMap<
            VehicleRevisionId,
            java.util.concurrent.CopyOnWriteArrayList<AIVote>> =
        java.util.concurrent.ConcurrentHashMap()

    private val reviewById:
        java.util.concurrent.ConcurrentHashMap<
            HumanReviewId,
            HumanReview> =
        java.util.concurrent.ConcurrentHashMap()

    private val reviewByRevision:
        java.util.concurrent.ConcurrentHashMap<
            VehicleRevisionId,
            HumanReview> =
        java.util.concurrent.ConcurrentHashMap()

    override fun submit(vote: AIVote): Result<Unit> {
        val list = votesByRevision.computeIfAbsent(vote.revisionId) {
            java.util.concurrent.CopyOnWriteArrayList()
        }
        list.add(vote)
        return Result.success(Unit)
    }

    override fun decide(
        revisionId: VehicleRevisionId,
    ): Result<AICouncilDecision> {
        val votes = votesFor(revisionId)
        // Filter out abstentions (they do not
        // count toward the decision).
        val voting = votes.filter { it.decision != AIVoteDecision.ABSTAIN }
        // Insufficient: fewer than 2 voting agents.
        if (voting.size < 2) {
            return Result.success(
                AICouncilDecision.Insufficient(
                    revisionId = revisionId,
                    votes = votes,
                    minimumRequired = 2,
                ),
            )
        }
        // Count approvals + rejections + escalations.
        val approvals = voting.filter { it.decision == AIVoteDecision.APPROVE }
        val rejections = voting.filter { it.decision == AIVoteDecision.REJECT }
        val escalations = voting.filter { it.decision == AIVoteDecision.ESCALATE }
        val averageConfidence = averageConfidence(votes)
        return Result.success(
            when {
                // All voting agents escalated.
                escalations.size == voting.size -> AICouncilDecision.Escalated(
                    revisionId = revisionId,
                    votes = votes,
                    averageConfidence = averageConfidence,
                    escalationReason = "every voting agent escalated",
                )
                // All voting agents approved (unanimously).
                approvals.size == voting.size -> AICouncilDecision.UnanimousApprove(
                    revisionId = revisionId,
                    votes = votes,
                    averageConfidence = averageConfidence,
                )
                // All voting agents rejected (unanimously).
                rejections.size == voting.size -> AICouncilDecision.UnanimousReject(
                    revisionId = revisionId,
                    votes = votes,
                    averageConfidence = averageConfidence,
                )
                // Approvals > rejections: majority approve.
                approvals.size > rejections.size && approvals.size > escalations.size ->
                    AICouncilDecision.MajorityApprove(
                        revisionId = revisionId,
                        votes = votes,
                        averageConfidence = averageConfidence,
                        dissentingVotes = voting.filter {
                            it.decision != AIVoteDecision.APPROVE
                        },
                    )
                // Rejections > approvals: majority reject.
                rejections.size > approvals.size && rejections.size > escalations.size ->
                    AICouncilDecision.MajorityReject(
                        revisionId = revisionId,
                        votes = votes,
                        averageConfidence = averageConfidence,
                        dissentingVotes = voting.filter {
                            it.decision != AIVoteDecision.REJECT
                        },
                    )
                // Otherwise: split.
                else -> AICouncilDecision.Split(
                    revisionId = revisionId,
                    votes = votes,
                    averageConfidence = averageConfidence,
                    tieBreakReason = buildString {
                        append("split vote: ")
                        append(approvals.size).append(" approve, ")
                        append(rejections.size).append(" reject, ")
                        append(escalations.size).append(" escalate")
                    },
                )
            },
        )
    }

    override fun applyReview(review: HumanReview): Result<Unit> {
        val revisionId = review.councilDecision.revisionId
        if (!votesByRevision.containsKey(revisionId)) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "AICouncil.applyReview.revisionId",
                    reason = "no votes for revision $revisionId",
                ),
            )
        }
        reviewById[review.reviewId] = review
        reviewByRevision[revisionId] = review
        return Result.success(Unit)
    }

    override fun reviewFor(
        revisionId: VehicleRevisionId,
    ): HumanReview? = reviewByRevision[revisionId]

    override fun votesFor(
        revisionId: VehicleRevisionId,
    ): List<AIVote> =
        (votesByRevision[revisionId] ?: emptyList())
            .sortedBy { it.timestampMs }

    /**
     * Compute the average confidence across all
     * votes. The function uses `BigDecimal` for
     * precision (per ADR-0001). The result is in
     * [0, 1]; if no votes, the result is
     * `BigDecimal.ZERO`.
     */
    private fun averageConfidence(votes: List<AIVote>): java.math.BigDecimal {
        if (votes.isEmpty()) return java.math.BigDecimal.ZERO
        val sum = votes.fold(java.math.BigDecimal.ZERO) { acc, v ->
            acc + v.confidence
        }
        return sum.divide(
            java.math.BigDecimal(votes.size),
            java.math.MathContext.DECIMAL64,
        )
    }
}
