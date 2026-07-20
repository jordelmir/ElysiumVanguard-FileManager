# Phase 83 — AI Operator Intent (Vision Section 8: AI as System Operator)

> **Status:** ✅ Shipped
> **Date:** 2026-07-19
> **Phase:** 83 / Foundry / AI Operator
> **Predecessor:** Phase F4 (AI Council), Phase 82 (Android Process Launcher)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.operator.*`)

---

## TL;DR

The Foundry's **AI Operator** is operational.
The typed foundation for the AI as system
operator (per the master vision section 8
+ `.ai/AGENTS.md` section 14).

Per the master vision (section 8):
"La IA no era un simple chatbot.
Funcionaba como agente de la plataforma:
Instalar una distro. Resolver
dependencias. Crear un entorno Windows.
Seleccionar Wine, Box64, FEX o QEMU.
Diagnosticar errores. ..."

Per `.ai/AGENTS.md` section 14 (the AI
authority boundary):
- AI MAY interpret / propose / draft /
  explain.
- AI MAY NOT directly approve safety-
  critical, certify regulatory, declare
  mechanical compatibility, finalize
  financial settlements, determine legal
  ownership, modify signed releases,
  create verified technical facts without
  evidence.

The OperatorIntent captures both the AI's
intent (what the AI wants to do) + the
authority boundary (what the AI is allowed
to do). The `OperatorIntentValidator`
enforces the boundary: a safe intent
(e.g. a diagnostic) is `Allowed`; a
sensitive intent (e.g. an install) is
`RequiresApproval`; an intent outside the
AI's scope is `Denied`.

The operator is **5 primitives**:

1. **`OperatorIntent`** (sealed class, 6
   cases) — the typed intent:
   `InstallDistro` / `CreateWorkspace` /
   `LaunchCapsule` / `StopProcess` /
   `RunDiagnostic` / `GenerateScript`.
2. **`OperatorAuthorityScope`** (sealed
   class, 3 cases) — the AI agent's
   authority scope: `Full` / `Restricted`
   / `ReadOnly`.
3. **`OperatorAuthority`** (data class) —
   the signed record of the AI agent's
   scope (issued by a human user).
4. **`ValidationResult`** (sealed class, 3
   cases) — the validator's output:
   `Allowed` / `RequiresApproval(reason)`
   / `Denied(reason)`.
5. **`OperatorIntentValidator`** (sealed
   class) + `InMemoryOperatorIntentValidator`
   (impl) — the validator.

Plus the typed error envelope:

- **`OperatorIntentError`** (sealed class,
  1 case) — `InvalidIntentIdFormat`.

---

## What shipped

### `OperatorIntent` (sealed class, 6 cases)

The typed intent. The sealed class has 6
cases:

- **`InstallDistro(intentId, agentId, description, distroId, targetWorkspaceId)`**
  — install a Linux distro. **Sensitive
  operation** (the distro becomes part of
  the device's storage).
- **`CreateWorkspace(intentId, agentId, description, workspaceName, sandboxProfile)`**
  — create a workspace. **Sensitive
  operation** (the workspace consumes disk
  + memory).
- **`LaunchCapsule(intentId, agentId, description, capsuleId, runtime)`**
  — launch a Capsule. **Sensitive
  operation** (the process may have network
  + storage access).
- **`StopProcess(intentId, agentId, description, handleId)`**
  — stop a running process. **Sensitive
  operation** (the process may have unsaved
  state).
- **`RunDiagnostic(intentId, agentId, description, diagnosticKind)`**
  — run a diagnostic. **Safe operation**
  (it only reads the device's state).
- **`GenerateScript(intentId, agentId, description, language)`**
  — generate a script. **Safe operation**
  (it only writes to a draft buffer).

### `IntentKind` (enum, 6 values)

The typed intent kind. The kind is the
**classification** of the intent; a
`when` on the kind is **exhaustive**. The
6 values mirror the 6 `OperatorIntent`
cases.

### `OperatorAuthorityScope` (sealed class, 3 cases)

The AI agent's authority scope. The scope
determines what the AI is allowed to do.
The sealed class has 3 cases:

- **`Full`** — all operations are
  `Allowed` (no human approval required).
  Used for **trusted** AI agents.
- **`Restricted(allowedKinds)`** — only the
  specified kinds are `Allowed` (no human
  approval required); other kinds are
  `RequiresApproval`.
- **`ReadOnly`** — only the safe operations
  (`RunDiagnostic` + `GenerateScript`) are
  `Allowed`; all other operations are
  `Denied`. This is the **default** scope
  for new AI agents.

### `OperatorAuthority` (data class)

The signed record of the AI agent's scope.
The authority has:

- **`agentId`** — the AI agent (a
  `UserId` with an `AIAuthor` role).
- **`scope`** — the agent's authority
  scope.
- **`issuedBy`** — the human user that
  issued the authority (a `UserId` with a
  `HumanAgent` role). The `agentId` and
  `issuedBy` MUST NOT be equal (an AI
  agent cannot self-issue authority).
- **`signature`** — the authority's
  signature.

### `ValidationResult` (sealed class, 3 cases)

The validator's output for a given
`OperatorIntent` + `OperatorAuthority`.
The sealed class has 3 cases:

- **`Allowed`** — the intent is allowed;
  the agent can execute without human
  approval.
- **`RequiresApproval(reason)`** — the
  intent is allowed but requires human
  approval before execution.
- **`Denied(reason)`** — the intent is
  denied; the agent cannot execute the
  intent.

### `OperatorIntentValidator` (sealed class)

The typed validator. The interface has:

- **`validate(intent, authority)`** —
  validate an `OperatorIntent` against an
  `OperatorAuthority`. Returns a
  `ValidationResult`.
- **`isAllowed(intent, authority)`** —
  check whether an `OperatorIntent` is
  allowed (the convenience predicate for
  the "no human approval required" case).

### `InMemoryOperatorIntentValidator` (impl)

The in-memory implementation. The
validator's rules are:

- If the kind is in the authority's
  `autoApprovedKinds` → `Allowed`.
- If the authority is `ReadOnly` AND the
  kind is not in the safe set → `Denied`.
- If the authority is `Restricted` or
  `Full` AND the kind is not in the
  auto-approved set → `RequiresApproval`.

The validator is **stateless** (no
mutable fields); the same impl is used
in tests + production. The validator is
thread-safe.

### `OperatorIntentError` (sealed class, 1 case)

The typed error envelope. The 1 variant:

- **`InvalidIntentIdFormat(rawInput, parseFailure)`**
  — the intent id string was not a valid
  UUID.

### `IntentId` (UUID value class)

The typed id of an operator intent. The id
is a UUID (per the Foundry id convention).

---

## Design decisions

### Why a sealed class for `OperatorIntent`, not a single class with a discriminator field?

A sealed class is **type-safe +
exhaustive**. The consumer (the operator
orchestrator) uses `when (intent)` to
dispatch by case:

- `is OperatorIntent.InstallDistro` —
  check the distro id + the target
  workspace id.
- `is OperatorIntent.CreateWorkspace` —
  check the workspace name + the sandbox
  profile.
- `is OperatorIntent.LaunchCapsule` —
  check the capsule id + the runtime.

A single class with a discriminator would
lose the type safety; the consumer would
need to check the discriminator. The
sealed class captures the **6 distinct
intent kinds** the platform supports.

### Why a sealed class for `OperatorAuthorityScope`, not a single class with a flag?

A sealed class is **type-safe +
exhaustive**. The consumer (the validator)
uses `when (scope)` to dispatch by case:

- `is OperatorAuthorityScope.Full` —
  allow all kinds.
- `is OperatorAuthorityScope.Restricted` —
  allow only the specified kinds.
- `is OperatorAuthorityScope.ReadOnly` —
  allow only the safe kinds (diagnostic
  + script generation).

The sealed class captures the **3 distinct
authority levels** the platform supports.

### Why is `ReadOnly` denied (not requires approval) for sensitive operations?

A `ReadOnly` AI agent is **not allowed**
to perform sensitive operations; the
human user can either:
- Change the scope to `Restricted` (then
  the agent can request approval).
- Perform the operation themselves.

A `RequiresApproval` would mean "the
agent can request approval for this
operation", but a `ReadOnly` agent is
not allowed to make the request at all
(it's a security boundary, not a
workflow boundary).

### Why is `OperatorAuthority.agentId != issuedBy` enforced at the type level?

An AI agent that issues its own authority
is a **self-issued authority** — a
security violation. The enforcement at
the type level (the `require` check in
the `init` block) prevents the
construction of an authority that
violates this invariant.

### Why does the `Restricted` scope have an `autoApprovedKinds` set, not a `deniedKinds` set?

A `deniedKinds` set would be a
**blacklist** (the agent can do anything
except the listed operations). A
blacklist is fragile: a new operation
added later is automatically allowed by
default (the AI can perform it without
human approval).

An `autoApprovedKinds` set is a
**whitelist** (the agent can ONLY do the
listed operations; everything else
requires human approval). A whitelist is
safe: a new operation added later is
**not** automatically allowed (the human
must explicitly authorize it).

The whitelist is the **safe default**.

### Why is the validator in a sealed class, not an interface?

The `OperatorIntentValidator` is a sealed
class with a single in-memory impl. The
sealed class captures the **abstract
behavior** (the platform's typed
validator contract); the in-memory impl
is the test + production default. A
future Phase 7+ increment may add a
`RemoteOperatorIntentValidator` (a
production impl that delegates to a
remote service for the validation);
the sealed class allows the consumer to
pattern-match on the impl.

---

## Tests

24 new tests in `OperatorIntentTest`. The
tests cover:

- **OperatorIntent invariants** (7
  tests): InstallDistro blank distroId,
  InstallDistro blank targetWorkspaceId,
  CreateWorkspace blank workspaceName,
  LaunchCapsule blank capsuleId,
  StopProcess blank handleId,
  RunDiagnostic blank diagnosticKind,
  GenerateScript blank language.
- **OperatorAuthority invariants** (1
  test): agentId == issuedBy.
- **OperatorAuthorityScope invariants**
  (1 test): Restricted empty allowedKinds.
- **ValidationResult invariants** (2
  tests): RequiresApproval blank reason,
  Denied blank reason.
- **InMemoryOperatorIntentValidator —
  Full** (1 test): Full allows any kind.
- **InMemoryOperatorIntentValidator —
  Restricted** (2 tests): Restricted
  allows a kind in allowedKinds,
  Restricted requires approval for a kind
  not in allowedKinds.
- **InMemoryOperatorIntentValidator —
  ReadOnly** (6 tests): ReadOnly allows
  RunDiagnostic, ReadOnly allows
  GenerateScript, ReadOnly denies
  InstallDistro, ReadOnly denies
  CreateWorkspace, ReadOnly denies
  LaunchCapsule, ReadOnly denies
  StopProcess.
- **InMemoryOperatorIntentValidator —
  isAllowed helper** (3 tests):
  isAllowed returns true for an allowed
  intent, isAllowed returns false for a
  denied intent, isAllowed returns false
  for a requires-approval intent.
- **Realistic scenario** (1 test): a
  ReadOnly AI agent tries to install a
  distro, the validator denies, the
  agent tries a diagnostic, the
  validator allows.

**Total foundry tests:** ~784 (was ~760;
+24 new).
**Total project tests:** 3314 (was 3290;
+24 new).

---

## Phase 83 closure

**The Foundry's AI Operator foundation is
operational.** The chain is now:

```
AI Agent (the user-supplied intent)
    ↓
