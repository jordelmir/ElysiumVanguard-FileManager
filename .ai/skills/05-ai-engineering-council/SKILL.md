---
name: ai-engineering-council
description: The multi-agent deliberation, voting, and arbitration system. Every non-trivial decision in the platform goes through the council. The council is the AI counterpart of a human engineering review board.
---

# Skill 05 — AI Engineering Council

## 1. Mission

Run the **multi-agent deliberation** for every
non-trivial decision in the platform. The council
is the AI counterpart of a human engineering
review board. It does not write product code; it
*deliberates* on the decisions other skills bring
to it.

The council is **not** a chat between AI agents.
The council is a **structured process** with
roles, voting rules, escalation paths, and audit
trails. Every decision the council makes is
recorded, signed, and filed in the catalog.

## 2. In-scope

- Deliberating on architectural decisions (the
  orchestrator escalates to the council).
- Deliberating on requirement ambiguities (skill
  02 escalates).
- Deliberating on DSL grammar changes (skill 04
  escalates).
- Deliberating on ontology changes (skill 03
  escalates).
- Deliberating on security trade-offs (skill 12
  escalates).
- Deliberating on regulatory trade-offs (skill
  13 escalates).
- Deliberating on cross-skill contract disputes
  (the orchestrator escalates).
- Translating natural-language user requests into
  formal DSL / ontology operations (in
  collaboration with skill 04).
- Maintaining the "council precedent" — past
  decisions that constrain future ones.

## 3. Out-of-scope

- Writing product code.
- Writing tests.
- Writing ADRs (the orchestrator does this).
- Implementing the decision (the responsible
  skill does this).
- Running CI (skill 15).

A request to the council that turns out to be a
product code request is handed off. The council
*decides*; the skills *implement*.

## 4. Inputs

- A `CouncilRequest` — a structured artifact
  under `.ai/council/requests/<id>.md`. The
  request includes:
  - The question (one sentence).
  - The context (background, prior art, the
    ADRs the question is in scope of).
  - The options (2-5 alternatives, each with a
    one-paragraph summary + the trade-offs).
  - The recommendation (the requesting skill's
    preferred option, with a one-paragraph
    rationale).
  - The deadline (the council has N days to
    respond; missing the deadline is an
    escalation).
- A quorum (the minimum number of council
  members required for a valid decision;
  defaults to 3).
- A voting rule (simple majority, supermajority,
  unanimous; defaults to simple majority).

## 5. Outputs

