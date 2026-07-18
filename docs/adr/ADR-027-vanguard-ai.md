# ADR-027 — Vanguard AI (Agent Operator)

Status: **Accepted** (Phase 57, 2026-07-18)
Owners: Runtime + AI
Supersedes: none
Superseded by: none

## Context

The master vision doc names a "Vanguard AI"
pillar that is fundamentally different from a
chatbot:

> "La IA no era un simple chatbot.
> Funcionaba como agente de la plataforma:
> - Instalar una distro.
> - Resolver dependencias.
> - Crear un entorno Windows.
> - Seleccionar Wine, Box64, FEX o QEMU.
> - Diagnosticar errores.
> - Interpretar logs.
> - Optimizar flags de compilación.
> - Generar scripts.
> - Explicar consumo de recursos.
> - Reparar configuraciones dañadas.
> - Crear snapshots antes de modificar un
>   entorno.
> - Revertir automáticamente cuando una
>   operación falle."

The AI is the runtime's "operator". The user
gives it a high-level goal; the AI produces a
plan and executes it. The AI is OUR
intellectual property — it does not call
OpenAI, Anthropic, or any third-party LLM. It
is a rule-based planner + executor that owns
the runtime's semantics.

The user was explicit:

> "vamos a crear lo nuestro propietario,
> nuestra propia informacion intelectual
> propietaria"

Phase 57 ships the Vanguard AI subsystem:
the agent, the planner, the executor, and
the natural-language parser. Phase 57 ships
a rule-based parser (no LLM); a future phase
adds an LLM-based parser that the user can
opt into.

## Decision

We split Vanguard AI into four small
pieces:

1. **`AgentAction`** (sealed class) — the
   primitive operations the agent can
   perform. Phase 57 ships:
   - `InstallDistro(distroId)`: install a
     signed distro (delegates to
     [com.elysium.vanguard.core.runtime.distros.manifest.installWithSignedManifest]).
   - `CreateWindowsEnvironment(binaryPath,
     runtimeKind)`: take a Windows .exe and
     run it via the orchestrator + the
     Wine + Box64 backend.
   - `CreateSnapshot(workspaceId, label)`:
     capture a snapshot of the workspace's
     live rootfs (Phase 49).
   - `RollbackToSnapshot(workspaceId,
     snapshotId)`: restore the workspace to
     a previous snapshot.
   - `RunBuild(toolchainKind, command)`:
     run a local build (Phase 56).
   - `RunCommand(command)`: run a generic
     command (delegates to the
     [com.elysium.vanguard.core.runtime.runner.ProcessLauncher]).

2. **`AgentPlan`** — a sequence of
   `AgentAction`s plus metadata
   (`id`, `goal`, `createdAtMs`,
   `riskLevel`). The plan is the
   orchestrator's input; the
   [PlanExecutor] (below) consumes it.

3. **`NaturalLanguageParser`** — translates
   the user's natural-language goal to an
   `AgentPlan`. Phase 57 ships a rule-based
   parser (keyword matching); a future
   phase adds an LLM-based parser. The
   parser is a pure function: same input,
   same output, no side effects.

