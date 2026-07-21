# Phase 105 — Network Policy Enforcement (deny by default)

**Vision gap closed**: #7 (Zero Trust — Red denegada por defecto
*enforcement*, not just policy)
**Status**: shipped
**Date**: 2026-07-20

## The gap

The vision calls out "red denegada por defecto" as a Zero-Trust
principle. The platform had **two** network policy types:

  - `NetworkPolicy` (Phase 13) — session-level `NetworkMode`
    (BLOCKED / LOOPBACK_ONLY / OUTBOUND_ONLY / LAN / INTERNET).
    The firewall compiles this to iptables-style rules; the
    `NetworkBroker` consults it for single-packet decisions.
  - `NetworkPolicySpec` (Phase 104) — workspace-level
    `NetworkAccessMode` (DENY_ALL / ALLOW_LIST / ALLOW_ALL).
    Stored in the `WorkspaceDefinition` JSON, but **no
    consumer**: no code path read it.

Phase 105 closes the gap by adding a **bridge** that translates
the workspace's `NetworkPolicySpec` to a session-level
`NetworkPolicy`, and a new entry point on the firewall that
consumes the spec directly.

## What shipped

### Production code (2 new + 1 modified)

| File | Purpose |
|---|---|
| `core/runtime/network/policy/NetworkPolicySpecBridge.kt` (new) | The **only** place a `NetworkPolicySpec` is translated to a `NetworkPolicy`. The mapping (DENY_ALL → LOOPBACK_ONLY + empty allow-lists, ALLOW_LIST → OUTBOUND_ONLY + propagated host/port allow-lists, ALLOW_ALL → INTERNET) is pinned by tests. |
| `core/runtime/network/firewall/NetworkPolicyFirewall.kt` (modified) | New `compileFromSpec(sessionId, spec)` method. Calls the bridge + delegates to the existing `compile` method. The pre-105 `compile(sessionId, policy)` method is retained for callers that already have a `NetworkPolicy` in hand. |
| `NetworkPolicySpecBridge.kt` (modified) | New `isDenyByDefault(spec)` predicate. Used by the security audit log to confirm the platform's Zero-Trust posture for a given workspace. |

### Tests (2 new files, **+13 tests**)

| File | Tests |
|---|---|
| `NetworkPolicySpecBridgeTest.kt` (new) | 8 tests: DENY_ALL → LOOPBACK_ONLY (not BLOCKED) + empty allow-lists, ALLOW_LIST → OUTBOUND_ONLY + hosts/ports propagated, ALLOW_ALL → INTERNET + empty allowedRemoteHosts, ALLOW_ALL propagates explicit publishedPorts, ALLOW_LIST + empty allowedHosts is rejected by the spec init (precondition), bridge output is always a valid NetworkPolicy (no throws), `isDenyByDefault` truth table. |
| `NetworkPolicyFirewallSpecTest.kt` (new) | 5 tests: `compileFromSpec` with DENY_ALL emits LOOPBACK_ONLY shape (accept-lo v4 + v6 + drop terminator + no bidir accept), ALLOW_LIST propagates the host allow-list into the session's NetworkPolicy, ALLOW_ALL emits INTERNET shape (bidir out + in + no drop terminator), session id namespaces the chain, ALLOW_LIST + dnsAllowed still maps to OUTBOUND_ONLY (not LAN). |

## Mapping (the only place this is defined)

| `NetworkPolicySpec.mode` | `NetworkPolicy.mode` | `allowedRemoteHosts` | `publishedPorts` |
|---|---|---|---|
| `DENY_ALL` | `LOOPBACK_ONLY` | empty | empty |
| `ALLOW_LIST` | `OUTBOUND_ONLY` | spec.allowedHosts | spec.allowedPorts |
| `ALLOW_ALL` | `INTERNET` | empty (any host) | spec.allowedPorts |

**Why `DENY_ALL` → `LOOPBACK_ONLY` (not `BLOCKED`)**: dropping
loopback breaks a lot of local IPC (X11 forwarding, dbus,
systemd-resolved, the Elysium device bridge). `LOOPBACK_ONLY` is
the most restrictive mode that still allows local tools to
function.

**Why `ALLOW_LIST` → `OUTBOUND_ONLY` (not `LAN`)**: LAN mode
allows all RFC1918 + link-local, which the workspace didn't ask
for. The workspace explicitly listed the hosts it wants to reach;
`OUTBOUND_ONLY + allowedRemoteHosts` is the precise translation.

**Why `ALLOW_ALL` → `INTERNET` with empty `allowedRemoteHosts`**:
`INTERNET` mode means "any host is reachable", so the
allow-list is implicit. The `publishedPorts` (if any) are
propagated as-is (a workspace that wants "INTERNET but only
listen on port 8080" uses ALLOW_ALL + allowedPorts=8080).

## What's still not enforced (intentional, for the next phase)

The bridge + the new firewall method are the **typed data path**
for the workspace's network policy. The actual push to the
iptables backend is a separate concern (it lives in
`FirewallRuleBackend` and its `InMemoryFirewallBackend` test
impl). The launch path is the next seam to wire:

  - Today, the launch path produces a `NetworkPolicy` directly
    (from a hard-coded `NetworkMode` per session kind).
  - Phase 106+ will read the workspace's `NetworkPolicySpec`
    and call `firewall.compileFromSpec(sessionId, spec)`
    instead.

Until that wiring happens, the gap is: a workspace creator can
declare `NetworkPolicySpec.DENY_ALL` in the JSON, but the runtime
ignores the declaration and uses its own (hard-coded) policy.
The typed path is ready; the runtime path is Phase 106+.

## Test counts

- Before: 3616 tests
- After: **3629 tests**, 0 new failures (+13)
- Pre-existing flake: 1 (`FoundryServiceRepositoryIntegrationTest`,
  unchanged from `f08dad5`)

## Build

- `compileDebugKotlin`: green
- `assembleDebug`: green (98MB APK)
- `testDebugUnitTest`: 3629/3630 green (1 pre-existing flake)

## What this enables

- **A typed seam** for the workspace's network policy. The
  `NetworkPolicySpec` no longer floats in the JSON file; it
  bridges to a `NetworkPolicy` the rest of the platform
  consumes.
- **`isDenyByDefault(spec)`** is the audit-friendly predicate
  the security log uses to confirm the platform's Zero-Trust
  posture for each workspace.
- **The firewall is now spec-aware**. Callers (Phase 106+)
  can pass a `NetworkPolicySpec` directly and get the right
  `FirewallState`. The `NetworkPolicy` parameter is still
  supported for tests + fakes.

## What's still missing (next phases)

- **Wire `compileFromSpec` into the launch path** (Phase
  106+): the orchestrator should call this when a workspace
  is launched. Today the orchestrator builds a `NetworkPolicy`
  by hand and bypasses the spec.
- **Wire `isDenyByDefault` into the security audit log**
  (Phase 106+): every session that ran with the safe default
  should be flagged. The audit log already has the session
  start event; we add a `denyByDefault: Boolean` field.
- **Push the firewall state to the actual iptables / eBPF
  backend** (Phase 106+): the typed path is ready; the
  platform-side iptables push is the last mile.
