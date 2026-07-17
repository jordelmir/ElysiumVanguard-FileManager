---
name: backend-event-platform
description: The event bus, the projections, the sagas, the transactional outbox. The skeleton the platform runs on. Every other skill publishes events; this skill routes them, persists them, and projects them into the read models the others consume.
---

# Skill 08 — Backend Event Platform

## 1. Mission

Build and maintain the **event-driven backbone** of
the platform. Every skill that mutates state
publishes a domain event. This skill:

- Routes the event to the right consumers.
- Persists the event in the event store.
- Projects the event into the read models the
  other skills consume.
- Runs the sagas that span multiple aggregates.
- Provides the transactional outbox that
  guarantees event publication.

The event platform is the **skeleton** the
platform runs on. The other skills are muscles.

## 2. In-scope

- The event bus (NATS or PostgreSQL LISTEN/NOTIFY,
  per the default in `AGENTS.md`).
- The event store (append-only, content-
  addressed, signed).
- The projections (the read models the skills
  consume).
- The sagas (the long-running processes that
  span multiple aggregates).
- The transactional outbox (the pattern that
  guarantees event publication on commit).
- The schema registry (the versioned schemas
  the events conform to).
- The dead-letter queue (the events that no
  consumer could process).
- The replay tool (the ability to rebuild a
  projection from scratch).
- The SLOs (latency, throughput, durability).

## 3. Out-of-scope

- The domain types (skill 03).
- The DSL (skill 04).
- The 3D assets (skill 06).
- The diagnostics (skill 07).
- The auth + secrets (skill 12).
- The CI + observability tooling (skill 15).

The event platform is the **plumbing**. The
domain types are the **water**. The two are
separated so the plumbing can be replaced
without changing the water.

## 4. Inputs

- A `DomainEvent` (typed, versioned, signed) from
  any skill.
- A `ProjectionQuery` (a typed query against a
  read model) from any skill.
- A `SagaDefinition` (a long-running process
  that spans multiple aggregates) from any
  skill.
- A `SchemaVersion` (the version of the event
  schema the producer is using) from the schema
  registry.

## 5. Outputs

- The event store (an append-only log of every
  event the platform has emitted, content-
  addressed + signed).
- The projections (the read models the skills
  consume).
- The dead-letter queue (the events that no
  consumer could process).
- The SLOs (latency, throughput, durability).
- The replay tool (a CLI the orchestrator
  uses to rebuild a projection).

The events land in the event store. The
projections land in the read models. The SLOs
land in the observability platform (skill 15).

## 6. Workflow

1. **Receive a domain event.** A skill calls
   `eventBus.publish(event)`. The outbox
   pattern guarantees the event is published
   on commit.
2. **Persist the event.** The event lands in
   the event store. The event is content-
   addressed (its ID is its hash) and signed
   (the producing skill's key).
3. **Route the event.** The bus routes the
   event to every consumer subscribed to its
   type. A consumer is either a projection
   (writes a read model) or a saga (starts a
   long-running process) or another skill
   (e.g. the diagnostic flow subscribes to
   `TelemetryReceived` events).
4. **Update projections.** Every projection
   consumer updates its read model. The
   projection is idempotent (the same event
   applied twice produces the same state).
5. **Run sagas.** Every saga consumer starts
   or advances a long-running process. A saga
   is a state machine persisted in the event
   store.
6. **Handle failures.** A consumer that fails
   to process an event after N retries routes
   the event to the dead-letter queue. The
   orchestrator is alerted.
7. **Re-emit on schema bump.** A new schema
   version is published; consumers have N days
   to migrate. After N days, the orchestrator
   forcibly migrates the consumer.
8. **Archive.** Old events are archived to cold
   storage (S3-compatible). The event store
   keeps the last 90 days hot; the rest is
   cold.

## 7. Quality gates

- The event store is append-only.
- Every event is content-addressed + signed.
- The outbox pattern guarantees event
  publication on commit (no event is lost on
  crash; no event is published twice on retry).
- Every projection is idempotent.
- Every saga is checkpointable.
- The dead-letter queue is monitored (alert if
  not empty).
- The SLOs are met (latency < 1s p99; throughput
  > 10k events/s; durability = 100%).
- The schema registry is versioned.
- The replay tool works (rebuilding a
  projection from scratch takes < 1 hour).

## 8. Failure modes

- **The event bus is down.** The outbox
  buffers events locally; the platform
  degrades to "events are delivered when the
  bus is back". A bus outage is a P0 incident.
- **A consumer is down.** The event is
  retried; if it still fails, the event goes
  to the dead-letter queue. The consumer's
  SLO is a P1 incident.
- **A projection diverges from the event
  store.** The replay tool rebuilds the
  projection from the event store. A
  divergence is a P1 incident.
- **A schema bump breaks a consumer.** The
  consumer has N days to migrate. The
  orchestrator forcibly migrates the
  consumer if the deadline passes.
- **The event store fills up.** The orchestrator
  archives old events to cold storage. A
  filled event store is a P1 incident.

## 9. Coordination contract

- **Input from**: every other skill.
- **Output to**: every other skill.
- **Triggered by**: every domain event.
- **Frequency**: continuous.

## 10. Forbidden patterns

- **Direct database writes from a skill.** A
  skill that writes directly to the database
  bypasses the event store. The next
  projection rebuild will overwrite the
  write. Bypass is a contract violation.
- **Synchronous inter-skill calls.** A skill
  that calls another skill synchronously
  creates a tight coupling. The right pattern
  is: publish an event, the other skill
  consumes it.
- **Shared mutable state across skills.** A
  global flag, a singleton, a static
  variable — all are coupling. The right
  pattern is: events.
- **Two-phase commit across aggregates.** A
  transaction that spans two aggregates is
  not a transaction; it is a saga. The right
  pattern is: outbox + saga.
- **Direct kafka / RabbitMQ / SQS usage.** The
  event bus is the abstraction. Skills publish
  events to the bus; they do not import the
  underlying broker.
- **Custom event formats.** Every event is a
  typed `DomainEvent` (sealed class) with a
  schema version. Free-form `Map<String, Any>`
  events are a contract violation.
- **Missing outbox.** A skill that publishes an
  event outside the outbox is a contract
  violation. The outbox is mandatory.
- **In-memory event bus.** A bus that does not
  survive a process restart is a contract
  violation. The bus is durable.

## 11. The event platform in the Elysium
Automotive Foundry

The event platform is the platform's "what
happened" answer. Every action — a brand
created, a spec compiled, a 3D asset imported,
a fault detected, a repair validated, a royalty
settled — is a domain event. The event store is
the platform's memory. The projections are the
platform's read models. The sagas are the
platform's long-running processes.

The event platform is the platform's "we know
what happened, and we can prove it" answer.

## 12. Working with this skill

When invoked, this skill:

1. Receives the domain event.
2. Persists it in the event store.
3. Routes it to the consumers.
4. Updates the projections.
5. Runs the sagas.
6. Returns the consumer status to the
   orchestrator.

The skill does not produce domain events (the
other skills do). The skill does not produce
projections (the other skills do). The skill is
the **plumbing** that makes the events flow.
