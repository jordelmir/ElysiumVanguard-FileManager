# ADR-008 — Hardware Broker (Pure-JVM Decision Engine)

Status: **Accepted** (Phase 18, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: ADR-009 (hardware enforcer, pending)

## Context

Master order §18 says the runtime mediates host hardware access
from a guest session: USB, Bluetooth, NFC, camera, microphone,
location, sensors. Each is a *bucket* of related platform
APIs that the guest should not be able to call directly.

Until Phase 18 there was no typed model for hardware access.
The network policy (Phases 13 / 15) had a clean split —
broker for decisions, backend for enforcement — but the
hardware path didn't have a broker at all. A guest that
wanted to call `BluetoothAdapter.startDiscovery()` had no
runtime-mediated barrier between the request and the OS.

The challenge is the same as the network one: a per-class
policy that says yes / no / require-confirmation for each
request, testable end-to-end without an Android device. The
*enforcement* (calling `UsbManager.requestPermission()`,
pairing a Bluetooth device, opening an NFC tag) is a Phase 19
concern. This ADR is only about the decision engine.

## Decision

A `HardwareBroker` that mirrors the `NetworkBroker` shape:

1. **Types**
   - `HardwareClass` enum (USB, BLUETOOTH, NFC, CAMERA,
     MICROPHONE, LOCATION, SENSORS).
   - `HardwareAction` enum (LIST, READ, WRITE, CONNECT) —
     the coarse shape of the request, not the platform
     API call.
   - `HardwareAccess` enum (BLOCKED, SILENT, READ_ONLY,
     READ_WRITE, CONFIRM) — the per-class access mode.
   - `HardwareTargetId` sealed class (Any, Specific,
     WildcardUsb) — the shape of the request's target
     filter. The broker looks at the *shape*; the id
     string is surfaced in the decision's reason for the UI.
   - `HardwarePolicy` data class (defaultMode +
     classAccess map + rememberConfirmations flag).
   - `HardwareDecision` sealed class (Allow /
     AllowWithConfirmation / Deny).

2. **Rules**

| Access | LIST / READ | WRITE / CONNECT |
|---|---|---|
| BLOCKED | Deny (no exception) | Deny (no exception) |
| SILENT | Allow (enforcer returns empty) | Allow (enforcer returns empty) |
| READ_ONLY | Allow | Deny |
| READ_WRITE | Allow (subject to wildcard rules) | Allow (subject to wildcard rules) |
| CONFIRM | AllowWithConfirmation | AllowWithConfirmation |

The wildcard rules (master order §18 "never allow access to
*every* device without explicit consent"):

- USB `LIST` or `CONNECT` with `WildcardUsb` target requires
  confirmation even under READ_WRITE.
- BLUETOOTH `LIST` with `Any` target requires confirmation
  even under READ_WRITE. A request for a specific MAC
  address is allowed.
- LOCATION `READ` with `Any` target (no accuracy hint)
  requires confirmation even under READ_WRITE. A request
  scoped to fine or coarse is allowed.

3. **Audit log**
   `HardwareAuditLog` is a thread-safe in-memory
   `synchronized(list)` that records every Allow /
   AllowWithConfirmation decision. Deny decisions are
   *not* recorded by the broker (the caller's deny path
   already audits with full context — same pattern as
   `NetworkBroker`).

### Why per-class + per-action two-axis

The `NetworkPolicy` only needed a mode + a target allow-list
because every network request is roughly the same shape
(connect to host X). Hardware is two-dimensional: the
*class* of hardware (USB, Bluetooth) and the *action* on it
(list, read, write, connect). A two-axis decision lets the
runtime express "USB is READ_WRITE but you can only list
devices" cleanly, without inventing a separate
"USB-READ-WRITE-EXCEPT-WRITE" mode.

The action enum is intentionally narrow. A guest that wants
to `usb.controlTransfer(0x21, request, value, index, buffer,
length, timeout)` is doing a *WRITE* on USB; the broker
doesn't see the control transfer, only the action label.
The platform enforcer (Phase 19) translates WRITE into
the specific `UsbDeviceConnection.controlTransfer` call
under an `openDevice()` handle that the broker already
vetted.

### Why "wildcard USB" is a separate shape

A USB request with no `vid`/`pid` filter is the
hardware-equivalent of "connect to 0.0.0.0" — the guest
wants every device. The master order §18 lists this
explicitly: "never allow access to *every* device without
explicit consent". A separate `WildcardUsb` shape (vs the
generic `Any`) lets the broker recognise the *kind* of
wildcard without parsing the (free-form) target string.

### Why `HardwareTargetId` is sealed

The id shape is a closed set: Any, Specific, WildcardUsb.
A free-form string would let callers invent "wildcard by
class" or "wildcard by tag id" without the broker's
knowledge. The sealed class keeps the broker's
`when (targetId)` exhaustive; adding a new shape (e.g.
"wildcard by name") is a deliberate code change.

## Consequences

### Positive

- **Pure-JVM policy.** Every rule the broker implements is
  covered by the 18 unit tests. A bug in the broker is
  caught before the device sees it.
- **Per-class + per-action.** The two-axis model expresses
  common patterns ("USB is READ_ONLY, you can list
  devices but not connect") without exploding the enum.
- **Wildcard rules are explicit.** The master order's
  "never allow access to *every* device" rule is in the
  code as a typed decision, not a comment.
- **Audit log mirrors the network audit log.** The same
  shape, the same thread-safety, the same deny-path
  decision. A future observability phase can ship a single
  `AuditLogReader` for both.
- **Seam for Phase 19.** The enforcer (Android's
  `UsbManager.requestPermission`, etc.) plugs in via
  `HardwareEnforcer` interface — the same translator +
  backend pattern as the firewall (Phase 15).

### Negative

- **No enforcement yet.** A guest that calls a platform API
  directly (bypassing the broker) is not stopped. Phase 19
  closes this with the enforcer, but until then the broker
  is advisory.
- **Action enum is coarse.** A guest that wants to
  `usb.controlTransfer` (a single byte write) is doing a
  WRITE; we don't distinguish "write 1 byte" from "write 4
  KB" from "format a flash drive". The platform's own
  permission system makes the fine-grained distinction; we
  rely on it.
- **No `manufacturer` / `product` allow-list.** A future
  "block this vendor's webcam" rule would need a richer
  target id. The `Specific` variant has a free-form string
  so the enforcer can match against any criterion; the
  broker just gates by the *shape*.

## Alternatives considered

1. **Skip the broker; the catalog's `HardwarePolicy` is
   just a list of `(class, mode)` pairs.** This is the
   `NetworkPolicy`-flavoured "default + allow-list"
   pattern. Rejected: hardware is two-axis (class +
   action), and a flat list can't express "USB is
   READ_ONLY" without inventing compound codes. The
   per-action enum keeps the policy expressible.
2. **Sealed class hierarchy of `HardwareRequest`
   subtypes (UsbList, BluetoothPair, NfcRead, etc.).**
   Rejected: the platform enforcer (Phase 19) is the
   place to specialise. The broker works on coarse
   `HardwareClass` + `HardwareAction` + `HardwareTargetId`
   because the *decision* is coarse.
3. **Have the broker call the platform API directly.** The
   same bug the network broker dodged: the broker must
   stay JVM-testable. Phase 19 wires the platform enforcer
   to the broker's decisions.

## Revisit triggers

- A new hardware class (e.g. `IR`, `RFID` long-range,
  `Thread` mesh networking) needs first-class support. We
  add a `HardwareClass` enum entry + a `wildcardDecision`
  branch + a test. The platform enforcer adds the
  corresponding `*Manager` adapter.
- A guest wants per-device rules (e.g. "block this webcam
  by serial number"). We add a
  `HardwareTargetId.Denied(id)` variant; the broker's
  decision path gets a `when (targetId)` branch.
- A second "remember confirmations" semantics is needed
  (e.g. "this device is paired, skip confirmation next
  time"). We add a `pairedDevices: Set<HardwareTargetId>`
  field to the policy; the broker checks the set under
  `READ_WRITE` and emits a plain `Allow` for known-paired
  targets.