4. **`PlanExecutor`** — the runner that
   consumes an `AgentPlan` and executes the
   actions. The executor:
   - Takes a snapshot before each
     destructive action (the master
     vision's "create snapshots before
     modifying an environment" rule).
   - On failure, rolls back to the snapshot
     (the "revert automatically when an
     operation fails" rule).
   - Publishes [RuntimeEvent.AgentActionStartedEvent]
     + [RuntimeEvent.AgentActionCompletedEvent]
     + [RuntimeEvent.AgentActionFailedEvent]
     + [RuntimeEvent.AgentActionRolledBackEvent]
     on the bus.
   - Returns an `ExecutionOutcome` (a
     sealed class: Success / Failure /
     RolledBack) the UI can render.

The agent is the planner; the executor is
the runner. The split mirrors the
[com.elysium.vanguard.core.runtime.orchestrator.RuntimeOrchestrator]
(Phase 53) and the [com.elysium.vanguard.core.runtime.wine.WineSessionRunner]
(Phase 54) patterns.

### Why a rule-based parser for v0

A rule-based parser is:
- **Deterministic.** Same input always
  produces the same plan. A test asserts
  this.
- **Offline.** No network call, no LLM
  provider, no API key. Works on a fresh
  device.
- **Auditable.** Every rule is a small
  `when` over the input text. A user with a
  "why did the agent install Debian for
  this goal?" question can read the rule
  table and answer it.
- **Fast.** A rule-based parser is O(n) in
  the input length; an LLM-based parser
  takes seconds.

The rule table is the source of truth for
Phase 57. A future phase adds an LLM-based
parser that the user explicitly enables
(e.g. "use the cloud LLM parser for this
goal"). The rule-based parser is the
default; the LLM is opt-in.

The rule table covers the master vision's
listed capabilities:
- "install <distro>" → InstallDistro
- "create windows env for <binary>" →
  CreateWindowsEnvironment
- "snapshot <workspace>" → CreateSnapshot
- "rollback <workspace>" →
  RollbackToSnapshot (with the latest
  snapshot)
- "build <toolchain> <command>" → RunBuild
- "run <command>" → RunCommand

A goal that does not match any rule
returns a `Plan.Unparseable` (a typed
rejection). The user can rephrase the
goal, or (future) opt into the LLM parser.

### Why snapshot-on-failure is a property of
the executor, not the planner

The master vision:
> "Crear snapshots antes de modificar un
> entorno. Revertir automáticamente cuando
> una operación falle."

The snapshot is a property of the
**executor**, not the planner. The
planner says "install this distro"; the
executor decides to take a snapshot
before the install (because install is
destructive) and to roll back on
failure. The planner does not need to
know about snapshots.

This split means:
- The planner is pure (a function from
  goal to plan).
- The executor owns the snapshot /
  rollback policy.
- A user with a "the agent should not
  snapshot before read-only operations"
  request changes the executor's policy,
  not the planner's output.

### Why a `riskLevel` on the plan

The plan carries a `riskLevel` (LOW /
MEDIUM / HIGH). The executor refuses to
execute a HIGH-risk plan without user
confirmation. The parser computes the
risk level from the input:
- "install debian" → MEDIUM (writes to
  /data; recoverable via snapshot).
- "create windows env" → MEDIUM (downloads
  Wine + Box64; recoverable).
- "rollback" → HIGH (irreversible without
  another snapshot).
- "build rust" → LOW (writes to
  `target/`; recoverable via `cargo
  clean`).
- "run command" → HIGH (the command is
  arbitrary; the user must confirm).

The risk level is the executor's gate. A
user with a "the agent should always
auto-confirm" preference can change the
gate; a user with a "the agent should
always require confirmation" preference
can change the gate the other way.

## Consequences

Positive:

- The master vision's "agent operator"
  is now real. A user can say
  "install debian-12" and the agent
  produces a plan and executes it.
- The agent is OUR intellectual property.
  The rule table, the plan format, the
  executor policy — all owned by
  Elysium Vanguard. No third-party LLM
  dependency.
- The snapshot-on-failure is a property
  of the executor, not the planner. The
  planner is pure; the executor is the
  policy holder.

Negative:

- The Phase 57 parser is rule-based. A
  user with a "the agent didn't understand
  my goal" complaint rephrases the goal
  or opts into the LLM parser (a Phase
  60+ follow-up).
- The executor's snapshot / rollback
  policy is conservative (a snapshot
  before every destructive action). A
  user with a "the agent takes too many
  snapshots" complaint can adjust the
  policy (a Phase 60+ follow-up).

## Revisit triggers

- If the runtime gains an LLM-based
  parser (e.g. a "use the cloud LLM
  parser" preference), the
  [NaturalLanguageParser] interface
  grows a `LlmNaturalLanguageParser`
  impl. The user opts in per goal; the
  rule-based parser is the default.

- If the executor's snapshot / rollback
  policy becomes more sophisticated
  (e.g. "only snapshot before HIGH-risk
  actions"), the policy is a method on
  the executor; the planner is
  unchanged.

- If the agent gains a "multi-step
  planning" capability (the user gives
  a goal that needs multiple plans), the
  agent becomes a stateful loop. Phase
  57's single-plan model is the
  starting point.
