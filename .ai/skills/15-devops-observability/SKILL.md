---
name: devops-observability
description: CI / CD pipelines, SLOs, distributed tracing, structured logging, metrics, on-call tooling. The "we know what the platform is doing right now" skill. The platform's nervous system.
---

# Skill 15 — DevOps + Observability

## 1. Mission

Build and maintain the **CI / CD pipelines** +
the **observability stack** that the platform runs
on. The pipelines gate every release. The
observability stack tells the on-call what the
platform is doing right now.

The devops + observability skill is the
platform's "we know what the platform is doing
right now" answer. Without it, the on-call is
blind; without blind on-call, the platform is
a liability.

## 2. In-scope

- The CI / CD pipelines (per skill, per
  environment).
- The SLOs (latency, throughput, availability,
  durability, error budget).
- The distributed tracing (OpenTelemetry).
- The structured logging (per request, per
  skill, per correlation ID).
- The metrics (per request, per skill, per
  business event).
- The alerting (per SLO violation, per
  anomaly).
- The dashboards (per skill, per environment,
  per on-call).
- The on-call tooling (PagerDuty / Opsgenie
  integration, the runbook, the escalation
  policy).
- The chaos engineering (the quarterly game day).
- The cost observability (per skill, per
  service, per environment).
- The deploy / rollback pipeline (the canary,
  the auto-rollback, the manual override).

## 3. Out-of-scope

- The product code (this skill operates it; the
  other skills implement it).
- The backend (skill 08).
- The mobile UX (skill 11).
- The security (skill 12).
- The regulatory (skill 13).
- The quality gates (skill 14) — this skill
  runs the CI; the gates are owned by skill 14.

The devops + observability skill is the
**nervous system** of the platform. The other
skills are muscles; this skill is the nerves
that carry the signals.

## 4. Inputs

- Every PR (the CI gate).
- Every release (the CD pipeline).
- Every alert (the on-call).
- The SLO definitions (from the orchestrator).
- The cost budget (from the user / finance).

## 5. Outputs

- The CI / CD pipelines (the per-skill, per-
  environment configuration).
- The SLOs (the per-skill, per-service
  definition).
- The dashboards (the per-skill, per-environment
  view).
- The alerts (the per-SLO-violation, per-anomaly
  rule).
- The on-call schedule (the per-week, per-team
  rotation).
- The chaos engineering report (the per-
  quarter game day).
- The cost report (the per-skill, per-service
  cost).

The devops + observability skill is the
platform's "we know what the platform is doing"
answer.

## 6. Workflow

1. **Receive a PR.** The CI runs the unit tests
   + the integration tests + the lint + the
   type check + the coverage.
2. **Receive a release.** The CD runs the full
   suite + the contract tests + the fuzz tests
   + the property tests + the mutation tests +
   the security review + the compliance review.
3. **Deploy.** The CD deploys to the canary
   environment. The canary is monitored for
   the SLO burn rate. If the burn rate is
   above the threshold, the CD auto-rolls-
   back.
4. **Monitor.** The observability stack
   collects:
   - Distributed traces (OpenTelemetry).
   - Structured logs (per request, per skill,
     per correlation ID).
   - Metrics (per request, per skill, per
     business event).
5. **Alert.** An SLO violation or an anomaly
   fires an alert. The on-call is paged.
6. **On-call.** The on-call investigates.
   The runbook is the starting point. The
   postmortem follows the incident.
7. **Chaos.** The quarterly game day injects
   failures (kill a service, drop a network
   partition, slow a database) and verifies
   the platform's resilience.
8. **Cost.** The cost report is filed per
   release. A release that exceeds the budget
   is blocked.

## 7. Quality gates

- Every PR runs the unit tests + the
  integration tests + the lint + the type
  check + the coverage.
- Every release runs the full suite.
- Every canary has a SLO burn rate threshold.
- Every SLO has a dashboard + an alert.
- Every on-call has a runbook.
- Every incident has a postmortem.
- Every quarter has a chaos engineering
  report.
- Every release has a cost report.

A failing gate blocks the pipeline.

## 8. Failure modes

- **The CI is down.** The orchestrator is
  informed. A P0 incident.
- **The CD is down.** The orchestrator is
  informed. A P0 incident.
- **The canary is unhealthy.** The CD auto-
  rolls-back. The on-call is paged.
- **The SLO is violated.** The on-call is
  paged. The error budget is consumed.
- **The on-call is unreachable.** The
  escalation policy is invoked. The
  orchestrator is paged.
- **The cost exceeds the budget.** The
  orchestrator is informed. A P1 incident.

## 9. Coordination contract

- **Input from**: every PR, every release, every
  alert, every SLO definition.
- **Output to**: the orchestrator (skill 00),
  the on-call, the cost report (finance).
- **Triggered by**: every PR, every release, every
  alert.
- **Frequency**: continuous.

## 10. Forbidden patterns

- **"We'll add monitoring later".** A service
  that ships without monitoring is a contract
  violation.
- **Manual deploys.** A deploy that requires
  a human in the loop is a contract violation.
  The CD is automated.
- **"We'll add tracing later".** A service
  that ships without distributed tracing is a
  contract violation.
- **"We'll add alerting later".** An SLO that
  ships without an alert is a contract
  violation.
- **"We'll add a runbook later".** A service
  that ships without a runbook is a contract
  violation.
- **"We'll do chaos later".** A platform that
  ships without a chaos engineering program
  is a contract violation.
- **"We'll do cost later".** A release that
  ships without a cost report is a contract
  violation.
- **Silent on-call.** An on-call that is paged
  silently (no Slack, no SMS, no escalation)
  is a contract violation.

## 11. The devops + observability stack in the
Elysium Automotive Foundry

The devops + observability stack is the
platform's nervous system. Every event is
traced. Every log is structured. Every metric
is collected. Every SLO has a dashboard. Every
alert has a runbook. Every incident has a
postmortem. Every quarter has a chaos game day.
Every release has a cost report.

The devops + observability stack is the
platform's "we know what the platform is doing"
answer.

## 12. Working with this skill

When invoked, this skill:

1. Receives the input (PR, release, alert,
   SLO definition).
2. Performs the action (CI, CD, monitoring,
   alerting, on-call).
3. Files the output (report, dashboard, alert,
   postmortem, cost report).
4. Returns the result to the orchestrator (or
   the on-call).

The skill does not implement product code. The
skill does not implement the backend. The
skill is the **nervous system** of the platform.
