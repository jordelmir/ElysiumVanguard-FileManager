# Phase 15 — Network Enforcement (Pure-JVM Translator)

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1094 tests, 0 failures, 2 skipped.

## What landed

The runtime's network policy now compiles to a typed
`FirewallState`, and a small backend interface lets the production
firewall renderer (Phase 15 production wiring) plug in without
forcing the policy logic to drag in `iptables` or `cgroup eBPF`.

### Files

**Production (4 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/network/firewall/FirewallRule.kt`
  — `FirewallRule` data class, `IpRange` value class, and the
  five enums (Direction, AddressFamily, FirewallProtocol,
  FirewallAction, ConnectionState). The `IpRange` value class
  validates CIDR format at construction; `FirewallState`'s init
  block rejects duplicate rule ids.
- `app/src/main/java/com/elysium/vanguard/core/runtime/network/firewall/FirewallState.kt`
  — `FirewallState(chainName, rules)` and
  `FirewallDiff(added, removed, kept)`. The `isEmpty` helper makes
  the apply path read like prose: "if the diff is empty, do
  nothing".
- `app/src/main/java/com/elysium/vanguard/core/runtime/network/firewall/NetworkPolicyFirewall.kt`
  — the translator. For each `NetworkMode`, emits a fixed set of
  rule families: BLOCKED → drop all; LOOPBACK_ONLY → accept loopback
  + drop all; OUTBOUND_ONLY → accept loopback + accept outbound
  NEW+ESTABLISHED + accept published-port inbound + drop all;
  LAN → accept loopback + accept LAN bidirectional + accept
  published ports from LAN + drop internet NEW; INTERNET → accept
  all (bidirectional) + accept published ports (informational).
  Includes a `chainNameFor(sessionId)` helper that caps the chain
  name at 28 chars (iptables' limit) and rejects alnum-empty
  inputs.
- `app/src/main/java/com/elysium/vanguard/core/runtime/network/firewall/FirewallRuleBackend.kt`
  — `FirewallRuleBackend` interface (`apply`, `remove`,
  `snapshot`, `listChains`) and `InMemoryFirewallBackend`
  implementation. The interface is the seam the production
  backend (ADR-007) satisfies.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/network/firewall/NetworkPolicyFirewallTest.kt`
  — 23 tests across compile / diff / backend / IpRange
  validation. Pins every mode's rule shape, the diff semantics,
  and the in-memory backend's thread-safety under 8 × 20
  concurrent applies.

**ADR (1 new):**

- `docs/adr/ADR-006-network-enforcement-pure-jvm.md` — context,
  decision, consequences, alternatives, revisit triggers.

### Why this matters

Before Phase 15, the `NetworkBroker` from Phase 13 was advisory:
"is this single request OK?" — the platform firewall was untouched.
Phase 15 closes that gap by compiling the policy into a typed
state. The runtime can now pair the broker's per-packet decision
with the firewall's per-session state, and the production backend
(Phase 15 production wiring, ADR-007) renders the state into
iptables / cgroup eBPF.

The split — **translator (policy logic, pure JVM) + backend
(renderer, host integration)** — keeps the policy testable
end-to-end without root or a network namespace. The same
`FirewallState` can be rendered as iptables, nftables, cgroup
eBPF, or Windows Filtering Platform rules.

### What the translator does

| Mode | Loopback | Outbound | Inbound | Drop | Published ports |
|---|---|---|---|---|---|
| BLOCKED | drop | drop | drop | drop all | n/a |
| LOOPBACK_ONLY | accept | accept | accept | drop all | ignored |
| OUTBOUND_ONLY | accept | accept NEW + ESTABLISHED | drop | drop all | accept on port |
| LAN | accept | accept LAN | accept LAN | drop internet NEW | accept on port from LAN |
| INTERNET | accept | accept all | accept all | none | informational |

`LAN` accepts RFC1918 (10/8, 172.16/12, 192.168/16) + link-local
(169.254/16) + CGN (100.64/10) in both directions and drops inbound
NEW from anywhere else. The translator is deterministic — same
policy + same session id yields the same `FirewallState` byte-for-byte.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `NetworkPolicyFirewallTest` | 23 | 0 |
| **Project total** | **1094** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Bugs found and fixed during this phase

1. **Duplicate rule ids for published ports** — the first cut
   used `IpRange.ANY` for both V4 and V6 source ranges, producing
   the same id for both families. Tightened the helper to take
   V4 / V6 source ranges separately; each rule id is now suffixed
   with `-v4` or `-v6`.
2. **`chainNameFor` accepted "---"** — the original filter
   allowed hyphens through, so a session id of "---" produced a
   valid (but useless) chain name. Tightened the validation to
   require at least one alnum character.
3. **Test assumed LOOPBACK_ONLY emits published-port rules** —
   it doesn't (and shouldn't; published ports are meaningless in
   loopback mode). Updated the test to use OUTBOUND_ONLY, which
   actually exercises the published-port path.

## Next phase

**Phase 15 production wiring** (separate ADR-007) — the iptables
production backend. The translator is already in place; Phase 15
production adds the `IptablesFirewallBackend` that renders each
`FirewallState` into `iptables-restore` text and applies it. This
is on-device test territory, not unit-test territory, so it goes
in a separate ADR.

Then **Phase 16** — wire `DistroManager.install` to use
`ProfileInstaller.plan` + `SystemLayerUpdater.apply` +
`ManifestSigner` for a real end-to-end provisioning path that
provisions a Linux rootfs, layers the Elysium overlay, and signs
the manifest.
