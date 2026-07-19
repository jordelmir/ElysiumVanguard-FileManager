package com.elysium.vanguard.foundry.core.council

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * Phase F4 first half (G5, I-4.1) — the JVM tests
 * for the AI council's core data model.
 *
 * These tests cover:
 *   - AIAuthor (ModelAgent + HumanAgent) + id +
 *     role validation.
 *   - AIProposal invariants (blank rationale,
 *     empty evidence, out-of-range confidence,
 *     non-positive timestamp).
 *   - AIProposalEvidence (Reference, Calculation,
 *     Comparison) validation.
 *   - AIVote + AIVoteDecision validation.
 *   - AICouncilDecision cases (Unanimous, Majority,
 *     Split, Escalated, Insufficient) +
 *     validation.
 *   - HumanReview + HumanReviewDecision validation
 *     (blank rationale, non-positive timestamp,
 *     blank signature).
 */
class AICouncilCoreTest {

    // ============================================================
    // AIAuthor + AIAuthorId + AIAuthorRole
    // ============================================================

    @Test
    fun `ModelAgent accepts a well-formed configuration`() {
        val author = AIAuthor.ModelAgent(
            authorId = AIAuthorId.random(),
            role = AIAuthorRole.DOMAIN_EXPERT,
            modelId = "elysium-ai",
            modelVersion = "1.0.0",
        )
        assertEquals("elysium-ai:1.0.0", author.modelIdentifier)
    }

    @Test
    fun `ModelAgent rejects blank modelId`() {
        try {
            AIAuthor.ModelAgent(
                authorId = AIAuthorId.random(),
                role = AIAuthorRole.DOMAIN_EXPERT,
                modelId = "",
                modelVersion = "1.0.0",
            )
            fail("expected IllegalArgumentException for blank modelId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("modelId"))
        }
    }

    @Test
    fun `ModelAgent rejects blank modelVersion`() {
        try {
            AIAuthor.ModelAgent(
                authorId = AIAuthorId.random(),
                role = AIAuthorRole.DOMAIN_EXPERT,
                modelId = "elysium-ai",
                modelVersion = "",
            )
            fail("expected IllegalArgumentException for blank modelVersion")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("modelVersion"))
        }
    }

    @Test
    fun `HumanAgent accepts a well-formed configuration`() {
        val author = AIAuthor.HumanAgent(
            authorId = AIAuthorId.random(),
            role = AIAuthorRole.SAFETY_ANALYST,
            employeeId = "emp-1234",
        )
        assertEquals("emp-1234", author.employeeId)
    }

    @Test
    fun `HumanAgent rejects blank employeeId`() {
        try {
            AIAuthor.HumanAgent(
                authorId = AIAuthorId.random(),
                role = AIAuthorRole.SAFETY_ANALYST,
                employeeId = "",
            )
            fail("expected IllegalArgumentException for blank employeeId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("employeeId"))
        }
    }

    @Test
    fun `every author role has a non-blank displayLabel`() {
        for (role in AIAuthorRole.values()) {
            assertTrue(
                "expected non-blank displayLabel for $role",
                role.displayLabel.isNotBlank(),
            )
        }
    }

    // ============================================================
    // AIProposal invariants
    // ============================================================

    @Test
    fun `AIProposal accepts a well-formed configuration`() {
        val proposal = buildProposal()
        assertEquals(AIProposalKind.APPROVE, proposal.kind)
    }

    @Test
    fun `AIProposal rejects blank rationale`() {
        try {
            buildProposal(rationale = "")
            fail("expected IllegalArgumentException for blank rationale")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("rationale"))
        }
    }

