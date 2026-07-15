# ADR-006 — Network Enforcement (Pure-JVM Translator)

Status: **Accepted** (Phase 15, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: ADR-007 (iptables production backend, pending)

## Context

Phase 13 added `NetworkBroker`, a pure-JVM decision engine that
answers "is this single request permitted under the session's
`NetworkPolicy`?" The broker's job ends at "yes / no /
require-confirmation". It does not actually enforce anything on the
host — the platform firewall is untouched.

The master order §10.2 says the runtime must enforce the policy
state across a session's lifetime, not just a single packet.
Without enforcement, the broker is advisory: a misbehaving
application inside the rootfs could open any socket it likes, and
the broker would only learn about the violation after the fact (if
at all).

The challenge: enforcement requires a host integration
(`iptables-restore`, cgroup eBPF maps, network namespaces) that is
platform-specific and may not be available in unit tests. We need a
**seam** between the policy logic and the host integration so the
policy logic stays JVM-testable end-to-end.

## Decision

We split enforcement into two layers:

1. **Translator** — `NetworkPolicyFirewall` (pure JVM). Takes a
   `NetworkPolicy` + a `sessionId` and returns a typed
   `FirewallState(chainName, rules: List<FirewallRule>)`. The
   translator is the policy logic: it encodes the master order's
   "what does BLOCKED mean", "what does LOOPBACK_ONLY mean", etc.
2. **Backend** — `FirewallRuleBackend` (interface) +
   `InMemoryFirewallBackend` (test impl). The backend takes a
   `FirewallState` and applies it. Production backends translate
   each `FirewallRule` into iptables / cgroup eBPF and call
   `iptables-restore`; the test backend stores the state in a
   `synchronized` map for assertions.

The split is enforced by the test suite: every test asserts on the
translator's output (`FirewallState`) and on the test backend's
`apply` / `snapshot`. The production backend (Phase 15 production
wiring, ADR-007) is a separate concern.

### The data model

A `FirewallRule` is intentionally narrow:

- `id: String` — stable per session, deterministic per mode. The
  enforcer diffs states by id; two rules with the same id are
  considered the same rule.
- `direction: Direction` (INBOUND / OUTBOUND)
- `family: AddressFamily` (IPV4 / IPV6 / ANY)
- `interfaceName: String` (`"lo"`, `"any"`, `"wlan0"`, etc.)
- `protocol: FirewallProtocol` (TCP / UDP / ICMP / ANY)
- `port: Int?` (null = any)
- `source: IpRange`, `destination: IpRange` — CIDR strings
- `state: ConnectionState` (NEW / ESTABLISHED / RELATED / ANY)
- `action: FirewallAction` (ACCEPT / DROP / REJECT)
- `priority: Int` — lower numbers emitted first
- `comment: String` — human-readable, never used for matching

The `FirewallState` is `chainName + rules: List<FirewallRule>`. The
init block enforces unique rule ids within a chain. The
`FirewallDiff` is `added + removed + kept`; the enforcer applies
the diff to a backend.

### Translation rules

For each `NetworkMode`, the translator emits a fixed set of
rule families. The master order's intent is preserved literally:

| Mode | Loopback | Outbound | Inbound | Drop | Published ports |
|---|---|---|---|---|---|
| BLOCKED | drop | drop | drop | drop all | n/a |
| LOOPBACK_ONLY | accept (4 rules) | accept | accept | drop all | ignored |
| OUTBOUND_ONLY | accept | accept NEW + ESTABLISHED | drop | drop all | accept on port (V4 + V6) |
| LAN | accept | accept LAN | accept LAN | drop internet NEW | accept on port from LAN |
| INTERNET | accept | accept all | accept all | none (informational) | accept on port (informational) |

`LAN` accepts RFC1918 (10/8, 172.16/12, 192.168/16) + link-local
(169.254/16) + CGN (100.64/10) in both directions and drops inbound
NEW connections from anywhere else. `OUTBOUND_ONLY` is the only mode
where the published-port accept is **enforced**; in `LAN` and
`INTERNET`, published ports are an **additional filter** when the
list is non-empty.

The translator is **deterministic** — same policy + same session id
yields the same `FirewallState` byte-for-byte. This is what makes
the diff path safe: the enforcer never sees "rule A and rule B are
the same thing".

### Why a `FirewallRule` and not a free-form iptables string

A free-form iptables string is what the production backend
produces. Keeping the runtime's policy in a typed structure buys
us:

- **Backend-agnostic policy**: the same `FirewallState` can be
  rendered as iptables, nftables, cgroup eBPF, or Windows
  Filtering Platform rules.
- **Testable shape**: 23 unit tests assert on the rule list
  directly, no string parsing.
- **Diffable**: the diff is on typed `id`s, not on string equality.
  A re-emitted rule with the same id is a no-op.

The cost is one more translation step in the production backend.
We accept the cost because the policy is the durable contract; the
backend is a renderer.

### Why rule ids include the family tag

IPv4 and IPv6 published-port rules for the same port are different
rules — they bind to different sockets. The translator suffixes
each id with `-v4` or `-v6` to keep them distinct. Without the
suffix, two rules with the same id would silently shadow each other
in the diff path (caught by `FirewallState`'s "no duplicate ids"
init check, but the error message would point at the wrong root
cause).

## Consequences

### Positive

- **Pure-JVM policy.** The translator + the test backend cover
  every rule the production backend will ever emit. A bug in the
  translator is caught by the unit tests; a bug in the production
  backend is caught by a different test (integration / on-device).
- **State diffing for free.** `FirewallDiff` is the minimal change
  set between two states. A session that upgrades from
  `LOOPBACK_ONLY` to `OUTBOUND_ONLY` triggers an `add outbound +
  remove drop-all` diff; the backend applies the two changes
  atomically.
- **Thread-safe backend.** `InMemoryFirewallBackend` is
  `synchronized` on the map; the production backend uses
  iptables' atomic restore. Either way, the runtime never sees a
  partial state.
- **Mode semantics are explicit.** A `LAN` policy literally emits a
  "drop inbound NEW" rule — the master order's "no internet
  inbound on a LAN session" is in the code, not in a comment.

### Negative

- **Two enforcement layers (translator + backend).** A bug can
  hide in either. We mitigate by pinning the test backend's
  behaviour in unit tests and running the production backend
  against a real iptables in a separate on-device test.
- **V6 LAN rules are deferred.** `LAN_RANGES_V6` is empty for now
  — the IPv6 LAN CIDR constants (fc00::/7, fe80::/10) need a
  proper value class. A `LAN` policy accepts IPv6 from any source
  (because the v4-only bidirectional block doesn't fire on v6 and
  the `drop-internet-inbound` is family-ANY). This is a known
  gap; the next phase adds the V6 constants and tightens the
  test.
- **One rule per interface, not "any" with multi-interface
  fallback.** We emit `"lo"` explicitly for loopback; a real
  iptables chain may need a per-interface table on devices with
  multiple physical interfaces. The translator is per-session and
  the chain is namespaced; the backend renders the actual
  `-i` flag.

## Alternatives considered

1. **Compile directly to iptables text and store the string.**
   Rejected: makes diffing a string-comparison problem and ties
   the policy to iptables. We can still render to iptables; we
   just don't make the iptables string the canonical state.
2. **Skip the translator; do everything in the broker.** Rejected:
   the broker answers one question at a time ("is this packet
   OK?"); the firewall answers a state question ("what rules are
   active for this session?"). The shapes are different. A single
   class doing both would have two interfaces and one obvious
   split.
3. **Use Linux network namespaces instead of iptables.** Out of
   scope for Phase 15. Namespaces are the right answer for full
   isolation but require root and a kernel that supports them;
   the runtime does not have root. The firewall is a
   userland-friendly approximation that can run alongside a
   namespace layer in a future phase.

## Revisit triggers

- The first real on-device install reveals that iptables rules
  need a structure this model doesn't express (e.g. NAT, mangle).
  We add a `FirewallRule.actionArguments: Map<String, String>` for
  backend-specific extensions.
- The session model moves from a single chain per session to a
  per-app chain (Phase 14's capsule has its own `NetworkPolicy`).
  We add a `chainPrefix: String` so the per-app chains can share
  the same translator.
- A user wants IPv6 LAN rules. We add the IPv6 CIDR constants and
  tighten the test.