OperatorIntent (the typed intent)
    ↓
OperatorAuthority (the agent's scope)
    ↓
OperatorIntentValidator (the authority
   boundary check)
    ↓
ValidationResult (Allowed /
   RequiresApproval / Denied)
    ↓
Human review (if RequiresApproval)
    ↓
Execution
```

The chain is **typed end-to-end** (the
operator's intent is captured as a typed
`OperatorIntent`; the authority boundary
is enforced at the type level; the
validation result is a typed
`ValidationResult`).

The next step in the operator chain:

- **Phase 84 — OperatorPlan** (a
  multi-step plan; the operator can
  issue a plan with multiple intents;
  the plan is executed step by step).
- **Phase 85 — OperatorAuditLog** (the
  operator's actions are recorded in an
  immutable audit log; the audit log is
  signed by the agent + the human
  reviewer).
- **Phase 86 — OperatorOrchestrator** (the
  orchestrator that takes an
  `OperatorPlan` + the operator's
  authority + the human's approval +
  executes the plan).

---

## What's next

The next concrete deliverable is up to
the user. The remaining work:

### Universal Execution Engine (next concrete)

- **Phase 87 — Sandbox Application**
  (the integration that takes a
  `SandboxPolicy` + a `LaunchPlan` and
  applies the bind mounts + the SELinux
  profile + the resource limits BEFORE
  launching the process).
- **Phase 88 — CriticalE2E with real
  AndroidProcessLauncher** (replace the
  InMemoryProcessLauncher in the
  Phase 71 / Phase 77 E2E tests with
  the real AndroidProcessLauncher).

### AI Operator (next concrete)

- **Phase 84 — OperatorPlan** (a
  multi-step plan; the operator can
  issue a plan with multiple intents).
- **Phase 85 — OperatorAuditLog** (the
  operator's actions are recorded in an
  immutable audit log).
- **Phase 86 — OperatorOrchestrator** (the
  orchestrator that takes an
  `OperatorPlan` + the operator's
  authority + the human's approval +
  executes the plan).

### Elysium Linux (next concrete)

- **Phase 73 fourth half — Minimal rootfs
  + Mesa/Turnip/Box64/FEX/Wine
  integration** (the actual binary;
  reproducible build on a Linux build
  server with ARM64 cross-compilation).
- **Phase 72 — Capsule installer UI**
  (Compose) for the new Elysium Linux
  distro.

### Foundry program (next concrete)

- **Phase F7 (G9+G10) — Production
  hardening**: threat model + SLOs +
  on-call + runbooks + red team + CVE
  SLA + observability + multi-module
  split (per ADR-0023).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/foundry/core/operator/OperatorIntent.kt` | new | OperatorIntent + OperatorAuthorityScope + OperatorAuthority + ValidationResult + OperatorIntentValidator + InMemoryOperatorIntentValidator + OperatorIntentError + IntentId |
| `app/src/test/java/com/elysium/vanguard/foundry/core/operator/OperatorIntentTest.kt` | new | 24 JVM tests |

---

## The role in the bigger picture

The AI Operator is the **AI as system
operator** primitive per the master vision
(section 8). The AI agent can:

- **Install a Linux distro** (sensitive
  operation; requires human approval by
  default).
- **Create a workspace** (sensitive
  operation; requires human approval by
  default).
- **Launch a Capsule** (sensitive
  operation; requires human approval by
  default).
- **Stop a running process** (sensitive
  operation; requires human approval by
  default).
- **Run a diagnostic** (safe operation;
  allowed by default for any authority
  scope).
- **Generate a script** (safe operation;
  allowed by default for any authority
  scope).

The AI Operator is the **typed
enforcement** of the AI authority
boundary (per `.ai/AGENTS.md` section
14). The boundary is enforced at the
type level (the `OperatorAuthorityScope`
is a sealed class; the
`OperatorIntentValidator` returns a
typed `ValidationResult`; the
`RequiresApproval` case requires human
review before execution).

The AI Operator is the **preparation for
the AI orchestrator**: the AI agent will
issue `OperatorIntent`s; the orchestrator
will validate the intents; the
orchestrator will execute the intents
(after human approval, if required).
The AI Operator is the **decision
logic** the AI orchestrator uses.
