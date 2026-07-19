# Phase F4 first half (G5, I-4.1) — AI Council Core Data Model

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** F4 / Foundry / AI council
> **Predecessor:** Foundry Phase F3 (3D pipeline + digital twin)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.council.*`)

---

## TL;DR

The AI council's **core data model** is operational. The
council's typed envelope is:

- **`AIAuthor`** — sealed class with 2 cases (ModelAgent +
  HumanAgent). Every proposal has a typed author with a
  role.
- **`AIProposal`** — typed envelope an agent writes to the
  council. Has id + author + target revision + kind +
  rationale + evidence + confidence (BigDecimal) + timestamp.
- **`AIProposalKind`** — enum (APPROVE / REJECT /
  REQUEST_EVIDENCE / ESCALATE).
- **`AIProposalEvidence`** — sealed class with 3 cases
  (Reference / Calculation / Comparison).
- **`AIVote`** + **`AIVoteDecision`** — individual agent's
  vote on a revision.
- **`AICouncilDecision`** — sealed class with 7 cases
  (UnanimousApprove / UnanimousReject / MajorityApprove /
  MajorityReject / Split / Escalated / Insufficient).
- **`HumanReview`** + **`HumanReviewDecision`** — the
  human's final decision on a council decision.

This is the **first of two halves** in Phase F4 (G5). The
next half is the **AICouncil orchestrator** — the
in-memory implementation that aggregates votes + computes
consensus + escalates to human review.

---

## What shipped

### `AIAuthor` (sealed class, 2 cases)

```kotlin
sealed class AIAuthor {
    abstract val authorId: AIAuthorId
    abstract val role: AIAuthorRole

    data class ModelAgent(
        override val authorId: AIAuthorId,
        override val role: AIAuthorRole,
        val modelId: String,
        val modelVersion: String,
    ) : AIAuthor()

