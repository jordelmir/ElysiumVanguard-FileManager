---
name: devops-observability
description: Builds reproducible delivery, infrastructure, monitoring, tracing, incident response and recovery for Elysium Automotive Foundry.
---

# Skill 15 — DevOps and Observability

## 1. Mission

**Make the platform deployable,
diagnosable, and recoverable without
relying on manual heroics.**

Every release is reproducible. Every
incident is diagnosable from the logs.
Every outage has a recovery procedure.
The platform does not depend on a
single human knowing a single thing.

The devops skill is the **delivery
infrastructure**. The observability
skill is the **diagnostic
infrastructure**. The two are
co-designed: an unobserved release is
not production-ready; an unreproducible
release is not production-ready.

## 2. Environments

The platform maintains **5
environments**. An environment that
is not in this list is a contract
violation.

- **Local.** A developer's
  machine. The local
  environment runs the
  full test suite + the
  full build.
- **CI.** The continuous
  integration environment.
  The CI runs the inner-loop
  tests on every PR + the
  full suite on every
  release.
- **Development.** A
  shared environment for
  the engineering team.
  The development
  environment is rebuilt
  per merge.
- **Staging.** A
  production-like
  environment for
  pre-release validation.
  The staging environment
  runs the full test
  suite + the smoke
  tests + the pen
  tests.
- **Production.** The
  live environment. The
  production environment
  is the only environment
  that serves real users.

**Production data must never be
copied into lower environments
without approved anonymization.** A
production data leak into
development is a P0 incident.

## 3. CI pipeline

The CI runs an **18-stage pipeline**.
A stage that fails blocks the
release.

1. **Formatting.** The
   per-language formatter
   (gofmt, ktlint, prettier,
   rustfmt).
2. **Static analysis.** The
   per-language static
   analyzer (detekt,
   eslint, clippy, sonarqube).
3. **Unit tests.** The inner
   loop. < 5 minutes.
4. **Property tests.** The
   merge queue. The 8
   property-based test
   categories (per skill 14
   section 3.5).
5. **Dependency scanning.**
   The CVE + the license
   audit.
6. **Secret scanning.** The
   per-PR + per-release
   scan for known secret
   patterns.
7. **SBOM generation.** The
   per-release Software
   Bill of Materials.
8. **Integration tests.** The
   cross-module integration
   tests.
9. **Migration validation.**
   The migration is
   re-runnable + idempotent.
10. **Android build.** The
    Android APK is built
    + signed.
11. **Backend build.** The
    backend is built +
    containerized.
12. **Rust worker build.** The
    Rust workers are
    built.
13. **Artifact signing.** Every
    release artifact is
    signed (Sigstore / cosign).
14. **Container scanning.**
    The container is scanned
    for CVEs.
15. **Staging deployment.**
    The release is deployed
    to staging.
16. **Smoke tests.** The
    staging environment
    runs the smoke tests
    (the critical user
    journeys).
17. **Approval.** A human
    approves the release
    (per skill 00 section 13).
18. **Production deployment.**
    The release is deployed
    to production via a
    canary or a staged
    rollout.

A stage that fails halts the
pipeline + emits a typed
`FoundryError`.

## 4. Observability

The platform instruments **14
surfaces**. A surface without
instrumentation is a contract
violation.

- **HTTP requests.** The
  per-request latency + the
  per-request error rate.
- **Database queries.** The
  per-query latency + the
  per-query error rate.
- **Compiler jobs.** The
  per-job latency + the
  per-job error rate.
- **Asset processing.** The
  per-asset latency + the
  per-asset error rate.
- **AI tool calls.** The
  per-call latency + the
  per-call cost + the
  per-call error rate.
- **Queue lag.** The
  outbox-to-bus lag.
- **Outbox lag.** The
  per-event outbox lag.
- **Authentication failures.**
  The per-failure reason +
  the source IP.
- **Authorization denials.**
  The per-denial reason +
  the user + the resource.
- **Contract changes.** The
  per-change event + the
  counter-signature.
- **Financial events.** The
  per-event type + the
  amount + the currency.
- **Scene-loading failures.**
  The per-failure reason +
  the asset hash.
- **Android crashes and
  ANRs.** The per-crash
  stack trace + the
  per-ANR stack trace.

