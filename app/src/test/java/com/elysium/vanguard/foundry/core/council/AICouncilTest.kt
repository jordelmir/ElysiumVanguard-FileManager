package com.elysium.vanguard.foundry.core.council

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Phase F4 second half (G5, I-4.2) — the JVM tests
 * for [AICouncil] (the in-memory orchestrator).
 *
 * These tests cover:
 *   - submit() stores votes keyed by revision.
 *   - votesFor() returns the votes sorted by
 *     timestamp.
 *   - decide() returns each of the 7 decision
 *     cases based on the vote distribution.
 *   - applyReview() stores the review; rejects
 *     unknown revisions.
 *   - reviewFor() returns the review for a
 *     revision.
 *   - The human review is the final authority
 *     (the human can override the council's
 *     decision).
 *   - End-to-end: agent submits votes → council
 *     computes decision → human reviews.
 */
class AICouncilTest {

    // ============================================================
    // submit() + votesFor()
    // ============================================================

    @Test
    fun `submit stores a vote under the revision`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        val vote = buildVote(revisionId = revisionId, decision = AIVoteDecision.APPROVE)
        council.submit(vote)
        val votes = council.votesFor(revisionId)
        assertEquals(1, votes.size)
        assertEquals(vote, votes[0])
    }

    @Test
    fun `submit can store multiple votes for the same revision`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        council.submit(buildVote(revisionId, AIVoteDecision.REJECT))
        council.submit(buildVote(revisionId, AIVoteDecision.ABSTAIN))
        assertEquals(3, council.votesFor(revisionId).size)
    }

    @Test
    fun `votesFor returns the votes sorted by timestamp ascending`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        val first = buildVote(revisionId, AIVoteDecision.APPROVE, timestampMs = 100L)
        val second = buildVote(revisionId, AIVoteDecision.REJECT, timestampMs = 200L)
        val third = buildVote(revisionId, AIVoteDecision.ESCALATE, timestampMs = 300L)
        // Submit in reverse order to verify the
        // sort works regardless of insertion
        // order.
        council.submit(third)
        council.submit(first)
        council.submit(second)
        val votes = council.votesFor(revisionId)
        assertEquals(listOf(first, second, third), votes)
    }

    @Test
    fun `votesFor returns an empty list for a revision with no votes`() {
        val council = InMemoryAICouncil()
        assertEquals(
            emptyList<AIVote>(),
            council.votesFor(VehicleRevisionId.random()),
        )
    }

    // ============================================================
    // decide() — Insufficient
    // ============================================================

    @Test
    fun `decide returns Insufficient when there are zero votes`() {
        val council = InMemoryAICouncil()
        val decision = council.decide(VehicleRevisionId.random()).getOrThrow()
        assertTrue(
            "expected Insufficient, got $decision",
            decision is AICouncilDecision.Insufficient,
        )
    }

    @Test
    fun `decide returns Insufficient when there is one vote`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        val decision = council.decide(revisionId).getOrThrow()
        assertTrue(
            "expected Insufficient, got $decision",
            decision is AICouncilDecision.Insufficient,
        )
    }

    // ============================================================
    // decide() — Unanimous
    // ============================================================

    @Test
    fun `decide returns UnanimousApprove when all voting agents approve`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        val decision = council.decide(revisionId).getOrThrow()
        assertTrue(
            "expected UnanimousApprove, got $decision",
            decision is AICouncilDecision.UnanimousApprove,
        )
    }

    @Test
    fun `decide returns UnanimousReject when all voting agents reject`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.REJECT))
        council.submit(buildVote(revisionId, AIVoteDecision.REJECT))
        val decision = council.decide(revisionId).getOrThrow()
        assertTrue(
            "expected UnanimousReject, got $decision",
            decision is AICouncilDecision.UnanimousReject,
        )
    }

    @Test
    fun `decide ignores abstentions when computing consensus`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        council.submit(buildVote(revisionId, AIVoteDecision.ABSTAIN))
        val decision = council.decide(revisionId).getOrThrow()
        // Two approvals + one abstention =
        // UnanimousApprove (the abstention is
        // ignored).
        assertTrue(
            "expected UnanimousApprove, got $decision",
            decision is AICouncilDecision.UnanimousApprove,
        )
    }

    // ============================================================
    // decide() — Majority
    // ============================================================

    @Test
    fun `decide returns MajorityApprove when approvals outnumber rejections`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        council.submit(buildVote(revisionId, AIVoteDecision.REJECT))
        val decision = council.decide(revisionId).getOrThrow()
        assertTrue(
            "expected MajorityApprove, got $decision",
            decision is AICouncilDecision.MajorityApprove,
        )
        val majority = decision as AICouncilDecision.MajorityApprove
        assertEquals(1, majority.dissentingVotes.size)
    }

    @Test
    fun `decide returns MajorityReject when rejections outnumber approvals`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.REJECT))
        council.submit(buildVote(revisionId, AIVoteDecision.REJECT))
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        val decision = council.decide(revisionId).getOrThrow()
        assertTrue(
            "expected MajorityReject, got $decision",
            decision is AICouncilDecision.MajorityReject,
        )
    }

    // ============================================================
    // decide() — Split
    // ============================================================

    @Test
    fun `decide returns Split when votes are tied`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        council.submit(buildVote(revisionId, AIVoteDecision.REJECT))
        val decision = council.decide(revisionId).getOrThrow()
        assertTrue(
            "expected Split, got $decision",
            decision is AICouncilDecision.Split,
        )
    }

    @Test
    fun `decide returns Split when all three categories are present`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        council.submit(buildVote(revisionId, AIVoteDecision.REJECT))
        council.submit(buildVote(revisionId, AIVoteDecision.ESCALATE))
        val decision = council.decide(revisionId).getOrThrow()
        assertTrue(
            "expected Split, got $decision",
            decision is AICouncilDecision.Split,
        )
    }

    // ============================================================
    // decide() — Escalated
    // ============================================================

    @Test
    fun `decide returns Escalated when every voting agent escalates`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.ESCALATE))
        council.submit(buildVote(revisionId, AIVoteDecision.ESCALATE))
        val decision = council.decide(revisionId).getOrThrow()
        assertTrue(
            "expected Escalated, got $decision",
            decision is AICouncilDecision.Escalated,
        )
    }

    // ============================================================
    // decide() — averageConfidence
    // ============================================================

    @Test
    fun `decide's averageConfidence is the average of the votes' confidence`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE, confidence = BigDecimal("0.6")))
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE, confidence = BigDecimal("0.8")))
        val decision = council.decide(revisionId).getOrThrow()
        // Average of 0.6 and 0.8 = 0.7.
        val expected = BigDecimal("0.7")
        assertTrue(
            "expected averageConfidence ≈ $expected, got ${decision.averageConfidence}",
            decision.averageConfidence.subtract(expected).abs() < BigDecimal("0.01"),
        )
    }

    // ============================================================
    // applyReview() + reviewFor()
    // ============================================================

    @Test
    fun `applyReview stores a review for a known revision`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE))
        val decision = council.decide(revisionId).getOrThrow()
        val review = buildHumanReview(decision = decision, revisionId = revisionId)
        val result = council.applyReview(review)
        assertTrue("expected applyReview success, got $result", result.isSuccess)
        assertEquals(review, council.reviewFor(revisionId))
    }

    @Test
    fun `applyReview rejects a review for an unknown revision`() {
        val council = InMemoryAICouncil()
        val unknownRevisionId = VehicleRevisionId.random()
        // Build a decision + review for an
        // unknown revision (no votes).
        val decision = AICouncilDecision.Insufficient(
            revisionId = unknownRevisionId,
            votes = emptyList(),
            minimumRequired = 2,
        )
        val review = buildHumanReview(decision = decision, revisionId = unknownRevisionId)
        val result = council.applyReview(review)
        assertTrue("expected applyReview failure, got $result", result.isFailure)
        assertNull(council.reviewFor(unknownRevisionId))
    }

    @Test
    fun `reviewFor returns null for a revision with no review`() {
        val council = InMemoryAICouncil()
        assertNull(council.reviewFor(VehicleRevisionId.random()))
    }

    // ============================================================
    // End-to-end
    // ============================================================

    @Test
    fun `end-to-end 3 agents vote, council decides, human reviews`() {
        val council = InMemoryAICouncil()
        val revisionId = VehicleRevisionId.random()

        // 3 agents vote: 2 approve, 1 rejects.
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE, authorIdSuffix = "a"))
        council.submit(buildVote(revisionId, AIVoteDecision.APPROVE, authorIdSuffix = "b"))
        council.submit(buildVote(revisionId, AIVoteDecision.REJECT, authorIdSuffix = "c"))

        val decision = council.decide(revisionId).getOrThrow()
        // 2 approvals > 1 rejection = MajorityApprove.
        assertTrue(
            "expected MajorityApprove, got $decision",
            decision is AICouncilDecision.MajorityApprove,
        )

        // The human reviews the decision + approves it.
        val review = buildHumanReview(decision = decision, revisionId = revisionId)
        council.applyReview(review)
        val storedReview = council.reviewFor(revisionId)
        assertNotNull(storedReview)
        assertEquals(HumanReviewDecision.APPROVE, storedReview!!.decision)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildVote(
        revisionId: VehicleRevisionId,
        decision: AIVoteDecision,
        authorIdSuffix: String = "x",
        confidence: BigDecimal = BigDecimal("0.85"),
        timestampMs: Long = 1_000L,
    ): AIVote = AIVote(
        voteId = AIVoteId.random(),
        author = AIAuthor.ModelAgent(
            authorId = AIAuthorId.random(),
            role = AIAuthorRole.DOMAIN_EXPERT,
            modelId = "elysium-ai-$authorIdSuffix",
            modelVersion = "1.0.0",
        ),
        revisionId = revisionId,
        decision = decision,
        rationale = "The revision is well-formed",
        confidence = confidence,
        timestampMs = timestampMs,
    )

    private fun buildHumanReview(
        decision: AICouncilDecision,
        revisionId: VehicleRevisionId,
    ): HumanReview = HumanReview(
        reviewId = HumanReviewId.random(),
        reviewerId = UserId.random(),
        councilDecision = decision,
        decision = HumanReviewDecision.APPROVE,
        rationale = "After review, the proposal is sound.",
        timestampMs = 1_000L,
        signature = Signature("human-signature"),
    )
}
