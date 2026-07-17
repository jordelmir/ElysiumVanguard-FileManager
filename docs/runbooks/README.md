# Runbooks — Elysium Automotive Foundry

> **Status:** Stub. The full content is
> produced during Phase 0 (Discovery) +
> G10 (Production hardening). Reference:
> `.ai/AGENTS.md` section 23 (Required
> Documentation) +
> `.ai/skills/12-security-zero-trust/SKILL.md`.

## What this directory must include

- **One runbook per top-10 incident.**
  The runbook is the on-call's guide
  to recognizing + diagnosing +
  mitigating + recovering from the
  incident.
- **The on-call rotation.** The
  humans + the schedule + the
  escalation path.
- **The postmortem template.** The
  format the postmortem follows.
- **The recovery procedures.** The
  per-incident recovery procedure.

## Runbook structure

Every runbook has the same structure:

1. **Summary.** One paragraph: what
   the incident is + what the user
   impact is.
2. **Severity.** P0 / P1 / P2 / P3.
3. **Recognition.** The signals that
   indicate the incident is
   happening (the dashboards + the
   alerts + the log patterns).
4. **Diagnosis.** The first-5-minutes
   diagnostic steps (the queries +
   the dashboards + the logs).
5. **Mitigation.** The immediate
   steps to reduce the user impact
   (the kill switch + the rollback
   + the rate limit + the feature
   flag).
6. **Recovery.** The longer-term
   steps to restore the service
   (the migration + the deploy +
   the verification).
7. **Postmortem.** The postmortem
   template + the timeline + the
   root cause + the action items.

## Top-10 incidents

The top-10 incidents are the most
common + the highest-severity
incidents the platform has. The list
is maintained by the orchestrator
(skill 00) + the security skill
(skill 12). The list is reviewed per
quarter.

## On-call rotation

The on-call rotation is maintained
by the orchestrator. The rotation:

- Covers 24/7.
- Has a primary + a secondary.
- Has an escalation path to the
  orchestrator.
- Has a P0 bypass: a P0 incident
  wakes the orchestrator directly.

## Postmortem template

The postmortem template is:

- **Summary.** One paragraph.
- **Timeline.** The event timeline
  (the recognition + the diagnosis
  + the mitigation + the recovery).
- **Root cause.** The 5-whys
  analysis.
- **Action items.** The per-item
  owner + the due date + the
  status.
- **Lessons learned.** What we
  learned + what we'll do
  differently.

A postmortem is filed within 7 days
of the incident. A postmortem without
action items is a "no action" — not
a "pass". A "no action" postmortem is
a P1 incident.

## Cross-references

- **Security model:**
  `docs/architecture/security-model.md`.
- **Threat model:** `docs/threat-model/`.
- **Security skill:**
  `.ai/skills/12-security-zero-trust/SKILL.md`.
- **Devops skill:**
  `.ai/skills/15-devops-observability/SKILL.md`.