    data class HumanAgent(
        override val authorId: AIAuthorId,
        override val role: AIAuthorRole,
        val employeeId: String,
    ) : AIAuthor()
}
```

The `ModelAgent` carries the model's id + version (e.g.
`"elysium-ai:1.0.0"`) for audit. The `HumanAgent` carries
the employee's id. Both have a `role` (the typed "hat" the
agent is wearing).

### `AIAuthorRole` (enum, 6 values)

| Role | Display | Lens |
| --- | --- | --- |
| `DOMAIN_EXPERT` | Domain Expert | Vehicle's powertrain, body, driveline |
| `COMPLIANCE_REVIEWER` | Compliance Reviewer | Regulatory requirements (emissions, safety standards, market access) |
| `SAFETY_ANALYST` | Safety Analyst | Physical safety (crash safety, brake performance, occupant protection) |
| `PERFORMANCE_ENGINEER` | Performance Engineer | Performance characteristics (power, torque, fuel economy, NVH) |
| `COST_ANALYST` | Cost Analyst | Cost implications (BOM cost, tooling cost, lifecycle cost) |
| `GENERAL_REVIEWER` | General Reviewer | Holistic assessment without a specific lens |

### `AIProposal` (data class)

The typed envelope an agent writes to the council. The
proposal has:

- **`proposalId`** — UUID.
- **`author`** — `AIAuthor` (sealed class).
- **`targetRevisionId`** — `VehicleRevisionId` (the proposal
  targets exactly one revision).
- **`kind`** — `AIProposalKind` (APPROVE / REJECT /
  REQUEST_EVIDENCE / ESCALATE).
- **`rationale`** — the human-readable reasoning (non-blank).
- **`evidence`** — the list of `AIProposalEvidence` (at
  least one piece of evidence; an evidence-less proposal
  is a smell).
- **`confidence`** — `BigDecimal` in [0, 1] (per ADR-0001
  "Money is BigDecimal, never Double/Float"; the same
  principle applies to confidence).
- **`timestampMs`** — millis since epoch (> 0).

### `AIProposalKind` (enum, 4 values)

| Kind | Meaning |
| --- | --- |
| `APPROVE` | The agent approves the target revision. |
| `REJECT` | The agent rejects the target revision. |
| `REQUEST_EVIDENCE` | The agent requests more evidence (conditional proposal). |
| `ESCALATE` | The agent escalates to human review (the proposal exceeds the AI's authority). |

### `AIProposalEvidence` (sealed class, 3 cases)

The **machine-readable** support for a proposal. The 3
cases are:

- **`Reference(reference, source)`** — a link to an
  external source (a standard, a regulation, a research
  paper).
- **`Calculation(expression, result)`** — a calculation
  result (e.g. "torque = power * 7121 / rpm = 350 Nm").
- **`Comparison(comparesTo, comparison)`** — a comparison
  to a known reference (e.g. "matches the 2024 reference
  design within 0.5% tolerance").

Every proposal must cite at least one piece of evidence.
This is the platform's response to the AI authority
boundary's "without evidence" clause (per `.ai/AGENTS.md`
section 21.3: AI may NOT create verified technical facts
without evidence).

### `AIVote` + `AIVoteDecision` (data class + enum, 4 values)

An individual agent's vote on a revision. The vote has:

- **`voteId`** — UUID.
- **`author`** — `AIAuthor`.
- **`revisionId`** — `VehicleRevisionId`.
- **`decision`** — `AIVoteDecision` (APPROVE / REJECT /
  ABSTAIN / ESCALATE).
- **`rationale`** — non-blank.
- **`confidence`** — `BigDecimal` in [0, 1].
- **`timestampMs`** — millis since epoch (> 0).

The `ABSTAIN` decision is the **agent's typed declination
to vote** (the agent does not have sufficient expertise on
the revision). `ESCALATE` defers to human review.

### `AICouncilDecision` (sealed class, 7 cases)

The **aggregated result** of the council's deliberation.
The 7 cases are:

| Case | Meaning | Human review required? |
| --- | --- | --- |
| `UnanimousApprove` | Every voting agent approved. | No |
| `UnanimousReject` | Every voting agent rejected. | No |
| `MajorityApprove` | Majority approved; dissenters recorded. | No |
| `MajorityReject` | Majority rejected; dissenters recorded. | No |
| `Split` | No clear majority. | **Yes** (tie-break) |
| `Escalated` | Every voting agent escalated. | **Yes** (authority exceeded) |
| `Insufficient` | Fewer than 2 voting agents. | **Yes** (council couldn't decide) |

Every case has:

- **`revisionId`** — the target revision.
- **`votes`** — the individual votes (sorted by timestamp).
- **`averageConfidence`** — `BigDecimal` in [0, 1] (the
  average across all voting agents).
- (Case-specific fields) — `dissentingVotes` for
  `MajorityApprove` / `MajorityReject`; `tieBreakReason`
  for `Split`; `escalationReason` for `Escalated`;
  `minimumRequired` for `Insufficient`.

### `HumanReview` (data class)

The **human's final decision** on a council decision. The
review has:

- **`reviewId`** — UUID.
- **`reviewerId`** — `UserId` (the platform user; NOT a
  model agent).
- **`councilDecision`** — the `AICouncilDecision` the human
  is reviewing.
- **`decision`** — `HumanReviewDecision` (APPROVE / REJECT
  / DEFER).
- **`rationale`** — non-blank.
- **`timestampMs`** — millis since epoch (> 0).
- **`signature`** — `Signature` (the human signs the
  review; the signature binds the human to the decision
  for audit + legal).

### `HumanReviewDecision` (enum, 3 values)

| Decision | Meaning |
| --- | --- |
| `APPROVE` | The human approves the council's decision. Final. |
| `REJECT` | The human rejects the council's decision. Final. |
| `DEFER` | The human defers; the council re-deliberates with additional context. |

---

## Design decisions

### Why a sealed class for `AIAuthor`, not a single class with a flag?

A sealed class is **type-safe**. The compiler knows the
author is one of exactly 2 kinds (ModelAgent or HumanAgent).
A `when` on the author is **exhaustive** — adding a 3rd kind
(e.g. `RuleBasedAgent` for a heuristic engine) is a compile
error in every consumer that hasn't been updated.

A single class with a `kind: AuthorKind` flag is **stringly
typed** — the flag is a string, the `when` on the flag is
not exhaustive, and a typo is a silent default.

The 2 cases reflect the **2 distinct agent types** the
platform supports (AI models + human experts). The
sealed class captures the distinction at the type level.

### Why a sealed class for `AICouncilDecision`, not a single class with a flag?

A sealed class is **type-safe + exhaustive**. The
council's decision is one of 7 distinct cases (Unanimous
Approve / Reject, Majority Approve / Reject, Split,
Escalated, Insufficient). A `when` on the decision is
**exhaustive** — adding an 8th case is a compile error in
every consumer that hasn't been updated.

The 7 cases reflect the **7 distinct outcomes** the council
can produce. The cases are not just "approve / reject /
escalate" — they capture the **voting distribution**
(unanimous vs majority) + the **reason** (split vs escalated
vs insufficient). A single class with a flag would lose
this distinction.

### Why is `confidence` a `BigDecimal`, not a `Double`?

Per ADR-0001 ("Money is `BigDecimal`, never `Double`/`Float`"),
the platform uses `BigDecimal` for **precision-sensitive**
values. Confidence is not money, but it has the same
property: precision matters for audit + reproducibility.

A `Double` would lose precision (e.g. `0.1 + 0.2 = 0.30000000000000004`),
and the average across multiple votes would compound the
error. A `BigDecimal` preserves precision exactly.

The trade-off: a `BigDecimal` is slightly more verbose to
construct (`BigDecimal("0.85")` vs `0.85`). The verbosity
is the cost of precision.

### Why is `evidence` a sealed class, not a list of strings?

A list of strings is **free-form** — the platform cannot
verify the evidence type, the source, the calculation
result, etc. A sealed class is **typed** — every piece of
evidence has a known type (Reference / Calculation /
Comparison) + known fields.

The platform can:

- **Verify** the evidence (e.g. "is the source a valid
  ISO standard?").
- **Render** the evidence (e.g. "show the calculation
  step by step").
- **Audit** the evidence (e.g. "the agent cited ISO 26262
  — is that the right standard?").

A list of strings would lose all of this.

### Why is `HumanReview.signature` mandatory, not optional?

Per `.ai/AGENTS.md` section 21.3: the human's review is the
**final authority** on decisions that exceed the AI's
authority (safety-critical, regulatory, mechanical
compatibility, financial settlements, legal ownership).
A decision without a signature is **not auditable** — the
platform cannot prove the human made the decision.

The signature is **mandatory** because the human review is
a **legal + audit** artifact. An unsigned review is a
**deployment error**.

---

## Bug-fixes (test-discovered, fixed in this phase)

### 1. Backtick-quoted test name with `>` character

**Symptom:** `e: AICouncilCoreTest.kt:408:9 Name contains
illegal characters: >` at compile time.

**Root cause:** The test name `Insufficient decision
requires minimum >= 2` contained a `>` character; Kotlin
backtick-quoted names reject `>` (per the memory rule).

**Fix:** Renamed to `Insufficient decision requires minimum
2 or more` (no `>` character).

This is a **test-discovered** bug — the test didn't exist
before this phase, and the memory rule fired at compile
time.

### 2. Assertion checked wrong substring for signature error

**Symptom:** `HumanReview rejects blank signature FAILED`.

**Root cause:** The test asserted `e.message!!.contains("signature")`
(lowercase), but the actual error message was "Signature
must not be empty" (capital S, from the `Signature` value
class's init block).

**Fix:** Changed the assertion to check for "Signature"
(capital S). The test now exercises the right error path.

---

## Tests

39 new tests in `AICouncilCoreTest`. The tests cover:

- **AIAuthor + AIAuthorId + AIAuthorRole** (6 tests):
  ModelAgent happy path + blank rejection x2, HumanAgent
  happy path + blank rejection, role displayLabel check.
- **AIProposal invariants** (7 tests): happy path, blank
  rationale, empty evidence, confidence < 0, confidence > 1,
  confidence at boundaries, non-positive timestamp.
- **AIProposalEvidence** (9 tests): Reference (happy +
  blank reference + blank source), Calculation (happy +
  blank expression + blank result), Comparison (happy +
  blank comparesTo + blank comparison).
- **AIVote** (5 tests): happy path, blank rationale,
  negative confidence, non-positive timestamp, decision
  displayLabel.
- **AICouncilDecision** (7 tests): UnanimousApprove,
  UnanimousReject, MajorityApprove (with dissenters),
  Split (blank tieBreakReason rejected), Escalated
  (blank escalationReason rejected), Insufficient
  (minimum < 2 rejected), Insufficient's averageConfidence
  is zero.
- **HumanReview** (5 tests): happy path, blank rationale,
  non-positive timestamp, blank signature, decision
  displayLabel.

**Total foundry tests:** ~600+ (was ~560; +39 new).
**Total project tests:** 2967 (was 2928, +39 new).

---

## What's next — Phase F4 second half (G5, I-4.2)

`AICouncil` orchestrator — the in-memory implementation that:

1. **Aggregates votes** — the `submit(vote)` method
   stores a vote in the council's vote store.
2. **Computes consensus** — the `decide(revisionId)` method
   reads the votes + computes an `AICouncilDecision`
   (Unanimous / Majority / Split / Escalated / Insufficient).
3. **Records the human review** — the `applyReview(review)`
   method stores the human's final decision.

The orchestrator is **pure-domain** (no I/O, no Android
dependencies). The test implementation is an in-memory
`InMemoryAICouncil`; the production implementation is a
distributed `RemoteAICouncil` (a future Phase 7+ increment
for the multi-node consensus).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/foundry/core/council/AIAuthor.kt` | new | sealed class + id + role |
| `app/src/main/java/com/elysium/vanguard/foundry/core/council/AIProposal.kt` | new | data class + id + kind + evidence |
| `app/src/main/java/com/elysium/vanguard/foundry/core/council/AICouncilDecision.kt` | new | decision sealed class + vote + human review + decision enums |
| `app/src/test/java/com/elysium/vanguard/foundry/core/council/AICouncilCoreTest.kt` | new | 39 JVM tests |

---

## The role in the bigger picture

The AI council's core data model is the **typed envelope**
for multi-agent deliberation. Every AI proposal + every
vote + every decision + every human review is a typed
value — the platform cannot lose track of "who said what,
when, with what evidence, with what confidence, with what
human review".

The model is the **foundation** for the council
orchestrator (Phase F4 second half). The orchestrator uses
the model to aggregate votes + compute consensus +
escalate to human review + record the human's final
decision.

The model is the **bridge** between the AI's interpretive
authority (per `.ai/AGENTS.md` section 21.3) and the
human's final authority (per the same section). The bridge
ensures:

- The AI **interprets, proposes, drafts, explains** (the
  `AIProposal` envelope).
- The AI may NOT directly **approve safety-critical,
  certify regulatory, declare mechanical compatibility,
  finalize financial settlements, determine legal
  ownership, modify signed releases, or create verified
  technical facts without evidence** (the
  `Escalated` / `Split` / `Insufficient` cases + the
  `HumanReview` envelope).
- The human has the **final authority** on these
  decisions (the `HumanReview.decision = APPROVE/REJECT`
  is final; `DEFER` re-deliberates).
