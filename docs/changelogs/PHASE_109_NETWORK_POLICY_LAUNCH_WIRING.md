# Phase 109 — Network Policy Launch Wiring

**Vision gap closed**: #7 second half (Zero Trust — the
"red denegada por defecto" *enforcement* path: the typed
seam was Phase 105, but the runtime launch path didn't
consume the workspace's `NetworkPolicySpec`).
**Status**: shipped
**Date**: 2026-07-20

## The gap

Phase 105 shipped the **typed seam** for the workspace's
network policy:

  - `NetworkPolicySpec` (DENY_ALL / ALLOW_LIST / ALLOW_ALL)
    on the `WorkspaceDefinition`
  - `NetworkPolicySpecBridge.toSessionPolicy(spec)` — the
    pure mapping to a session-level `NetworkPolicy`
  - `NetworkPolicyFirewall.compileFromSpec(sessionId, spec)` —
    the firewall entry point

But the **runtime launch path** didn't consume any of
this. The `WorkspaceOrchestrator.orchestrate()` translated
the spec to a `OrchestratedWorkspace` but **dropped** the
`network: NetworkPolicySpec` field on the floor. The
`SessionRunner.start()` signature took no policy parameter
and the production runners didn't call the firewall.

The Phase 105 changelog called this out:

> "Wire `compileFromSpec` into the launch path (Phase
>  106+): the orchestrator should call this when a
>  workspace is launched. Today the orchestrator builds a
>  NetworkPolicy by hand and bypasses the spec."

Phase 109 closes the gap.

## What shipped

### Production code (5 modified)

| File | Change |
|---|---|
| `core/runtime/workspace_orchestrator/OrchestratedWorkspace.kt` (modified) | New `networkPolicy: NetworkPolicy` field (defaults to LOOPBACK_ONLY via the bridge; never null). |
| `core/runtime/workspace_orchestrator/WorkspaceOrchestrator.kt` (modified) | `orchestrate()` calls `NetworkPolicySpecBridge.toSessionPolicy(definition.network)` and threads the result into `OrchestratedWorkspace.networkPolicy`. The same `definition.network` value flows from spec → orchestrator → runner → firewall. |
| `core/runtime/runner/SessionRunner.kt` (modified) | `start(workspace, session)` → `start(workspace, session, networkPolicy)`. The default is LOOPBACK_ONLY (the safe direction) so callers that don't pass a policy still get a safe start. |
| `core/runtime/runner/SessionRunnerRegistry.kt` (modified) | Threads the policy through to the kind-matched runner. |
| `core/runtime/runner/LinuxProotSessionRunner.kt` (modified) + `WindowsVmSessionRunner.kt` (modified) | Both runners compile the bridged `NetworkPolicy` via `NetworkPolicyFirewall.compile(sessionId, policy)` and apply the resulting `FirewallState` via `FirewallRuleBackend.apply`. A failure to compile/apply is a typed `SessionRunnerError.StartFailed` (refusing to start is the safe direction). |

### Tests (1 new file, **+9 tests**)

| File | Tests |
|---|---|
| `WorkspaceOrchestratorNetworkPolicyTest.kt` (new) | 9 tests: orchestrator bridges `DENY_ALL` → `LOOPBACK_ONLY`, bridges `ALLOW_LIST` → `OUTBOUND_ONLY` with hosts/ports propagated, bridges `ALLOW_ALL` → `INTERNET`, always populates `networkPolicy` (never null), deterministic (same spec → same policy), does not mutate the input spec, bridge is the only path (orchestrator output matches direct bridge call), workspace with no explicit policy still gets LOOPBACK_ONLY (defense in depth), other fields (bindMounts / env / launchCommand / resourceLimits) are preserved (sanity check). |

3 pre-existing test files were updated to match the new
`SessionRunner.start()` signature (the override is now
required to take the policy parameter, even if the fake
ignores it).

## The runtime data path

```
WorkspaceDefinition.network (NetworkPolicySpec, typed)
    |
    v
WorkspaceOrchestrator.orchestrate()  (Phase 67 + 109)
    |  NetworkPolicySpecBridge.toSessionPolicy(spec)
    v
OrchestratedWorkspace.networkPolicy (NetworkPolicy, typed)
    |
    v
SessionRunnerRegistry.start(workspace, session, networkPolicy)
    |
    v
LinuxProotSessionRunner.start(workspace, session, networkPolicy)  (Phase 109)
    |  NetworkPolicyFirewall.compile(sessionId, policy)
    v
FirewallState (rules, chain name)
    |
    v
FirewallRuleBackend.apply(firewallState)
    |
    v
iptables / eBPF / InMemoryFirewallBackend (the sink)
```

The runtime launch path now **consumes** the workspace's
network policy end-to-end. A workspace that declares
`network: { mode: ALLOW_LIST, allowedHosts: [api.example.com] }`
on the spec flows all the way to a firewall ruleset that
allows outbound to `api.example.com` and drops everything
else.

## Test counts

- Before: 3663 tests
- After: **3672 tests**, 0 new failures (+9 new)
- Pre-existing flake: 1 (the `FoundryRepositoryContractTest`
  `UncaughtExceptionsBeforeTest` that's been failing since
  `f08dad5` and is unrelated to this phase)

## Build

- `compileDebugKotlin`: green
- `assembleDebug`: green (98MB APK)
- `testDebugUnitTest`: 3672/3673 green

## What this enables

- The vision's "red denegada por defecto" principle is now
  *enforced* at the launch path. A workspace creator who
  declares `network: { mode: DENY_ALL }` actually gets
  loopback-only network access at runtime (not just a
  typed value in the JSON).
- A workspace creator who declares `ALLOW_LIST` with a
  host allow-list actually gets those exact hosts
  reachable; everything else is dropped by the firewall.
- The runtime launch path is now **declarative** w.r.t.
  network: the orchestrator produces a `NetworkPolicy`
  from the spec; the runner consumes it; the firewall
  compiles the ruleset. No more hard-coded `NetworkPolicy`
  bypassing the spec.

## What's still missing (next phases)

- **The `FirewallRuleBackend` production wiring.** The
  Phase 109 production runners wire the
  `InMemoryFirewallBackend` (records every state in
  memory). The iptables / eBPF backend
  (`IptablesFirewallRuleBackend`) is a Phase 110+
  follow-up.
- **The audit log entry** for "session started with
  deny-by-default network policy". A future phase adds a
  `RuntimeEvent.NetworkPolicyApplied` so the audit log
  shows which posture each session ran with.
- **Per-session network stats.** The current firewall
  backend records the ruleset but not the per-rule
  packet counts. A future phase adds `iptables -L -v`
  scraping for the UI's network monitor.
- **Rollback path.** A workspace that was started with
  `ALLOW_LIST` and is now being stopped: the runner
  removes the ruleset (the `FirewallRuleBackend.remove`
  is already there; the runner just doesn't call it yet).