- A `CouncilDecision` — a structured artifact
  under `.ai/council/decisions/<id>.md`. The
  decision includes:
  - The question (verbatim from the request).
  - The vote (each member's vote + rationale).
  - The decision (the chosen option).
  - The dissent (any minority opinion, with a
    one-paragraph rationale; dissent is not a
    bug, it is a feature).
  - The implementation handoff (which skill
    implements the decision; the orchestrator
    wires the handoff).
  - The precedent (a one-line summary the next
    council can cite).
  - The signature (every council member signs
    the decision; the orchestrator signs the
    final artifact).
- An updated `council-precedent.md` (a running
  list of the one-line summaries).

The decision is **the** authoritative source.
The ADRs reference the decision; the
implementation skills consume the decision
through the orchestrator.

## 6. Workflow

1. **Receive a request.** From any skill, via
   the `CouncilRequest` artifact.
2. **Quorum check.** Are enough council members
   available? If not, the orchestrator
   escalates.
3. **Triage.** Which members are relevant to
   the question? A question about the DSL
   grammar pulls in skill 04 + skill 03; a
   question about security pulls in skill 12 +
   skill 13.
4. **Deliberation.** Each member submits a
   position (one paragraph) and a vote (one of
   the options). The deliberation is async
   (members have a deadline, not a meeting).
5. **Tally.** The orchestrator tallies the
   votes per the voting rule.
6. **Decision.** The orchestrator writes the
   `CouncilDecision` artifact, including the
   dissent.
7. **Implementation handoff.** The orchestrator
   routes the decision to the responsible
   skill.
8. **Precedent update.** The one-line summary
   lands in `council-precedent.md`.
9. **Archive.** The request + decision land in
   `.ai/council/archive/`.

## 7. Council membership

The council is composed of skill agents. A
council member is a skill that:

- Owns a bounded context.
- Has published a `SKILL.md` (the agent's
  charter).
- Has a signing key in the catalog.
- Is willing to deliberate (i.e. is not
  currently blocked on a hard dependency).

The standing council is:

- skill 00 (orchestrator) — chair, tie-breaker.
- skill 02 (PRD) — speaks for the user.
- skill 03 (ontology) — speaks for the domain.
- skill 04 (DSL) — speaks for the language.
- skill 12 (security) — speaks for trust.
- skill 13 (regulatory) — speaks for compliance.
- skill 14 (quality) — speaks for testability.

A council session MAY pull in any other skill as
a guest (e.g. skill 06 for a 3D-related
question). A guest has a vote; the standing
council does not.

## 8. Quality gates

- The request is structured (not free-form text).
- The request lists 2-5 options. A "build it
  anyway" option is a smell; the council
  escalates.
- Every council member submits a position +
  vote by the deadline. A missing vote is an
  escalation.
- The decision includes the dissent.
- The decision is signed by every member who
  voted.
- The decision is filed in the catalog.
- The precedent is updated.
- The implementation handoff is logged.

## 9. Failure modes

- **Quorum not met.** The orchestrator
  escalates to the user.
- **Vote is tied.** The orchestrator (chair)
  breaks the tie. The tie-break is recorded.
- **A member dissents strongly.** The dissent
  is in the decision. The orchestrator decides
  whether to escalate to the user.
- **A member's position is incoherent.** The
  other members may vote against; the chair
  may ask for a revision.
- **The implementation skill rejects the
  decision.** The decision is re-opened. The
  rejection is filed as a precedent.
- **The decision is later found to be wrong.**
  The decision is marked as deprecated; the
  precedent is updated. A reversal is itself
  a precedent.

## 10. Coordination contract

- **Input from**: every other skill.
- **Output to**: every other skill (via the
  orchestrator's handoff).
- **Triggered by**: any "I am not sure" / "this
  is a trade-off" / "two skills disagree"
  situation.
- **Frequency**: as needed. The council is a
  deliberative body, not a meeting factory.

## 11. Voting rules

The default voting rule is **simple majority**.
A request MAY specify a different rule:

- **Unanimous** — used for breaking changes to
  the global contract.
- **Supermajority (2/3)** — used for security
  + regulatory decisions.
- **Simple majority** — the default.
- **Plurality** — used for "pick one of these
  names" decisions.

A voting rule is recorded in the request and
enforced by the orchestrator.

## 12. Forbidden patterns

- **Skipping the council.** A non-trivial
  decision that bypasses the council is a
  contract violation.
- **Free-form deliberation.** "Let's have a
  chat about this" is not a council session. A
  council session is a structured process.
- **Voting without a position.** A vote without
  a rationale is a coin flip; the council
  escalates.
- **Hidden dissent.** A member who disagrees
  must file the dissent. Quietly going along
  with a decision the member disagrees with is
  a contract violation.
- **Re-litigating past decisions.** A council
  may reverse a past decision, but the
  reversal is itself a decision (with a
  precedent). "We've always done it this way"
  is not an argument.
- **Skipping the precedent.** Every decision
  updates `council-precedent.md`. A decision
  without a precedent update is not valid.
- **Council members who never disagree.** A
  council where every member votes with the
  chair is a rubber-stamp. The chair is
  responsible for the council's health.

## 13. Working with this skill

When invoked, this skill:

1. Reads the request.
2. Identifies the relevant members.
3. Runs the deliberation (async, with a
   deadline).
4. Tallies the votes.
5. Writes the decision.
6. Returns the decision to the orchestrator.

The skill is a process, not a chat. The
deliberation is logged. The votes are recorded.
The dissent is filed. The decision is the
authoritative source for the next step.
