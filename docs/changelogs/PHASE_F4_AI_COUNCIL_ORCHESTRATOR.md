# Phase F4 second half (G5, I-4.2) — AI Council Orchestrator

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** F4 / Foundry / AI council
> **Predecessor:** Phase F4 first half (core data model)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.council.*`)

---

## TL;DR

The AI council's **orchestrator** is operational. The
in-memory implementation:

1. **Stores votes** keyed by `revisionId`.
2. **Computes consensus** — the `decide(revisionId)`
   method returns the appropriate `AICouncilDecision`
   (Unanimous / Majority / Split / Escalated / Insufficient).
3. **Records human reviews** — the `applyReview(review)`
   method stores the human's final decision.

The orchestrator follows the **AI authority boundary** (per
`.ai/AGENTS.md` section 21.3): the council may produce a
decision, but the human has the **final authority** on
safety-critical, regulatory, mechanical compatibility,
financial settlements, legal ownership decisions.

**Phase F4 is now CLOSED** (G5 closure). The Foundry has
the multi-agent deliberation system.

---

## What shipped

### `AICouncil` (interface)

```kotlin
interface AICouncil {
    fun submit(vote: AIVote): Result<Unit>
    fun decide(revisionId: VehicleRevisionId): Result<AICouncilDecision>
    fun applyReview(review: HumanReview): Result<Unit>
    fun reviewFor(revisionId: VehicleRevisionId): HumanReview?
    fun votesFor(revisionId: VehicleRevisionId): List<AIVote>
}
```

The interface is the **orchestrator contract**. The
in-memory implementation (`InMemoryAICouncil`) is the test
+ local runtime; the production implementation
(`RemoteAICouncil`) is a future Phase 7+ increment for
multi-node consensus.

### `InMemoryAICouncil` (class)

The in-memory implementation. The class has:

- **`votesByRevision`** — `ConcurrentHashMap<
  VehicleRevisionId, CopyOnWriteArrayList<AIVote>>`
  (the votes, sorted by timestamp).
- **`reviewById`** — `ConcurrentHashMap<HumanReviewId,
  HumanReview>` (the reviews, keyed by review id).
- **`reviewByRevision`** — `ConcurrentHashMap<
  VehicleRevisionId, HumanReview>` (the reviews,
  indexed by revision for fast lookup).

The class is **thread-safe** (the underlying maps are
`ConcurrentHashMap`; the votes list is
`CopyOnWriteArrayList` for safe iteration during
aggregation).

### The consensus algorithm

The `decide(revisionId)` method computes the consensus:

1. **Filter abstentions.** Abstentions do not count toward
   the decision.
2. **Insufficient.** If fewer than 2 voting agents → return
   `Insufficient`.
3. **Count decisions.** Count `APPROVE`, `REJECT`,
   `ESCALATE` in the voting set.
4. **All escalations.** If every voting agent escalated →
   return `Escalated` with reason "every voting agent
   escalated".
5. **Unanimous approve.** If every voting agent approved
   → return `UnanimousApprove`.
6. **Unanimous reject.** If every voting agent rejected
   → return `UnanimousReject`.
7. **Majority approve.** If approvals > rejections AND
   approvals > escalations → return `MajorityApprove` with
   the dissenters.
8. **Majority reject.** If rejections > approvals AND
   rejections > escalations → return `MajorityReject` with
   the dissenters.
9. **Split.** Otherwise → return `Split` with a descriptive
   `tieBreakReason`.

The algorithm is **deterministic** + **total**: every
vote distribution produces exactly one decision.

### The average confidence

The `decide` method computes the **average confidence**
across all votes (including abstentions). The average uses
`BigDecimal` for precision (per ADR-0001). The result is in
[0, 1].

### The human review

The `applyReview(review)` method:

1. Checks that the review's `councilDecision.revisionId`
   has at least one vote (a review for an unknown revision
   is rejected).
2. Stores the review in `reviewById` (keyed by `reviewId`)
   + `reviewByRevision` (keyed by `revisionId`).

The human's review is the **final authority**: the council
can produce a decision, but the human can override it (the
override is a `HumanReview.decision = APPROVE / REJECT`).

---

## Design decisions

### Why an interface + in-memory implementation, not a single class?

A **pure-domain interface** + a **test implementation** is
the platform's standard pattern (per `.ai/AGENTS.md`
section 24.1). The interface is the **typed contract**; the
implementation is the **runtime** that satisfies the
contract.

A single class would conflate the contract with the
implementation. A future Phase 7+ increment can add a
`RemoteAICouncil` (a distributed consensus via the Vanguard
Cloud) without changing the consumers — the consumers talk
to the interface.

### Why `CopyOnWriteArrayList` for the votes?

The votes list is **read-mostly** (a revision is voted on a
few times, then the consensus is computed + the list is
read for display). `CopyOnWriteArrayList` is **optimized
for many reads + few writes**: the read path is lock-free
(fast iteration during aggregation), and the write path
copies the list (acceptable for the small number of writes).

A regular `ArrayList` would require explicit synchronization
for safe iteration during aggregation. The
`CopyOnWriteArrayList` removes the need for explicit
synchronization.

### Why are abstentions ignored in the consensus?

Abstentions are the agent's **typed declination to vote**
(per the `AIVoteDecision.ABSTAIN` enum case). The agent
says "I don't have enough expertise on this revision to
vote". An abstention is **not a vote** — it does not count
toward the decision.

A revision with 2 approvals + 1 abstention is a
**UnanimousApprove** (the abstention is ignored; the 2
approvals are unanimous). This is the correct semantics: a
non-vote is a non-vote, not a vote against.

### Why does `applyReview` reject unknown revisions?

A review for an unknown revision is a **deployment error**:
the human is reviewing a decision the council never
produced. The platform rejects the review as a typed
`FoundryError.VehicleDefinitionInvalid` to surface the
error.

The check is **fast** (one `containsKey` on a
`ConcurrentHashMap`) and **safe** (the check is atomic with
the write).

### Why is the `Escalated` reason "every voting agent escalated"?

The `Escalated` decision's `escalationReason` is a
**machine-readable** description of why the council
escalated. The reason "every voting agent escalated" is
the **canonical** reason: the council cannot make a
decision because every voting agent deferred to the human.

A future increment can add **more detailed reasons** (e.g.
"the proposal targets a safety-critical revision" + "the
agent explicitly cited AI authority boundary clause 21.3.2"
+ ...). For now, the canonical reason is sufficient.

---

## Bug-fixes (test-discovered, fixed in this phase)

### 1. `FoundryError.Companion` unresolved reference

**Symptom:** `Unresolved reference: Companion` at compile
time.

**Root cause:** I accidentally typed
`ConcurrentHashMap<com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError.Companion, ...>`
when I meant to use a different type for the
`HumanReviewId` -> `HumanReview` map.

**Fix:** Replaced with `ConcurrentHashMap<HumanReviewId, HumanReview>`.

### 2. `!in` operator on `ConcurrentHashMap` ambiguous

**Symptom:** `Method 'contains' from ConcurrentHashMap may
have unexpected semantics: it calls 'containsValue' instead
of 'containsKey'`.

**Root cause:** Kotlin's `!in` operator on
`ConcurrentHashMap` calls `containsValue` (which scans all
values) instead of `containsKey` (which is O(1)). The
operator is ambiguous.

**Fix:** Replaced `revisionId !in votesByRevision` with
`!votesByRevision.containsKey(revisionId)` for explicit
O(1) key lookup.

### 3. Backtick-quoted test name with `:` character

**Symptom:** `Name contains illegal characters: :` at
compile time.

**Root cause:** The test name `end-to-end: 3 agents vote,
...` contained a `:` character; Kotlin backtick-quoted
names reject `:` (per the memory rule).

**Fix:** Renamed to `end-to-end 3 agents vote, council
decides, human reviews` (no `:` character).

---

## Tests

19 new tests in `AICouncilTest`. The tests cover:

- **submit() + votesFor()** (4 tests): stores a vote,
  stores multiple votes, sorts by timestamp, returns empty
  for unknown revision.
- **decide() — Insufficient** (2 tests): zero votes, one
  vote.
- **decide() — Unanimous** (3 tests): all approve, all
  reject, ignores abstentions.
- **decide() — Majority** (2 tests): majority approve with
  dissenters, majority reject.
- **decide() — Split** (2 tests): tied votes, three
  categories present.
- **decide() — Escalated** (1 test): every voting agent
  escalated.
- **decide() — averageConfidence** (1 test): average
  confidence across votes.
- **applyReview() + reviewFor()** (3 tests): stores a
  review, rejects unknown revision, returns null for
  unknown revision.
- **End-to-end** (1 test): 3 agents vote, council decides,
  human reviews.

**Total foundry tests:** ~620 (was ~600; +19 new).
**Total project tests:** 2986 (was 2967, +19 new).

---

## Phase F4 — CLOSED

With the orchestrator shipped, **Phase F4 (G5) is closed**.
The Foundry has the full multi-agent deliberation system:

- **Phase F4 first half**: core data model (AIAuthor +
  AIProposal + AIVote + AICouncilDecision + HumanReview).
- **Phase F4 second half**: orchestrator (in-memory impl +
  consensus algorithm + human review recording).

The council is **operational**:
- Agents submit typed proposals + votes.
- The orchestrator aggregates votes into a typed consensus.
- The human reviews the consensus + makes the final
  decision (when required).

The next Phase F5 (G6+G7) increment is the **commercial
foundation**: `RoyaltyContract` + `License` + royalty
engine.

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/foundry/core/council/AICouncil.kt` | new | interface + InMemoryAICouncil orchestrator |
| `app/src/test/java/com/elysium/vanguard/foundry/core/council/AICouncilTest.kt` | new | 19 JVM tests |

---

## The role in the bigger picture

The AI council is the **multi-agent deliberation system**
that closes the AI authority boundary loop. Per
`.ai/AGENTS.md` section 21.3:

- The AI may **interpret, propose, draft, explain** (the
  `AIProposal` envelope + the `submit(vote)` orchestrator).
- The AI may NOT directly **approve safety-critical,
  certify regulatory, declare mechanical compatibility,
  finalize financial settlements, determine legal
  ownership, modify signed releases, or create verified
  technical facts without evidence**.
- The human has the **final authority** (the
  `applyReview(review)` orchestrator).

The orchestrator is the **runtime** that enforces the
authority boundary. Every proposal + every vote + every
decision + every human review is a typed value; the
platform cannot lose track of "who said what, when, with
what evidence, with what confidence, with what human
review".

The council is the **bridge** between the AI's
interpretive authority and the human's final authority.
The bridge is the platform's compliance with the AI
authority boundary — the **non-negotiable** line that
prevents AI from overstepping into safety-critical,
regulatory, mechanical compatibility, financial, or
legal domains.