**Use correlation IDs across
mobile, API, worker, and event
processing.** A request that
cannot be correlated is a
contract violation (per
`.ai/AGENTS.md` section 24.3).

## 5. Metrics

The platform tracks **13 metrics**.
A metric outside the approved
budget is a P1 incident.

- **Compilation success
  rate.** The % of
  compilations that
  succeed.
- **Compilation duration.**
  The P50 + the P99
  duration per compilation.
- **Asset rejection rate.**
  The % of asset uploads
  that are rejected.
- **Renderer memory
  pressure.** The peak
  memory per scene +
  the % of scenes that
  approach the budget.
- **Frame-time
  percentiles.** The P50
  + the P99 frame time
  per scene.
- **AI cost per project.**
  The per-project AI cost
  (the per-role budgets
  consumed).
- **AI proposal acceptance
  rate.** The % of AI
  proposals that are
  accepted by the human
  reviewer.
- **Event-processing lag.**
  The P99 lag from event
  publish to event
  consumer.
- **Royalty calculation
  failures.** The per-failure
  reason + the
  `eventId`.
- **Unauthorized-access
  attempts.** The
  per-attempt reason +
  the user + the source
  IP.
- **Backup age.** The age
  of the most recent
  backup.
- **Recovery-point
  objective (RPO).** The
  maximum data loss
  measured per quarter.
- **Recovery-time objective
  (RTO).** The maximum
  time to restore measured
  per quarter.

## 6. Deployment strategy

The platform uses **8 deployment
strategies**. A strategy that is
not in this list is a contract
violation.

- **Backward-compatible
  database expansion before
  contraction.** A column
  is added (nullable +
  default value); the new
  code reads + writes; the
  old code is migrated; the
  column is made
  non-nullable; the old
  code is removed. The
  two phases are separate
  releases.
- **Feature flags.** A
  feature is behind a flag;
  the flag is rolled out
  per cohort.
- **Canary or staged
  rollout.** A release
  is deployed to 1% of
  the traffic; the
  metrics are observed; the
  release is rolled out to
  100%.
- **Automated health
  checks.** A health check
  (a smoke test) runs per
  release; a failed health
  check triggers an
  automatic rollback.
- **Safe rollback for
  application code.** An
  application code release
  can be rolled back via
  the versioned deployment.
- **Forward recovery for
  irreversible data
  migrations.** A migration
  that cannot be reversed
  has a forward recovery
  plan (a documented
  procedure to re-create
  the state from the new
  shape).
- **Versioned APIs.** A
  breaking API change is
  a major version bump;
  the old version is
  supported for the
  deprecation period.
- **Graceful worker
  shutdown.** A worker
  finishes its in-flight
  work before shutting
  down; no work is lost.

## 7. Backup and recovery

The platform tests **7 backup +
recovery procedures**. A backup
that has never been restored is
not proven.

- **Database restore.** The
  per-quarter restore test.
- **Object-store recovery.**
  The per-quarter object
  store recovery test.
- **Audit-log integrity.**
  The per-quarter audit
  log integrity test (the
  hash chain is valid).
- **Contract recovery.** The
  per-quarter `RoyaltyContract`
  recovery test.
- **Artifact revalidation.**
  The per-quarter
  re-validation test (the
  hash matches; the
  signature is valid).
- **Secret rotation.** The
  per-quarter secret
  rotation drill.
- **Regional outage
  procedures.** The
  per-quarter regional
  failover drill (RPO +
  RTO measured).

A backup that has never been
restored is not a backup; the
per-quarter drill is the proof.

## 8. Workflow

1. **Receive a PR.** The CI
   runs the inner-loop
   stages (per section 3,
   stages 1-13).
2. **Receive a release.** The
   CI runs the full pipeline
   (per section 3, all 18
   stages).
3. **Deploy to staging.** The
   release is deployed to
   staging + the smoke tests
   run.
4. **Approve the release.** A
   human approves the
   release (per skill 00
   section 13).
5. **Deploy to production.**
   The release is deployed
   to production via the
   canary rollout.
6. **Monitor the metrics.**
   The platform monitors
   the 13 metrics; a
   regression triggers an
   automatic rollback.
7. **Run the per-quarter
   drills.** The per-quarter
   backup + recovery +
   security + DR drills are
   run.

## 9. Quality gates

- The 18-stage CI pipeline
  is in place.
