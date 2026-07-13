# ADR-009: Hardware broker capability framework

- Status: Draft
- Date: 2026-07-13
- Owners: Elysium Vanguard runtime

## Context

Guest applications may need access to device hardware: camera, microphone, GPS,
Bluetooth, USB peripherals, OBD-II readers, serial ports and CAN bus
interfaces. Unrestricted hardware access is a security and privacy risk. The
app must broker hardware resources with explicit consent, audit trails and
isolation between guests.

Coarse Android permissions (CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION) are
insufficient for per-guest fine-grained access control.

## Decision

Implement a HardwareBroker as the single point of access for all device
hardware resources:

### Capability tokens

Each hardware resource is represented by a `CapabilityToken`:

```kotlin
data class CapabilityToken(
    val resource: HardwareResource,     // CAMERA, MICROPHONE, GPS, BLUETOOTH,
                                        // USB_ACCESSORY, OBD2, SERIAL, CAN
    val accessLevel: AccessLevel,       // DISABLED, OBSERVE, CONTROL, EXCLUSIVE
    val duration: Duration?,            // null = one-shot, non-null = time-bound
    val expiresAt: Instant?,
    val sessionId: SessionId
)
```

Tokens are created by the broker and must be presented for every hardware
access call.

### Access policies

| Resource | Default policy | Required Android permission |
|---|---|---|
| Camera | ASK_EVERY_TIME | CAMERA |
| Microphone | ASK_EVERY_TIME | RECORD_AUDIO |
| GPS | ASK_EVERY_TIME | ACCESS_FINE_LOCATION |
| Bluetooth LE | OBSERVE | BLUETOOTH_SCAN |
| USB accessory | ASK_EVERY_TIME | none (USB permission) |
| OBD-II | DISABLED | none (BT serial) |
| Serial port | DISABLED | none |
| CAN bus | DISABLED | none |

Each policy can be overridden per-guest or per-session in Settings.

### Audit trail

Every hardware access event is recorded:
- Token issuance and revocation
- Access attempt (granted or denied)
- Data volume (for camera/mic streams)
- Session and guest identity

The audit log is stored in Room (see ADR-017) and retained for 90 days.

### Isolation

- Camera and microphone streams are routed through the broker, not exposed
  directly to the guest.
- GPS location is cloaked to configurable precision (100m, 1km, 10km) before
  being provided to the guest.
- Bluetooth is proxied through the broker; the guest does not see the real
  Bluetooth adapter.
- USB peripherals are assigned to exactly one guest at a time.

## Invariants

1. No hardware resource is accessible without a valid CapabilityToken.
2. Tokens are scoped to a single session.
3. Token duration is enforced; expired tokens are automatically revoked.
4. The audit log is append-only and tamper-evident (HMAC chain).
5. Exclusive resources are released on session stop.

## Alternatives considered

### Pass Android permissions directly to the guest

Rejected. Android permissions are per-app, not per-guest. A guest could use
the app's permissions indefinitely without per-session consent.

### No broker — delegate to guest

Rejected. Without a broker there is no audit trail, no revocation and no
isolation between guests sharing hardware.

## Consequences

- All hardware access adds latency through the broker layer.
- The audit log requires storage and a retention policy.
- Some hardware (USB serial, CAN) will not work without additional kernel
  support on non-rooted devices.
- The broker architecture supports future hardware types without changing the
  permission model.
- Third-party apps can integrate with the broker via the published interface.

## Revisit triggers

- Android introduces its own per-guest or per-session hardware permission API.
- A hardware resource requires direct guest access that the broker cannot
  proxy.
- Audit log storage exceeds 10 MB on typical devices.