    @Test
    fun `AIProposal rejects empty evidence`() {
        try {
            buildProposal(evidence = emptyList())
            fail("expected IllegalArgumentException for empty evidence")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'evidence', got: ${e.message}",
                e.message!!.contains("evidence"),
            )
        }
    }

    @Test
    fun `AIProposal rejects confidence below 0`() {
        try {
            buildProposal(confidence = BigDecimal("-0.1"))
            fail("expected IllegalArgumentException for negative confidence")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("confidence"))
        }
    }

    @Test
    fun `AIProposal rejects confidence above 1`() {
        try {
            buildProposal(confidence = BigDecimal("1.1"))
            fail("expected IllegalArgumentException for confidence > 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("confidence"))
        }
    }

    @Test
    fun `AIProposal accepts confidence at the boundaries 0 and 1`() {
        buildProposal(confidence = BigDecimal.ZERO)
        buildProposal(confidence = BigDecimal.ONE)
    }

    @Test
    fun `AIProposal rejects non-positive timestamp`() {
        try {
            buildProposal(timestampMs = 0L)
            fail("expected IllegalArgumentException for timestamp = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    // ============================================================
    // AIProposalEvidence
    // ============================================================

    @Test
    fun `Reference evidence accepts a well-formed configuration`() {
        val ev = AIProposalEvidence.Reference(
            reference = "ISO 26262-1:2018",
            source = "ISO standards catalog",
        )
        assertEquals("ISO 26262-1:2018", ev.reference)
    }

    @Test
    fun `Reference evidence rejects blank reference`() {
        try {
            AIProposalEvidence.Reference(reference = "", source = "x")
            fail("expected IllegalArgumentException for blank reference")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reference"))
        }
    }

    @Test
    fun `Reference evidence rejects blank source`() {
        try {
            AIProposalEvidence.Reference(reference = "x", source = "")
            fail("expected IllegalArgumentException for blank source")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("source"))
        }
    }

    @Test
    fun `Calculation evidence accepts a well-formed configuration`() {
        val ev = AIProposalEvidence.Calculation(
            expression = "torque = power * 7121 / rpm",
            result = "350 Nm @ 5000 rpm",
        )
        assertEquals("torque = power * 7121 / rpm", ev.expression)
    }

    @Test
    fun `Calculation evidence rejects blank expression`() {
        try {
            AIProposalEvidence.Calculation(expression = "", result = "x")
            fail("expected IllegalArgumentException for blank expression")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("expression"))
        }
    }

    @Test
    fun `Calculation evidence rejects blank result`() {
        try {
            AIProposalEvidence.Calculation(expression = "x", result = "")
            fail("expected IllegalArgumentException for blank result")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("result"))
        }
    }

    @Test
    fun `Comparison evidence accepts a well-formed configuration`() {
        val ev = AIProposalEvidence.Comparison(
            comparesTo = "2024 reference design",
            comparison = "matches within 0.5% tolerance",
        )
        assertEquals("2024 reference design", ev.comparesTo)
    }

    @Test
    fun `Comparison evidence rejects blank comparesTo`() {
        try {
            AIProposalEvidence.Comparison(comparesTo = "", comparison = "x")
            fail("expected IllegalArgumentException for blank comparesTo")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("comparesTo"))
        }
    }

    @Test
    fun `Comparison evidence rejects blank comparison`() {
        try {
            AIProposalEvidence.Comparison(comparesTo = "x", comparison = "")
            fail("expected IllegalArgumentException for blank comparison")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("comparison"))
        }
    }

    // ============================================================
    // AIVote
    // ============================================================

    @Test
    fun `AIVote accepts a well-formed configuration`() {
        val vote = buildVote(decision = AIVoteDecision.APPROVE)
        assertEquals(AIVoteDecision.APPROVE, vote.decision)
    }

    @Test
    fun `AIVote rejects blank rationale`() {
        try {
            buildVote(rationale = "")
            fail("expected IllegalArgumentException for blank rationale")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("rationale"))
        }
    }

    @Test
    fun `AIVote rejects confidence below 0`() {
        try {
            buildVote(confidence = BigDecimal("-0.1"))
            fail("expected IllegalArgumentException for negative confidence")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("confidence"))
        }
    }

    @Test
    fun `AIVote rejects non-positive timestamp`() {
        try {
            buildVote(timestampMs = 0L)
            fail("expected IllegalArgumentException for timestamp = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    @Test
    fun `every vote decision has a non-blank displayLabel`() {
        for (decision in AIVoteDecision.values()) {
            assertTrue(
                "expected non-blank displayLabel for $decision",
                decision.displayLabel.isNotBlank(),
            )
        }
    }

    // ============================================================
    // AICouncilDecision
    // ============================================================

    @Test
    fun `UnanimousApprove accepts a well-formed configuration`() {
        val decision = AICouncilDecision.UnanimousApprove(
            revisionId = VehicleRevisionId.random(),
            votes = listOf(
                buildVote(decision = AIVoteDecision.APPROVE),
                buildVote(decision = AIVoteDecision.APPROVE),
            ),
            averageConfidence = BigDecimal("0.95"),
        )
        assertEquals(BigDecimal("0.95"), decision.averageConfidence)
    }

    @Test
    fun `UnanimousReject accepts a well-formed configuration`() {
        val decision = AICouncilDecision.UnanimousReject(
            revisionId = VehicleRevisionId.random(),
            votes = listOf(
                buildVote(decision = AIVoteDecision.REJECT),
                buildVote(decision = AIVoteDecision.REJECT),
            ),
            averageConfidence = BigDecimal("0.95"),
        )
        assertEquals(BigDecimal("0.95"), decision.averageConfidence)
    }

    @Test
    fun `MajorityApprove accepts dissenting votes`() {
        val decision = AICouncilDecision.MajorityApprove(
            revisionId = VehicleRevisionId.random(),
            votes = listOf(
                buildVote(decision = AIVoteDecision.APPROVE),
                buildVote(decision = AIVoteDecision.APPROVE),
                buildVote(decision = AIVoteDecision.REJECT),
            ),
            averageConfidence = BigDecimal("0.85"),
            dissentingVotes = listOf(
                buildVote(decision = AIVoteDecision.REJECT),
            ),
        )
        assertEquals(1, decision.dissentingVotes.size)
    }

    @Test
    fun `Split decision rejects blank tieBreakReason`() {
        try {
            AICouncilDecision.Split(
                revisionId = VehicleRevisionId.random(),
                votes = listOf(
                    buildVote(decision = AIVoteDecision.APPROVE),
                    buildVote(decision = AIVoteDecision.REJECT),
                ),
                averageConfidence = BigDecimal("0.5"),
                tieBreakReason = "",
            )
            fail("expected IllegalArgumentException for blank tieBreakReason")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("tieBreakReason"))
        }
    }

    @Test
    fun `Escalated decision rejects blank escalationReason`() {
        try {
            AICouncilDecision.Escalated(
                revisionId = VehicleRevisionId.random(),
                votes = listOf(
                    buildVote(decision = AIVoteDecision.ESCALATE),
                ),
                averageConfidence = BigDecimal("0.95"),
                escalationReason = "",
            )
            fail("expected IllegalArgumentException for blank escalationReason")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("escalationReason"))
        }
    }

    @Test
    fun `Insufficient decision requires minimum 2 or more`() {
        try {
            AICouncilDecision.Insufficient(
                revisionId = VehicleRevisionId.random(),
                votes = emptyList(),
                minimumRequired = 1,
            )
            fail("expected IllegalArgumentException for minimumRequired < 2")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("minimumRequired"))
        }
    }

    @Test
    fun `Insufficient decision's averageConfidence is zero`() {
        val decision = AICouncilDecision.Insufficient(
            revisionId = VehicleRevisionId.random(),
            votes = emptyList(),
            minimumRequired = 2,
        )
        assertEquals(BigDecimal.ZERO, decision.averageConfidence)
    }

    // ============================================================
    // HumanReview
    // ============================================================

    @Test
    fun `HumanReview accepts a well-formed configuration`() {
        val review = HumanReview(
            reviewId = HumanReviewId.random(),
            reviewerId = UserId.random(),
            councilDecision = AICouncilDecision.Insufficient(
                revisionId = VehicleRevisionId.random(),
                votes = emptyList(),
                minimumRequired = 2,
            ),
            decision = HumanReviewDecision.APPROVE,
            rationale = "After review, the proposal is sound.",
            timestampMs = 1_000L,
            signature = Signature("human-signature"),
        )
        assertEquals(HumanReviewDecision.APPROVE, review.decision)
    }

    @Test
    fun `HumanReview rejects blank rationale`() {
        try {
            buildHumanReview(rationale = "")
            fail("expected IllegalArgumentException for blank rationale")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("rationale"))
        }
    }

    @Test
    fun `HumanReview rejects non-positive timestamp`() {
        try {
            buildHumanReview(timestampMs = 0L)
            fail("expected IllegalArgumentException for timestamp = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    @Test
    fun `HumanReview rejects blank signature`() {
        try {
            buildHumanReview(signature = Signature(""))
            fail("expected IllegalArgumentException for blank signature")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'Signature', got: ${e.message}",
                e.message!!.contains("Signature"),
            )
        }
    }

    @Test
    fun `every HumanReviewDecision has a non-blank displayLabel`() {
        for (decision in HumanReviewDecision.values()) {
            assertTrue(
                "expected non-blank displayLabel for $decision",
                decision.displayLabel.isNotBlank(),
            )
        }
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildProposal(
        rationale: String = "The proposal is well-formed",
        evidence: List<AIProposalEvidence> = listOf(
            AIProposalEvidence.Reference(
                reference = "ISO 26262",
                source = "ISO standards",
            ),
        ),
        confidence: BigDecimal = BigDecimal("0.85"),
        timestampMs: Long = 1_000L,
        kind: AIProposalKind = AIProposalKind.APPROVE,
    ): AIProposal = AIProposal(
        proposalId = AIProposalId.random(),
        author = AIAuthor.ModelAgent(
            authorId = AIAuthorId.random(),
            role = AIAuthorRole.DOMAIN_EXPERT,
            modelId = "elysium-ai",
            modelVersion = "1.0.0",
        ),
        targetRevisionId = VehicleRevisionId.random(),
        kind = kind,
        rationale = rationale,
        evidence = evidence,
        confidence = confidence,
        timestampMs = timestampMs,
    )

    private fun buildVote(
        decision: AIVoteDecision = AIVoteDecision.APPROVE,
        rationale: String = "The revision is well-formed",
        confidence: BigDecimal = BigDecimal("0.85"),
        timestampMs: Long = 1_000L,
    ): AIVote = AIVote(
        voteId = AIVoteId.random(),
        author = AIAuthor.ModelAgent(
            authorId = AIAuthorId.random(),
            role = AIAuthorRole.DOMAIN_EXPERT,
            modelId = "elysium-ai",
            modelVersion = "1.0.0",
        ),
        revisionId = VehicleRevisionId.random(),
        decision = decision,
        rationale = rationale,
        confidence = confidence,
        timestampMs = timestampMs,
    )

    private fun buildHumanReview(
        rationale: String = "After review, the proposal is sound.",
        timestampMs: Long = 1_000L,
        signature: Signature = Signature("human-signature"),
    ): HumanReview = HumanReview(
        reviewId = HumanReviewId.random(),
        reviewerId = UserId.random(),
        councilDecision = AICouncilDecision.Insufficient(
            revisionId = VehicleRevisionId.random(),
            votes = emptyList(),
            minimumRequired = 2,
        ),
        decision = HumanReviewDecision.APPROVE,
        rationale = rationale,
        timestampMs = timestampMs,
        signature = signature,
    )
}