- The 14 observability
  surfaces are
  instrumented.
- The 13 metrics are
  tracked.
- The 8 deployment
  strategies are
  available.
- The 7 backup + recovery
  procedures are tested
  per quarter.
- Production data is
  never copied to lower
  environments.
- The correlation ID is
  propagated across
  mobile + API + worker
  + event processing.
- The CVE patch SLA is
  met (HIGH 14 / MEDIUM 30
  / LOW 90).
- The per-quarter DR
  drill is run; a
  failed drill is a P1
  incident.

## 10. Failure modes

- **The CI pipeline is down.**
  A release cannot ship.
  The CI is restored as a
  P0 incident.
- **A release fails the
  health check.** The
  release is rolled back
  automatically; the
  postmortem is filed.
- **A CVE is discovered.** The
  CVE is patched within
  the SLA. A missed SLA is
  a P1 incident.
- **A backup fails.** The
  backup is re-run; a
  failed backup is a P1
  incident.
- **A regional outage
  occurs.** The failover
  is initiated; the RPO +
  RTO are measured.
- **A correlation ID is
  missing.** The request
  is rejected; the
  observability is
  repaired.

## 11. Coordination contract

- **Input from**: every
  PR + every release +
  every incident.
- **Output to**: every
  other skill (the
  observability data +
  the CI gate status +
  the backup + recovery
  status).
- **Triggered by**: every
  PR + every release +
  every CVE + every
  incident + every
  per-quarter drill.
- **Frequency**: continuous.

## 12. Forbidden patterns

- **Production data in
  lower environments.** A
  production data leak is
  a P0 incident.
- **A release that bypasses
  the CI.** A release that
  does not pass every
  stage of the 18-stage
  pipeline is a contract
  violation.
- **A release without a
  rollback procedure.** A
  release that cannot be
  rolled back is a
  contract violation.
- **A backup that has
  never been restored.** A
  backup is not a backup
  if it has not been
  tested; the per-quarter
  drill is the proof.
- **An outage with no
  recovery procedure.** An
  outage without a
  documented recovery
  procedure is a P0
  incident.
- **A correlation ID
  missing.** A request
  without a correlation
  ID is a contract
  violation.
- **A "we'll fix it later"
  CVE patch.** A CVE
  patch without an SLA
  is a P1 incident.
- **A manual heroics
  recovery.** A recovery
  that depends on a
  single human is a
  contract violation;
  the recovery is
  automated + documented.

## 13. Working with this skill

When invoked, this skill:

1. Receives the input (PR,
   release, incident,
   CVE, drill).
2. Runs the CI pipeline
   (or the incident
   response).
3. Files the output (the
   gate status, the
   release artifact, the
   postmortem, the
   backup verification).
4. Returns the result to
   the orchestrator.

The skill does **not** implement
product code. The skill is the
**delivery + observability
infrastructure**.

## 14. Cross-references

- **Orchestrator (skill 00):**
  `.ai/skills/00-program-orchestrator/SKILL.md`.
- **Completion standard:**
  `.ai/AGENTS.md` section 21.
- **Cross-cutting concerns
  (correlation ID, retry,
  no-leak):** `.ai/AGENTS.md`
  section 24.
- **Project gates (G0–G10):**
  `.ai/AGENTS.md` section 22.
- **Quality (skill 14):**
  `.ai/skills/14-quality-verification/SKILL.md`.
- **Security (skill 12):**
  `.ai/skills/12-security-zero-trust/SKILL.md`.
- **AI council (skill 05):**
  `.ai/skills/05-ai-engineering-council/SKILL.md`.
- **Backend event platform
  (skill 08):**
  `.ai/skills/08-backend-event-platform/SKILL.md`.
- **3D pipeline (skill 06):**
  `.ai/skills/06-3d-cad-asset-pipeline/SKILL.md`.
- **Mobile UX (skill 11):**
  `.ai/skills/11-mobile-forge-ux/SKILL.md`.
- **Marketplace (skill 10):**
  `.ai/skills/10-marketplace-manufacturing/SKILL.md`.
- **Regulatory (skill 13):**
  `.ai/skills/13-functional-safety-regulatory/SKILL.md`.
- **Runbooks (orchestrator +
  devops):**
  `docs/runbooks/`.
- **Threat model:**
  `docs/threat-model/`.
