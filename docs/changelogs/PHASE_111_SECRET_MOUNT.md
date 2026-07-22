# Phase 111 — Vault de Secretos Integration with Shell

**Status:** ✅ SHIPPED
**Date:** 2026-07-22
**Commit:** (this commit)
**Build:** APK 98 MB
**Tests:** 3733 (+16 from Phase 110)

## Vision Alignment

The vision's "Bóveda de secretos" (gap #8)
calls for **secretos reales** — a typed,
audited vault that the shell can surface to
workspaces via env var injection. Phase 110
shipped the typed surface; Phase 111 wires
the resolver into the orchestrator + adds the
seam the runtime hook consumes.

The vault is **proprietary** (no HashiCorp
Vault, no AWS Secrets Manager, no third-party
dependency). The `SecretStore` is the source
of truth; the `SecretResolver` is the seam.

## Deliverables

### 1. `SecretResolver` interface (`core/security/SecretResolver.kt`)

The seam between the orchestrator (pure-domain)
and the `SecretStore` (I/O). The interface
returns `Result<String>` (the UTF-8 decoding
of the secret bytes). A future phase adds a
"remote vault" impl (e.g. a Vault server
reachable over the local network); the
interface is the seam.

### 2. `SecretStoreResolver` production impl (`core/security/SecretStoreResolver.kt`)

Reads from the `SecretStore` with the
`accessReason = "workspace-session-start"`. The
store audits every read (Phase 100 invariant).

### 3. `secretEnvRefs` on `OrchestratedWorkspace`

A new field `Map<String, String>` carrying the
env-name → secret-id references for every
`EnvSpec.secret = true` entry. The orchestrator
emits this map at plan time; the runtime hook
reads it at session start to know which env
vars need resolution.

### 4. `resolveSecrets(plan, resolver)` on `WorkspaceOrchestrator`

A pure function that takes an
`OrchestratedWorkspace` + a `SecretResolver` +
returns a new `OrchestratedWorkspace` with the
secret values populated in `environment` + the
`secretEnvRefs` map cleared.

The result is typed
([`SecretResolutionResult`](../app/src/main/java/com/elysium/vanguard/core/runtime/workspace_orchestrator/SecretResolutionResult.kt)):

- `Success(plan)` — every secret was resolved.
- `MissingSecret(envName, secretId, cause)` —
  at least one secret failed to resolve. The
  runtime hook refuses to start the session.

### 5. Hilt wiring (`SecurityModule.kt`)

`SecretResolver` → `SecretStoreResolver` (singleton).

## Test Coverage

| Test class | New tests | Total |
|---|---|---|
| `SecretStoreResolverTest` | 6 | 6 |
| `WorkspaceOrchestratorSecretsTest` | 10 | 10 |

Total new: 16 test methods.
Total: 3733 tests, 1 pre-existing flake (unchanged
from Phase 110), 2 skipped.

## Architecture Decisions

### Why a `SecretResolver` interface (not calling `SecretStore` directly)?

The orchestrator is pure-domain (no I/O). The
`SecretStore` is I/O. Separating the two
concerns lets the orchestrator stay JVM-testable
(uses a hand-rolled `FakeSecretResolver`) +
the production wiring binds the interface to
the store via Hilt.

### Why fail-closed (a missing secret aborts the resolution)?

The user's intent is "this workspace needs
secret FOO at session start". A missing secret
is a misconfiguration (the user forgot to set
up the secret, or the secret was deleted).
Silently passing an empty string would mask
the misconfiguration + the workspace would
fail later with a confusing "auth failed"
error from the app. The fail-closed stance
surfaces the issue immediately.

### Why `secretEnvRefs` on the plan (not just in the env map)?

The plan has two distinct concerns:

- `environment: Map<String, String>` — the
  env vars as they'll be passed to the
  container (literal values, with the secret
  id as a placeholder).
- `secretEnvRefs: Map<String, String>` — the
  env names that need resolution (env name →
  secret id).

Keeping the two concerns separate lets the
runtime hook decide when (if ever) to
resolve the secrets — a workspace that
doesn't need them at start (e.g. a build
workspace that only reads the secret at
runtime via the `SecretStore`) can skip the
resolution entirely.

### Why a separate `SecretResolutionResult` (not a `Result<OrchestratedWorkspace>`)?

The error variant carries the env name + the
secret id + the cause — the runtime hook
needs this info to surface a typed error to
the user. A `Result<T>` erases the error
type + the caller would have to unwrap +
inspect the throwable. A sealed class
preserves the structure + the caller
pattern-matches.

### Why not call `SecretStore.get` at orchestrator time (not at session start)?

The orchestrator is pure-domain (no I/O).
Pushing the resolution to the runtime hook
also lets the platform rotate secrets
without re-orchestrating the workspace — a
secret can be replaced in the store, and the
next session start picks up the new value.

## Files

### New (production)

- `app/src/main/java/com/elysium/vanguard/core/security/SecretResolver.kt`
- `app/src/main/java/com/elysium/vanguard/core/security/SecretStoreResolver.kt`
- `app/src/main/java/com/elysium/vanguard/core/runtime/workspace_orchestrator/SecretResolutionResult.kt`

### New (tests)

- `app/src/test/java/com/elysium/vanguard/core/security/SecretStoreResolverTest.kt`
- `app/src/test/java/com/elysium/vanguard/core/runtime/workspace_orchestrator/WorkspaceOrchestratorSecretsTest.kt`

### Modified (production)

- `app/src/main/java/com/elysium/vanguard/core/runtime/workspace_orchestrator/OrchestratedWorkspace.kt` (added `secretEnvRefs` field)
- `app/src/main/java/com/elysium/vanguard/core/runtime/workspace_orchestrator/WorkspaceOrchestrator.kt` (emits `secretEnvRefs` + new `resolveSecrets` method)
- `app/src/main/java/com/elysium/vanguard/core/security/SecurityModule.kt` (Hilt provider for `SecretResolver`)

## Next

- **Phase 112** — Split screen + drag-drop
  cross-runtime + clipboard: `DesktopShell`
  has `WindowFrame` + `Dock` + drag (Phase
  78). Missing: split screen layout +
  cross-runtime drag-drop (drag from Linux to
  Windows VM window) + shared clipboard.
- **Phase 113** — Multi-sesiones en el shell:
  shell shows 1 desktop; needs multiple
  instances.
- **Phase 114** — Monitor recursos + monitor
  temperatura: `/proc/meminfo`, `/proc/stat`,
  `/sys/class/thermal/`.
- **Phase 115** — Box64 / FEX / DXVK graphics
  translation: depends on having the binaries
  (Phase 101 rootfs lists them but the
  integration is Phase 109+ work).
- **Runtime hook integration (Phase 116+)** —
  The LinuxProotSessionRunner and
  WindowsVmSessionRunner don't yet consume
  the `secretEnvRefs` from the plan. A future
  phase wires the orchestrator → resolver →
  session start path end-to-end.
